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
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser.MediaItem;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Message;
import android.os.Parcel;
import android.os.Parcelable;
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
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import java.util.ArrayList;
import java.util.List;

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
    //Set active device
    public static final int MESSAGE_SET_ACTIVE_DEVICE = 3;
    //No active device available, notify to application
    public static final int MESSAGE_CLEAR_ACTIVE_DEVICE = 4;

    //100->199 Internal Events
    protected static final int CLEANUP = 100;
    private static final int CONNECT_TIMEOUT = 101;

    static final int MESSAGE_PROCESS_AVAILABLE_PLAYER_CHANGED = 156;

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
    static final int MESSAGE_PROCESS_UIDS_CHANGED = 219;
    static final int MESSAGE_PROCESS_RC_FEATURES = 220;
    static final int MESSAGE_PROCESS_ERROR_STATUS_CODE = 221;

    //300->399 Events for Browsing
    static final int MESSAGE_GET_FOLDER_ITEMS = 300;
    static final int MESSAGE_PLAY_ITEM = 301;
    static final int MSG_AVRCP_PASSTHRU = 302;
    static final int MSG_AVRCP_SET_SHUFFLE = 303;
    static final int MSG_AVRCP_SET_REPEAT = 304;
    // Internal message sent when to issue pass-through command with key state (pressed/released).
    static final int MSG_AVRCP_PASSTHRU_EXT = 305;

    static final int MESSAGE_INTERNAL_ABS_VOL_TIMEOUT = 404;

    private static final int L2CAP_PSM_UNDEFINED = -1;

    // commands for BIP
    public static final int MESSAGE_BIP_CONNECTED = 500;
    public static final int MESSAGE_BIP_DISCONNECTED = 501;
    public static final int MESSAGE_BIP_THUMB_NAIL_FETCHED = 502;
    public static final int MESSAGE_BIP_IMAGE_FETCHED = 503;
    public static final int MESSAGE_BIP_CONNECT_TIMEOUT = 504;

    static final int BIP_RECONNECTION_DELAY_MILLTS = 100; //100ms

    // Event for active device
    static final int MESSAGE_PROCESS_ACTIVE_DEVICE_CHANGED = 600;

    /*
     * Base value for absolute volume from JNI
     */
    private static final int ABS_VOL_BASE = 127;

    /*
     * Notification types for Avrcp protocol JNI.
     */
    private static final byte NOTIFICATION_RSP_TYPE_INTERIM = 0x00;
    private static final byte NOTIFICATION_RSP_TYPE_CHANGED = 0x01;

    private Car mCar;
    private CarAudioManager mCarAudioManager;
    private static int mVolumeGroupId;
    private static int mMaxVolume;

    // Remove static to support multiple AvrcpControllerBipStateMachine instances
    private AvrcpControllerBipStateMachine mBipStateMachine;

    private static final int MAX_BIP_CONNECT_RETRIES = 3;

    protected final BluetoothDevice mDevice;
    protected final byte[] mDeviceAddress;
    protected final AvrcpControllerService mService;
    protected final Disconnected mDisconnected;
    protected final Connecting mConnecting;
    protected final Connected mConnected;
    protected final Disconnecting mDisconnecting;

    protected int mMostRecentState = BluetoothProfile.STATE_DISCONNECTED;

    boolean mRemoteControlConnected = false;
    boolean mBrowsingConnected = false;
    final BrowseTree mBrowseTree;
    private AvrcpPlayer mAddressedPlayer = new AvrcpPlayer();
    private int mAddressedPlayerId = -1;
    private int mUidCounter = -1;
    private SparseArray<AvrcpPlayer> mAvailablePlayerList = new SparseArray<AvrcpPlayer>();
    private int mVolumeChangedNotificationsToIgnore = 0;
    private int mRemoteFeatures;
    private int mVolumeNotificationLabel = -1;
    private int mBipL2capPsm;

    // Send pass through command (with key state)
    public static final String CUSTOM_ACTION_SEND_PASS_THRU_CMD =
        "com.android.bluetooth.avrcpcontroller.CUSTOM_ACTION_SEND_PASS_THRU_CMD";
    public static final String KEY_CMD = "cmd";
    public static final String KEY_STATE = "state";

    GetFolderList mGetFolderList = null;

    public static final String EXTRA_OPERATION_CODE =
       "com.android.bluetooth.avrcpcontroller.extra.OPERATION_CODE";
    public static final String EXTRA_ID =
       "com.android.bluetooth.avrcpcontroller.extra.ID";
    public static final String EXTRA_STATUS =
       "com.android.bluetooth.avrcpcontroller.extra.STATUS";

    //Number of items to get in a single fetch
    static final int ITEM_PAGE_SIZE = 20;
    static final int CMD_TIMEOUT_MILLIS = 10000;
    static final int ABS_VOL_TIMEOUT_MILLIS = 1000; //1s

    // Identify whether BIP is in reconnection
    private boolean mBipReconnectonFlag = false;
    private int mRetryBipAttempt= 0;

    AvrcpControllerStateMachine(BluetoothDevice device, AvrcpControllerService service) {
        super(TAG);
        mDevice = device;
        mDeviceAddress = Utils.getByteAddress(mDevice);
        mService = service;
        mRemoteFeatures = BluetoothAvrcpController.BTRC_FEAT_NONE;
        mBipL2capPsm = L2CAP_PSM_UNDEFINED;
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

        mCar = Car.createCar(service.getApplicationContext(), mConnection);
        mCar.connect();

        setInitialState(mDisconnected);
        mBipStateMachine = AvrcpControllerBipStateMachine.make(this, getHandler(), service);
        resetRetryBipCount();
    }

    public void doQuit() {
        Log.d(TAG, "doQuit");
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

    BrowseTree.BrowseNode getTrackFromNowPlayingList(int trackNumber) {
        logD("getTrackFromNowPlayingList trackNumber " + trackNumber);
        return mBrowseTree.getTrackFromNowPlayingList(trackNumber);
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
     * Get the current playback state
     *
     * @return current playback state
     */
    public int getPlaybackState() {
        return mAddressedPlayer.getPlaybackState().getState();
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

    public synchronized void setRemoteBipPsm( int remotePsm) {
        mBipL2capPsm = remotePsm;
    }

    public synchronized int getRemoteBipPsm () {
        return mBipL2capPsm;
    }

    public synchronized boolean isCoverArtSupported() {
        return ((mRemoteFeatures & BluetoothAvrcpController.BTRC_FEAT_COVER_ART) != 0);
    }

    public synchronized boolean isAvrcpVersion1_0() {
        return getRemoteFeatures() == BluetoothAvrcpController.BTRC_FEAT_NONE;
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
     * send set active device command asynchronously
     */
    public void setActiveDevice() {
        sendMessage(MESSAGE_SET_ACTIVE_DEVICE);
    }

    /**
     * send clear active device command asynchronously
     */
    public void clearActiveDevice() {
        sendMessage(MESSAGE_CLEAR_ACTIVE_DEVICE);
    }

    /**
     * Dump the current State Machine to the string builder.
     *
     * @param sb output string
     */
    public void dump(StringBuilder sb) {
        ProfileService.println(sb, "mDevice: " + mDevice.getAddress() + "("
                + mDevice.getName() + ") " + this.toString());
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
        mService.sBrowseTree.mRootNode.addChild(mBrowseTree.mRootNode);
        BluetoothMediaBrowserService.notifyChanged(mService
                .sBrowseTree.mRootNode);
        mBrowsingConnected = true;
    }

    synchronized void onBrowsingDisconnected() {
        if (!mBrowsingConnected) return;
        mAddressedPlayer.setPlayStatus(PlaybackStateCompat.STATE_ERROR);
        mAddressedPlayer.updateCurrentTrack(null);
        mBrowseTree.mNowPlayingNode.setCached(false);
        BluetoothMediaBrowserService.notifyChanged(mBrowseTree.mNowPlayingNode);
        mService.sBrowseTree.mRootNode.removeChild(
                mBrowseTree.mRootNode);
        BluetoothMediaBrowserService.notifyChanged(mService
                .sBrowseTree.mRootNode);
        mBrowsingConnected = false;
    }

    private void notifyChanged(BrowseTree.BrowseNode node) {
        BluetoothMediaBrowserService.notifyChanged(node);
    }

    void requestContents(BrowseTree.BrowseNode node) {
        sendMessage(MESSAGE_GET_FOLDER_ITEMS, node);

        logD("Fetching " + node);
    }

    void nowPlayingContentChanged() {
        mBrowseTree.mNowPlayingNode.setCached(false);
        sendMessage(MESSAGE_GET_FOLDER_ITEMS, mBrowseTree.mNowPlayingNode);
    }

    protected class Disconnected extends State {
        @Override
        public void enter() {
            logD("Enter Disconnected");
            if (mMostRecentState != BluetoothProfile.STATE_DISCONNECTED) {
                sendMessage(CLEANUP);
            }
            broadcastConnectionStateChanged(BluetoothProfile.STATE_DISCONNECTED);
        }

        @Override
        public boolean processMessage(Message message) {
            switch (message.what) {
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
                broadcastConnectionStateChanged(BluetoothProfile.STATE_CONNECTED);
            } else {
                logD("ReEnteringConnected");
            }
            super.enter();
        }

        @Override
        public boolean processMessage(Message msg) {
            logD(STATE_TAG + " " + mDevice + " processMessage " + msg.what);
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
                    playItem((BrowseTree.BrowseNode) msg.obj);
                    return true;

                case MSG_AVRCP_PASSTHRU:
                    passThru(msg.arg1);
                    return true;

                case MESSAGE_SET_ACTIVE_DEVICE:
                    mService.setActiveDeviceNative(mDeviceAddress);
                    return true;

                case MESSAGE_CLEAR_ACTIVE_DEVICE:
                    processClearActiveDevice();
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

                case MESSAGE_PROCESS_TRACK_CHANGED:
                    TrackInfo trackInfo = (TrackInfo) msg.obj;
                    mAddressedPlayer.updateCurrentTrack(trackInfo);

                    if (!mAddressedPlayer.getCurrentTrack().getCoverArtHandle().isEmpty()
                            && mBipStateMachine != null) {
                        int FLAG;
                        // Image or Thumbnail Image
                        if (AvrcpControllerBipStateMachine.mImageType.
                                equalsIgnoreCase("thumbnaillinked")) {
                            FLAG = AvrcpControllerBipStateMachine.
                            MESSAGE_FETCH_THUMBNAIL;
                        } else {
                            FLAG = AvrcpControllerBipStateMachine.MESSAGE_FETCH_IMAGE;
                        }
                        mBipStateMachine.sendMessage(FLAG,
                                mAddressedPlayer.getCurrentTrack().getCoverArtHandle());
                    } else {
                        if (isCoverArtSupported())
                            Log.e(TAG, " Cover Art Handle not valid ");
                    }
                    notifyTrackChanged(mAddressedPlayer.getCurrentTrack().getMetadata());
                    return true;

                case MESSAGE_PROCESS_PLAY_STATUS_CHANGED:
                    if (msg.arg1 != mAddressedPlayer.getPlaybackState().getState()) {
                        mAddressedPlayer.setPlayStatus(msg.arg1);
                        notifyPlaystateChanged(mAddressedPlayer.getPlaybackState());
                    }
                    if (mAddressedPlayer.getPlaybackState().getState()
                            == PlaybackStateCompat.STATE_PLAYING
                            && A2dpSinkService.getFocusState() == AudioManager.AUDIOFOCUS_NONE) {
                        if (shouldRequestFocus()) {
                            mService.getMediaSessionCallback().onPrepare();
                        } else {
                        sendMessage(MSG_AVRCP_PASSTHRU,
                                AvrcpControllerService.PASS_THRU_CMD_ID_PAUSE);
                        }
                    }
                    return true;

                case MESSAGE_PROCESS_PLAY_POS_CHANGED:
                    if (msg.arg2 != -1) {
                        mAddressedPlayer.setPlayTime(msg.arg2);
                        notifyPlaystateChanged(mAddressedPlayer.getPlaybackState());
                    }
                    return true;

                case MESSAGE_PROCESS_ADDRESSED_PLAYER_CHANGED:
                    mAddressedPlayerId = msg.arg1;
                    logD("AddressedPlayer = " + mAddressedPlayerId);
                    AvrcpPlayer updatedPlayer = mAvailablePlayerList.get(mAddressedPlayerId);
                    if (updatedPlayer != null) {
                        mAddressedPlayer = updatedPlayer;
                        logD("AddressedPlayer = " + mAddressedPlayer.getName());
                    } else {
                        mBrowseTree.mRootNode.setCached(false);
                        mBrowseTree.mRootNode.setExpectedChildren(255);
                        BluetoothMediaBrowserService.notifyChanged(mBrowseTree.mRootNode);
                    }
                    // Get playback state for new addressed player
                    mService.getPlaybackStateNative(mDeviceAddress);
                    return true;

                case MESSAGE_PROCESS_AVAILABLE_PLAYER_CHANGED:
                    processAvailablePlayerChanged();
                    return true;

                case MESSAGE_PROCESS_SUPPORTED_APPLICATION_SETTINGS:
                    mAddressedPlayer.setSupportedPlayerApplicationSettings(
                            (PlayerApplicationSettings) msg.obj);
                    notifyPlaystateChanged(mAddressedPlayer.getPlaybackState());
                    return true;

                case MESSAGE_PROCESS_CURRENT_APPLICATION_SETTINGS:
                    mAddressedPlayer.setCurrentPlayerApplicationSettings(
                            (PlayerApplicationSettings) msg.obj);
                    notifyPlaystateChanged(mAddressedPlayer.getPlaybackState());
                    return true;

                case DISCONNECT:
                    mBipStateMachine.sendMessage(
                        AvrcpControllerBipStateMachine.MESSAGE_CLEAR_COVEARART_CACHE);
                    mBipStateMachine.sendMessage(
                        AvrcpControllerBipStateMachine.MESSAGE_DISCONNECT_BIP, mDevice);
                    transitionTo(mDisconnecting);
                    return true;

                case MESSAGE_PROCESS_UIDS_CHANGED:
                    processUIDSChange(msg);
                    return true;

                case MESSAGE_PROCESS_RC_FEATURES:
                    setRemoteFeatures(msg.arg1);

                    // Set active device directly if peer device only supports AVRCP 1.0
                    if (mService.getActiveDevice() == null
                            && isAvrcpVersion1_0()) {
                        setActiveDevice();
                    }

                    if (isCoverArtSupported() && mBipStateMachine != null) {
                        setRemoteBipPsm(msg.arg2);
                        mBipStateMachine.sendMessage(AvrcpControllerBipStateMachine.
                            MESSAGE_CONNECT_BIP, getRemoteBipPsm(), 0,
                            mDevice);
                    }
                    return true;

                case MESSAGE_BIP_CONNECTED:
                    processBipConnected();
                    return true;

                case MESSAGE_BIP_DISCONNECTED:
                    processBipDisconnected();
                    return true;

                case MESSAGE_BIP_CONNECT_TIMEOUT:
                    processBipConnectTimeout();
                    return true;

                case MESSAGE_BIP_IMAGE_FETCHED:
                    processBipImageFetched(msg);
                    return true;

                case MESSAGE_BIP_THUMB_NAIL_FETCHED:
                    processBipThumbNailFetched(msg);
                    return true;

                case MESSAGE_PROCESS_ACTIVE_DEVICE_CHANGED:
                    processActiveDeviceChanged(msg.arg1);
                    return true;

                case MESSAGE_PROCESS_ERROR_STATUS_CODE:
                    processErrorStatusCode((Bundle) msg.obj);
                    return true;

                default:
                    return super.processMessage(msg);
            }

        }

        private void playItem(BrowseTree.BrowseNode node) {
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
                    // FF/RW is in progress and other operation is desired
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
                // Change playback status directly if peer device only supports AVRCP 1.0
                if (isAvrcpVersion1_0()) {
                    if (cmd == AvrcpControllerService.PASS_THRU_CMD_ID_PAUSE) {
                        mAddressedPlayer.setPlayStatus(PlaybackStateCompat.STATE_PAUSED);
                        notifyPlaystateChanged(mAddressedPlayer.getPlaybackState());
                    } else {
                        // Similar with PASS_THRU_CMD_ID_PLAY, music playing is started after
                        // switching(PASS_THRU_CMD_ID_FORWARD/PASS_THRU_CMD_ID_BACKWARD)
                        mAddressedPlayer.setPlayStatus(PlaybackStateCompat.STATE_PLAYING);
                        notifyPlaystateChanged(mAddressedPlayer.getPlaybackState());
                    }
                }
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

        private void processAvailablePlayerChanged() {
            logD("processAvailablePlayerChanged");
            mBrowseTree.mRootNode.setCached(false);
            mBrowseTree.mRootNode.setExpectedChildren(255);
            BluetoothMediaBrowserService.notifyChanged(mBrowseTree.mRootNode);
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
    }

    // Handle the get folder listing action
    // a) Fetch the listing of folders
    // b) Once completed return the object listing
    class GetFolderList extends State {
        private static final String STATE_TAG = "Avrcp.GetFolderList";

        boolean mAbort;
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
            }

            if (mBrowseNode == null) {
                transitionTo(mConnected);
            } else {
                navigateToFolderOrRetrieve(mBrowseNode);
            }
        }

        @Override
        public boolean processMessage(Message msg) {
            logD(STATE_TAG + " processMessage " + msg.what);
            switch (msg.what) {
                case MESSAGE_PROCESS_GET_FOLDER_ITEMS:
                    ArrayList<MediaItem> folderList = (ArrayList<MediaItem>) msg.obj;
                    int endIndicator = mBrowseNode.getExpectedChildren() - 1;
                    logD("GetFolderItems: End " + endIndicator
                            + " received " + folderList.size());

                    // Always update the node so that the user does not wait forever
                    // for the list to populate.
                    mBrowseNode.addChildren(folderList);
                    notifyChanged(mBrowseNode);

                    if (mBrowseNode.getChildrenCount() >= endIndicator || folderList.size() == 0
                            || mAbort) {
                        // If we have fetched all the elements or if the remotes sends us 0 elements
                        // (which can lead us into a loop since mCurrInd does not proceed) we simply
                        // abort.
                        mBrowseNode.setCached(true);
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
                    mBrowseTree.setCurrentBrowsedPlayer(mNextStep.getID(), msg.arg1, msg.arg2);
                    removeMessages(MESSAGE_INTERNAL_CMD_TIMEOUT);
                    sendMessageDelayed(MESSAGE_INTERNAL_CMD_TIMEOUT, CMD_TIMEOUT_MILLIS);
                    navigateToFolderOrRetrieve(mBrowseNode);
                    break;

                case MESSAGE_PROCESS_FOLDER_PATH:
                    mBrowseTree.setCurrentBrowsedFolder(mNextStep.getID());
                    mBrowseTree.getCurrentBrowsedFolder().setExpectedChildren(msg.arg1);

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

                case CONNECT:
                case DISCONNECT:
                case MSG_AVRCP_PASSTHRU:
                case MSG_AVRCP_PASSTHRU_EXT:
                case MESSAGE_PROCESS_SET_ABS_VOL_CMD:
                case MESSAGE_PROCESS_REGISTER_ABS_VOL_NOTIFICATION:
                case MESSAGE_PROCESS_TRACK_CHANGED:
                case MESSAGE_PROCESS_PLAY_POS_CHANGED:
                case MESSAGE_PROCESS_PLAY_STATUS_CHANGED:
                case MESSAGE_PROCESS_VOLUME_CHANGED_NOTIFICATION:
                case MESSAGE_PLAY_ITEM:
                case MESSAGE_PROCESS_ADDRESSED_PLAYER_CHANGED:
                case MESSAGE_PROCESS_RC_FEATURES:
                    // All of these messages should be handled by parent state immediately.
                    return false;

                case MESSAGE_PROCESS_UIDS_CHANGED:
                    processUIDSChange(msg);
                    break;

                default:
                    logD(STATE_TAG + " deferring message " + msg.what
                                + " to connected!");
                    deferMessage(msg);
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
                    || mNextStep.equals(mBrowseTree.getCurrentBrowsedFolder())) {
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

        @Override
        public void exit() {
            removeMessages(MESSAGE_INTERNAL_CMD_TIMEOUT);
            mBrowseNode = null;
            super.exit();
        }
    }

    protected class Disconnecting extends State {
        @Override
        public void enter() {
            onBrowsingDisconnected();
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

    private void setBipReconnectionFlag(boolean flag) {
        mBipReconnectonFlag = flag;
    }

    private boolean getBipReconnectionFlag() {
        return mBipReconnectonFlag;
    }
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

        if (isCoverArtSupported() && mBipStateMachine != null) {
            mBipStateMachine.sendMessage(AvrcpControllerBipStateMachine.
                MESSAGE_DISCONNECT_BIP, getRemoteBipPsm(), 0,
                mDevice);
            setBipReconnectionFlag(true);
        }

        Intent intent_uids = new Intent(BluetoothAvrcpController.ACTION_UIDS_EVENT);
        intent_uids.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        mService.sendBroadcast(intent_uids, ProfileService.BLUETOOTH_PERM);

        // transition to mConnected might cause some operations blocked, such
        // as processing the message "MESSAGE_PROCESS_FOLDER_PATH", so
        // remove the logic of transtion state.
    }

    private void processBipConnected() {
        logD("processBipConnected");
        mBipStateMachine.updateRequiredImageProperties();
        resetRetryBipCount();
        if (!mAddressedPlayer.getCurrentTrack().getCoverArtHandle().isEmpty()) {
            int FLAG;
            // Image or Thumbnail Image
            if (AvrcpControllerBipStateMachine.mImageType.
                    equalsIgnoreCase("thumbnaillinked")) {
                FLAG = AvrcpControllerBipStateMachine.
                MESSAGE_FETCH_THUMBNAIL;
            } else {
                FLAG = AvrcpControllerBipStateMachine.MESSAGE_FETCH_IMAGE;
            }
            mBipStateMachine.sendMessage(FLAG,
                    mAddressedPlayer.getCurrentTrack().getCoverArtHandle());
        }
    }

    private void processBipDisconnected() {
        logD("processBipDisconnected");
        // Clear cover art related info for current track.
        mAddressedPlayer.getCurrentTrack().clearCoverArtData();

        if (getBipReconnectionFlag()) {
            mBipStateMachine.sendMessageDelayed(AvrcpControllerBipStateMachine.
                MESSAGE_CONNECT_BIP, getRemoteBipPsm(), 0,
                mDevice, BIP_RECONNECTION_DELAY_MILLTS);
            setBipReconnectionFlag(false);
        }
    }

    private void incrementRetryBipCount() {
        mRetryBipAttempt++;
    }

    private boolean canRetryBipConnect() {
        if (getRetryBipCount() < MAX_BIP_CONNECT_RETRIES)
            return true;
        else
            return false;
    }

    private int getRetryBipCount() {
        return mRetryBipAttempt;
    }

    private void resetRetryBipCount() {
        mRetryBipAttempt = 0;
    }

    private void processBipConnectTimeout() {
        Log.d(TAG, "processBipConnectTimeout");

        boolean retry = canRetryBipConnect();
        if (retry) {
            Log.d(TAG, "retry BIP connection with attempt: " + getRetryBipCount());
            mBipStateMachine.sendMessage(AvrcpControllerBipStateMachine.
                MESSAGE_CONNECT_BIP, getRemoteBipPsm(), 0,
                mDevice);
        }
        incrementRetryBipCount();
    }

    private void processBipImageFetched(Message msg) {
        logD("processBipImageFetched");
        boolean imageUpdated = mAddressedPlayer.getCurrentTrack().updateImageLocation(
          msg.getData().getString(AvrcpControllerBipStateMachine.COVER_ART_HANDLE),
          msg.getData().getString(AvrcpControllerBipStateMachine.COVER_ART_IMAGE_LOCATION));

        if (imageUpdated) {
            broadcastMetaDataChanged();
        }
    }

    private void processBipThumbNailFetched(Message msg) {
        logD("processBipThumbNailFetched");
        boolean thumbNailUpdated = mAddressedPlayer.getCurrentTrack().updateThumbNailLocation(
          msg.getData().getString(AvrcpControllerBipStateMachine.COVER_ART_HANDLE),
          msg.getData().getString(AvrcpControllerBipStateMachine.COVER_ART_IMAGE_LOCATION));

        if (thumbNailUpdated) {
            broadcastMetaDataChanged();
        }
    }

    private void notifyPlaystateChanged(PlaybackStateCompat state) {
        logD("notify play state changed " + state);
        if (mDevice.equals(mService.getActiveDevice())) {
            BluetoothMediaBrowserService.notifyChanged(state);
        }
    }

    private void notifyTrackChanged(MediaMetadata data) {
        logD("notify meta data changed " + data);
        if (mDevice.equals(mService.getActiveDevice())) {
            BluetoothMediaBrowserService.trackChanged(data);
        }
    }

    private void processActiveDeviceChanged(int result) {
        logD("processActiveDeviceChanged, result " + result);
        if (result == BluetoothAvrcpController.RESULT_SUCCESS) {
            mService.registerMediaSessionCallback();
            // Set default status to STOPPED if peer device only supports AVRCP 1.0
            if (isAvrcpVersion1_0()) {
                mAddressedPlayer.setPlayStatus(PlaybackStateCompat.STATE_STOPPED);
            }
            notifyPlaystateChanged(mAddressedPlayer.getPlaybackState());
            // Only valid if peer device AVRCP version is greater than 1.0
            if (!isAvrcpVersion1_0()) {
                notifyTrackChanged(mAddressedPlayer.getCurrentTrack().getMetadata());
            }
        }
        broadcastActiveDeviceChanged(result);
    }

    private void broadcastActiveDeviceChanged(int result) {
        logD("broadcastActiveDeviceChanged, result " + result);
        Intent intent = new Intent(BluetoothAvrcpController.ACTION_ACTIVE_DEVICE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, mDevice);
        intent.putExtra(BluetoothAvrcpController.EXTRA_RESULT, result);
        mService.sendBroadcast(intent, ProfileService.BLUETOOTH_PERM);
    }

    private void processClearActiveDevice() {
        if (DBG) {
            Log.d(TAG, "processClearActiveDevice");
        }
        BluetoothMediaBrowserService.trackChanged(null);
        BluetoothMediaBrowserService.addressedPlayerChanged(null);
        broadcastActiveDeviceChanged(BluetoothAvrcpController.RESULT_FAILURE);
    }

    private void processErrorStatusCode(Bundle extra) {
        int opcode = extra.getInt(EXTRA_OPERATION_CODE);
        int id = extra.getInt(EXTRA_ID);
        int status = extra.getInt(EXTRA_STATUS);

        broadcastErrorStatusCode(opcode, id, status);
    }

    private void broadcastErrorStatusCode(int opcode, int id, int status) {
        logD("broadcastErrorStatusCode opcode:" + opcode + ", id: " + id + ", status: " + status);

        Intent intent = new Intent(BluetoothMediaBrowserService.ACTION_ERROR_STATUS_CODE);
        intent.putExtra(BluetoothMediaBrowserService.EXTRA_OPERATION_CODE, opcode);
        intent.putExtra(BluetoothMediaBrowserService.EXTRA_ID, id);
        intent.putExtra(BluetoothMediaBrowserService.EXTRA_STATUS, status);
        mService.sendBroadcast(intent, ProfileService.BLUETOOTH_PERM);
    }

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

    private void broadcastMetaDataChanged() {
        if (!mDevice.equals(mService.getActiveDevice())) {
            return;
        }
        MediaMetadata metadata = mAddressedPlayer.getCurrentTrack().getMetadata();
        BluetoothMediaBrowserService.trackChanged(metadata);

        Intent intent = new Intent(AvrcpControllerService.ACTION_TRACK_EVENT);
        intent.putExtra(AvrcpControllerService.EXTRA_METADATA, metadata);
        logD(" broadcastMetaDataChanged = " + metadata.getDescription());
        mService.sendBroadcast(intent, ProfileService.BLUETOOTH_PERM);
    }

    private boolean shouldRequestFocus() {
        return mService.getResources()
                .getBoolean(R.bool.a2dp_sink_automatically_request_audio_focus);
    }

    public void handleCustomActionSendPassThruCmd(Bundle extras) {
        Log.d(TAG, "handleCustomActionSendPassThruCmd extras: " + extras);
        if (extras == null) {
            return;
        }

        int cmd = extras.getInt(KEY_CMD);
        int state = extras.getInt(KEY_STATE);
        sendMessage(MSG_AVRCP_PASSTHRU_EXT, cmd, state);
    }
}
