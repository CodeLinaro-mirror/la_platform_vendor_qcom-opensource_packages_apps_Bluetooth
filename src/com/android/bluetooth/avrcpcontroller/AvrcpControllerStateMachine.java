/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.bluetooth.avrcpcontroller;

import android.bluetooth.BluetoothAvrcpController;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.car.Car;
import android.car.CarNotConnectedException;
import android.car.media.CarAudioManager;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.AudioManager;
import android.media.AudioAttributes;
import android.support.v4.media.MediaBrowserCompat.MediaItem;
import android.media.MediaMetadata;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Message;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;
import android.util.SparseArray;

import com.android.bluetooth.BluetoothMetricsProto;
import com.android.bluetooth.R;
import com.android.bluetooth.Utils;
import com.android.bluetooth.a2dpsink.A2dpSinkService;
import com.android.bluetooth.btservice.MetricsLogger;
import com.android.bluetooth.btservice.ProfileService;
import com.android.bluetooth.statemachine.State;
import com.android.bluetooth.statemachine.StateMachine;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Provides Bluetooth AVRCP Controller State Machine responsible for all remote control connections
 * and interactions with a remote controlable device.
 */
class AvrcpControllerStateMachine extends StateMachine {
    static final String TAG = "AvrcpControllerStateMachine";
    static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);

    //0->99 Events from Outside
    public static final int CONNECT = 1;
    public static final int DISCONNECT = 2;

    //100->199 Internal Events
    protected static final int CLEANUP = 100;
    private static final int CONNECT_TIMEOUT = 101;
    static final int MESSAGE_INTERNAL_ABS_VOL_TIMEOUT = 102;

    //200->299 Events from Native
    static final int STACK_EVENT = 200;
    static final int MESSAGE_INTERNAL_CMD_TIMEOUT = 201;

    static final int MESSAGE_PROCESS_SET_ABS_VOL_CMD = 203;
    static final int MESSAGE_PROCESS_REGISTER_ABS_VOL_NOTIFICATION = 204;
    static final int MESSAGE_PROCESS_TRACK_CHANGED = 205;
    static final int MESSAGE_PROCESS_PLAY_POS_CHANGED = 206;
    static final int MESSAGE_PROCESS_PLAY_STATUS_CHANGED = 207;
    static final int MESSAGE_PROCESS_VOLUME_CHANGED_NOTIFICATION = 208;
    static final int MESSAGE_PROCESS_GET_FOLDER_ITEMS = 209;
    static final int MESSAGE_PROCESS_GET_FOLDER_ITEMS_OUT_OF_RANGE = 210;
    static final int MESSAGE_PROCESS_GET_PLAYER_ITEMS = 211;
    static final int MESSAGE_PROCESS_FOLDER_PATH = 212;
    static final int MESSAGE_PROCESS_SET_BROWSED_PLAYER = 213;
    static final int MESSAGE_PROCESS_SET_ADDRESSED_PLAYER = 214;
    static final int MESSAGE_PROCESS_ADDRESSED_PLAYER_CHANGED = 215;
    static final int MESSAGE_PROCESS_NOW_PLAYING_CONTENTS_CHANGED = 216;
    static final int MESSAGE_PROCESS_SUPPORTED_APPLICATION_SETTINGS = 217;
    static final int MESSAGE_PROCESS_CURRENT_APPLICATION_SETTINGS = 218;
    static final int MESSAGE_PROCESS_AVAILABLE_PLAYER_CHANGED = 219;
    static final int MESSAGE_PROCESS_RECEIVED_COVER_ART_PSM = 220;
    static final int MESSAGE_PROCESS_SEARCH_RESP = 221;  // vendor extension base
    static final int MESSAGE_PROCESS_UIDS_CHANGED = 222;
    static final int MESSAGE_PROCESS_RC_FEATURES = 223;
    static final int MESSAGE_PROCESS_ADD_TO_NOW_PLAYING = 224;

    //300->399 Events for Browsing
    //Internal
    static final int MESSAGE_GET_FOLDER_ITEMS = 300;
    static final int MESSAGE_PLAY_ITEM = 301;
    //External
    static final int MSG_AVRCP_PASSTHRU = 350;
    static final int MSG_AVRCP_SET_SHUFFLE = 351;
    static final int MSG_AVRCP_SET_REPEAT = 352;
    static final int MSG_AVRCP_SEARCH = 353;
    static final int MSG_AVRCP_PASSTHRU_EXT = 354;
    static final int MSG_AVRCP_GET_ITEM_ATTR = 355;
    static final int MSG_AVRCP_GET_ELEMENT_ATTR = 356;
    static final int MSG_AVRCP_GET_FOLDER_ITEMS_PTS = 357;
    static final int MSG_AVRCP_REQUEST_CONTINUING_RESPONSE = 358;
    static final int MSG_AVRCP_ABORT_CONTINUING_RESPONSE = 359;
    static final int MSG_AVRCP_ADD_TO_NOW_PLAYING = 360;

    //400->499 Events for Cover Artwork
    //Internal
    static final int MESSAGE_PROCESS_IMAGE_DOWNLOADED = 400;
    //External
    static final int MSG_AVRCP_FETCH_COVER_ART = 450;

    /*
     * Base value for absolute volume from JNI
     */
    private static final int ABS_VOL_BASE = 127;

    /*
     * Notification types for Avrcp protocol JNI.
     */
    private static final byte NOTIFICATION_RSP_TYPE_INTERIM = 0x00;
    private static final byte NOTIFICATION_RSP_TYPE_CHANGED = 0x01;

    // The value of UTF-8 as defined in IANA character set document
    private static final int AVRC_CHARSET_UTF8 = 0x006A;

    private static BluetoothDevice sActiveDevice;
    private Car mCar;
    private CarAudioManager mCarAudioManager;
    private static int mVolumeGroupId;
    private static int mMaxVolume;

    protected final BluetoothDevice mDevice;
    protected final byte[] mDeviceAddress;
    protected final AvrcpControllerService mService;
    protected int mCoverArtPsm;
    protected final AvrcpCoverArtManager mCoverArtManager;
    protected final Disconnected mDisconnected;
    protected final Connecting mConnecting;
    protected final Connected mConnected;
    protected final Disconnecting mDisconnecting;
    protected final Search mSearch;

    protected int mMostRecentState = BluetoothProfile.STATE_DISCONNECTED;

    boolean mRemoteControlConnected = false;
    boolean mBrowsingConnected = false;
    final BrowseTree mBrowseTree;
    private AvrcpPlayer mAddressedPlayer = new AvrcpPlayer();
    private int mAddressedPlayerId = -1;
    private int mUidCounter = 0;
    private SparseArray<AvrcpPlayer> mAvailablePlayerList = new SparseArray<AvrcpPlayer>();
    private int mVolumeChangedNotificationsToIgnore = 0;
    private int mVolumeNotificationLabel = -1;
    private int mRemoteFeatures;

    /**
     * Custom action to search.
     *
     * <p>This is called in {@link MediaController.TransportControls.sendCustomAction}
     *
     * <p>This is an asynchronous call: it will return immediately.
     *
     * <p>Intent {@link #ACTION_CUSTOM_ACTION_RESULT} will be broadcast to notify the result.
     * {@link AvrcpControllerService} will also receive search result.
     * Application can find search list when to browse AVRCP folder.
     *
     * @param Bundle wrapped with {@link #KEY_SEARCH}
     *
     * @return void
     *
     * @See {@link android.media.session.MediaController}
     *      {@link com.android.bluetooth.avrcpcontroller.AvrcpControllerService}
     */
    public static final String CUSTOM_ACTION_SEARCH =
        "android.bluetooth.avrcp-controller.profile.action.CUSTOM_ACTION_SEARCH";
    public static final String KEY_SEARCH = "search";

    // Send pass through command (with key state)
    public static final String CUSTOM_ACTION_SEND_PASS_THRU_CMD =
        "android.bluetooth.avrcp-controller.profile.action.CUSTOM_ACTION_SEND_PASS_THRU_CMD";
    public static final String KEY_CMD = "cmd";
    public static final String KEY_STATE = "state";

    /**
     * Custom action to get item attributes.
     *
     * <p>This is called in {@link MediaController.TransportControls.sendCustomAction}
     *
     * <p>This is an asynchronous call: it will return immediately.
     *
     * <p>Intent {@link AvrcpControllerService.ACTION_TRACK_EVENT} will be broadcast.
     * to notify the item attributes retrieved.
     *
     * @param Bundle wrapped with {@link MediaMetadata.METADATA_KEY_MEDIA_ID}
     *
     * @return void
     *
     * @See {@link android.media.session.MediaController}
     *      {@link android.media.MediaMetadata}
     *      {@link com.android.bluetooth.avrcpcontroller.AvrcpControllerService}
     */
    public static final String CUSTOM_ACTION_GET_ITEM_ATTR =
        "android.bluetooth.avrcp-controller.profile.action.CUSTOM_ACTION_GET_ITEM_ATTR";
    public static final String KEY_BROWSE_SCOPE = "scope";
    public static final String KEY_ATTRIBUTE_ID = "attribute_id";

    /**
     * Custom action to get element attributes.
     *
     * <p>This is called in {@link MediaController.TransportControls.sendCustomAction}
     *
     * <p>This is an asynchronous call: it will return immediately.
     *
     * <p>Intent {@link AvrcpControllerService.ACTION_TRACK_EVENT} will be broadcast.
     * to notify the item attributes retrieved.
     *
     * @param Bundle wrapped with KEY_ATTRIBUTE_ID
     *
     * @return void
     *
     * @See {@link android.media.session.MediaController}
     *      {@link android.media.MediaMetadata}
     *      {@link com.android.bluetooth.avrcpcontroller.AvrcpControllerService}
     */
    public static final String CUSTOM_ACTION_GET_ELEMENT_ATTR =
        "android.bluetooth.avrcp-controller.profile.action.CUSTOM_ACTION_GET_ELEMENT_ATTR";

    /**
     * Custom action to get folder items.
     *
     * <p>This is called in {@link MediaController.TransportControls.sendCustomAction}
     *
     * <p>This is an asynchronous call: it will return immediately.
     *
     * <p>Intent {@link AvrcpControllerService.EXTRA_FOLDER_LIST} will be broadcast.
     * to notify the items(player or folder/item) retrieved.
     *
     * @param Bundle wrapped with KEY_BROWSE_SCOPE and KEY_ATTRIBUTE_ID
     *
     * @return void
     *
     * @See {@link android.media.session.MediaController}
     *      {@link android.media.MediaMetadata}
     *      {@link com.android.bluetooth.avrcpcontroller.AvrcpControllerService}
     */
    public static final String CUSTOM_ACTION_GET_FOLDER_ITEM =
        "android.bluetooth.avrcp-controller.profile.action.CUSTOM_ACTION_GET_FOLDER_ITEM";
    public static final String KEY_START = "start";
    public static final String KEY_END = "end";

    // Intent used to broadcast A2DP/AVRCP custom action result
    // Requires {@link android.Manifest.permission#BLUETOOTH} permission to receive
    public static final String ACTION_CUSTOM_ACTION_RESULT =
        "android.bluetooth.avrcp-controller.profile.action.CUSTOM_ACTION_RESULT";
    public static final String EXTRA_CUSTOM_ACTION =
        "android.bluetooth.avrcp-controller.profile.extra.CUSTOM_ACTION";
    public static final String EXTRA_CUSTOM_ACTION_RESULT =
        "android.bluetooth.avrcp-controller.profile.extra.CUSTOM_ACTION_RESULT";
    public static final String EXTRA_NUM_OF_ITEMS =
        "android.bluetooth.avrcp-controller.profile.extra.NUM_OF_ITEMS";

    /**
     * Custom action to request for continuing response packets.
     *
     * <p>This is called in {@link MediaController.TransportControls.sendCustomAction}
     *
     * <p>This is an asynchronous call: it will return immediately.
     *
     * @param Bundle wrapped with {@link #KEY_PDU_ID}
     *
     * @return void
     *
     * @See {@link android.media.session.MediaController}
     */
    public static final String CUSTOM_ACTION_REQUEST_CONTINUING_RESPONSE =
        "android.bluetooth.avrcp-controller.profile.action.CUSTOM_ACTION_REQUEST_CONTINUING_RESPONSE";
    public static final String KEY_PDU_ID = "pdu_id";

    /**
     * Custom action to abort continuing response.
     *
     * <p>This is called in {@link MediaController.TransportControls.sendCustomAction}
     *
     * <p>This is an asynchronous call: it will return immediately.
     *
     * @param Bundle wrapped with {@link #KEY_PDU_ID}
     *
     * @return void
     *
     * @See {@link android.media.session.MediaController}
     */
    public static final String CUSTOM_ACTION_ABORT_CONTINUING_RESPONSE =
        "android.bluetooth.avrcp-controller.profile.action.CUSTOM_ACTION_ABORT_CONTINUING_RESPONSE";

    /**
     * Custom action to add item into NowPlaying.
     *
     * <p>This is called in {@link MediaController.TransportControls.sendCustomAction}
     *
     * <p>This is an asynchronous call: it will return immediately.
     *
     * <p>Intent {@link #ACTION_CUSTOM_ACTION_RESULT} will be broadcast to notify the result.
     * {@link AvrcpControllerService} will update NowPlaying list if succeed.
     *
     * @param Bundle wrapped with {@link #MediaMetadata.METADATA_KEY_MEDIA_ID}
     *
     * @return void
     *
     * @See {@link android.media.session.MediaController}
     *      {@link com.android.bluetooth.avrcpcontroller.AvrcpControllerService}
     */
    public static final String CUSTOM_ACTION_ADD_TO_NOW_PLAYING =
        "android.bluetooth.avrcp-controller.profile.action.CUSTOM_ACTION_ADD_TO_NOW_PLAYING";

    // Result code
    public static final int RESULT_SUCCESS = 0;
    public static final int RESULT_ERROR = 1;
    public static final int RESULT_INVALID_PARAMETER = 2;
    public static final int RESULT_NOT_SUPPORTED = 3;
    public static final int RESULT_TIMEOUT = 4;

    GetFolderList mGetFolderList = null;
    AddToNowPlaying mAddToNowPlaying = null;

    //Number of items to get in a single fetch
    static final int ITEM_PAGE_SIZE = 20;
    static final int CMD_TIMEOUT_MILLIS = 10000;
    static final int ABS_VOL_TIMEOUT_MILLIS = 1000; //1s

    AvrcpControllerStateMachine(BluetoothDevice device, AvrcpControllerService service) {
        super(TAG);
        mDevice = device;
        mDeviceAddress = Utils.getByteAddress(mDevice);
        mService = service;
        mRemoteFeatures = BluetoothAvrcpController.BTRC_FEAT_NONE;
        mCoverArtPsm = 0;
        mCoverArtManager = service.getCoverArtManager();
        logD(device.toString());

        mBrowseTree = new BrowseTree(mDevice);
        mDisconnected = new Disconnected();
        mConnecting = new Connecting();
        mConnected = new Connected();
        mDisconnecting = new Disconnecting();

        addState(mDisconnected);
        addState(mConnecting);
        addState(mConnected);
        addState(mDisconnecting);

        mGetFolderList = new GetFolderList();
        addState(mGetFolderList, mConnected);
        mSearch = new Search();
        addState(mSearch, mConnected);
        mAddToNowPlaying = new AddToNowPlaying();
        addState(mAddToNowPlaying, mConnected);

        mCar = Car.createCar(service.getApplicationContext(), mConnection);
        mCar.connect();

        setInitialState(mDisconnected);
    }

    public void doQuit() {
        logD("doQuit");
        if (mCar != null && mCar.isConnected()) {
            mCar.disconnect();
            mCar = null;
        }

        quitNow();
    }

    BrowseTree.BrowseNode findNode(String parentMediaId) {
        logD("FindNode");
        return mBrowseTree.findBrowseNodeByID(parentMediaId);
    }

    /**
     * Get the current connection state
     *
     * @return current State
     */
    public int getState() {
        return mMostRecentState;
    }

    /**
     * Get the underlying device tracked by this state machine
     *
     * @return device in focus
     */
    public synchronized BluetoothDevice getDevice() {
        return mDevice;
    }

    public synchronized void setRemoteFeatures(int remoteFeatures) {
        mRemoteFeatures = remoteFeatures;
    }

    public synchronized int getRemoteFeatures() {
        return mRemoteFeatures;
    }

    /**
     * send the connection event asynchronously
     */
    public boolean connect(StackEvent event) {
        if (event.mBrowsingConnected) {
            onBrowsingConnected();
        }
        mRemoteControlConnected = event.mRemoteControlConnected;
        sendMessage(CONNECT);
        return true;
    }

    /**
     * send the Disconnect command asynchronously
     */
    public void disconnect() {
        sendMessage(DISCONNECT);
    }

    /**
     * Get the current playing track
     */
    public AvrcpItem getCurrentTrack() {
        return mAddressedPlayer.getCurrentTrack();
    }

    /**
     * Dump the current State Machine to the string builder.
     *
     * @param sb output string
     */
    public void dump(StringBuilder sb) {
        ProfileService.println(sb, "mDevice: " + mDevice.getAddress() + "("
                + mDevice.getName() + ") " + this.toString());
        ProfileService.println(sb, "isActive: " + isActive());
    }

    @VisibleForTesting
    boolean isActive() {
        return mDevice == sActiveDevice;
    }

    /*
     * requestActive
     *
     * Set the current device active if nothing an already connected device isn't playing
     */
    private boolean requestActive() {
        if (sActiveDevice == null
                || BluetoothMediaBrowserService.getPlaybackState()
                != PlaybackStateCompat.STATE_PLAYING) {
            return setActive(true);
        }
        return false;
    }

    /**
     * Attempt to set the active status for this device
     */
    boolean setActive(boolean becomeActive) {
        logD("setActive(" + becomeActive + ")");
        if (becomeActive) {
            if (isActive()) {
                return true;
            }

            // By default, sBrowseTree.mSearchNode is invalid because device is null.
            // So it is necessary to update it when there is a active device.
            if (mService.sBrowseTree != null) {
                mService.sBrowseTree.updateSearchNode(mBrowseTree.mSearchNode);
            }

            A2dpSinkService a2dpSinkService = A2dpSinkService.getA2dpSinkService();
            if (a2dpSinkService == null) {
                return false;
            }

            if (a2dpSinkService.setActiveDeviceNative(mDeviceAddress)) {
                sActiveDevice = mDevice;
                BluetoothMediaBrowserService.addressedPlayerChanged(mSessionCallbacks);
                BluetoothMediaBrowserService.notifyChanged(mAddressedPlayer.getPlaybackState());
                BluetoothMediaBrowserService.notifyChanged(mBrowseTree.mNowPlayingNode);
            }
            return mDevice == sActiveDevice;
        } else if (isActive()) {
            // Always clear cache when device becomes inactive
            refreshSearchNode(false);

            sActiveDevice = null;
            BluetoothMediaBrowserService.trackChanged(null);
            BluetoothMediaBrowserService.addressedPlayerChanged(null);
        }
        return true;
    }

    @Override
    protected void unhandledMessage(Message msg) {
        Log.w(TAG, "Unhandled message in state " + getCurrentState() + "msg.what=" + msg.what);
    }

    private static void logD(String message) {
        if (DBG) {
            Log.d(TAG, message);
        }
    }

    synchronized void onBrowsingConnected() {
        if (mBrowsingConnected) return;
        if (mService.sBrowseTree != null && mService.sBrowseTree.mRootNode!= null) {
            mService.sBrowseTree.mRootNode.addChild(mBrowseTree.mRootNode);
            BluetoothMediaBrowserService.notifyChanged(mService
                    .sBrowseTree.mRootNode);
            mBrowsingConnected = true;
        }
    }

    synchronized void onBrowsingDisconnected() {
        if (!mBrowsingConnected) return;
        mAddressedPlayer.setPlayStatus(PlaybackStateCompat.STATE_ERROR);
        AvrcpItem previousTrack = mAddressedPlayer.getCurrentTrack();
        String previousTrackUuid = previousTrack != null ? previousTrack.getCoverArtUuid() : null;
        mAddressedPlayer.updateCurrentTrack(null);
        mBrowseTree.mNowPlayingNode.setCached(false);
        if (isActive()) {
            BluetoothMediaBrowserService.notifyChanged(mBrowseTree.mNowPlayingNode);
        }
        if (mService.sBrowseTree != null && mService.sBrowseTree.mRootNode!= null) {
            mService.sBrowseTree.mRootNode.removeChild(
                    mBrowseTree.mRootNode);
            BluetoothMediaBrowserService.notifyChanged(mService
                    .sBrowseTree.mRootNode);
            removeUnusedArtwork(previousTrackUuid);
            removeUnusedArtworkFromBrowseTree();
            mBrowsingConnected = false;
        }
    }

    synchronized void connectCoverArt() {
        // Called from "connected" state, which assumes either control or browse is connected
        if (mCoverArtManager != null && mCoverArtPsm != 0
                && mCoverArtManager.getState(mDevice) != BluetoothProfile.STATE_CONNECTED) {
            logD("Attempting to connect to AVRCP BIP, psm: " + mCoverArtPsm);
            mCoverArtManager.connect(mDevice, /* psm */ mCoverArtPsm);
        }
    }

    synchronized void refreshCoverArt() {
        if (mCoverArtManager != null && mCoverArtPsm != 0
                && mCoverArtManager.getState(mDevice) == BluetoothProfile.STATE_CONNECTED) {
            logD("Attempting to refresh AVRCP BIP OBEX session, psm: " + mCoverArtPsm);
            mCoverArtManager.refreshSession(mDevice);
        }
    }

    synchronized void disconnectCoverArt() {
        // Safe to call even if we're not connected
        if (mCoverArtManager != null) {
            logD("Disconnect BIP cover artwork");
            mCoverArtManager.disconnect(mDevice);
        }
    }

    /**
     * Remove an unused cover art image from storage if it's unused by the browse tree and the
     * current track.
     */
    synchronized void removeUnusedArtwork(String previousTrackUuid) {
        logD("removeUnusedArtwork(" + previousTrackUuid + ")");
        if (mCoverArtManager == null) return;
        AvrcpItem currentTrack = getCurrentTrack();
        String currentTrackUuid = currentTrack != null ? currentTrack.getCoverArtUuid() : null;
        if (previousTrackUuid != null) {
            if (!previousTrackUuid.equals(currentTrackUuid)
                    && mBrowseTree.getNodesUsingCoverArt(previousTrackUuid).isEmpty()) {
                mCoverArtManager.removeImage(mDevice, previousTrackUuid);
            }
        }
    }

    /**
     * Queries the browse tree for unused uuids and removes the associated images from storage
     * if the uuid is not used by the current track.
     */
    synchronized void removeUnusedArtworkFromBrowseTree() {
        logD("removeUnusedArtworkFromBrowseTree()");
        if (mCoverArtManager == null) return;
        AvrcpItem currentTrack = getCurrentTrack();
        String currentTrackUuid = currentTrack != null ? currentTrack.getCoverArtUuid() : null;
        ArrayList<String> unusedArtwork = mBrowseTree.getAndClearUnusedCoverArt();
        for (String uuid : unusedArtwork) {
            if (!uuid.equals(currentTrackUuid)) {
                mCoverArtManager.removeImage(mDevice, uuid);
            }
        }
    }

    private void notifyChanged(BrowseTree.BrowseNode node) {
        // We should only notify now playing content updates if we're the active device. VFS
        // updates are fine at any time
        int scope = node.getScope();
        if (scope != AvrcpControllerService.BROWSE_SCOPE_NOW_PLAYING
                || (scope == AvrcpControllerService.BROWSE_SCOPE_NOW_PLAYING
                && isActive())) {
            BluetoothMediaBrowserService.notifyChanged(node);
        }
    }

    private void notifyChanged(PlaybackStateCompat state) {
        if (isActive()) {
            BluetoothMediaBrowserService.notifyChanged(state);
        }
    }

    void requestContents(BrowseTree.BrowseNode node) {
        sendMessage(MESSAGE_GET_FOLDER_ITEMS, node);
        logD("Fetching " + node);
    }

    public void playItem(BrowseTree.BrowseNode node) {
        sendMessage(MESSAGE_PLAY_ITEM, node);
    }

    void nowPlayingContentChanged() {
        mBrowseTree.mNowPlayingNode.setCached(false);
        removeUnusedArtworkFromBrowseTree();
        sendMessage(MESSAGE_GET_FOLDER_ITEMS, mBrowseTree.mNowPlayingNode);
    }

    void refreshSearchNode(boolean isAddNode) {
        mBrowseTree.mSearchNode.setCached(false);

        BrowseTree.BrowseNode currBrPlayer = mBrowseTree.getCurrentBrowsedPlayer();
        if (currBrPlayer != null) {
            if (isAddNode) {
                currBrPlayer.addChild(mBrowseTree.mSearchNode);
            } else {
                currBrPlayer.removeChild(mBrowseTree.mSearchNode);
            }

            BluetoothMediaBrowserService.notifyChanged(currBrPlayer);
        } else {
            Log.d(TAG, "currBrPlayer is NULL");
        }
    }

    protected class Disconnected extends State {
        @Override
        public void enter() {
            logD("Enter Disconnected");

            if (isActive()) {
                refreshSearchNode(false);
            }

            if (mMostRecentState != BluetoothProfile.STATE_DISCONNECTED) {
                sendMessage(CLEANUP);
            }
            broadcastConnectionStateChanged(BluetoothProfile.STATE_DISCONNECTED);
        }

        @Override
        public boolean processMessage(Message message) {
            switch (message.what) {
                case MESSAGE_PROCESS_RECEIVED_COVER_ART_PSM:
                    mCoverArtPsm = message.arg1;
                    break;
                case CONNECT:
                    logD("Connect");
                    transitionTo(mConnecting);
                    break;
                case CLEANUP:
                    mService.removeStateMachine(AvrcpControllerStateMachine.this);
                    break;
            }
            return true;
        }
    }

    protected class Connecting extends State {
        @Override
        public void enter() {
            logD("Enter Connecting");
            broadcastConnectionStateChanged(BluetoothProfile.STATE_CONNECTING);
            transitionTo(mConnected);
        }
    }


    class Connected extends State {
        private static final String STATE_TAG = "Avrcp.ConnectedAvrcpController";
        private int mCurrentlyHeldKey = 0;

        @Override
        public void enter() {
            if (mMostRecentState == BluetoothProfile.STATE_CONNECTING) {
                requestActive();
                broadcastConnectionStateChanged(BluetoothProfile.STATE_CONNECTED);
                connectCoverArt(); // only works if we have a valid PSM
            } else {
                logD("ReEnteringConnected");
            }
            super.enter();
        }

        @Override
        public boolean processMessage(Message msg) {
            logD(STATE_TAG + " processMessage " + msg.what);
            switch (msg.what) {
                case MESSAGE_PROCESS_SET_ABS_VOL_CMD:
                    mVolumeChangedNotificationsToIgnore++;
                    removeMessages(MESSAGE_INTERNAL_ABS_VOL_TIMEOUT);
                    sendMessageDelayed(MESSAGE_INTERNAL_ABS_VOL_TIMEOUT,
                            ABS_VOL_TIMEOUT_MILLIS);
                    handleAbsVolumeRequest(msg.arg1, msg.arg2);
                    return true;

                case MESSAGE_PROCESS_REGISTER_ABS_VOL_NOTIFICATION:
                    mVolumeNotificationLabel = msg.arg1;
                    mService.sendRegisterAbsVolRspNative(mDeviceAddress,
                            NOTIFICATION_RSP_TYPE_INTERIM,
                            getAbsVolume(), mVolumeNotificationLabel);
                    return true;

                case MESSAGE_GET_FOLDER_ITEMS:
                    transitionTo(mGetFolderList);
                    return true;

                case MESSAGE_PLAY_ITEM:
                    //Set Addressed Player
                    processPlayItem((BrowseTree.BrowseNode) msg.obj);
                    return true;

                case MSG_AVRCP_PASSTHRU:
                    passThru(msg.arg1);
                    return true;

                case MSG_AVRCP_SEARCH:
                    // Reset search node before processing new search request.
                    refreshSearchNode(false);

                    processSearchReq((String) msg.obj);
                    return true;

                case MESSAGE_PROCESS_UIDS_CHANGED:
                    processUIDSChange(msg);
                    return true;

                case MESSAGE_PROCESS_RC_FEATURES:
                    setRemoteFeatures(msg.arg1);
                    return true;

                case MSG_AVRCP_PASSTHRU_EXT:
                    passThru(msg.arg1, msg.arg2);
                    return true;

                case MSG_AVRCP_SET_REPEAT:
                    setRepeat(msg.arg1);
                    return true;

                case MSG_AVRCP_SET_SHUFFLE:
                    setShuffle(msg.arg1);
                    return true;

                case MSG_AVRCP_GET_ITEM_ATTR:
                    getItemAttributes((Bundle) msg.obj);
                    return true;

                case MSG_AVRCP_GET_ELEMENT_ATTR:
                    getElementAttributes((Bundle) msg.obj);
                    return true;

                case MSG_AVRCP_GET_FOLDER_ITEMS_PTS:
                    getFolderItems((Bundle) msg.obj);
                    transitionTo(mGetFolderList);
                    return true;

                case MSG_AVRCP_REQUEST_CONTINUING_RESPONSE:
                    RequestContinuingResponse(msg.arg1);
                    return true;

                case MSG_AVRCP_ABORT_CONTINUING_RESPONSE:
                    AbortContinuingResponse(msg.arg1);
                    return true;

                case MSG_AVRCP_ADD_TO_NOW_PLAYING:
                    transitionTo(mAddToNowPlaying);
                    return true;

                case MESSAGE_PROCESS_TRACK_CHANGED: {
                    AvrcpItem track = (AvrcpItem) msg.obj;
                    AvrcpItem previousTrack = mAddressedPlayer.getCurrentTrack();
                    downloadImageIfNeeded(track);
                    mAddressedPlayer.updateCurrentTrack(track);
                    if (isActive()) {
                        BluetoothMediaBrowserService.trackChanged(track);
                    }
                    if (previousTrack != null) {
                        removeUnusedArtwork(previousTrack.getCoverArtUuid());
                        removeUnusedArtworkFromBrowseTree();
                    }
                    return true;
                }

                case MESSAGE_PROCESS_PLAY_STATUS_CHANGED:
                    mAddressedPlayer.setPlayStatus(msg.arg1);
                    if (!isActive()) {
                        sendMessage(MSG_AVRCP_PASSTHRU,
                                AvrcpControllerService.PASS_THRU_CMD_ID_PAUSE);
                        return true;
                    }

                    PlaybackStateCompat playbackState = mAddressedPlayer.getPlaybackState();
                    BluetoothMediaBrowserService.notifyChanged(playbackState);

                    int focusState = AudioManager.ERROR;
                    A2dpSinkService a2dpSinkService = A2dpSinkService.getA2dpSinkService();
                    if (a2dpSinkService != null) {
                        focusState = a2dpSinkService.getFocusState();
                    }

                    if (focusState == AudioManager.ERROR) {
                        sendMessage(MSG_AVRCP_PASSTHRU,
                                AvrcpControllerService.PASS_THRU_CMD_ID_PAUSE);
                        return true;
                    }

                    if (playbackState.getState() == PlaybackStateCompat.STATE_PLAYING
                            && focusState == AudioManager.AUDIOFOCUS_NONE) {
                        if (shouldRequestFocus()) {
                            mSessionCallbacks.onPrepare();
                        } else {
                            sendMessage(MSG_AVRCP_PASSTHRU,
                                    AvrcpControllerService.PASS_THRU_CMD_ID_PAUSE);
                        }
                    }
                    return true;

                case MESSAGE_PROCESS_PLAY_POS_CHANGED:
                    if (msg.arg2 != -1) {
                        mAddressedPlayer.setPlayTime(msg.arg2);
                        notifyChanged(mAddressedPlayer.getPlaybackState());
                    }
                    return true;

                case MESSAGE_PROCESS_ADDRESSED_PLAYER_CHANGED:
                    mAddressedPlayerId = msg.arg1;
                    logD("AddressedPlayer = " + mAddressedPlayerId);

                    // The now playing list is tied to the addressed player by specification in
                    // AVRCP 5.9.1. A new addressed player means our now playing content is now
                    // invalid
                    mBrowseTree.mNowPlayingNode.setCached(false);
                    if (isActive()) {
                        BluetoothMediaBrowserService.notifyChanged(mBrowseTree.mNowPlayingNode);
                    }

                    AvrcpPlayer updatedPlayer = mAvailablePlayerList.get(mAddressedPlayerId);
                    if (updatedPlayer != null) {
                        mAddressedPlayer = updatedPlayer;
                        // If the new player supports the now playing feature then fetch it
                        if (mAddressedPlayer.supportsFeature(AvrcpPlayer.FEATURE_NOW_PLAYING)) {
                            sendMessage(MESSAGE_GET_FOLDER_ITEMS, mBrowseTree.mNowPlayingNode);
                        }
                        logD("AddressedPlayer = " + mAddressedPlayer.getName());
                    } else {
                        logD("Addressed player changed to unknown ID=" + mAddressedPlayerId);
                        mBrowseTree.mRootNode.setCached(false);
                        mBrowseTree.mRootNode.setExpectedChildren(255);
                        BluetoothMediaBrowserService.notifyChanged(mBrowseTree.mRootNode);
                    }
                    removeUnusedArtworkFromBrowseTree();
                    return true;

                case MESSAGE_PROCESS_SUPPORTED_APPLICATION_SETTINGS:
                    mAddressedPlayer.setSupportedPlayerApplicationSettings(
                            (PlayerApplicationSettings) msg.obj);
                    notifyChanged(mAddressedPlayer.getPlaybackState());
                    return true;

                case MESSAGE_PROCESS_CURRENT_APPLICATION_SETTINGS:
                    mAddressedPlayer.setCurrentPlayerApplicationSettings(
                            (PlayerApplicationSettings) msg.obj);
                    notifyChanged(mAddressedPlayer.getPlaybackState());
                    return true;

                case MESSAGE_PROCESS_AVAILABLE_PLAYER_CHANGED:
                    processAvailablePlayerChanged();
                    return true;

                case MESSAGE_PROCESS_RECEIVED_COVER_ART_PSM:
                    mCoverArtPsm = msg.arg1;
                    connectCoverArt();
                    return true;

                case MESSAGE_PROCESS_IMAGE_DOWNLOADED:
                    AvrcpCoverArtManager.DownloadEvent event =
                            (AvrcpCoverArtManager.DownloadEvent) msg.obj;
                    String uuid = event.getUuid();
                    Uri uri = event.getUri();
                    logD("Received image for " + uuid + " at " + uri.toString());

                    // Let the addressed player know we got an image so it can see if the current
                    // track now has cover artwork
                    boolean addedArtwork = mAddressedPlayer.notifyImageDownload(uuid, uri);
                    if (addedArtwork && isActive()) {
                        BluetoothMediaBrowserService.trackChanged(
                                mAddressedPlayer.getCurrentTrack());
                    }

                    // Let the browse tree know of the newly downloaded image so it can attach it to
                    // all the items that need it. Notify of changed nodes accordingly
                    Set<BrowseTree.BrowseNode> nodes = mBrowseTree.notifyImageDownload(uuid, uri);
                    for (BrowseTree.BrowseNode node : nodes) {
                        notifyChanged(node);
                    }

                    // Delete images that were downloaded and entirely unused
                    if (!addedArtwork && nodes.isEmpty()) {
                        removeUnusedArtwork(uuid);
                        removeUnusedArtworkFromBrowseTree();
                    }

                    return true;

                case MSG_AVRCP_FETCH_COVER_ART: {
                    // New scheme is retrieved through property
                    // AvrcpCoverArtManager.AVRCP_CONTROLLER_COVER_ART_SCHEME
                    mCoverArtManager.updateImageProperties();
                    AvrcpItem track = mAddressedPlayer.getCurrentTrack();
                    downloadImageIfNeeded(track, true);
                    return true;
                }

                case DISCONNECT:
                    transitionTo(mDisconnecting);
                    return true;

                default:
                    return super.processMessage(msg);
            }

        }

        private void processPlayItem(BrowseTree.BrowseNode node) {
            setActive(true);
            if (node == null) {
                Log.w(TAG, "Invalid item to play");
            } else {
                mService.playItemNative(
                        mDeviceAddress, node.getScope(),
                        node.getBluetoothID(), mUidCounter);
            }
        }

        private synchronized void passThru(int cmd) {
            logD("msgPassThru " + cmd);
            // Some keys should be held until the next event.
            if (mCurrentlyHeldKey != 0) {
                mService.sendPassThroughCommandNative(
                        mDeviceAddress, mCurrentlyHeldKey,
                        AvrcpControllerService.KEY_STATE_RELEASED);

                if (mCurrentlyHeldKey == cmd) {
                    // Return to prevent starting FF/FR operation again
                    mCurrentlyHeldKey = 0;
                    return;
                } else {
                    // FF/FR is in progress and other operation is desired
                    // so after stopping FF/FR, not returning so that command
                    // can be sent for the desired operation.
                    mCurrentlyHeldKey = 0;
                }
            }

            // Send the pass through.
            mService.sendPassThroughCommandNative(mDeviceAddress, cmd,
                    AvrcpControllerService.KEY_STATE_PRESSED);

            if (isHoldableKey(cmd)) {
                // Release cmd next time a command is sent.
                mCurrentlyHeldKey = cmd;
            } else {
                mService.sendPassThroughCommandNative(mDeviceAddress,
                        cmd, AvrcpControllerService.KEY_STATE_RELEASED);
            }
        }

        private boolean isHoldableKey(int cmd) {
            return (cmd == AvrcpControllerService.PASS_THRU_CMD_ID_REWIND)
                    || (cmd == AvrcpControllerService.PASS_THRU_CMD_ID_FF);
        }

        private synchronized void passThru(int cmd, int state) {
            logD("msgPassThru " + cmd + ", key state " + state);
            mService.sendPassThroughCommandNative(
                    mDeviceAddress, cmd,
                    state);
        }

        private void setRepeat(int repeatMode) {
            mService.setPlayerApplicationSettingValuesNative(mDeviceAddress, (byte) 1,
                    new byte[]{PlayerApplicationSettings.REPEAT_STATUS}, new byte[]{
                            PlayerApplicationSettings.mapAvrcpPlayerSettingstoBTattribVal(
                                    PlayerApplicationSettings.REPEAT_STATUS, repeatMode)});
        }

        private void setShuffle(int shuffleMode) {
            mService.setPlayerApplicationSettingValuesNative(mDeviceAddress, (byte) 1,
                    new byte[]{PlayerApplicationSettings.SHUFFLE_STATUS}, new byte[]{
                            PlayerApplicationSettings.mapAvrcpPlayerSettingstoBTattribVal(
                                    PlayerApplicationSettings.SHUFFLE_STATUS, shuffleMode)});
        }

        private synchronized void getItemAttributes(Bundle extras) {
            int scope = extras.getInt(KEY_BROWSE_SCOPE, 0);
            String mediaId = extras.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
            int [] attributeId = extras.getIntArray(KEY_ATTRIBUTE_ID);

            if (mediaId != null) {
                BrowseTree.BrowseNode currItem = mBrowseTree.findBrowseNodeByID(mediaId);
                logD("processGetItemAttrReq mediaId=" + mediaId + " node=" + currItem);
                if (currItem != null) {
                    int features = getRemoteFeatures();
                    if ((features & BluetoothAvrcpController.BTRC_FEAT_BROWSE) != 0) {
                        AvrcpControllerService.getItemAttributesNative(
                            mDeviceAddress, (byte) scope,
                            currItem.getBluetoothID(),
                            mUidCounter, (byte) attributeId.length, attributeId);
                    } else {
                        logD("Browsing channel not supported!!!");
                    }
                }
            } else {
                logD("processGetItemAttrReq GetElementAttributes");
            }
        }

        private synchronized void getElementAttributes(Bundle extras) {
            int [] attributeId = extras.getIntArray(KEY_ATTRIBUTE_ID);
            AvrcpControllerService.getElementAttributesNative(
                mDeviceAddress, (byte) attributeId.length, attributeId);
        }

        private synchronized void getFolderItems(Bundle extras) {
            int scope = extras.getInt(KEY_BROWSE_SCOPE, 0);
            int start = extras.getInt(KEY_START, 0);
            int end = extras.getInt(KEY_END, 0xFF);
            int [] attributeId = extras.getIntArray(KEY_ATTRIBUTE_ID);
            AvrcpControllerService.getFolderItemsNative(
                mDeviceAddress, (byte) scope, (byte) start, (byte) end,
                (byte) attributeId.length, attributeId);
        }

        private void RequestContinuingResponse(int pduId) {
            logD("processRequestContinuingResponse pduId=" + pduId);
            AvrcpControllerService.requestContinuingResponseNative(
                mDeviceAddress, (byte) pduId);
        }

        private void AbortContinuingResponse(int pduId) {
            logD("processAbortContinuingResponse pduId=" + pduId);
            AvrcpControllerService.abortContinuingResponseNative(
                mDeviceAddress, (byte) pduId);
        }

        private void processAvailablePlayerChanged() {
            logD("processAvailablePlayerChanged");
            mBrowseTree.mRootNode.setCached(false);
            mBrowseTree.mRootNode.setExpectedChildren(255);
            BluetoothMediaBrowserService.notifyChanged(mBrowseTree.mRootNode);
            removeUnusedArtworkFromBrowseTree();
        }

        private void processSearchReq(String query) {
            if (mSearch.isSearchingSupported()) {
                logD("processSearchReq search: " + query);
                AvrcpControllerService.searchNative(mDeviceAddress,
                    AVRC_CHARSET_UTF8, query.length(), query);
                transitionTo(mSearch);
            } else {
                Log.w(TAG, "Search not supported");
            }
        }
    }

    // Handle the get folder listing action
    // a) Fetch the listing of folders
    // b) Once completed return the object listing
    class GetFolderList extends State {
        private static final String STATE_TAG = "Avrcp.GetFolderList";

        boolean mAbort;
        byte mScope = AvrcpControllerService.BROWSE_SCOPE_VFS;
        BrowseTree.BrowseNode mBrowseNode;
        BrowseTree.BrowseNode mNextStep;

        @Override
        public void enter() {
            logD(STATE_TAG + " Entering GetFolderList");
            // Setup the timeouts.
            sendMessageDelayed(MESSAGE_INTERNAL_CMD_TIMEOUT, CMD_TIMEOUT_MILLIS);
            super.enter();
            mAbort = false;
            Message msg = getCurrentMessage();
            if (msg.what == MESSAGE_GET_FOLDER_ITEMS) {
                {
                    logD(STATE_TAG + " new Get Request");
                    mBrowseNode = (BrowseTree.BrowseNode) msg.obj;
                }
            } else if (msg.what == MSG_AVRCP_GET_FOLDER_ITEMS_PTS)  {
                Bundle extras = (Bundle) msg.obj;
                int scope = extras.getInt(KEY_BROWSE_SCOPE, 0);
                if (scope == AvrcpControllerService.BROWSE_SCOPE_SEARCH) {
                    mBrowseNode = mBrowseTree.mSearchNode;
                } else if (scope == AvrcpControllerService.BROWSE_SCOPE_NOW_PLAYING) {
                    mBrowseNode = mBrowseTree.mNowPlayingNode;
                } else if (scope == AvrcpControllerService.BROWSE_SCOPE_PLAYER_LIST) {
                    mBrowseNode = mBrowseTree.mRootNode;
                } else {
                    mBrowseNode = mBrowseTree.getCurrentBrowsedFolder();
                }
            }

            if (mBrowseNode == null) {
                setScope(AvrcpControllerService.BROWSE_SCOPE_VFS);
                transitionTo(mConnected);
            } else {
                if (mBrowseNode.equals(mBrowseTree.mSearchNode)) {
                    setScope(AvrcpControllerService.BROWSE_SCOPE_SEARCH);
                } else if (mBrowseNode.equals(mBrowseTree.mNowPlayingNode)) {
                    setScope(AvrcpControllerService.BROWSE_SCOPE_NOW_PLAYING);
                } else if (mBrowseNode.equals(mBrowseTree.mRootNode)) {
                    setScope(AvrcpControllerService.BROWSE_SCOPE_PLAYER_LIST);
                } else {
                    setScope(AvrcpControllerService.BROWSE_SCOPE_VFS);
                }

                if (msg.what != MSG_AVRCP_GET_FOLDER_ITEMS_PTS) {
                    navigateToFolderOrRetrieve(mBrowseNode);
                }
            }
        }

        public void setScope(byte scope) {
            mScope = scope;
        }

        @Override
        public boolean processMessage(Message msg) {
            logD(STATE_TAG + " processMessage " + msg.what);
            switch (msg.what) {
                case MESSAGE_PROCESS_GET_FOLDER_ITEMS:
                    ArrayList<AvrcpItem> folderList = (ArrayList<AvrcpItem>) msg.obj;
                    int endIndicator = mBrowseNode.getExpectedChildren() - 1;
                    logD("GetFolderItems: End " + endIndicator
                            + " received " + folderList.size());

                    // Queue up image download if the item has an image and we don't have it yet
                    // Only do this if the feature is enabled.
                    for (AvrcpItem track : folderList) {
                        if (shouldDownloadBrowsedImages()) {
                            downloadImageIfNeeded(track);
                        } else {
                            track.setCoverArtUuid(null);
                        }
                    }

                    // Always update the node so that the user does not wait forever
                    // for the list to populate.
                    int newSize = mBrowseNode.addChildren(folderList, mScope);
                    logD("Added " + newSize + " items to the browse tree");
                    notifyChanged(mBrowseNode);

                    if (mBrowseNode.getChildrenCount() >= endIndicator || folderList.size() == 0
                            || mAbort) {
                        // If we have fetched all the elements or if the remotes sends us 0 elements
                        // (which can lead us into a loop since mCurrInd does not proceed) we simply
                        // abort.
                        mBrowseNode.setCached(true);
                        sendFolderBroadcastAndUpdateNode();
                        transitionTo(mConnected);
                    } else {
                        // Fetch the next set of items.
                        fetchContents(mBrowseNode);
                        // Reset the timeout message since we are doing a new fetch now.
                        removeMessages(MESSAGE_INTERNAL_CMD_TIMEOUT);
                        sendMessageDelayed(MESSAGE_INTERNAL_CMD_TIMEOUT, CMD_TIMEOUT_MILLIS);
                    }
                    break;
                case MESSAGE_PROCESS_SET_BROWSED_PLAYER:
                    BrowseTree.BrowseNode preBrPlayer = mBrowseTree.getCurrentBrowsedPlayer();
                    mBrowseTree.setCurrentBrowsedPlayer(mNextStep.getID(), msg.arg1, msg.arg2);
                    BrowseTree.BrowseNode currBrPlayer = mBrowseTree.getCurrentBrowsedPlayer();
                    // Reset search node if browsed player is invalid or changed.
                    if (currBrPlayer == null ||
                            (currBrPlayer != null && (!currBrPlayer.equals(preBrPlayer)))) {
                        if (isActive()) {
                            refreshSearchNode(false);
                        }
                    }
                    removeMessages(MESSAGE_INTERNAL_CMD_TIMEOUT);
                    sendMessageDelayed(MESSAGE_INTERNAL_CMD_TIMEOUT, CMD_TIMEOUT_MILLIS);
                    navigateToFolderOrRetrieve(mBrowseNode);
                    break;

                case MESSAGE_PROCESS_FOLDER_PATH:
                    mBrowseTree.setCurrentBrowsedFolder(mNextStep.getID());
                    mBrowseTree.getCurrentBrowsedFolder().setExpectedChildren(msg.arg1);

                    // AVRCP Specification says, if we're not database aware, we must disconnect and
                    // reconnect our BIP client each time we successfully change path
                    refreshCoverArt();

                    if (mAbort) {
                        transitionTo(mConnected);
                    } else {
                        removeMessages(MESSAGE_INTERNAL_CMD_TIMEOUT);
                        sendMessageDelayed(MESSAGE_INTERNAL_CMD_TIMEOUT, CMD_TIMEOUT_MILLIS);
                        navigateToFolderOrRetrieve(mBrowseNode);
                    }
                    break;

                case MESSAGE_PROCESS_GET_PLAYER_ITEMS:
                    BrowseTree.BrowseNode rootNode = mBrowseTree.mRootNode;
                    if (!rootNode.isCached()) {
                        List<AvrcpPlayer> playerList = (List<AvrcpPlayer>) msg.obj;
                        mAvailablePlayerList.clear();
                        for (AvrcpPlayer player : playerList) {
                            mAvailablePlayerList.put(player.getId(), player);
                        }
                        rootNode.addChildren(playerList);
                        mBrowseTree.setCurrentBrowsedFolder(BrowseTree.ROOT);
                        rootNode.setExpectedChildren(playerList.size());
                        rootNode.setCached(true);
                        // mBrowseNode could be null when doing PTS test
                        // E.g. When flag mPTSTag is set to true.
                        if (mBrowseNode == null) {
                            mBrowseNode = rootNode;
                        }
                        sendFolderBroadcastAndUpdateNode();
                        notifyChanged(rootNode);
                    }
                    transitionTo(mConnected);
                    break;

                case MESSAGE_INTERNAL_CMD_TIMEOUT:
                    // We have timed out to execute the request, we should simply send
                    // whatever listing we have gotten until now.
                    Log.w(TAG, "TIMEOUT");
                    transitionTo(mConnected);
                    break;

                case MESSAGE_PROCESS_GET_FOLDER_ITEMS_OUT_OF_RANGE:
                    // If we have gotten an error for OUT OF RANGE we have
                    // already sent all the items to the client hence simply
                    // transition to Connected state here.
                    mBrowseNode.setCached(true);
                    transitionTo(mConnected);
                    break;

                case MESSAGE_GET_FOLDER_ITEMS:
                    if (!mBrowseNode.equals(msg.obj)) {
                        if (shouldAbort(mBrowseNode.getScope(),
                                ((BrowseTree.BrowseNode) msg.obj).getScope())) {
                            mAbort = true;
                        }
                        deferMessage(msg);
                        logD("GetFolderItems: Go Get Another Directory");
                    } else {
                        logD("GetFolderItems: Get The Same Directory, ignore");
                    }
                    break;

                case MSG_AVRCP_SEARCH:
                    mAbort = true;
                    deferMessage(msg);
                    break;

                default:
                    // All of these messages should be handled by parent state immediately.
                    return false;
            }
            return true;
        }

        /**
         * shouldAbort calculates the cases where fetching the current directory is no longer
         * necessary.
         *
         * @return true:  a new folder in the same scope
         * a new player while fetching contents of a folder
         * false: other cases, specifically Now Playing while fetching a folder
         */
        private boolean shouldAbort(int currentScope, int fetchScope) {
            if ((currentScope == fetchScope)
                    || (currentScope == AvrcpControllerService.BROWSE_SCOPE_VFS
                    && fetchScope == AvrcpControllerService.BROWSE_SCOPE_PLAYER_LIST)) {
                return true;
            }
            return false;
        }

        private void fetchContents(BrowseTree.BrowseNode target) {
            int start = target.getChildrenCount();
            int end = Math.min(target.getExpectedChildren(), target.getChildrenCount()
                    + ITEM_PAGE_SIZE) - 1;
            logD("fetchContents(title=" + target.getID() + ", scope=" + target.getScope()
                    + ", start=" + start + ", end=" + end + ", expected="
                    + target.getExpectedChildren() + ")");
            switch (target.getScope()) {
                case AvrcpControllerService.BROWSE_SCOPE_PLAYER_LIST:
                    mService.getPlayerListNative(mDeviceAddress,
                            start, end);
                    break;
                case AvrcpControllerService.BROWSE_SCOPE_NOW_PLAYING:
                    mService.getNowPlayingListNative(
                            mDeviceAddress, start, end);
                    break;
                case AvrcpControllerService.BROWSE_SCOPE_VFS:
                    mService.getFolderListNative(mDeviceAddress,
                            start, end);
                    break;
                case AvrcpControllerService.BROWSE_SCOPE_SEARCH:
                    AvrcpControllerService.getSearchListNative(
                            mDeviceAddress, start, end);
                    break;

                default:
                    Log.e(TAG, STATE_TAG + " Scope " + target.getScope()
                            + " cannot be handled here.");
            }
        }

        /* One of several things can happen when trying to get a folder list
         *
         *
         * 0: The folder handle is no longer valid
         * 1: The folder contents can be retrieved directly (NowPlaying, Root, Current)
         * 2: The folder is a browsable player
         * 3: The folder is a non browsable player
         * 4: The folder is not a child of the current folder
         * 5: The folder is a child of the current folder
         *
         */
        private void navigateToFolderOrRetrieve(BrowseTree.BrowseNode target) {
            mNextStep = mBrowseTree.getNextStepToFolder(target);
            logD("NAVIGATING From "
                    + mBrowseTree.getCurrentBrowsedFolder().toString());
            logD("NAVIGATING Toward " + target.toString());
            if (mNextStep == null) {
                return;
            } else if (target.equals(mBrowseTree.mNowPlayingNode)
                    || target.equals(mBrowseTree.mRootNode)
                    || mNextStep.equals(mBrowseTree.getCurrentBrowsedFolder())
                    || target.equals(mBrowseTree.mSearchNode)) {
                fetchContents(mNextStep);
            } else if (mNextStep.isPlayer()) {
                logD("NAVIGATING Player " + mNextStep.toString());
                if (mNextStep.isBrowsable()) {
                    mService.setBrowsedPlayerNative(
                            mDeviceAddress, (int) mNextStep.getBluetoothID());
                } else {
                    logD("Player doesn't support browsing");
                    mNextStep.setCached(true);
                    transitionTo(mConnected);
                }
            } else if (mNextStep.equals(mBrowseTree.mNavigateUpNode)) {
                logD("NAVIGATING UP " + mNextStep.toString());
                mNextStep = mBrowseTree.getCurrentBrowsedFolder().getParent();
                mBrowseTree.getCurrentBrowsedFolder().setCached(false);
                removeUnusedArtworkFromBrowseTree();
                mService.changeFolderPathNative(
                        mDeviceAddress, mUidCounter,
                        AvrcpControllerService.FOLDER_NAVIGATION_DIRECTION_UP,
                        0);

            } else {
                logD("NAVIGATING DOWN " + mNextStep.toString());
                mService.changeFolderPathNative(
                        mDeviceAddress, mUidCounter,
                        AvrcpControllerService.FOLDER_NAVIGATION_DIRECTION_DOWN,
                        mNextStep.getBluetoothID());
            }
        }

        // Broadcast results into BTTestApp for PTS verification
        private void sendFolderBroadcastAndUpdateNode() {
            // This broadcast is for PTS test only
            if (!Utils.isPtsTestMode()) {
                return;
            }

            String id = mBrowseNode.getID();
            logD("sendFolderBroadcastAndUpdateNode, folderID: " + id + ", size: " + mBrowseNode.getChildrenCount());

            List<MediaItem> list = mBrowseNode.getContents();
            ArrayList<MediaItem> folderList = new ArrayList<MediaItem>(0);
            for(MediaItem folder: list) {
                folderList.add(folder);
            }

            Intent intent = new Intent(AvrcpControllerService.ACTION_FOLDER_LIST);
            intent.putExtra(AvrcpControllerService.EXTRA_FOLDER_ID, id);
            intent.putParcelableArrayListExtra(AvrcpControllerService.EXTRA_FOLDER_LIST, folderList);
            mService.sendBroadcast(intent, ProfileService.BLUETOOTH_PERM);

            return;
        }

        @Override
        public void exit() {
            removeMessages(MESSAGE_INTERNAL_CMD_TIMEOUT);
            mBrowseNode = null;
            super.exit();
        }
    }

    // Handle the search action
    class Search extends State {
        private String STATE_TAG = "Avrcp.Search";

        public boolean isSearchingSupported() {
            boolean supported = false;
            BrowseTree.BrowseNode currBrPlayer =
                mBrowseTree.getCurrentBrowsedPlayer();
            if (currBrPlayer != null && currBrPlayer.mItem != null) {
                long UID = currBrPlayer.mItem.getUid();
                AvrcpPlayer player = mAvailablePlayerList.get((int)UID);
                supported = player.isSearchingSupported();
            }
            return supported;
        }

        @Override
        public void enter() {
            super.enter();
            sendMessageDelayed(MESSAGE_INTERNAL_CMD_TIMEOUT, CMD_TIMEOUT_MILLIS);
        }

        @Override
        public boolean processMessage(Message msg) {
            if (DBG) Log.d(STATE_TAG, "processMessage " + msg);
            switch (msg.what) {
                case MESSAGE_PROCESS_SEARCH_RESP:
                    int status = msg.arg1;
                    int items = msg.arg2;
                    if (DBG) Log.d(STATE_TAG, "search response, status: " + status + ", items: " + items);
                    mBrowseTree.mSearchNode.setExpectedChildren(items);
                    broadcastNumOfItems(CUSTOM_ACTION_SEARCH, status, items);

                    if (isActive()) {
                        refreshSearchNode(true);
                    }
                    transitionTo(mConnected);
                    break;

                case MESSAGE_INTERNAL_CMD_TIMEOUT:
                    Log.e(STATE_TAG, "search timeout");
                    transitionTo(mConnected);
                    break;

                default:
                    if (DBG) Log.d(STATE_TAG, "deferring message " + msg + " to connected!");
                    deferMessage(msg);
            }
            return true;
        }

        @Override
        public void exit() {
            removeMessages(MESSAGE_INTERNAL_CMD_TIMEOUT);
            super.exit();
        }
    }

    class AddToNowPlaying extends State {
        private String STATE_TAG = "Avrcp.AddToNowPlaying";
        private String mMediaId = null;
        private int mScope = AvrcpControllerService.BROWSE_SCOPE_VFS;;

        private boolean isSupported() {
            boolean supported = false;
            BrowseTree.BrowseNode currBrPlayer =
                mBrowseTree.getCurrentBrowsedPlayer();

            if (currBrPlayer != null) {
                int playerId = (int)(currBrPlayer.getBluetoothID());
                if (DBG) {
                    Log.d(STATE_TAG, "current browsed playerId " + playerId);
                }
                for (int i = 0; i < mAvailablePlayerList.size(); i++) {
                    AvrcpPlayer player = mAvailablePlayerList.valueAt(i);
                    if (player.getId() == playerId) {
                        supported = player.supportsFeature(AvrcpPlayer.FEATURE_ADD_TO_NOWPLAYING);
                        break;
                    }
                }
            }
            return supported;
        }

        @Override
        public void enter() {
            Message msg = getCurrentMessage();
            if (msg.what == MSG_AVRCP_ADD_TO_NOW_PLAYING) {
                mMediaId = ((Bundle) msg.obj).getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
                mScope = ((Bundle) msg.obj).getInt(KEY_BROWSE_SCOPE, 0);
            }

            BrowseTree.BrowseNode currItem = mBrowseTree.findBrowseNodeByID(mMediaId);
            Log.d(STATE_TAG, "processAddToNowPlayingReq mediaId=" + mMediaId + " node=" + currItem +
                " scope=" + mScope);

            if (currItem != null) {
                if (isSupported()) {
                    Log.d(STATE_TAG, "Add to now playing, scope: " + mScope);

                    if (mScope != AvrcpControllerService.BROWSE_SCOPE_PLAYER_LIST) {
                        AvrcpControllerService.addToNowPlayingNative(
                            mDeviceAddress, (byte)mScope,
                            currItem.getBluetoothID(), mUidCounter);
                        sendMessageDelayed(MESSAGE_INTERNAL_CMD_TIMEOUT, CMD_TIMEOUT_MILLIS);
                    } else {
                        Log.w(STATE_TAG, "Add to now playing invalid scope: " + mScope);
                        broadcastAddToNowPlayingResult(AvrcpControllerService.JNI_AVRC_STS_INVALID_SCOPE);
                        transitionTo(mConnected);
                    }
                } else {
                    Log.w(STATE_TAG, "Add to now playing not supported");
                    broadcastAddToNowPlayingResult(AvrcpControllerService.JNI_AVRC_STS_INVALID_CMD);
                    transitionTo(mConnected);
                }
            } else {
                transitionTo(mConnected);
            }
        }

        @Override
        public boolean processMessage(Message msg) {
            Log.d(STATE_TAG, "processMessage " + msg);
            switch (msg.what) {
                case MESSAGE_PROCESS_ADD_TO_NOW_PLAYING:
                    removeMessages(MESSAGE_INTERNAL_CMD_TIMEOUT);
                    broadcastAddToNowPlayingResult(msg.arg1);
                    transitionTo(mConnected);
                    break;

                case MESSAGE_INTERNAL_CMD_TIMEOUT:
                    transitionTo(mConnected);
                    break;

                case MESSAGE_PROCESS_UIDS_CHANGED:
                    processUIDSChange(msg);
                    break;

                default:
                    Log.d(STATE_TAG, "deferring message " + msg + " to connected!");
                    deferMessage(msg);
            }
            return true;
        }
    }

    protected class Disconnecting extends State {
        @Override
        public void enter() {
            disconnectCoverArt();
            onBrowsingDisconnected();
            setActive(false);
            broadcastConnectionStateChanged(BluetoothProfile.STATE_DISCONNECTING);
            transitionTo(mDisconnected);
        }
    }

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            try {
                mCarAudioManager = (CarAudioManager) mCar.getCarManager(Car.AUDIO_SERVICE);
                mVolumeGroupId = mCarAudioManager.getVolumeGroupIdForUsage(AudioAttributes.USAGE_MEDIA);

                mMaxVolume = mCarAudioManager.getGroupMaxVolume(mVolumeGroupId);
            } catch (CarNotConnectedException e) {
                Log.e(TAG, "Car is not connected!", e);
            } catch (NullPointerException e) {
                Log.e(TAG, "mCarAudioManager is NULL!", e);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.e(TAG, "Car service is disconnected");
        }
    };

    /**
     * Handle a request to align our local volume with the volume of a remote device. If
     * we're assuming the source volume is fixed then a response of ABS_VOL_MAX will always be
     * sent and no volume adjustment action will be taken on the sink side.
     *
     * @param absVol A volume level based on a domain of [0, ABS_VOL_MAX]
     * @param label Volume notification label
     */
    private void handleAbsVolumeRequest(int absVol, int label) {
        logD("handleAbsVolumeRequest: absVol = " + absVol + ", label = " + label);
        mVolumeChangedNotificationsToIgnore++;
        removeMessages(MESSAGE_INTERNAL_ABS_VOL_TIMEOUT);
        sendMessageDelayed(MESSAGE_INTERNAL_ABS_VOL_TIMEOUT,
                ABS_VOL_TIMEOUT_MILLIS);
        setAbsVolume(absVol);
        mService.sendAbsVolRspNative(mDeviceAddress, absVol, label);
    }

    /**
     * Align our volume with a requested absolute volume level
     *
     * @param absVol A volume level based on a domain of [0, ABS_VOL_MAX]
     */
    private void setAbsVolume(int absVol) {
        int currIndex = 0;

        try {
            currIndex = mCarAudioManager.getGroupVolume(mVolumeGroupId);
        } catch (CarNotConnectedException e) {
            Log.e(TAG, "Car is not connected", e);
        } catch (NullPointerException e) {
            Log.e(TAG, "mCarAudioManager is NULL!", e);
        }

        int newIndex = (mMaxVolume * absVol) / ABS_VOL_BASE;
        logD(" setAbsVolume =" + absVol + " maxVol = " + mMaxVolume
                + " cur = " + currIndex + " new = " + newIndex);

        /*
         * In some cases change in percentage is not sufficient enough to warrant
         * change in index values which are in range of 0-15. For such cases
         * no action is required
         */
        if (newIndex != currIndex) {
            try {
                mCarAudioManager.setGroupVolume(mVolumeGroupId, newIndex,
                        AudioManager.FLAG_SHOW_UI);
            } catch (CarNotConnectedException e) {
                Log.e(TAG, "Car is not connected", e);
            } catch (NullPointerException e) {
                Log.e(TAG, "mCarAudioManager is NULL!", e);
            }
        }
    }

    private int getAbsVolume() {
        int currIndex = 0;
        int newIndex = 0;

        try {
            currIndex = mCarAudioManager.getGroupVolume(mVolumeGroupId);
        } catch (CarNotConnectedException e) {
            Log.e(TAG, "Car is not connected", e);
        } catch (NullPointerException e) {
            Log.e(TAG, "mCarAudioManager is NULL!", e);
        }

        if (mMaxVolume != 0) {
            Log.w(TAG, "mMaxVolume is not updated!");
            newIndex = (currIndex * ABS_VOL_BASE) / mMaxVolume;
        }

        return newIndex;
    }

    private void processUIDSChange(Message msg) {
        BluetoothDevice device = (BluetoothDevice) msg.obj;
        int uidCounter = msg.arg1;
        if (DBG) {
            Log.d(TAG, " processUIDSChange device: " + device + ", uidCounter: " + uidCounter);
        }
        mUidCounter = uidCounter;

        if (Utils.isPtsTestMode()) {
            refreshCoverArt();
        }

        Intent intent_uids = new Intent(BluetoothAvrcpController.ACTION_UIDS_EVENT);
        intent_uids.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        mService.sendBroadcast(intent_uids, ProfileService.BLUETOOTH_PERM);
    }

    private boolean shouldDownloadBrowsedImages() {
        return mService.getResources()
                .getBoolean(R.bool.avrcp_controller_cover_art_browsed_images);
    }

    private void downloadImageIfNeeded(AvrcpItem track) {
        downloadImageIfNeeded(track, false);
    }

    private void downloadImageIfNeeded(AvrcpItem track, boolean forced) {
        if (mCoverArtManager == null) return;
        String uuid = track.getCoverArtUuid();
        Uri imageUri = null;
        if (uuid != null) {
            if (forced) {
                if (mCoverArtManager.getHandleForUuid(mDevice, uuid) != null) {
                    mCoverArtManager.downloadImage(mDevice, uuid, forced);
                } else {
                    mService.getElementAttributesNative(mDeviceAddress, (byte)0, null);
                }
            } else {
                imageUri = mCoverArtManager.getImageUri(mDevice, uuid);
                if (imageUri != null) {
                    track.setCoverArtLocation(imageUri);
                } else {
                    mCoverArtManager.downloadImage(mDevice, uuid);
                }
            }
        }
    }

    MediaSessionCompat.Callback mSessionCallbacks = new MediaSessionCompat.Callback() {
        @Override
        public void onPlay() {
            logD("onPlay");
            onPrepare();
            sendMessage(MSG_AVRCP_PASSTHRU, AvrcpControllerService.PASS_THRU_CMD_ID_PLAY);
        }

        @Override
        public void onPause() {
            logD("onPause");
            sendMessage(MSG_AVRCP_PASSTHRU, AvrcpControllerService.PASS_THRU_CMD_ID_PAUSE);
        }

        @Override
        public void onSkipToNext() {
            logD("onSkipToNext");
            onPrepare();
            sendMessage(MSG_AVRCP_PASSTHRU, AvrcpControllerService.PASS_THRU_CMD_ID_FORWARD);
        }

        @Override
        public void onSkipToPrevious() {
            logD("onSkipToPrevious");
            onPrepare();
            sendMessage(MSG_AVRCP_PASSTHRU, AvrcpControllerService.PASS_THRU_CMD_ID_BACKWARD);
        }

        @Override
        public void onSkipToQueueItem(long id) {
            logD("onSkipToQueueItem id=" + id);
            onPrepare();
            BrowseTree.BrowseNode node = mBrowseTree.getTrackFromNowPlayingList((int) id);
            if (node != null) {
                sendMessage(MESSAGE_PLAY_ITEM, node);
            }
        }

        @Override
        public void onStop() {
            logD("onStop");
            sendMessage(MSG_AVRCP_PASSTHRU, AvrcpControllerService.PASS_THRU_CMD_ID_STOP);
        }

        @Override
        public void onPrepare() {
            logD("onPrepare");
            A2dpSinkService a2dpSinkService = A2dpSinkService.getA2dpSinkService();
            if (a2dpSinkService != null) {
                a2dpSinkService.requestAudioFocus(mDevice, true);
            }
        }

        @Override
        public void onRewind() {
            logD("onRewind");
            sendMessage(MSG_AVRCP_PASSTHRU, AvrcpControllerService.PASS_THRU_CMD_ID_REWIND);
        }

        @Override
        public void onFastForward() {
            logD("onFastForward");
            sendMessage(MSG_AVRCP_PASSTHRU, AvrcpControllerService.PASS_THRU_CMD_ID_FF);
        }

        @Override
        public void onPlayFromMediaId(String mediaId, Bundle extras) {
            logD("onPlayFromMediaId");
            // Play the item if possible.
            onPrepare();
            BrowseTree.BrowseNode node = mBrowseTree.findBrowseNodeByID(mediaId);
            if (node != null) {
                // node was found on this bluetooth device
                sendMessage(MESSAGE_PLAY_ITEM, node);
            } else {
                // node was not found on this device, pause here, and play on another device
                sendMessage(MSG_AVRCP_PASSTHRU, AvrcpControllerService.PASS_THRU_CMD_ID_PAUSE);
                mService.playItem(mediaId);
            }
        }

        @Override
        public void onCustomAction(String action, Bundle extras) {
            logD("onCustomAction:" + action);
            if (CUSTOM_ACTION_SEARCH.equals(action)) {
                handleCustomActionSearch(extras);
            } else if (CUSTOM_ACTION_SEND_PASS_THRU_CMD.equals(action)) {
                handleCustomActionSendPassThruCmd(extras);
            } else if (CUSTOM_ACTION_GET_ITEM_ATTR.equals(action)) {
                handleCustomActionGetItemAttributes(extras);
            } else if (CUSTOM_ACTION_GET_ELEMENT_ATTR.equals(action)) {
                handleCustomActionGetElementAttributes(extras);
            } else if (CUSTOM_ACTION_GET_FOLDER_ITEM.equals(action)) {
                handleCustomActionGetFolderItems(extras);
            } else if (CUSTOM_ACTION_REQUEST_CONTINUING_RESPONSE.equals(action)) {
                handleCustomActionRequestContinuingResponse(extras);
            } else if (CUSTOM_ACTION_ABORT_CONTINUING_RESPONSE.equals(action)) {
                handleCustomActionAbortContinuingResponse(extras);
            } else if (CUSTOM_ACTION_ADD_TO_NOW_PLAYING.equals(action)) {
                handleCustomActionAddToNowPlaying(extras);
            } else {
                Log.w(TAG, "Custom action " + action + " not supported.");
            }
        }

        @Override
        public void onSetRepeatMode(int repeatMode) {
            logD("onSetRepeatMode");
            sendMessage(MSG_AVRCP_SET_REPEAT, repeatMode);
        }

        @Override
        public void onSetShuffleMode(int shuffleMode) {
            logD("onSetShuffleMode");
            sendMessage(MSG_AVRCP_SET_SHUFFLE, shuffleMode);

        }
    };

    protected void broadcastConnectionStateChanged(int currentState) {
        if (mMostRecentState == currentState) {
            return;
        }
        if (currentState == BluetoothProfile.STATE_CONNECTED) {
            MetricsLogger.logProfileConnectionEvent(
                    BluetoothMetricsProto.ProfileId.AVRCP_CONTROLLER);
        }
        logD("Connection state " + mDevice + ": " + mMostRecentState + "->" + currentState);
        Intent intent = new Intent(BluetoothAvrcpController.ACTION_CONNECTION_STATE_CHANGED);
        intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, mMostRecentState);
        intent.putExtra(BluetoothProfile.EXTRA_STATE, currentState);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, mDevice);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        mMostRecentState = currentState;
        mService.sendBroadcast(intent, ProfileService.BLUETOOTH_PERM);
    }

    private void broadcastAddToNowPlayingResult(int status) {
        logD("broadcastAddToNowPlayingResult status: " + status);
        broadcastResult(CUSTOM_ACTION_ADD_TO_NOW_PLAYING, status);
    }

    private void broadcastResult(String cmd, int status) {
        int result = getResult(status);
        logD("broadcastResult cmd: " + cmd + ", result: " + result + ", status: " + status);

        Intent intent = new Intent(ACTION_CUSTOM_ACTION_RESULT);
        intent.putExtra(EXTRA_CUSTOM_ACTION, cmd);
        intent.putExtra(EXTRA_CUSTOM_ACTION_RESULT, result);

        mService.sendBroadcast(intent, ProfileService.BLUETOOTH_PERM);
    }

    private boolean shouldRequestFocus() {
        return mService.getResources()
                .getBoolean(R.bool.a2dp_sink_automatically_request_audio_focus);
    }

    private void handleCustomActionSearch(Bundle extras) {
        logD("handleCustomActionSearch extras: " + extras);
        if (extras == null) {
            return;
        }

        String searchQuery = extras.getString(KEY_SEARCH);
        sendMessage(MSG_AVRCP_SEARCH, searchQuery);
    }

    public void handleCustomActionSendPassThruCmd(Bundle extras) {
        logD("handleCustomActionSendPassThruCmd extras: " + extras);
        if (extras == null) {
            return;
        }

        int cmd = extras.getInt(KEY_CMD);
        int state = extras.getInt(KEY_STATE);
        sendMessage(MSG_AVRCP_PASSTHRU_EXT, cmd, state);
    }

    public void handleCustomActionGetItemAttributes(Bundle extras) {
        logD("handleCustomActionGetItemAttributes extras: " + extras);
        if (extras == null) {
            return;
        }

        sendMessage(MSG_AVRCP_GET_ITEM_ATTR, extras);
    }

    public void handleCustomActionGetElementAttributes(Bundle extras) {
        logD("handleCustomActionGetElementAttributes extras" + extras);
        if (extras == null) {
            return;
        }

        sendMessage(MSG_AVRCP_GET_ELEMENT_ATTR, extras);
    }

    public void handleCustomActionGetFolderItems(Bundle extras) {
        logD("handleCustomActionGetFolderItems extras: " + extras);
        if (extras == null) {
            return;
        }

        sendMessage(MSG_AVRCP_GET_FOLDER_ITEMS_PTS, extras);
    }

    public void handleCustomActionRequestContinuingResponse(Bundle extras) {
        logD("handleCustomActionRequestContinuingResponse extras: " + extras);
        if (extras == null) {
            return;
        }

        int pduId = extras.getInt(KEY_PDU_ID, 0);
        sendMessage(MSG_AVRCP_REQUEST_CONTINUING_RESPONSE, pduId, 0);
    }

    public void handleCustomActionAbortContinuingResponse(Bundle extras) {
        logD("handleCustomActionAbortContinuingResponse extras: " + extras);
        if (extras == null) {
            return;
        }

        int pduId = extras.getInt(KEY_PDU_ID, 0);
        sendMessage(MSG_AVRCP_ABORT_CONTINUING_RESPONSE, pduId, 0);
    }

    public void handleCustomActionAddToNowPlaying(Bundle extras) {
        logD("handleCustomActionAddToNowPlaying extras: " + extras);
        if (extras == null) {
            return;
        }

        sendMessage(MSG_AVRCP_ADD_TO_NOW_PLAYING, extras);
    }

    private void broadcastNumOfItems(String cmd, int status, int items) {
        logD("broadcastNumOfItems cmd: " + cmd + ", status: " + status + ", items: " + items);
        Intent intent = createIntent(cmd, status);
        intent.putExtra(EXTRA_NUM_OF_ITEMS, items);
        mService.sendBroadcast(intent, ProfileService.BLUETOOTH_PERM);
    }

    private Intent createIntent(String cmd, int status) {
        int result = getResult(status);
        Intent intent = new Intent(ACTION_CUSTOM_ACTION_RESULT);
        intent.putExtra(EXTRA_CUSTOM_ACTION, cmd);
        intent.putExtra(EXTRA_CUSTOM_ACTION_RESULT, result);
        return intent;
    }

    private int getResult(int status) {
        switch (status) {
            case AvrcpControllerService.JNI_AVRC_STS_NO_ERROR:
                return RESULT_SUCCESS;

            case AvrcpControllerService.JNI_AVRC_STS_INVALID_CMD:
                return RESULT_NOT_SUPPORTED;

            case AvrcpControllerService.JNI_AVRC_STS_INVALID_PARAMETER:
            case AvrcpControllerService.JNI_AVRC_STS_INVALID_SCOPE:
            case AvrcpControllerService.JNI_AVRC_INV_RANGE:
                return RESULT_INVALID_PARAMETER;

            default:
                return RESULT_ERROR;
        }
    }

}
