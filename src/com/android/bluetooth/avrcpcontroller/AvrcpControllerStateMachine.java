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
import android.bluetooth.BluetoothAvrcpPlayerSettings;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.media.AudioManager;
import android.media.browse.MediaBrowser;
import android.media.browse.MediaBrowser.MediaItem;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Message;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemProperties;
import android.car.Car;
import android.car.CarNotConnectedException;
import android.car.media.CarAudioManager;
import android.util.Log;

import com.android.bluetooth.Utils;
import com.android.bluetooth.a2dpsink.A2dpSinkService;
import com.android.bluetooth.a2dpsink.mbs.A2dpMediaBrowserService;
import com.android.bluetooth.btservice.ProfileService;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

/**
 * Provides Bluetooth AVRCP Controller State Machine responsible for all remote control connections
 * and interactions with a remote controlable device.
 */
class AvrcpControllerStateMachine extends StateMachine {

    public interface FolderListListener {
        void onFolderListUpdated(Bundle extra);
    }

    // commands from Binder service
    static final int MESSAGE_SEND_PASS_THROUGH_CMD = 1;
    static final int MESSAGE_SEND_GROUP_NAVIGATION_CMD = 3;
    static final int MESSAGE_GET_NOW_PLAYING_LIST = 5;
    static final int MESSAGE_GET_FOLDER_LIST = 6;
    static final int MESSAGE_GET_PLAYER_LIST = 7;
    static final int MESSAGE_CHANGE_FOLDER_PATH = 8;
    static final int MESSAGE_FETCH_ATTR_AND_PLAY_ITEM = 9;
    static final int MESSAGE_SET_BROWSED_PLAYER = 10;
    static final int MESSAGE_SEARCH = 50;  // vendor extension base
    static final int MESSAGE_GET_SEARCH_LIST = 51;
    // set current player application setting
    static final int MESSAGE_SET_CURRENT_PAS = 52;
    static final int MESSAGE_ADD_TO_NOW_PLAYING = 53;
    static final int MESSAGE_GET_ITEM_ATTR = 54;
    static final int MESSAGE_GET_ELEMENT_ATTR = 55;
    static final int MESSAGE_GET_FOLDER_ITEM = 56;
    static final int MESSAGE_GET_NUM_OF_ITEMS = 57;
    static final int MESSAGE_SET_ADDRESSED_PLAYER = 58;
    static final int MESSAGE_REQUEST_CONTINUING_RESPONSE = 59;
    static final int MESSAGE_ABORT_CONTINUING_RESPONSE = 60;
    static final int MESSAGE_RELEASE_CONNECTION = 61;

    // commands from native layer
    static final int MESSAGE_PROCESS_SET_ABS_VOL_CMD = 103;
    static final int MESSAGE_PROCESS_REGISTER_ABS_VOL_NOTIFICATION = 104;
    static final int MESSAGE_PROCESS_TRACK_CHANGED = 105;
    static final int MESSAGE_PROCESS_PLAY_POS_CHANGED = 106;
    static final int MESSAGE_PROCESS_PLAY_STATUS_CHANGED = 107;
    static final int MESSAGE_PROCESS_VOLUME_CHANGED_NOTIFICATION = 108;
    static final int MESSAGE_PROCESS_GET_FOLDER_ITEMS = 109;
    static final int MESSAGE_PROCESS_GET_FOLDER_ITEMS_OUT_OF_RANGE = 110;
    static final int MESSAGE_PROCESS_GET_PLAYER_ITEMS = 111;
    static final int MESSAGE_PROCESS_FOLDER_PATH = 112;
    static final int MESSAGE_PROCESS_SET_BROWSED_PLAYER = 113;
    static final int MESSAGE_PROCESS_SET_ADDRESSED_PLAYER = 114;
    static final int MESSAGE_PROCESS_UIDS_CHANGED = 115;
    static final int MESSAGE_PROCESS_SEARCH_RESP = 150;  // vendor extension base
    // list supported player application setting value
    static final int MESSAGE_PROCESS_LIST_PAS = 151;
    // player application setting value changed
    static final int MESSAGE_PROCESS_PAS_CHANGED = 152;
    static final int MESSAGE_PROCESS_ATTR_CHANGED = 153;
    static final int MESSAGE_PROCESS_NUM_OF_ITEMS = 154;
    static final int MESSAGE_PROCESS_ADDRESSED_PLAYER_CHANGED = 155;
    static final int MESSAGE_PROCESS_AVAILABLE_PLAYER_CHANGED = 156;
    static final int MESSAGE_PROCESS_NOW_PLAYING_CHANGED = 157;
    static final int MESSAGE_PROCESS_ADD_TO_NOW_PLAYING_RESP = 158;
    static final int MESSAGE_PROCESS_ERROR_STATUS_CODE = 159;

    // commands from A2DP sink
    static final int MESSAGE_STOP_METADATA_BROADCASTS = 201;
    static final int MESSAGE_START_METADATA_BROADCASTS = 202;

    // commands for connection
    static final int MESSAGE_PROCESS_RC_FEATURES = 301;
    static final int MESSAGE_PROCESS_CONNECTION_CHANGE = 302;
    static final int MESSAGE_PROCESS_BROWSE_CONNECTION_CHANGE = 303;

    // Interal messages
    static final int MESSAGE_INTERNAL_BROWSE_DEPTH_INCREMENT = 401;
    static final int MESSAGE_INTERNAL_MOVE_N_LEVELS_UP = 402;
    static final int MESSAGE_INTERNAL_CMD_TIMEOUT = 403;

    // commands for BIP
    public static final int MESSAGE_BIP_CONNECTED = 500;
    public static final int MESSAGE_BIP_DISCONNECTED = 501;
    public static final int MESSAGE_BIP_THUMB_NAIL_FETCHED = 502;
    public static final int MESSAGE_BIP_IMAGE_FETCHED = 503;
    public static final int MESSAGE_BIP_CONNECT_TIMEOUT = 504;

    public static final String EXTRA_OPERATION_CODE =
        "com.android.bluetooth.avrcpcontroller.extra.OPERATION_CODE";

    public static final String EXTRA_ID =
        "com.android.bluetooth.avrcpcontroller.extra.ID";

    public static final String EXTRA_STATUS =
        "com.android.bluetooth.avrcpcontroller.extra.STATUS";

    static final int CMD_TIMEOUT_MILLIS = 5000; // 5s
    // Fetch only 5 items at a time.
    static final int GET_FOLDER_ITEMS_PAGINATION_SIZE = 5;

    static final int BIP_RECONNECTION_DELAY_MILLTS = 100; //100ms

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
    private static final int  AVRC_CHARSET_UTF8 = 0x006A;

    private static final String TAG = "AvrcpControllerSM";
    private static final boolean DBG = true;
    private static final boolean VDBG = true;

    private final Context mContext;

    private final AudioManager mAudioManager;
    // Maximum audio volume for mAudioManager
    private final int mMaxAudioVolume;
    private CarAudioManager mCarAudioManager;
    private final Car mCar;
    private static AvrcpControllerBipStateMachine mBipStateMachine;

    private static final int MAX_BIP_CONNECT_RETRIES = 3;

    private final State mDisconnected;
    private final State mConnected;
    private final SetBrowsedPlayer mSetBrowsedPlayer;
    private final SetAddresedPlayerAndPlayItem mSetAddrPlayer;
    private final ChangeFolderPath mChangeFolderPath;
    private final GetFolderList mGetFolderList;
    private final GetPlayerListing mGetPlayerListing;
    private final MoveToRoot mMoveToRoot;
    private final Search mSearch;
    private final SetCurrentPas mSetCurrentPas;
    private final AddToNowPlaying mAddToNowPlaying;
    private final GetTotalNumOfItems mGetTotalNumOfItems;
    private final Object mLock = new Object();
    private final Object mBrowseLock = new Object();
    private static final ArrayList<MediaItem> mEmptyMediaItemList = new ArrayList<>();
    private static List<AvrcpPlayer> playerList = new ArrayList<AvrcpPlayer>();
    private static final MediaMetadata mEmptyMMD = new MediaMetadata.Builder().build();
    private final List<FolderListListener> mListeners = new CopyOnWriteArrayList<>();


    // APIs exist to access these so they must be thread safe
    private Boolean mIsConnected = false;
    private RemoteDevice mRemoteDevice;
    private AvrcpPlayer mAddressedPlayer;
    private int mUidCounter = 0;

    // Only accessed from State Machine processMessage
    private boolean mAbsoluteVolumeChangeInProgress = false;
    private boolean mBroadcastMetadata = false;
    private int previousPercentageVol = -1;

    // Depth from root of current browsing. This can be used to move to root directly.
    private int mBrowseDepth = 0;

    // Browse tree.
    private BrowseTree mBrowseTree = new BrowseTree();

    // Identify whether BIP is in reconnection
    private boolean mBipReconnectonFlag = false;
    private int mRetryBipAttempt= 0;

    AvrcpControllerStateMachine(Context context) {
        super(TAG);
        mContext = context;

        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        mMaxAudioVolume = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        mCar = Car.createCar(context, mConnection);
        mCar.connect();

        IntentFilter filter = new IntentFilter(AudioManager.VOLUME_CHANGED_ACTION);
        mContext.registerReceiver(mBroadcastReceiver, filter);

        mDisconnected = new Disconnected();
        mConnected = new Connected();

        // Used to change folder path and fetch the new folder listing.
        mSetBrowsedPlayer = new SetBrowsedPlayer();
        mSetAddrPlayer = new SetAddresedPlayerAndPlayItem();
        mChangeFolderPath = new ChangeFolderPath();
        mGetFolderList = new GetFolderList();
        mGetPlayerListing = new GetPlayerListing();
        mMoveToRoot = new MoveToRoot();
        mSearch = new Search();
        mSetCurrentPas = new SetCurrentPas();
        mAddToNowPlaying = new AddToNowPlaying();
        mGetTotalNumOfItems = new GetTotalNumOfItems();

        addState(mDisconnected);
        addState(mConnected);

        // Any action that needs blocking other requests to the state machine will be implemented as
        // a separate substate of the mConnected state. Once transtition to the sub-state we should
        // only handle the messages that are relevant to the sub-action. Everything else should be
        // deferred so that once we transition to the mConnected we can process them hence.
        addState(mSetBrowsedPlayer, mConnected);
        addState(mSetAddrPlayer, mConnected);
        addState(mChangeFolderPath, mConnected);
        addState(mGetFolderList, mConnected);
        addState(mGetPlayerListing, mConnected);
        addState(mMoveToRoot, mConnected);
        addState(mSearch, mConnected);
        addState(mSetCurrentPas, mConnected);
        addState(mAddToNowPlaying, mConnected);
        addState(mGetTotalNumOfItems, mConnected);

        setInitialState(mDisconnected);
        mBipStateMachine = AvrcpControllerBipStateMachine.make(this, getHandler(), context);
        resetRetryBipCount();
    }

    public AvrcpPlayer getAddressedPlayer() {
        return mAddressedPlayer;
    }

    class Disconnected extends State {

        @Override
        public boolean processMessage(Message msg) {
            Log.d(TAG, " HandleMessage: " + dumpMessageString(msg.what));
            switch (msg.what) {
                case MESSAGE_PROCESS_CONNECTION_CHANGE:
                    if (msg.arg1 == BluetoothProfile.STATE_CONNECTED) {
                        BluetoothDevice rtDevice = (BluetoothDevice) msg.obj;
                        if (okToConnect(rtDevice)){
                            mBrowseTree.init();
                            transitionTo(mConnected);
                            synchronized(mLock) {
                                mRemoteDevice = new RemoteDevice(rtDevice);
                                mAddressedPlayer = new AvrcpPlayer();
                                mIsConnected = true;
                            }
                            Intent intent = new Intent(
                                BluetoothAvrcpController.ACTION_CONNECTION_STATE_CHANGED);
                            intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE,
                                BluetoothProfile.STATE_DISCONNECTED);
                            intent.putExtra(BluetoothProfile.EXTRA_STATE,
                                BluetoothProfile.STATE_CONNECTED);
                            intent.putExtra(BluetoothDevice.EXTRA_DEVICE, rtDevice);
                            mContext.sendBroadcast(intent, ProfileService.BLUETOOTH_PERM);
                        } else {
                            //reject the connection and stay in Disconnected state itself
                            Log.d(TAG, "Incoming AVRCP rejected");
                            AvrcpControllerService.disconnectNative(Utils.getByteAddress(rtDevice));
                        }
                    }
                    break;

                default:
                    Log.w(TAG,"Currently Disconnected not handling " + dumpMessageString(msg.what));
                    return false;
            }
            return true;
        }
    }

    class Connected extends State {
        @Override
        public boolean processMessage(Message msg) {
            Log.d(TAG, " HandleMessage: " + dumpMessageString(msg.what));
            A2dpSinkService a2dpSinkService = A2dpSinkService.getA2dpSinkService();
            synchronized (mLock) {
                switch (msg.what) {
                    case MESSAGE_STOP_METADATA_BROADCASTS:
                        mBroadcastMetadata = false;
                        broadcastPlayBackStateChanged(new PlaybackState.Builder().setState(
                            PlaybackState.STATE_PAUSED, mAddressedPlayer.getPlayTime(),
                            0).build());
                        break;

                    case MESSAGE_START_METADATA_BROADCASTS:
                        mBroadcastMetadata = true;
                        broadcastPlayBackStateChanged(mAddressedPlayer.getPlaybackState());
                        if (mAddressedPlayer.getCurrentTrack() != null) {
                            broadcastMetaDataChanged(
                                mAddressedPlayer.getCurrentTrack().getMediaMetaData());
                        }
                        break;

                    case MESSAGE_SEND_PASS_THROUGH_CMD:
                        BluetoothDevice device = (BluetoothDevice) msg.obj;
                        AvrcpControllerService
                            .sendPassThroughCommandNative(Utils.getByteAddress(device), msg.arg1,
                                msg.arg2);
                        if (a2dpSinkService != null) {
                            Log.d(TAG, " inform AVRCP Commands to A2DP Sink ");
                            a2dpSinkService.informAvrcpPassThroughCmd(device, msg.arg1, msg.arg2);
                        }
                        break;

                    case MESSAGE_SEND_GROUP_NAVIGATION_CMD:
                        AvrcpControllerService.sendGroupNavigationCommandNative(
                            mRemoteDevice.getBluetoothAddress(), msg.arg1, msg.arg2);
                        break;

                    case MESSAGE_GET_NOW_PLAYING_LIST:
                        mGetFolderList.setFolder((String) msg.obj);
                        mGetFolderList.setBounds((int) msg.arg1, (int) msg.arg2);
                        mGetFolderList.setScope(AvrcpControllerService.BROWSE_SCOPE_NOW_PLAYING);
                        transitionTo(mGetFolderList);
                        break;

                    case MESSAGE_GET_FOLDER_LIST:
                        // Whenever we transition we set the information for folder we need to
                        // return result.
                        mGetFolderList.setBounds(msg.arg1, msg.arg2);
                        mGetFolderList.setFolder((String) msg.obj);
                        mGetFolderList.setScope(AvrcpControllerService.BROWSE_SCOPE_VFS);
                        transitionTo(mGetFolderList);
                        break;

                    case MESSAGE_GET_SEARCH_LIST:
                        mGetFolderList.setBounds(msg.arg1, msg.arg2);
                        mGetFolderList.setFolder((String) msg.obj);
                        mGetFolderList.setScope(AvrcpControllerService.BROWSE_SCOPE_SEARCH);
                        transitionTo(mGetFolderList);
                        break;

                    case MESSAGE_GET_PLAYER_LIST:
                        AvrcpControllerService.getPlayerListNative(
                            mRemoteDevice.getBluetoothAddress(), (byte) msg.arg1,
                            (byte) msg.arg2);
                        transitionTo(mGetPlayerListing);
                        sendMessageDelayed(MESSAGE_INTERNAL_CMD_TIMEOUT, CMD_TIMEOUT_MILLIS);
                        break;

                    case MESSAGE_CHANGE_FOLDER_PATH: {
                        int direction = msg.arg1;
                        Bundle b = (Bundle) msg.obj;
                        String uid = b.getString(AvrcpControllerService.EXTRA_FOLDER_BT_ID);
                        String fid = b.getString(AvrcpControllerService.EXTRA_FOLDER_ID);


                        // Parse the change path operations object.
                        List<Parcelable> extraParcelableList =
                            (ArrayList<Parcelable>) b.getParcelableArrayList(
                                AvrcpControllerService.EXTRA_FOLDER_CHANGE_OPERATIONS);

                        ArrayList<BrowseTree.BrowseStep> check =
                            new ArrayList<BrowseTree.BrowseStep>();
                        for (Parcelable p : extraParcelableList) {
                            check.add((BrowseTree.BrowseStep) p);
                        }

                        b = new Bundle();
                        b.putParcelableArrayList(
                            AvrcpControllerService.EXTRA_FOLDER_CHANGE_OPERATIONS, check);

                        // String is encoded as a Hex String (mostly for display purposes)
                        // hence convert this back to real byte string.
                        AvrcpControllerService.changeFolderPathNative(
                            mRemoteDevice.getBluetoothAddress(), mUidCounter, (byte) msg.arg1,
                            AvrcpControllerService.hexStringToByteUID(uid));
                        mChangeFolderPath.setFolder(fid);
                        transitionTo(mChangeFolderPath);
                        sendMessage(MESSAGE_INTERNAL_BROWSE_DEPTH_INCREMENT, (byte) msg.arg1, 0, b);
                        sendMessageDelayed(MESSAGE_INTERNAL_CMD_TIMEOUT, CMD_TIMEOUT_MILLIS);

                        // According to AVRCP 1.6 SPEC(Chapter 5.14.2.2.2)
                        // We shall reset BIP connection to make sure Cover Art handle is valid when
                        // UIDS become invalid.
                        if (!mAddressedPlayer.isDatabaseAwarePlayer()) {
                            mBipStateMachine.sendMessage(AvrcpControllerBipStateMachine.
                                MESSAGE_DISCONNECT_BIP, mRemoteDevice.getRemoteBipPsm(), 0,
                                mRemoteDevice.mBTDevice);

                            setBipReconnectionFlag(true);
                        }

                        break;
                    }

                    case MESSAGE_FETCH_ATTR_AND_PLAY_ITEM: {
                        int scope = msg.arg1;
                        String playItemUid = (String) msg.obj;
                        BrowseTree.BrowseNode currBrPlayer =
                            mBrowseTree.getCurrentBrowsedPlayer();
                        BrowseTree.BrowseNode currAddrPlayer =
                            mBrowseTree.getCurrentAddressedPlayer();
                        if (DBG) {
                            Log.d(TAG, "currBrPlayer " + currBrPlayer +
                                " currAddrPlayer " + currAddrPlayer);
                        }

                        if (currBrPlayer == null || currBrPlayer.equals(currAddrPlayer)) {
                            // String is encoded as a Hex String (mostly for display purposes)
                            // hence convert this back to real byte string.
                            // NOTE: It may be possible that sending play while the same item is
                            // playing leads to reset of track.
                            AvrcpControllerService.playItemNative(
                                mRemoteDevice.getBluetoothAddress(), (byte) scope,
                                AvrcpControllerService.hexStringToByteUID(playItemUid), mUidCounter);
                        } else {
                            // Send out the request for setting addressed player.
                            AvrcpControllerService.setAddressedPlayerNative(
                                mRemoteDevice.getBluetoothAddress(),
                                currBrPlayer.getPlayerID());
                            mSetAddrPlayer.setItemAndScope(
                                currBrPlayer.getID(), playItemUid, scope);
                            transitionTo(mSetAddrPlayer);
                        }
                        break;
                    }

                    case MESSAGE_SET_BROWSED_PLAYER: {
                        AvrcpControllerService.setBrowsedPlayerNative(
                            mRemoteDevice.getBluetoothAddress(), (int) msg.arg1);
                        mSetBrowsedPlayer.setFolder((String) msg.obj);
                        mSetBrowsedPlayer.setPlayerId((int) msg.arg1);
                        transitionTo(mSetBrowsedPlayer);
                        break;
                    }

                    case MESSAGE_SET_CURRENT_PAS: {
                        BluetoothAvrcpPlayerSettings plAppSetting = (BluetoothAvrcpPlayerSettings) msg.obj;
                        int settings = plAppSetting.getSettings();
                        Log.d(TAG, "settings " + settings);
                        byte numAttributes = 0;
                        /* calculate number of attributes in request */
                        while (settings > 0) {
                            numAttributes += ((settings & 0x01)!= 0)?1: 0;
                            settings = settings >> 1;
                        }
                        Log.d(TAG, "numAttributes " + numAttributes);
                        settings = plAppSetting.getSettings();
                        byte[] attributeIds = new byte [numAttributes];
                        byte[] attributeVals = new byte [numAttributes];

                        PlayerApplicationSettings.getNativeSettingsFromAvrcpPlayerSettings(
                            plAppSetting, attributeIds, attributeVals);

                        AvrcpControllerService.setPlayerApplicationSettingValuesNative(
                            mRemoteDevice.getBluetoothAddress(),
                            numAttributes, attributeIds, attributeVals);
                        transitionTo(mSetCurrentPas);
                        break;
                    }

                    case MESSAGE_PROCESS_CONNECTION_CHANGE:
                        if (msg.arg1 == BluetoothProfile.STATE_DISCONNECTED
                                && mBipStateMachine != null && mRemoteDevice != null) {
                            mBipStateMachine.sendMessage(
                                    AvrcpControllerBipStateMachine.MESSAGE_CLEAR_COVEARART_CACHE);
                            mBipStateMachine.sendMessage(
                                    AvrcpControllerBipStateMachine.MESSAGE_DISCONNECT_BIP,
                                    mRemoteDevice.mBTDevice);
                            synchronized (mLock) {
                                mIsConnected = false;
                                mRemoteDevice = null;
                            }
                            mBrowseTree.clear();
                            transitionTo(mDisconnected);
                            BluetoothDevice rtDevice = (BluetoothDevice) msg.obj;
                            Intent intent = new Intent(
                                BluetoothAvrcpController.ACTION_CONNECTION_STATE_CHANGED);
                            intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE,
                                BluetoothProfile.STATE_CONNECTED);
                            intent.putExtra(BluetoothProfile.EXTRA_STATE,
                                BluetoothProfile.STATE_DISCONNECTED);
                            intent.putExtra(BluetoothDevice.EXTRA_DEVICE, rtDevice);
                            mContext.sendBroadcast(intent, ProfileService.BLUETOOTH_PERM);
                        }
                        break;

                    case MESSAGE_PROCESS_BROWSE_CONNECTION_CHANGE:
                        // Service tells us if the browse is connected or disconnected.
                        // This is useful only for deciding whether to send browse commands rest of
                        // the connection state handling should be done via the message
                        // MESSAGE_PROCESS_CONNECTION_CHANGE.
                        Intent intent = new Intent(
                            AvrcpControllerService.ACTION_BROWSE_CONNECTION_STATE_CHANGED);
                        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, (BluetoothDevice) msg.obj);
                        if (DBG) {
                            Log.d(TAG, "Browse connection state " + msg.arg1);
                        }
                        if (msg.arg1 == 1) {
                            intent.putExtra(
                                BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_CONNECTED);
                        } else if (msg.arg1 == 0) {
                            intent.putExtra(
                                BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED);
                            // If browse is disconnected, the next time we connect we should
                            // be at the ROOT.
                            mBrowseDepth = 0;
                        } else {
                            Log.w(TAG, "Incorrect browse state " + msg.arg1);
                        }

                        mContext.sendBroadcast(intent, ProfileService.BLUETOOTH_PERM);
                        break;

                    case MESSAGE_PROCESS_RC_FEATURES:
                        if (mRemoteDevice != null) {
                            mRemoteDevice.setRemoteFeatures(msg.arg1);
                            if (mRemoteDevice.isCoverArtSupported() && mBipStateMachine != null) {
                                mRemoteDevice.setRemoteBipPsm(msg.arg2);
                                mBipStateMachine.sendMessage(AvrcpControllerBipStateMachine.
                                            MESSAGE_CONNECT_BIP, mRemoteDevice.getRemoteBipPsm(), 0,
                                            mRemoteDevice.mBTDevice);
                            }
                        }
                        break;

                    case MESSAGE_PROCESS_SET_ABS_VOL_CMD:
                        mAbsoluteVolumeChangeInProgress = true;
                        setAbsVolume(msg.arg1, msg.arg2);
                        break;

                    case MESSAGE_PROCESS_REGISTER_ABS_VOL_NOTIFICATION: {
                        mRemoteDevice.setNotificationLabel(msg.arg1);
                        mRemoteDevice.setAbsVolNotificationRequested(true);
                        int percentageVol = getVolumePercentage();
                        Log.d(TAG,
                            " Sending Interim Response = " + percentageVol + " label " + msg.arg1);
                        AvrcpControllerService
                            .sendRegisterAbsVolRspNative(mRemoteDevice.getBluetoothAddress(),
                                NOTIFICATION_RSP_TYPE_INTERIM,
                                percentageVol,
                                mRemoteDevice.getNotificationLabel());
                    }
                    break;

                    case MESSAGE_PROCESS_VOLUME_CHANGED_NOTIFICATION: {
                        if (mAbsoluteVolumeChangeInProgress) {
                            mAbsoluteVolumeChangeInProgress = false;
                        } else {
                            if (mRemoteDevice.getAbsVolNotificationRequested()) {
                                // setAbsVolume when system volume is changed,
                                // otherwise percentageVol will not be changed at all.
                                setAbsVolume(msg.arg1, 0);
                                int percentageVol = getVolumePercentage();
                                if (percentageVol != previousPercentageVol) {
                                    AvrcpControllerService.sendRegisterAbsVolRspNative(
                                        mRemoteDevice.getBluetoothAddress(),
                                        NOTIFICATION_RSP_TYPE_CHANGED,
                                        percentageVol, mRemoteDevice.getNotificationLabel());
                                    previousPercentageVol = percentageVol;
                                    mRemoteDevice.setAbsVolNotificationRequested(false);
                                }
                            }
                        }
                    }
                    break;

                    case MESSAGE_PROCESS_TRACK_CHANGED: // fall through
                    case MESSAGE_PROCESS_ATTR_CHANGED:
                        mAddressedPlayer.updateCurrentTrack((TrackInfo) msg.obj);
                        if (!mAddressedPlayer.getCurrentTrack().getCoverArtHandle().isEmpty()
                                && mBipStateMachine != null) {
                            int FLAG;
                            if (AvrcpControllerBipStateMachine.mImageType.
                                    equalsIgnoreCase("thumbnaillinked")) {
                                FLAG = AvrcpControllerBipStateMachine.
                                MESSAGE_FETCH_THUMBNAIL;
                            } else {
                                // Image or Thumbnail Image
                                FLAG = AvrcpControllerBipStateMachine.MESSAGE_FETCH_IMAGE;
                            }
                            mBipStateMachine.sendMessage(FLAG,
                                    mAddressedPlayer.getCurrentTrack().getCoverArtHandle());
                        } else {
                            if (mRemoteDevice != null && mRemoteDevice.isCoverArtSupported())
                                Log.e(TAG, " Cover Art Handle not valid ");
                        }

                        if (mBroadcastMetadata) {
                            broadcastMetaDataChanged(mAddressedPlayer.getCurrentTrack().
                                getMediaMetaData());
                        }
                        break;

                    case MESSAGE_PROCESS_PLAY_POS_CHANGED:
                        mAddressedPlayer.setPlayTime(msg.arg2);
                        if (mBroadcastMetadata) {
                            broadcastPlayBackStateChanged(getCurrentPlayBackState());
                        }
                        break;

                    case MESSAGE_PROCESS_PLAY_STATUS_CHANGED:
                        int status = msg.arg1;
                        mAddressedPlayer.setPlayStatus(status);
                        if (status == PlaybackState.STATE_PLAYING) {
                            a2dpSinkService.informTGStatePlaying(mRemoteDevice.mBTDevice, true);
                        } else if (status == PlaybackState.STATE_PAUSED ||
                            status == PlaybackState.STATE_STOPPED) {
                            a2dpSinkService.informTGStatePlaying(mRemoteDevice.mBTDevice, false);
                        }
                        break;

                    case MESSAGE_BIP_CONNECTED:
                        processBipConnected();
                        break;

                    case MESSAGE_BIP_DISCONNECTED:
                        processBipDisconnected();
                        break;

                    case MESSAGE_BIP_CONNECT_TIMEOUT:
                        processBipConnectTimeout();
                        break;

                    case MESSAGE_BIP_IMAGE_FETCHED:
                        processBipImageFetched(msg);
                        break;

                    case MESSAGE_BIP_THUMB_NAIL_FETCHED:
                        processBipThumbNailFetched(msg);
                        break;

                    case MESSAGE_PROCESS_UIDS_CHANGED:
                        processUIDSChange(msg);
                        break;

                    case MESSAGE_SEARCH:
                        processSearchReq((String) msg.obj);
                        break;

                    case MESSAGE_PROCESS_LIST_PAS:
                        processListPas((byte[])msg.obj);
                        break;

                    case MESSAGE_PROCESS_PAS_CHANGED:
                        processPasChanged((byte[])msg.obj);
                        break;

                    case MESSAGE_ADD_TO_NOW_PLAYING:
                        processAddToNowPlayingReq(msg.arg1, (String) msg.obj);
                        break;

                    case MESSAGE_GET_ITEM_ATTR:
                        processGetItemAttrReq((Bundle) msg.obj);
                        break;

                    case MESSAGE_GET_ELEMENT_ATTR:
                        processGetElementAttrReq((Bundle) msg.obj);
                        break;

                    case MESSAGE_GET_FOLDER_ITEM:
                        processGetFolderItems((Bundle) msg.obj);
                        break;

                    case MESSAGE_GET_NUM_OF_ITEMS:
                        processGetNumOfItemsReq(msg.arg1);
                        break;

                    case MESSAGE_SET_ADDRESSED_PLAYER:
                        processSetAddressedPlayerReq(msg.arg1, (String) msg.obj);
                        break;

                    case MESSAGE_REQUEST_CONTINUING_RESPONSE:
                        processRequestContinuingResponse(msg.arg1);
                        break;

                    case MESSAGE_ABORT_CONTINUING_RESPONSE:
                        processAbortContinuingResponse(msg.arg1);
                        break;

                    case MESSAGE_RELEASE_CONNECTION:
                        processReleaseConnection((BluetoothDevice) msg.obj);
                        break;

                    case MESSAGE_PROCESS_ADDRESSED_PLAYER_CHANGED:
                        processAddressedPlayerChanged(msg.arg1, msg.arg2);
                        break;

                    case MESSAGE_PROCESS_AVAILABLE_PLAYER_CHANGED:
                        processAvailablePlayerChanged();
                        break;

                    case MESSAGE_PROCESS_NOW_PLAYING_CHANGED:
                        processNowPlayingChanged();
                        break;

                    case MESSAGE_PROCESS_SET_ADDRESSED_PLAYER:
                        processSetAddressedPlayerResp(msg.arg1);
                        break;

                    case MESSAGE_PROCESS_ERROR_STATUS_CODE:
                        processErrorStatusCode((Bundle) msg.obj);
                        break;

                    default:
                        return false;
                }
            }
            return true;
        }
    }

    // Handle the change folder path meta-action.
    // a) Send Change folder command
    // b) Once successful transition to folder fetch state.
    class ChangeFolderPath extends CmdState {
        private String STATE_TAG = "AVRCPSM.ChangeFolderPath";
        private int mTmpIncrDirection;
        private String mID = "";
        // Store folder change operations which forwards to target folder
        private ArrayList<BrowseTree.BrowseStep> mOperations = new ArrayList<BrowseTree.BrowseStep>();

        public void setFolder(String id) {
            mID = id;
        }

        @Override
        public void enter() {
            super.enter();
            mTmpIncrDirection = -1;
            mOperations.clear();
        }

        @Override
        public boolean processMessage(Message msg) {
            Log.d(STATE_TAG, "processMessage " + msg);
            switch (msg.what) {
                case MESSAGE_INTERNAL_BROWSE_DEPTH_INCREMENT:
                    mTmpIncrDirection = msg.arg1;
                    Bundle extra = (Bundle) msg.obj;
                    List<Parcelable> extraParcelableList =
                        (ArrayList<Parcelable>) extra.getParcelableArrayList(
                            AvrcpControllerService.EXTRA_FOLDER_CHANGE_OPERATIONS);

                    for (Parcelable p : extraParcelableList) {
                        mOperations.add((BrowseTree.BrowseStep) p);
                    }
                    break;

                case MESSAGE_PROCESS_FOLDER_PATH: {
                    // Fetch the listing of objects in this folder.
                    Log.d(STATE_TAG, "MESSAGE_PROCESS_FOLDER_PATH returned " + msg.arg1 +
                        " elements");

                    // Update the folder depth.
                    if (mTmpIncrDirection ==
                        AvrcpControllerService.FOLDER_NAVIGATION_DIRECTION_UP) {
                        mBrowseDepth -= 1;;
                    } else if (mTmpIncrDirection ==
                        AvrcpControllerService.FOLDER_NAVIGATION_DIRECTION_DOWN) {
                        mBrowseDepth += 1;
                    } else {
                        throw new IllegalStateException("incorrect nav " + mTmpIncrDirection);
                    }
                    Log.d(STATE_TAG, "New browse depth " + mBrowseDepth);


                    // Check whether there is any remaining unexecuted folder change operations in the list
                    if (!checkOpsListAndChangeFolder(mOperations, true)) {
                        if (msg.arg1 > 0) {
                            sendMessage(MESSAGE_GET_FOLDER_LIST, 0, msg.arg1 -1, mID);
                        } else {
                            // Return an empty response to the upper layer.
                            broadcastFolderList(mID, mEmptyMediaItemList);
                        }
                        mBrowseTree.setCurrentBrowsedFolder(mID);
                        transitionTo(mConnected);
                    }

                    break;
                }

                case MESSAGE_INTERNAL_CMD_TIMEOUT:
                    // We timed out changing folders. It is imperative we tell
                    // the upper layers that we failed by giving them an empty list.
                    Log.e(STATE_TAG, "change folder failed, sending empty list.");
                    broadcastFolderList(mID, mEmptyMediaItemList);
                    transitionTo(mConnected);
                    break;

                case MESSAGE_PROCESS_UIDS_CHANGED:
                    processUIDSChange(msg);
                    break;

                default:
                    Log.d(STATE_TAG, "deferring message " + msg + " to Connected state.");
                    deferMessage(msg);
            }
            return true;
        }
    }

    // Handle the get folder listing action
    // a) Fetch the listing of folders
    // b) Once completed return the object listing
    class GetFolderList extends CmdState {
        private String STATE_TAG = "AVRCPSM.GetFolderList";

        String mID = "";
        int mStartInd;
        int mEndInd;
        int mCurrInd;
        int mScope;
        private ArrayList<MediaItem> mFolderList = new ArrayList<>();

        @Override
        public void enter() {
            mCurrInd = 0;
            mFolderList.clear();

            callNativeFunctionForScope(
                mStartInd, Math.min(mEndInd, mStartInd + GET_FOLDER_ITEMS_PAGINATION_SIZE - 1));
        }

        public void setScope(int scope) {
            mScope = scope;
        }

        public void setFolder(String id) {
            Log.d(STATE_TAG, "Setting folder to " + id);
            mID = id;
        }

        public void setBounds(int startInd, int endInd) {
            if (DBG) {
                Log.d(STATE_TAG, "startInd " + startInd + " endInd " + endInd);
            }
            mStartInd = startInd;
            mEndInd = endInd;
        }

        @Override
        public boolean processMessage(Message msg) {
            Log.d(STATE_TAG, "processMessage " + msg);
            switch (msg.what) {
                case MESSAGE_PROCESS_GET_FOLDER_ITEMS:
                    ArrayList<MediaItem> folderList = (ArrayList<MediaItem>) msg.obj;
                    mFolderList.addAll(folderList);
                    if (DBG) {
                        Log.d(STATE_TAG, "Start " + mStartInd + " End " + mEndInd + " Curr " +
                            mCurrInd + " received " + folderList.size());
                    }
                    mCurrInd += folderList.size();

                    synchronized (mBrowseLock) {
                        // Always update the node so that the user does not wait forever
                        // for the list to populate.
                        sendFolderBroadcastAndUpdateNode();
                    }

                    if (mCurrInd > mEndInd || folderList.size() == 0) {
                        // If we have fetched all the elements or if the remotes sends us 0 elements
                        // (which can lead us into a loop since mCurrInd does not proceed) we simply
                        // abort.
                        BrowseTree.BrowseNode bn = mBrowseTree.findBrowseNodeByID(mID);
                        if (bn != null)
                            bn.setFetchingFlag(false);
                        transitionTo(mConnected);
                    } else {
                        // Fetch the next set of items.
                        callNativeFunctionForScope(mCurrInd, Math.min(mEndInd,
                                mCurrInd + GET_FOLDER_ITEMS_PAGINATION_SIZE - 1));
                        // Reset the timeout message since we are doing a new fetch now.
                        removeMessages(MESSAGE_INTERNAL_CMD_TIMEOUT);
                        sendMessageDelayed(MESSAGE_INTERNAL_CMD_TIMEOUT, CMD_TIMEOUT_MILLIS);
                    }
                    break;

                case MESSAGE_INTERNAL_CMD_TIMEOUT:
                    // We have timed out to execute the request, we should simply send
                    // whatever listing we have gotten until now.
                    synchronized (mBrowseLock) {
                        sendFolderBroadcastAndUpdateNode();
                    }

                    transitionTo(mConnected);
                    break;

                case MESSAGE_PROCESS_GET_FOLDER_ITEMS_OUT_OF_RANGE:
                    // If we have gotten an error for OUT OF RANGE we have
                    // already sent all the items to the client hence simply
                    // transition to Connected state here.
                    BrowseTree.BrowseNode bn = mBrowseTree.findBrowseNodeByID(mID);
                    if (bn != null)
                        bn.setFetchingFlag(false);
                    transitionTo(mConnected);
                    break;

                case MESSAGE_PROCESS_UIDS_CHANGED:
                    processUIDSChange(msg);
                    break;

                case MESSAGE_CHANGE_FOLDER_PATH:
                case MESSAGE_FETCH_ATTR_AND_PLAY_ITEM:
                case MESSAGE_GET_PLAYER_LIST:
                case MESSAGE_GET_NOW_PLAYING_LIST:
                case MESSAGE_SET_BROWSED_PLAYER:
                case MESSAGE_SEARCH:
                case MESSAGE_GET_SEARCH_LIST:
                    // A new request has come in, no need to fetch more.
                    mEndInd = 0;
                    deferMessage(msg);
                    break;

                case MESSAGE_SEND_PASS_THROUGH_CMD:
                case MESSAGE_SEND_GROUP_NAVIGATION_CMD:
                case MESSAGE_PROCESS_SET_ABS_VOL_CMD:
                case MESSAGE_PROCESS_REGISTER_ABS_VOL_NOTIFICATION:
                case MESSAGE_PROCESS_TRACK_CHANGED:
                case MESSAGE_PROCESS_ATTR_CHANGED:
                case MESSAGE_PROCESS_PLAY_POS_CHANGED:
                case MESSAGE_PROCESS_PLAY_STATUS_CHANGED:
                case MESSAGE_PROCESS_VOLUME_CHANGED_NOTIFICATION:
                case MESSAGE_STOP_METADATA_BROADCASTS:
                case MESSAGE_START_METADATA_BROADCASTS:
                case MESSAGE_PROCESS_CONNECTION_CHANGE:
                case MESSAGE_PROCESS_BROWSE_CONNECTION_CHANGE:
                case MESSAGE_SET_CURRENT_PAS:
                    // All of these messages should be handled by parent state immediately.
                    return false;

                default:
                    Log.d(STATE_TAG, "deferring message " + msg + " to connected!");
                    deferMessage(msg);
            }
            return true;
        }

        private void sendFolderBroadcastAndUpdateNode() {
            BrowseTree.BrowseNode bn = mBrowseTree.findBrowseNodeByID(mID);
            if (bn == null) {
                Log.e(STATE_TAG, "bn is null! Should be something wrong here.");
                return;
            }

            Log.d(STATE_TAG, "sendFolderBroadcastAndUpdateNode BrowseNode ID: " + bn.getID());
            if (bn.isPlayer()) {
                // Add the now playing folder. TODO: Why not VFS ?
                addFolder(bn, BrowseTree.NOW_PLAYING_PREFIX);

                if (mSearch.getItems() > 0) {
                    // Add the search list folder.
                    addFolder(bn, BrowseTree.SEARCH_PREFIX);
                }
            }
            bn.setFetchingFlag(true);
            mBrowseTree.refreshChildren(bn, mFolderList);
            broadcastFolderList(mID, mFolderList);

            // For now playing or search list, we need to set the current browsed folder here.
            // For normal folders it is set after ChangeFolderPath.
            if (isNowPlaying() || isSearch()) {
                mBrowseTree.setCurrentBrowsedFolder(mID);
            }
        }

        private void addFolder(BrowseTree.BrowseNode bn, String prefix) {
            Log.d(STATE_TAG, "addFolder prefix: " + prefix);
            MediaDescription.Builder mdb = new MediaDescription.Builder();
            mdb.setMediaId(prefix + ":" +
                bn.getPlayerID());
            mdb.setTitle(prefix);
            Bundle mdBundle = new Bundle();
            mdBundle.putString(
                AvrcpControllerService.MEDIA_ITEM_UID_KEY,
                prefix + ":" + bn.getID());
            mdb.setExtras(mdBundle);
            mFolderList.add(new MediaItem(mdb.build(), MediaItem.FLAG_BROWSABLE));
        }

        private void callNativeFunctionForScope(int start, int end) {
            switch (mScope) {
                case AvrcpControllerService.BROWSE_SCOPE_NOW_PLAYING:
                    AvrcpControllerService.getNowPlayingListNative(
                        mRemoteDevice.getBluetoothAddress(), start, end);
                    break;
                case AvrcpControllerService.BROWSE_SCOPE_VFS:
                    AvrcpControllerService.getFolderListNative(
                        mRemoteDevice.getBluetoothAddress(), start, end);
                    break;
                case AvrcpControllerService.BROWSE_SCOPE_SEARCH:
                    AvrcpControllerService.getSearchListNative(
                        mRemoteDevice.getBluetoothAddress(), start, end);
                    break;
                default:
                    Log.e(STATE_TAG, "Scope " + mScope + " cannot be handled here.");
            }
        }

        private boolean isVfs() {
            return mScope == AvrcpControllerService.BROWSE_SCOPE_VFS;
        }

        private boolean isNowPlaying() {
            return mScope == AvrcpControllerService.BROWSE_SCOPE_NOW_PLAYING;
        }

        private boolean isSearch() {
            return mScope == AvrcpControllerService.BROWSE_SCOPE_SEARCH;
        }
    }

    // Handle the get player listing action
    // a) Fetch the listing of players
    // b) Once completed return the object listing
    class GetPlayerListing extends CmdState {
        private String STATE_TAG = "AVRCPSM.GetPlayerList";

        @Override
        public boolean processMessage(Message msg) {
            Log.d(STATE_TAG, "processMessage " + msg);
            switch (msg.what) {
                case MESSAGE_PROCESS_GET_PLAYER_ITEMS:
                    // Refresh playerList
                    playerList.clear();
                    playerList.addAll((List<AvrcpPlayer>) msg.obj);

                    mBrowseTree.refreshChildren(BrowseTree.ROOT, playerList);
                    ArrayList<MediaItem> mediaItemList = new ArrayList<>();
                    for (BrowseTree.BrowseNode c :
                            mBrowseTree.findBrowseNodeByID(BrowseTree.ROOT).getChildren()) {
                        mediaItemList.add(c.getMediaItem());
                    }
                    broadcastFolderList(BrowseTree.ROOT, mediaItemList);
                    mBrowseTree.setCurrentBrowsedFolder(BrowseTree.ROOT);

                    BluetoothAvrcpPlayerSettings playerSetting = mAddressedPlayer.getAvrcpSettings();
                    Log.d(STATE_TAG, "Supported PAS setting " + playerSetting.getSettings());
                    if (playerSetting.getSettings() == 0) {
                        /* Cannot find supported value due to address player changed*/
                        Log.d(STATE_TAG, "No supported value found, try to fetch again");
                        boolean found = false;
                        for (AvrcpPlayer c : playerList) {
                            if (c.getId() == mAddressedPlayer.getId()) {
                                /* Update avrcp player with player list */
                                mAddressedPlayer = c;
                                Log.d(STATE_TAG, "Found player id " + c.getId() + " Play status " + mAddressedPlayer.getPlaybackState());
                                found = true;
                            }
                        }

                        if (found) {
                            /* Get support value and current values */
                            Log.d(STATE_TAG, "fetchPlayerApplicationSettingNative");
                            AvrcpControllerService.fetchPlayerApplicationSettingNative(mRemoteDevice.getBluetoothAddress());
                        } else {
                            Log.e(STATE_TAG, "Failed to find player by id " + mAddressedPlayer.getId());
                        }
                    }

                    transitionTo(mConnected);
                    break;

                case MESSAGE_INTERNAL_CMD_TIMEOUT:
                    // We have timed out to execute the request.
                    // Send an empty list here.
                    broadcastFolderList(BrowseTree.ROOT, mEmptyMediaItemList);
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

    class MoveToRoot extends CmdState {
        private String STATE_TAG = "AVRCPSM.MoveToRoot";
        private String mID = "";

        public void setFolder(String id) {
            Log.d(STATE_TAG, "setFolder " + id);
            mID = id;
        }

        @Override
        public void enter() {
            // Setup the timeouts.
            super.enter();

            // We need to move mBrowseDepth levels up. The following message is
            // completely internal to this state.
            sendMessage(MESSAGE_INTERNAL_MOVE_N_LEVELS_UP);
        }

        @Override
        public boolean processMessage(Message msg) {
            Log.d(STATE_TAG, "processMessage " + msg + " browse depth " + mBrowseDepth);
            switch (msg.what) {
                case MESSAGE_INTERNAL_MOVE_N_LEVELS_UP:
                    if (mBrowseDepth == 0) {
                        Log.w(STATE_TAG, "Already in root!");
                        transitionTo(mConnected);
                        sendMessage(MESSAGE_GET_FOLDER_LIST, 0, AvrcpControllerService.MAX_ITEMS, mID);
                    } else {
                        AvrcpControllerService.changeFolderPathNative(
                            mRemoteDevice.getBluetoothAddress(), mUidCounter,
                            (byte) AvrcpControllerService.FOLDER_NAVIGATION_DIRECTION_UP,
                            AvrcpControllerService.hexStringToByteUID(null));
                    }
                    break;

                case MESSAGE_PROCESS_FOLDER_PATH:
                    mBrowseDepth -= 1;
                    Log.d(STATE_TAG, "New browse depth " + mBrowseDepth);
                    if (mBrowseDepth < 0) {
                        throw new IllegalArgumentException("Browse depth negative!");
                    }

                    sendMessage(MESSAGE_INTERNAL_MOVE_N_LEVELS_UP);
                    break;

                case MESSAGE_PROCESS_UIDS_CHANGED:
                    processUIDSChange(msg);
                    break;

                case MESSAGE_INTERNAL_CMD_TIMEOUT:
                    Log.w(STATE_TAG, "Command timeout");
                    transitionTo(mConnected);
                    break;

                default:
                    Log.d(STATE_TAG, "deferring message " + msg + " to connected!");
                    deferMessage(msg);
            }
            return true;
        }
    }

    class SetBrowsedPlayer extends CmdState {
        private String STATE_TAG = "AVRCPSM.SetBrowsedPlayer";
        private final int INVALID_ID = -1;
        String mID = "";
        int mPlayerId = INVALID_ID;

        public void setFolder(String id) {
            mID = id;
        }

        public void setPlayerId(int playerId) {
            mPlayerId = playerId;
        }

        @Override
        public boolean processMessage(Message msg) {
            Log.d(STATE_TAG, "processMessage " + msg);
            switch (msg.what) {
                case MESSAGE_PROCESS_SET_BROWSED_PLAYER:
                    // Set the new depth.
                    Log.d(STATE_TAG, "player depth " + msg.arg2);
                    mBrowseDepth = msg.arg2;

                    // If we already on top of player and there is no content.
                    // This should very rarely happen.
                    if (mBrowseDepth == 0 && msg.arg1 == 0) {
                        broadcastFolderList(mID, mEmptyMediaItemList);
                        transitionTo(mConnected);
                    } else {
                        // Otherwise move to root and fetch the listing.
                        // the MoveToRoot#enter() function takes care of fetch.
                        mMoveToRoot.setFolder(mID);
                        transitionTo(mMoveToRoot);
                    }
                    mBrowseTree.setCurrentBrowsedFolder(mID);
                    // Also set the browsed player here.
                    mBrowseTree.setCurrentBrowsedPlayer(mID);
                    // Update AvrcpPlayer during setting browsed player
                    for(AvrcpPlayer player: playerList) {
                        if (player.getId() == mPlayerId) {
                            mAddressedPlayer.setId(mPlayerId);
                            mAddressedPlayer.setName(player.getName());
                            mAddressedPlayer.setTransportFlags(player.getTransportFlags());
                            break;
                        }
                    }
                    break;

                case MESSAGE_INTERNAL_CMD_TIMEOUT:
                    broadcastFolderList(mID, mEmptyMediaItemList);
                    transitionTo(mConnected);
                    break;

                case MESSAGE_PROCESS_UIDS_CHANGED:
                    processUIDSChange(msg);
                    break;

                case MESSAGE_PROCESS_TRACK_CHANGED:
                case MESSAGE_PROCESS_ATTR_CHANGED:
                case MESSAGE_PROCESS_PLAY_POS_CHANGED:
                case MESSAGE_PROCESS_PLAY_STATUS_CHANGED:
                case MESSAGE_PROCESS_CONNECTION_CHANGE:
                case MESSAGE_SEND_PASS_THROUGH_CMD:
                    // All of these messages should be handled by parent state immediately.
                    return false;

                default:
                    Log.d(STATE_TAG, "deferring message " + msg + " to connected!");
                    deferMessage(msg);
            }
            return true;
        }
    }

    class SetAddresedPlayerAndPlayItem extends CmdState {
        private String STATE_TAG = "AVRCPSM.SetAddresedPlayerAndPlayItem";
        int mScope;
        String mPlayItemId;
        String mAddrPlayerId;

        public void setItemAndScope(String addrPlayerId, String playItemId, int scope) {
            mAddrPlayerId = addrPlayerId;
            mPlayItemId = playItemId;
            mScope = scope;
        }

        @Override
        public boolean processMessage(Message msg) {
            Log.d(STATE_TAG, "processMessage " + msg);
            switch (msg.what) {
                case MESSAGE_PROCESS_SET_ADDRESSED_PLAYER:
                    // Set the new addressed player.
                    mBrowseTree.setCurrentAddressedPlayer(mAddrPlayerId);
                    // And now play the item.
                    AvrcpControllerService.playItemNative(
                        mRemoteDevice.getBluetoothAddress(), (byte) mScope,
                        AvrcpControllerService.hexStringToByteUID(mPlayItemId), mUidCounter);

                    // Transition to connected state here.
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

    // Handle the search action
    class Search extends CmdState {
        private String STATE_TAG = "AVRCPSM.Search";
        // Items found in last search
        private int mItems = 0;

        public int getItems() {
            return mItems;
        }

        public int getItems(int items) {
            if (DBG) {
                Log.d(TAG, "getItems expected: " + items + ", actual: " + mItems);
            }
            if ((items <= 0) || (mItems <= 0))
                return 0;

            return Math.min(items, mItems - 1);
        }

        public boolean isSearchingSupported() {
            boolean supported = false;
            BrowseTree.BrowseNode currBrPlayer =
                mBrowseTree.getCurrentBrowsedPlayer();
            if (currBrPlayer != null) {
                int playerId = currBrPlayer.getPlayerID();
                if (DBG) {
                    Log.d(TAG, "current browsed playerId " + playerId);
                }
                for(AvrcpPlayer player: playerList) {
                    if (player.getId() == playerId) {
                        supported = player.isSearchingSupported();
                        break;
                    }
                }
            }
            return supported;
        }

        @Override
        public void enter() {
            super.enter();
            mItems = 0;
        }

        @Override
        public boolean processMessage(Message msg) {
            Log.d(STATE_TAG, "processMessage " + msg);
            switch (msg.what) {
                case MESSAGE_PROCESS_SEARCH_RESP:
                    int status = msg.arg1;
                    int items = msg.arg2;
                    Log.d(TAG, "search response, status: " + status + ", items: " + items);
                    broadcastNumOfItems(A2dpMediaBrowserService.CUSTOM_ACTION_SEARCH,
                        status, items);
                    // Store search result
                    mItems = items;
                    // Set current browsed folder as ROOT. This guarantees that
                    // search folder can be listed out when to browse player later.
                    // The reason is that browsed player may be cached internally
                    // so that search folder may not be found.
                    mBrowseTree.setCurrentBrowsedFolder(BrowseTree.ROOT);
                    transitionTo(mConnected);
                    break;

                case MESSAGE_INTERNAL_CMD_TIMEOUT:
                    Log.e(STATE_TAG, "search timeout");
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

    class SetCurrentPas extends CmdState {
        private String STATE_TAG = "AVRCPSM.SetCurrentPas";

        @Override
        public boolean processMessage(Message msg) {
            Log.d(STATE_TAG, "processMessage " + msg);
            switch (msg.what) {
                case MESSAGE_PROCESS_PAS_CHANGED:
                    mAddressedPlayer.makePlayerAppSetting((byte[])msg.obj);
                    broadcastPlayerAppSettingChanged(mAddressedPlayer.getAvrcpSettings());
                    // Transition to connected state here.
                    transitionTo(mConnected);
                    break;

                case MESSAGE_INTERNAL_CMD_TIMEOUT:
                    transitionTo(mConnected);
                    break;

                case MESSAGE_SEND_PASS_THROUGH_CMD:
                case MESSAGE_SEND_GROUP_NAVIGATION_CMD:
                case MESSAGE_PROCESS_SET_ABS_VOL_CMD:
                case MESSAGE_PROCESS_REGISTER_ABS_VOL_NOTIFICATION:
                case MESSAGE_PROCESS_TRACK_CHANGED:
                case MESSAGE_PROCESS_PLAY_POS_CHANGED:
                case MESSAGE_PROCESS_PLAY_STATUS_CHANGED:
                case MESSAGE_PROCESS_VOLUME_CHANGED_NOTIFICATION:
                case MESSAGE_STOP_METADATA_BROADCASTS:
                case MESSAGE_START_METADATA_BROADCASTS:
                case MESSAGE_PROCESS_CONNECTION_CHANGE:
                case MESSAGE_PROCESS_BROWSE_CONNECTION_CHANGE:
                case MESSAGE_SET_CURRENT_PAS:
                    // All of these messages should be handled by parent state immediately.
                    return false;

                default:
                    Log.d(STATE_TAG, "deferring message " + msg + " to connected!");
                    deferMessage(msg);
            }
            return true;
        }
    }

    class AddToNowPlaying extends CmdState {
        private String STATE_TAG = "AVRCPSM.AddToNowPlaying";
        String mediaId;

        public boolean isSupported() {
            boolean supported = false;
            BrowseTree.BrowseNode currBrPlayer =
                mBrowseTree.getCurrentBrowsedPlayer();
            if (currBrPlayer != null) {
                int playerId = currBrPlayer.getPlayerID();
                if (DBG) {
                    Log.d(TAG, "current browsed playerId " + playerId);
                }
                for(AvrcpPlayer player: playerList) {
                    if (player.getId() == playerId) {
                        supported = player.isAddToNowPlayingSupported();
                        break;
                    }
                }
            }
            return supported;
        }

        public void setMediaId(String mediaId) {
            this.mediaId = mediaId;
        }

        @Override
        public boolean processMessage(Message msg) {
            Log.d(STATE_TAG, "processMessage " + msg);
            switch (msg.what) {
                case MESSAGE_PROCESS_ADD_TO_NOW_PLAYING_RESP:
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

    class GetTotalNumOfItems extends CmdState {
        private String STATE_TAG = "AVRCPSM.GetTotalNumOfItems";
        int mScope = 0;

        public void setScope(int scope) {
            mScope = scope;
        }

        public boolean isSupported() {
            boolean supported = false;
            BrowseTree.BrowseNode currBrPlayer =
                mBrowseTree.getCurrentBrowsedPlayer();
            if (currBrPlayer != null) {
                int playerId = currBrPlayer.getPlayerID();
                if (DBG) {
                    Log.d(TAG, "current browsed playerId " + playerId);
                }
                for(AvrcpPlayer player: playerList) {
                    if (player.getId() == playerId) {
                        supported = player.isNumberOfItemsSupported();
                        break;
                    }
                }
            }
            return supported;
        }

        @Override
        public boolean processMessage(Message msg) {
            Log.d(STATE_TAG, "processMessage " + msg);
            switch (msg.what) {
                case MESSAGE_PROCESS_NUM_OF_ITEMS:
                    broadcastNumOfItems(A2dpMediaBrowserService.CUSTOM_ACTION_GET_TOTAL_NUM_OF_ITEMS,
                        msg.arg1, msg.arg2);
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

    // Class template for commands. Each state should do the following:
    // (a) In enter() send a timeout message which could be tracked in the
    // processMessage() stage.
    // (b) In exit() remove all the timeouts.
    //
    // Essentially the lifecycle of a timeout should be bounded to a CmdState always.
    abstract class CmdState extends State {
        @Override
        public void enter() {
            sendMessageDelayed(MESSAGE_INTERNAL_CMD_TIMEOUT, CMD_TIMEOUT_MILLIS);
        }

        @Override
        public void exit() {
            removeMessages(MESSAGE_INTERNAL_CMD_TIMEOUT);
        }
    }

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            try {
                mCarAudioManager = (CarAudioManager) mCar.getCarManager(Car.AUDIO_SERVICE);
            } catch (CarNotConnectedException e) {
                Log.e(TAG, "Car is not connected!", e);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            Log.e(TAG, "Car service is disconnected");
        }
    };

    // Interface APIs
    boolean isConnected() {
        synchronized (mLock) {
            return mIsConnected;
        }
    }

    // Utility Functions
    boolean okToConnect(BluetoothDevice device) {
        log("Enter okToConnect");
        A2dpSinkService a2dpSinkService = A2dpSinkService.getA2dpSinkService();
        int priority = a2dpSinkService.getPriority(device);
        // check priority and accept or reject the connection. if priority is undefined
        // it is likely that our SDP has not completed and peer is initiating the
        // connection. Allow this connection, provided the device is bonded
        if((BluetoothProfile.PRIORITY_OFF < priority) ||
                ((BluetoothProfile.PRIORITY_UNDEFINED == priority) &&
                (device.getBondState() != BluetoothDevice.BOND_NONE))){
            return true;
        }
        logw("okToConnect not OK to connect " + device);
        return false;
    }

    void doQuit() {
        try {
            mContext.unregisterReceiver(mBroadcastReceiver);
        } catch (IllegalArgumentException expected) {
            // If the receiver was never registered unregister will throw an
            // IllegalArgumentException.
        }
        if (mBipStateMachine != null) {
            mBipStateMachine.doQuit();
            mBipStateMachine = null;
            if (DBG) {
                Log.d(TAG, "mBipStateMachine doQuit ");
            }
        }
        // We should discard all currently queued up messages.
        quitNow();
    }

    void dump(StringBuilder sb) {
        ProfileService.println(sb, "StateMachine: " + this.toString());
    }

    MediaMetadata getCurrentMetaData() {
        synchronized (mLock) {
            if (mAddressedPlayer != null && mAddressedPlayer.getCurrentTrack() != null) {
                MediaMetadata mmd = mAddressedPlayer.getCurrentTrack().getMediaMetaData();
                if (DBG) {
                    Log.d(TAG, "getCurrentMetaData mmd " + mmd);
                }
            }
            return mEmptyMMD;
        }
    }

    PlaybackState getCurrentPlayBackState() {
        return getCurrentPlayBackState(true);
    }

    PlaybackState getCurrentPlayBackState(boolean cached) {
        if (cached) {
            synchronized (mLock) {
                if (mAddressedPlayer == null) {
                    return new PlaybackState.Builder().setState(PlaybackState.STATE_ERROR,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN,0).build();
                }
                return mAddressedPlayer.getPlaybackState();
            }
        } else {
            // Issue a native request, we return NULL since this is only for PTS.
            AvrcpControllerService.getPlaybackStateNative(mRemoteDevice.getBluetoothAddress());
            return null;
        }
    }

    int getSupportedFeatures(BluetoothDevice device) {
        BluetoothDevice currentDevice = (mRemoteDevice != null) ? mRemoteDevice.mBTDevice : null;
        Log.d(TAG, "device: " + device + ", current: " + currentDevice);

        if ((device == null) ||
            (currentDevice == null) ||
            !currentDevice.equals(device)) {
            return BluetoothAvrcpController.BTRC_FEAT_NONE;
        }

        return mRemoteDevice.getRemoteFeatures();
    }

    boolean checkOpsListAndChangeFolder(ArrayList<BrowseTree.BrowseStep> operations, boolean flag) {
        if((operations != null) && (operations.size() > 0)) {
            // Find the direction of traversal.
            int direction = -1;
            BrowseTree.BrowseStep step = operations.remove(0);
            if (step.getDirection() == BrowseTree.DIRECTION_DOWN) {
                direction = AvrcpControllerService.FOLDER_NAVIGATION_DIRECTION_DOWN;
            } else if (step.getDirection() == BrowseTree.DIRECTION_UP) {
                direction = AvrcpControllerService.FOLDER_NAVIGATION_DIRECTION_UP;
            }

            Bundle b = new Bundle();
            b.putString(AvrcpControllerService.EXTRA_FOLDER_ID, step.getID());
            b.putString(AvrcpControllerService.EXTRA_FOLDER_BT_ID, step.getFolderUID());
            b.putParcelableArrayList(AvrcpControllerService.EXTRA_FOLDER_CHANGE_OPERATIONS, operations);

            if (flag) {
                transitionTo(mConnected);
            }

            sendMessage(
                AvrcpControllerStateMachine.MESSAGE_CHANGE_FOLDER_PATH, direction, 0, b);
            return true;
        } else {
            return false;
        }
    }

    // Entry point to the state machine where the services should call to fetch children
    // for a specific node. It checks if the currently browsed node is the same as the one being
    // asked for, in that case it returns the currently cached children. This saves bandwidth and
    // also if we are already fetching elements for a current folder (since we need to batch
    // fetches) then we should not submit another request but simply return what we have fetched
    // until now.
    //
    // It handles fetches to all VFS, Now Playing and Media Player lists.
    void getChildren(String parentMediaId, int start, int items) {
        BrowseTree.BrowseNode currFolder = mBrowseTree.getCurrentBrowsedFolder();
        BrowseTree.BrowseNode bn = mBrowseTree.findBrowseNodeByID(parentMediaId);
        if (bn == null) {
            Log.e(TAG, "Invalid folder to browse " + mBrowseTree);
            broadcastFolderList(parentMediaId, mEmptyMediaItemList);
            return;
        }

        if (DBG) {
            Log.d(TAG, "To Browse folder " + bn + " is cached " + bn.isCached() +
                " current folder " + currFolder);
        }

        synchronized (mBrowseLock) {
            if (bn.equals(currFolder) && bn.isCached()) {
                /* It is an ugly design to return existing children each time, the
                   new onLocadChildren request can be satisfied by the subsequent
                   broadcastFolderList */
                if (!bn.isFetching() || currFolder.isNowPlaying()) {
                    if (DBG) {
                        Log.d(TAG, "Same cached folder -- returning existing children.");
                    }

                    BrowseTree.BrowseNode n = mBrowseTree.findBrowseNodeByID(parentMediaId);
                    if (n == null) return;
                    ArrayList<MediaItem> childrenList = new ArrayList<MediaItem>();
                    for (BrowseTree.BrowseNode cn : n.getChildren()) {
                        childrenList.add(cn.getMediaItem());
                    }
                    broadcastFolderList(parentMediaId, childrenList);
                }
                return;
            }
        }

        Message msg = null;
        int btDirection = mBrowseTree.getDirection(parentMediaId);
        if (DBG) {
            Log.d(TAG, "Browse direction parent " + currFolder +
                " req " + parentMediaId + " direction " + btDirection);
        }
        if (BrowseTree.ROOT.equals(parentMediaId)) {
            // Root contains the list of players.
            msg = obtainMessage(AvrcpControllerStateMachine.MESSAGE_GET_PLAYER_LIST, start, items);
        } else if (bn.isPlayer() && btDirection != BrowseTree.DIRECTION_SAME) {
            // Set browsed (and addressed player) as the new player.
            // This should fetch the list of folders.
            msg = obtainMessage(AvrcpControllerStateMachine.MESSAGE_SET_BROWSED_PLAYER,
                bn.getPlayerID(), 0, bn.getID());
        } else if (bn.isNowPlaying()) {
            // Issue a request to fetch the items.
            msg = obtainMessage(
                AvrcpControllerStateMachine.MESSAGE_GET_NOW_PLAYING_LIST,
                start, items, parentMediaId);
        } else if (bn.isSearch()) {
            // Issue a request to fetch the items in search list.
            msg = obtainMessage(
                AvrcpControllerStateMachine.MESSAGE_GET_SEARCH_LIST,
                start, mSearch.getItems(items), parentMediaId);
        } else {
            // Only change folder if desired. If an app refreshes a folder
            // (because it resumed etc) and current folder does not change
            // then we can simply fetch list.

            // We exempt two conditions from change folder:
            // a) If the new folder is the same as current folder (refresh of UI)
            // b) If the new folder is ROOT and current folder is NOW_PLAYING or Search (or vice-versa)
            // In this condition we 'fake' child-parent hierarchy but it does not exist in
            // bluetooth world.
            boolean isNowPlayingOrSearch = currFolder.isNowPlaying() || currFolder.isSearch();
            boolean isNowPlayingOrSearchToRoot =
                isNowPlayingOrSearch && bn.getID().equals(BrowseTree.ROOT);
            if (!isNowPlayingOrSearchToRoot) {
                // Find the direction of traversal.
                int direction = -1;

                BrowseTree.BrowseNode rootFol =
                    mBrowseTree.findBrowseNodeByID(BrowseTree.ROOT);

                if (isNowPlayingOrSearch) {
                    currFolder = mBrowseTree.getPreviousBrowsedVfsFolder();
                }

                List<BrowseTree.BrowseNode> startRoute =
                    new ArrayList<BrowseTree.BrowseNode>();
                // Find the route from root node to the start node
                mBrowseTree.getNodesRoute(currFolder, rootFol, startRoute);
                List<BrowseTree.BrowseNode> endRoute = new ArrayList<BrowseTree.BrowseNode>();
                // Find the route from root node to the end node
                mBrowseTree.getNodesRoute(bn, rootFol, endRoute);

                // Find the shortest route from start node to end node
                List<BrowseTree.BrowseNode> shortestRoute =
                    mBrowseTree.getShortestRoute(endRoute, startRoute, rootFol);
                // Generate the collection of change folder path steps,
                // which from start node to end node
                ArrayList<BrowseTree.BrowseStep> operations =
                    mBrowseTree.getFolderChangeOps(shortestRoute);

                if (!checkOpsListAndChangeFolder(operations, false)) {
                    // Fetch the listing without changing paths.
                    msg = obtainMessage(
                        AvrcpControllerStateMachine.MESSAGE_GET_FOLDER_LIST,
                        start, items, parentMediaId);
                    mBrowseTree.setCurrentBrowsedFolder(parentMediaId);
                }
            } else {
                // Fetch the listing without changing paths.
                msg = obtainMessage(
                    AvrcpControllerStateMachine.MESSAGE_GET_FOLDER_LIST,
                    start, items, bn.getFolderUID());
            }
        }

        if (msg != null) {
            sendMessage(msg);
        }
    }

    public void fetchAttrAndPlayItem(String uid) {
        BrowseTree.BrowseNode currItem = mBrowseTree.findFolderByIDLocked(uid);
        BrowseTree.BrowseNode currFolder = mBrowseTree.getCurrentBrowsedFolder();
        Log.d(TAG, "fetchAttrAndPlayItem mediaId=" + uid + " node=" + currItem);
        if (currItem != null) {
            int scope = getScope(currFolder);
            Log.d(TAG, "fetchAttrAndPlayItem scope=" + scope + " folder=" + currFolder.getID());
            Message msg = obtainMessage(
                AvrcpControllerStateMachine.MESSAGE_FETCH_ATTR_AND_PLAY_ITEM,
                scope, 0, currItem.getFolderUID());
            sendMessage(msg);
        }
    }

    public synchronized void addListener(FolderListListener listener) {
        mListeners.add(listener);
    }

    public synchronized void removeListener(FolderListListener listener) {
        mListeners.remove(listener);
    }

    private void broadcastMetaDataChanged(MediaMetadata metadata) {
        Intent intent = new Intent(AvrcpControllerService.ACTION_TRACK_EVENT);
        intent.putExtra(AvrcpControllerService.EXTRA_METADATA, metadata);
        if (DBG) {
            Log.d(TAG, " broadcastMetaDataChanged = " + metadata.getDescription());
        }
        mContext.sendBroadcast(intent, ProfileService.BLUETOOTH_PERM);
    }

    private synchronized void broadcastFolderList(String id, ArrayList<MediaItem> items) {
        Bundle extra = new Bundle();
        Log.d(TAG, "broadcastFolderList id " + id + " items size " + items.size());
        extra.putString(AvrcpControllerService.EXTRA_FOLDER_ID, id);
        extra.putParcelableArrayList(AvrcpControllerService.EXTRA_FOLDER_LIST, items);

        for (FolderListListener listener : mListeners) {
            listener.onFolderListUpdated(extra);
        }
    }

    private void broadcastPlayBackStateChanged(PlaybackState state) {
        Intent intent = new Intent(AvrcpControllerService.ACTION_TRACK_EVENT);
        intent.putExtra(AvrcpControllerService.EXTRA_PLAYBACK, state);
        if (DBG) {
            Log.d(TAG, " broadcastPlayBackStateChanged = " + state.toString());
        }
        mContext.sendBroadcast(intent, ProfileService.BLUETOOTH_PERM);
    }

    private void processUIDSChange(Message msg) {
        BluetoothDevice device = (BluetoothDevice) msg.obj;
        int uidCounter = msg.arg1;
        if (DBG) {
            Log.d(TAG, " processUIDSChange device: " + device + ", uidCounter: " + uidCounter);
        }
        mUidCounter = uidCounter;

        if (mRemoteDevice != null &&
            mRemoteDevice.isCoverArtSupported() && mBipStateMachine != null) {
            mBipStateMachine.sendMessage(AvrcpControllerBipStateMachine.
                MESSAGE_DISCONNECT_BIP, mRemoteDevice.getRemoteBipPsm(), 0,
                mRemoteDevice.mBTDevice);

            setBipReconnectionFlag(true);
        }

        Intent intent_uids = new Intent(BluetoothAvrcpController.ACTION_UIDS_EVENT);
        intent_uids.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        mContext.sendBroadcast(intent_uids, ProfileService.BLUETOOTH_PERM);

        // transition to mConnected might cause some operations blocked, such
        // as processing the message "MESSAGE_PROCESS_FOLDER_PATH", so
        // remove the logic of transtion state.
    }

    private void processSearchReq(String query) {
        if (mSearch.isSearchingSupported()) {
            Log.d(TAG, "processSearchReq search: " + query);
            AvrcpControllerService.searchNative(mRemoteDevice.getBluetoothAddress(),
                AVRC_CHARSET_UTF8, query.length(), query);
            transitionTo(mSearch);
        } else {
            Log.w(TAG, "Search not supported");
        }
    }

    private void processAddToNowPlayingReq(int scope, String mediaId) {
        BrowseTree.BrowseNode currItem = mBrowseTree.findFolderByIDLocked(mediaId);
        Log.d(TAG, "processAddToNowPlayingReq mediaId=" + mediaId + " node=" + currItem);
        if (currItem != null) {
            String uid = currItem.getFolderUID();
            Log.d(TAG, "processAddToNowPlayingReq scope=" + scope + " uid=" + uid);

            // Only add item playable.
            boolean isSupported = mAddToNowPlaying.isSupported() && currItem.isPlayable();
            if (isSupported) {
                Log.d(TAG, "Add to now playing, scope: " + scope);
                if (isVfs(scope) || isSearch(scope)) {
                    mAddToNowPlaying.setMediaId(mediaId);
                    AvrcpControllerService.addToNowPlayingNative(
                        mRemoteDevice.getBluetoothAddress(), (byte) scope,
                        AvrcpControllerService.hexStringToByteUID(uid), mUidCounter);
                    transitionTo(mAddToNowPlaying);
                } else if (isNowPlaying(scope)) {
                    Log.d(TAG, "Already in NowPlaying");
                    broadcastAddToNowPlayingResult(AvrcpControllerService.JNI_AVRC_STS_NO_ERROR);
                } else {
                    Log.w(TAG, "Add to now playing invalid scope: " + scope);
                    broadcastAddToNowPlayingResult(AvrcpControllerService.JNI_AVRC_STS_INVALID_SCOPE);
                }
            } else {
                Log.w(TAG, "Add to now playing not supported");
                broadcastAddToNowPlayingResult(AvrcpControllerService.JNI_AVRC_STS_INVALID_CMD);
            }
        }
    }

    private void processGetItemAttrReq(Bundle extras) {
        int scope = extras.getInt(A2dpMediaBrowserService.KEY_BROWSE_SCOPE, 0);
        String mediaId = extras.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
        int [] attributeId = extras.getIntArray(A2dpMediaBrowserService.KEY_ATTRIBUTE_ID);

        if (mediaId != null) {
            BrowseTree.BrowseNode currItem = mBrowseTree.findBrowseNodeByID(mediaId);
            Log.d(TAG, "processGetItemAttrReq mediaId=" + mediaId + " node=" + currItem);
            if (currItem != null) {
                String uid = currItem.getFolderUID();
                Log.d(TAG, "processGetItemAttrReq scope=" + scope + " uid=" + uid);
                getItemAttributes(mRemoteDevice, scope, uid, attributeId);
            }
        } else {
            Log.d(TAG, "processGetItemAttrReq GetElementAttributes");
        }
    }

    private void processGetElementAttrReq(Bundle extras) {
        Log.d(TAG, "processGetElementAttrReq");
        int [] attributeId = extras.getIntArray(A2dpMediaBrowserService.KEY_ATTRIBUTE_ID);
        AvrcpControllerService.getElementAttributesNative(
            mRemoteDevice.getBluetoothAddress(), (byte) attributeId.length, attributeId);
    }

    private void processGetFolderItems(Bundle extras) {
        Log.d(TAG, "processGetFolderItems");
        int scope = extras.getInt(A2dpMediaBrowserService.KEY_BROWSE_SCOPE, 0);
        int start = extras.getInt(A2dpMediaBrowserService.KEY_START, 0);
        int end = extras.getInt(A2dpMediaBrowserService.KEY_END, 0xFF);
        int [] attributeId = extras.getIntArray(A2dpMediaBrowserService.KEY_ATTRIBUTE_ID);
        AvrcpControllerService.getFolderItemsNative(
            mRemoteDevice.getBluetoothAddress(), (byte) scope, start, end,
            (byte) attributeId.length, attributeId);
    }

    private void processGetNumOfItemsReq(int scope) {
        if (mGetTotalNumOfItems.isSupported()) {
            Log.d(TAG, "Get total num of items, scope: " + scope);
            mGetTotalNumOfItems.setScope(scope);

            AvrcpControllerService.getTotalNumOfItemsNative(
                mRemoteDevice.getBluetoothAddress(), (byte) scope);
            transitionTo(mGetTotalNumOfItems);
        } else {
            Log.w(TAG, "Get total num of items not supported");
            broadcastNumOfItems(A2dpMediaBrowserService.CUSTOM_ACTION_GET_TOTAL_NUM_OF_ITEMS,
                AvrcpControllerService.JNI_AVRC_STS_INVALID_CMD, 0);
        }
    }

    private void processSetAddressedPlayerReq(int id, String mediaId) {
        Log.d(TAG, "processSetAddressedPlayerReq mediaId=" + mediaId + " playerId=" + id);
        AvrcpControllerService.setAddressedPlayerNative(
            mRemoteDevice.getBluetoothAddress(), id);
    }

    private void processRequestContinuingResponse(int pduId) {
        Log.d(TAG, "processRequestContinuingResponse pduId=" + pduId);
        AvrcpControllerService.requestContinuingResponseNative(
            mRemoteDevice.getBluetoothAddress(), (byte) pduId);
    }

    private void processAbortContinuingResponse(int pduId) {
        Log.d(TAG, "processAbortContinuingResponse pduId=" + pduId);
        AvrcpControllerService.abortContinuingResponseNative(
            mRemoteDevice.getBluetoothAddress(), (byte) pduId);
    }

    private void processReleaseConnection(BluetoothDevice device) {
        Log.d(TAG, "processReleaseConnection device=" + device);
        AvrcpControllerService.disconnectNative(
            mRemoteDevice.getBluetoothAddress());
    }

    private void processAddressedPlayerChanged(int playerId, int uidCounter) {
        boolean result = false;
        Log.d(TAG, "processAddressedPlayerChanged, playerId " + playerId
            + " uidCounter " + uidCounter);
        // Update mAddressedPlayer
        for(AvrcpPlayer player: playerList) {
            if (player.getId() == playerId) {
                mAddressedPlayer = player;
                result = true;
                break;
            }
        }

        if (result) {
            result = false;
            for (BrowseTree.BrowseNode c :
                    mBrowseTree.findBrowseNodeByID(BrowseTree.ROOT).getChildren()) {
                if (c.getPlayerID() == playerId) {
                    // Set the new addressed player in browser tree.
                    Log.d(TAG, "setCurrentAddressedPlayer, id " + c.getID());
                    mBrowseTree.setCurrentAddressedPlayer(c.getID());
                    result = true;
                }
            }

            BluetoothAvrcpPlayerSettings playerSetting = mAddressedPlayer.getAvrcpSettings();
            int settings = playerSetting.getSettings();
            Log.d(TAG, "Supported PAS setting " + settings);
            if (settings == 0) {
                /* Cannot find supported value due to address player changed */
                Log.d(TAG, "No supported value found, try to fetch again");
                AvrcpControllerService.fetchPlayerApplicationSettingNative(mRemoteDevice.getBluetoothAddress());
            }
        } else {
            Log.e(TAG, "Cannot find player id " + playerId + " in player list");
        }

        if (!result) {
            // Cannot find the player in browse tree, get player list first
            mAddressedPlayer = new AvrcpPlayer();
            mAddressedPlayer.setId(playerId);
            AvrcpControllerService.getPlayerListNative(
                mRemoteDevice.getBluetoothAddress(), 0, AvrcpControllerService.MAX_ITEMS);
            transitionTo(mGetPlayerListing);
        }

    }

    private void processAvailablePlayerChanged() {
        Log.d(TAG, "processAvailablePlayerChanged");
        AvrcpControllerService.getPlayerListNative(
            mRemoteDevice.getBluetoothAddress(), 0, AvrcpControllerService.MAX_ITEMS);
        transitionTo(mGetPlayerListing);
    }

    private void processNowPlayingChanged() {
        Log.d(TAG, "processNowPlayingChanged");
        int start = 0;
        int items = AvrcpControllerService.MAX_ITEM_NUMBER;
        String parentMediaId = null;
        BrowseTree.BrowseNode currBrPlayer =
            mBrowseTree.getCurrentBrowsedPlayer();

        if (currBrPlayer != null) {
            for (BrowseTree.BrowseNode cn : currBrPlayer.getChildren()) {
                if (cn.isNowPlaying()) {
                    parentMediaId = cn.getID();
                    break;
                }
            }

            if (parentMediaId != null) {
                // Issue a request to fetch NowPlaying items.
                Log.d(TAG, "processNowPlayingChanged mediaId: " + parentMediaId);
                Message msg = obtainMessage(
                              AvrcpControllerStateMachine.MESSAGE_GET_NOW_PLAYING_LIST,
                              start, items, parentMediaId);
                sendMessage(msg);
            } else {
                Log.w(TAG, "Can't find BrowseNode for NowPlaying");
            }
        } else {
            Log.w(TAG, "Current browsed player null");
        }
    }

    private void processSetAddressedPlayerResp(int status) {
        Log.d(TAG, "processSetAddressedPlayerResp status: " + status);
        broadcastSetAddressedPlayerResult(status);
    }

    private void processErrorStatusCode(Bundle extra) {
        int opcode = extra.getInt(EXTRA_OPERATION_CODE);
        int id = extra.getInt(EXTRA_ID);
        int status = extra.getInt(EXTRA_STATUS);

        broadcastErrorStatusCode(opcode, id, status);
    }

    private void processBipConnected() {
        Log.d(TAG, "processBipConnected");
        mBipStateMachine.updateRequiredImageProperties();
        resetRetryBipCount();
        if (mRemoteDevice != null) {
            if (mAddressedPlayer.getCurrentTrack().getCoverArtHandle().isEmpty()) {
                /* track changed happened before BIP connection. should fetch
               * cover art handle. NumAttributes  = 0 and
               * attributes list as null will fetch all attributes
               */
                AvrcpControllerService.getItemElementAttributesNative(
                    mRemoteDevice.getBluetoothAddress(), (byte)0, null);
            } else {
                int FLAG;
                if (AvrcpControllerBipStateMachine.mImageType.
                        equalsIgnoreCase("thumbnaillinked")) {
                    FLAG = AvrcpControllerBipStateMachine.
                    MESSAGE_FETCH_THUMBNAIL;
                } else {
                    // Image or Thumbnail Image
                    FLAG = AvrcpControllerBipStateMachine.MESSAGE_FETCH_IMAGE;
                }
                mBipStateMachine.sendMessage(FLAG,
                        mAddressedPlayer.getCurrentTrack().getCoverArtHandle());
            }
        }
    }

    private void processBipDisconnected() {
        Log.d(TAG, "processBipDisconnected");
        // Clear cover art related info for current track.
        mAddressedPlayer.getCurrentTrack().clearCoverArtData();

        if (getBipReconnectionFlag()) {
            mBipStateMachine.sendMessageDelayed(AvrcpControllerBipStateMachine.
                MESSAGE_CONNECT_BIP, mRemoteDevice.getRemoteBipPsm(), 0,
                mRemoteDevice.mBTDevice, BIP_RECONNECTION_DELAY_MILLTS);

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
                MESSAGE_CONNECT_BIP, mRemoteDevice.getRemoteBipPsm(), 0,
                mRemoteDevice.mBTDevice);
        }
        incrementRetryBipCount();
    }

    private void processBipImageFetched(Message msg) {
        Log.d(TAG, "processBipImageFetched");
        boolean imageUpdated = mAddressedPlayer.getCurrentTrack().updateImageLocation(
          msg.getData().getString(AvrcpControllerBipStateMachine.COVER_ART_HANDLE),
          msg.getData().getString(AvrcpControllerBipStateMachine.COVER_ART_IMAGE_LOCATION));

        if (imageUpdated) {
            broadcastMetaDataChanged(mAddressedPlayer.getCurrentTrack().getMediaMetaData());
        }
    }

    private void processBipThumbNailFetched(Message msg) {
        Log.d(TAG, "processBipThumbNailFetched");
        boolean thumbNailUpdated = mAddressedPlayer.getCurrentTrack().updateThumbNailLocation(
          msg.getData().getString(AvrcpControllerBipStateMachine.COVER_ART_HANDLE),
          msg.getData().getString(AvrcpControllerBipStateMachine.COVER_ART_IMAGE_LOCATION));

        if (thumbNailUpdated) {
            broadcastMetaDataChanged(mAddressedPlayer.getCurrentTrack().getMediaMetaData());
        }
    }

    private void processListPas(byte[] btAvrcpAttributeList) {
        Log.d(TAG, "processListPas");
        mAddressedPlayer.setSupportedPlayerAppSetting(btAvrcpAttributeList);
    }

    private void processPasChanged(byte[] btAvrcpAttributeList) {
        Log.d(TAG, "processPasChanged");
        mAddressedPlayer.makePlayerAppSetting(btAvrcpAttributeList);
        broadcastPlayerAppSettingChanged(mAddressedPlayer.getAvrcpSettings());
    }

    private void getItemAttributes(RemoteDevice device,
        int scope, String uid, int [] attributeId) {
        int features = getSupportedFeatures(device.mBTDevice);
        if ((features & BluetoothAvrcpController.BTRC_FEAT_BROWSE) != 0) {
            Log.d(TAG, "Send GetItemAttributes");
            AvrcpControllerService.getItemAttributesNative(
                device.getBluetoothAddress(), (byte) scope,
                AvrcpControllerService.hexStringToByteUID(uid),
                mUidCounter, (byte) attributeId.length, attributeId);
        } else {
            Log.d(TAG, "browsing channel not supported!!!");
        }
    }

    private int getScope(BrowseTree.BrowseNode folder) {
        if (folder.isNowPlaying())
            return AvrcpControllerService.BROWSE_SCOPE_NOW_PLAYING;
        else if (folder.isSearch())
            return AvrcpControllerService.BROWSE_SCOPE_SEARCH;
        else
            return AvrcpControllerService.BROWSE_SCOPE_VFS;
    }

    private void broadcastPlayerAppSettingChanged(BluetoothAvrcpPlayerSettings mPlAppSetting) {
        Intent intent = new Intent(BluetoothAvrcpController.ACTION_PLAYER_SETTING);
        intent.putExtra(BluetoothAvrcpController.EXTRA_PLAYER_SETTING, mPlAppSetting);
        if (DBG) Log.d(TAG," broadcastPlayerAppSettingChanged = " +
                displayBluetoothAvrcpSettings(mPlAppSetting));
        mContext.sendBroadcast(intent, ProfileService.BLUETOOTH_PERM);
    }

    private void broadcastAddToNowPlayingResult(int status) {
        Log.d(TAG, "broadcastAddToNowPlayingResult status: " + status);
        Intent intent = createIntent(
            A2dpMediaBrowserService.CUSTOM_ACTION_ADD_TO_NOW_PLAYING, status);
        mContext.sendBroadcast(intent, ProfileService.BLUETOOTH_PERM);
    }

    private void broadcastNumOfItems(String cmd, int status, int items) {
        Log.d(TAG, "broadcastNumOfItems cmd: " + cmd + ", status: " + status + ", items: " + items);
        Intent intent = createIntent(cmd, status);
        intent.putExtra(A2dpMediaBrowserService.EXTRA_NUM_OF_ITEMS, items);
        mContext.sendBroadcast(intent, ProfileService.BLUETOOTH_PERM);
    }

    private void broadcastSetAddressedPlayerResult(int status) {
        Log.d(TAG, "broadcastSetAddressedPlayerResult status: " + status);
        Intent intent = createIntent(
            A2dpMediaBrowserService.CUSTOM_ACTION_SET_ADDRESSED_PLAYER, status);
        mContext.sendBroadcast(intent, ProfileService.BLUETOOTH_PERM);
    }

    private void broadcastErrorStatusCode(int opcode, int id, int status) {
        Log.d(TAG, "broadcastErrorStatusCode opcode:" + opcode + ", id: " + id + ", status: " + status);

        Intent intent = new Intent(A2dpMediaBrowserService.ACTION_ERROR_STATUS_CODE);
        intent.putExtra(A2dpMediaBrowserService.EXTRA_OPERATION_CODE, opcode);
        intent.putExtra(A2dpMediaBrowserService.EXTRA_ID, id);
        intent.putExtra(A2dpMediaBrowserService.EXTRA_STATUS, status);

        mContext.sendBroadcast(intent, ProfileService.BLUETOOTH_PERM);
    }

    private Intent createIntent(String cmd, int status) {
        int result = getResult(status);
        Intent intent = new Intent(A2dpMediaBrowserService.ACTION_CUSTOM_ACTION_RESULT);
        intent.putExtra(A2dpMediaBrowserService.EXTRA_CUSTOM_ACTION, cmd);
        intent.putExtra(A2dpMediaBrowserService.EXTRA_CUSTOM_ACTION_RESULT, result);
        return intent;
    }

    private int getResult(int status) {
        switch (status) {
            case AvrcpControllerService.JNI_AVRC_STS_NO_ERROR:
                return A2dpMediaBrowserService.RESULT_SUCCESS;

            case AvrcpControllerService.JNI_AVRC_STS_INVALID_CMD:
                return A2dpMediaBrowserService.RESULT_NOT_SUPPORTED;

            case AvrcpControllerService.JNI_AVRC_STS_INVALID_PARAMETER:
            case AvrcpControllerService.JNI_AVRC_STS_INVALID_SCOPE:
            case AvrcpControllerService.JNI_AVRC_INV_RANGE:
                return A2dpMediaBrowserService.RESULT_INVALID_PARAMETER;

            default:
                return A2dpMediaBrowserService.RESULT_ERROR;
        }
    }

    public static boolean isPlayerList(int scope) {
        return scope == AvrcpControllerService.BROWSE_SCOPE_PLAYER_LIST;
    }

    public static boolean isVfs(int scope) {
        return scope == AvrcpControllerService.BROWSE_SCOPE_VFS;
    }

    public static boolean isSearch(int scope) {
        return scope == AvrcpControllerService.BROWSE_SCOPE_SEARCH;
    }

    public static boolean isNowPlaying(int scope) {
        return scope == AvrcpControllerService.BROWSE_SCOPE_NOW_PLAYING;
    }

    private void setBipReconnectionFlag(boolean flag) {
        mBipReconnectonFlag = flag;
    }

    private boolean getBipReconnectionFlag() {
        return mBipReconnectonFlag;
    }

    private void setAbsVolume(int absVol, int label) {
        int maxVolume = 0;
        int currIndex = 0;

        try {
            maxVolume = mCarAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        } catch (CarNotConnectedException e) {
            Log.e(TAG, "Car is not connected", e);
        }

        try {
            currIndex = mCarAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        } catch (CarNotConnectedException e) {
            Log.e(TAG, "Car is not connected", e);
        }

        // Ignore first volume command since phone may not know difference between stream volume
        // and amplifier volume.
        if (mRemoteDevice.getFirstAbsVolCmdRecvd()) {
            int newIndex = (maxVolume * absVol) / ABS_VOL_BASE;
            Log.d(TAG,
                " setAbsVolume =" + absVol + " maxVol = " + maxVolume + " cur = " + currIndex +
                    " new = " + newIndex);
            /*
             * In some cases change in percentage is not sufficient enough to warrant
             * change in index values which are in range of 0-15. For such cases
             * no action is required
             */
            if (newIndex != currIndex) {
                try {
                    mCarAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newIndex,
                        AudioManager.FLAG_SHOW_UI);
                } catch (CarNotConnectedException e) {
                    Log.e(TAG, "Car is not connected", e);
                }
            }
        } else {
            mRemoteDevice.setFirstAbsVolCmdRecvd();
            absVol = (currIndex * ABS_VOL_BASE) / maxVolume;
            Log.d(TAG, " SetAbsVol recvd for first time, respond with " + absVol);
        }

        // sendAbsVolRspNative if SET_ABS_VOL_CMD is issued
        if (mAbsoluteVolumeChangeInProgress) {
            AvrcpControllerService.sendAbsVolRspNative(
                mRemoteDevice.getBluetoothAddress(), absVol, label);
        }
    }

    private int getVolumePercentage() {
        int maxVolume = 0;
        int currIndex = 0;

        try {
            maxVolume = mCarAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        } catch (CarNotConnectedException e) {
            Log.e(TAG, "Car is not connected", e);
        }

        try {
            currIndex = mCarAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        } catch (CarNotConnectedException e) {
            Log.e(TAG, "Car is not connected", e);
        }

        int percentageVol = ((currIndex * ABS_VOL_BASE) / maxVolume);
        return percentageVol;
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(AudioManager.VOLUME_CHANGED_ACTION)) {
                int streamType = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, -1);
                if (streamType == AudioManager.STREAM_MUSIC) {
                    int streamValue = intent .getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_VALUE, -1);
                    int maxVolume = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    // convert system volume to abs volume.
                    if (maxVolume > 0) {
                        int absVol = ((streamValue * ABS_VOL_BASE) / maxVolume);
                        obtainMessage(MESSAGE_PROCESS_VOLUME_CHANGED_NOTIFICATION, absVol).sendToTarget();
                    } else {
                        Log.e(TAG, " can't get max stream volume ");
                    }
                }
            }
        }
    };

    public static String dumpMessageString(int message) {
        String str = "UNKNOWN";
        switch (message) {
            case MESSAGE_SEND_PASS_THROUGH_CMD:
                str = "REQ_PASS_THROUGH_CMD";
                break;
            case MESSAGE_SEND_GROUP_NAVIGATION_CMD:
                str = "REQ_GRP_NAV_CMD";
                break;
            case MESSAGE_PROCESS_SET_ABS_VOL_CMD:
                str = "CB_SET_ABS_VOL_CMD";
                break;
            case MESSAGE_PROCESS_REGISTER_ABS_VOL_NOTIFICATION:
                str = "CB_REGISTER_ABS_VOL";
                break;
            case MESSAGE_PROCESS_TRACK_CHANGED:
                str = "CB_TRACK_CHANGED";
                break;
            case MESSAGE_PROCESS_PLAY_POS_CHANGED:
                str = "CB_PLAY_POS_CHANGED";
                break;
            case MESSAGE_PROCESS_PLAY_STATUS_CHANGED:
                str = "CB_PLAY_STATUS_CHANGED";
                break;
            case MESSAGE_PROCESS_RC_FEATURES:
                str = "CB_RC_FEATURES";
                break;
            case MESSAGE_PROCESS_CONNECTION_CHANGE:
                str = "CB_CONN_CHANGED";
                break;
            case MESSAGE_PROCESS_UIDS_CHANGED:
                str = "CB_UIDS_CHANGED";
                break;
            case MESSAGE_BIP_CONNECTED:
                str = "BIP_CONNECTED";
                break;
            case MESSAGE_BIP_DISCONNECTED:
                str = "BIP_DISCONNECTED";
                break;
            case MESSAGE_BIP_IMAGE_FETCHED:
                str = "BIP_IMAGE_FETCHED";
                break;
            case MESSAGE_BIP_THUMB_NAIL_FETCHED:
                str = "BIP_THUMB_NAIL_FETCHED";
                break;
            case MESSAGE_SEARCH:
                str = "REQ_SEARCH";
                break;
            case MESSAGE_PROCESS_SEARCH_RESP:
                str = "CB_SEARCH_RESP";
                break;
            case MESSAGE_GET_SEARCH_LIST:
                str = "REQ_GET_SEARCH_LIST";
                break;
            case MESSAGE_SET_CURRENT_PAS:
                str = "REQ_SET_CURRENT_PAS";
                break;
            case MESSAGE_PROCESS_LIST_PAS:
                str = "CB_LIST_PAS";
                break;
            case MESSAGE_PROCESS_PAS_CHANGED:
                str = "CB_PAS_CHANGED";
                break;
            case MESSAGE_ADD_TO_NOW_PLAYING:
                str = "REQ_ADD_TO_NOW_PLAYING";
                break;
            case MESSAGE_PROCESS_ADD_TO_NOW_PLAYING_RESP:
                str = "CB_ADD_TO_NOW_PLAYING";
                break;
            case MESSAGE_GET_ITEM_ATTR:
                str = "REQ_GET_ITEM_ATTR";
                break;
            case MESSAGE_GET_ELEMENT_ATTR:
                str = "REQ_GET_ELEMENT_ATTR";
                break;
            case MESSAGE_GET_FOLDER_ITEM:
                str = "REG_GET_FOLDER_ITEM";
                break;
            case MESSAGE_PROCESS_ATTR_CHANGED:
                str = "CB_ATTR_CHANGED";
                break;
            case MESSAGE_GET_NUM_OF_ITEMS:
                str = "REQ_GET_NUM_OF_ITEMS";
                break;
            case MESSAGE_PROCESS_NUM_OF_ITEMS:
                str = "CB_NUM_OF_ITEMS";
                break;
            case MESSAGE_SET_ADDRESSED_PLAYER:
                str = "REQ_SET_ADDRESSED_PLAYER";
                break;
            case MESSAGE_PROCESS_SET_ADDRESSED_PLAYER:
                str = "CB_SET_ADDRESSED_PLAYER";
                break;
            case MESSAGE_REQUEST_CONTINUING_RESPONSE:
                str = "REQ_REQUEST_CONTINUING_RESPONSE";
                break;
            case MESSAGE_ABORT_CONTINUING_RESPONSE:
                str = "REQ_ABORT_CONTINUING_RESPONSE";
                break;
            case MESSAGE_RELEASE_CONNECTION:
                str = "REQ_RELEASE_CONNECTION";
                break;
            default:
                str = Integer.toString(message);
                break;
        }
        return str;
    }

    public static String displayBluetoothAvrcpSettings(BluetoothAvrcpPlayerSettings mSett) {
        StringBuffer sb =  new StringBuffer();
        int supportedSetting = mSett.getSettings();
        if (VDBG) Log.d(TAG," setting: " + supportedSetting);
        if ((supportedSetting & BluetoothAvrcpPlayerSettings.SETTING_EQUALIZER) != 0) {
            sb.append(" EQ : ");
            sb.append(Integer.toString(mSett.getSettingValue(BluetoothAvrcpPlayerSettings.
                                                             SETTING_EQUALIZER)));
        }
        if ((supportedSetting & BluetoothAvrcpPlayerSettings.SETTING_REPEAT) != 0) {
            sb.append(" REPEAT : ");
            sb.append(Integer.toString(mSett.getSettingValue(BluetoothAvrcpPlayerSettings.
                                                             SETTING_REPEAT)));
        }
        if ((supportedSetting & BluetoothAvrcpPlayerSettings.SETTING_SHUFFLE) != 0) {
            sb.append(" SHUFFLE : ");
            sb.append(Integer.toString(mSett.getSettingValue(BluetoothAvrcpPlayerSettings.
                                                             SETTING_SHUFFLE)));
        }
        if ((supportedSetting & BluetoothAvrcpPlayerSettings.SETTING_SCAN) != 0) {
            sb.append(" SCAN : ");
            sb.append(Integer.toString(mSett.getSettingValue(BluetoothAvrcpPlayerSettings.
                                                             SETTING_SCAN)));
        }
        return sb.toString();
    }
}
