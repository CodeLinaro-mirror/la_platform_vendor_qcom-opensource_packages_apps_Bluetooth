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
 *
 * Changes from Qualcomm Innovation Center, Inc. are provided under the following license:
 * Copyright (c) 2024 Qualcomm Innovation Center, Inc. All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.android.bluetooth.avrcpcontroller;

import android.bluetooth.BluetoothAvrcpPlayerSettings;
import android.bluetooth.BluetoothDevice;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.SystemClock;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import java.util.Arrays;
import java.util.ArrayList;

/*
 * Contains information about remote player
 */
class AvrcpPlayer {
    private static final String TAG = "AvrcpPlayer";
    private static final boolean DBG = true;

    public static final int DEFAULT_ID = -1;

    public static final int TYPE_UNKNOWN = -1;
    public static final int TYPE_AUDIO = 0;
    public static final int TYPE_VIDEO = 1;
    public static final int TYPE_BROADCASTING_AUDIO = 2;
    public static final int TYPE_BROADCASTING_VIDEO = 3;

    public static final int SUB_TYPE_UNKNOWN = -1;
    public static final int SUB_TYPE_AUDIO_BOOK = 0;
    public static final int SUB_TYPE_PODCAST = 1;

    public static final int INVALID_ID = -1;

    public static final int FEATURE_PLAY = 40;
    public static final int FEATURE_STOP = 41;
    public static final int FEATURE_PAUSE = 42;
    public static final int FEATURE_REWIND = 44;
    public static final int FEATURE_FAST_FORWARD = 45;
    public static final int FEATURE_FORWARD = 47;
    public static final int FEATURE_PREVIOUS = 48;
    public static final int FEATURE_BROWSING = 59;
    public static final int FEATURE_ADD_TO_NOWPLAYING = 61;
    public static final int FEATURE_NOW_PLAYING = 65;

    // Same to BTRC_FEATURE_BIT_MASK_SIZE in bt_rc.h
    public static final int FEATURE_BIT_MASK_SIZE = 16;

    // Octect value for Feature Bit Mask
    public static final int UIDS_UNIQUE_OCTECT_VALUE = 7;
    // Bit value for Feature Bit Mask
    public static final int UIDS_UNIQUE_BIT_VALUE = 2 << 6;

    // Octect value for Searching
    public static final int SEARCHING_OCTECT_VALUE = 7;
    // Bit value for Searching
    public static final int SEARCHING_BIT_VALUE = 1 << 4;

    /* Octect value for NumberOfItems */
    public static final int NUMBER_OF_ITEMS_OCTECT_VALUE = 8;

    /* Bit value for NumberOfItems */
    public static final int NUMBER_OF_ITEMS_BIT_VALUE = 1 << 3;

    private int mPlayStatus = PlaybackState.STATE_NONE;
    private long mPlayTime = PlaybackState.PLAYBACK_POSITION_UNKNOWN;

    private BluetoothDevice mDevice;
    private long mPlayTimeUpdate = 0;
    private float mPlaySpeed = 1;
    private int mId;
    private String mName = "";
    private int mPlayerType;
    private byte[] mPlayerFeatures = new byte[FEATURE_BIT_MASK_SIZE];
    private long mAvailableActions;
    private AvrcpItem mCurrentTrack;
    private PlaybackState mPlaybackState;

    private TrackInfo mCurrentTrackInfo = new TrackInfo();
    private PlayerApplicationSettings mPlayerAppSetting = new PlayerApplicationSettings();
    private PlayerApplicationSettings mCurrentPlayerApplicationSettings;

    AvrcpPlayer() {
        mId = INVALID_ID;
        //Set Default Actions in case Player data isn't available.
        mAvailableActions = PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY
                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                | PlaybackState.ACTION_STOP;
        PlaybackState.Builder playbackStateBuilder = new PlaybackState.Builder()
                .setActions(mAvailableActions);
        mPlaybackState = playbackStateBuilder.build();
    }

    AvrcpPlayer(BluetoothDevice device, int id, int playerType, int playerSubType,
            String name, byte[] playerFeatures, int playStatus) {
        mDevice = device;
        mId = id;
        mName = name;
        mPlayStatus = playStatus;
        mPlayerType = playerType;
        mPlayerFeatures = Arrays.copyOf(playerFeatures, playerFeatures.length);
        if (mPlayerAppSetting != null) {
            mPlayerAppSetting.makeSupportedSettings(playerFeatures);
        } else {
            Log.e(TAG, "mPlayerAppSetting is null");
        }
        updateAvailableActions();
        PlaybackState.Builder playbackStateBuilder = new PlaybackState.Builder()
                .setActions(mAvailableActions);
        mPlaybackState = playbackStateBuilder.build();
    }

    public BluetoothDevice getDevice() {
        return mDevice;
    }

    public void setId(int id) {
        mId = id;
    }

    public int getId() {
        return mId;
    }

    public void setName(String name) {
        mName = name;
    }

    public String getName() {
        return mName;
    }

    public void setPlayerFeatures(byte[] playerFeatures) {
        System.arraycopy(playerFeatures, 0, mPlayerFeatures, 0, FEATURE_BIT_MASK_SIZE);
        if (mPlayerAppSetting != null) {
            mPlayerAppSetting.makeSupportedSettings(playerFeatures);
        } else {
            Log.e(TAG, "mPlayerAppSetting is null");
        }
    }

    public byte[] getPlayerFeatures() {
        return mPlayerFeatures;
    }

    public void setSupportedPlayerAppSetting (byte[] btAvrcpAttributeList) {
        if (mPlayerAppSetting != null) {
            mPlayerAppSetting.makeSupportedSettings(btAvrcpAttributeList);
        } else {
            Log.e(TAG, "mPlayerAppSetting is null");
        }
    }

    public void makePlayerAppSetting(byte[] btAvrcpAttributeList) {
        if (mPlayerAppSetting != null) {
            mPlayerAppSetting.makeSettings(btAvrcpAttributeList);
        } else {
            Log.e(TAG, "mPlayerAppSetting is null");
        }
    }

    public BluetoothAvrcpPlayerSettings getAvrcpSettings() {
        /* Player App Setting has been cached when Avrcp connected */
        if (mPlayerAppSetting != null) {
            return mPlayerAppSetting.getAvrcpSettings();
        } else {
            Log.e(TAG, "mPlayerAppSetting is null");
            return null;
        }
    }

    public boolean supportsSettings(BluetoothAvrcpPlayerSettings settingsToCheck) {
        if (mPlayerAppSetting != null) {
            return mPlayerAppSetting.supportsSettings(settingsToCheck);
        } else {
            Log.e(TAG, "mPlayerAppSetting is null");
            return false;
        }
    }

    public ArrayList<Byte> getNativeSettings() {
        if (mPlayerAppSetting != null) {
            return mPlayerAppSetting.getNativeSettings();
        } else {
            Log.e(TAG, "mPlayerAppSetting is null");
            return null;
        }
    }

    public boolean isSearchingSupported() {
        return isFeatureSupported(SEARCHING_OCTECT_VALUE, SEARCHING_BIT_VALUE);
    }

    public boolean isNumberOfItemsSupported() {
      return isFeatureSupported(NUMBER_OF_ITEMS_OCTECT_VALUE,
          NUMBER_OF_ITEMS_BIT_VALUE);
    }

    private boolean isFeatureSupported(int octVal, int bitVal) {
        if (octVal < FEATURE_BIT_MASK_SIZE) {
            byte flag = mPlayerFeatures[octVal];
            return (flag & bitVal) == bitVal ? true : false;
        } else {
            return false;
        }
    }

    public void setPlayTime(int playTime) {
        mPlayTime = playTime;
        mPlayTimeUpdate = SystemClock.elapsedRealtime();
        mPlaybackState = new PlaybackState.Builder(mPlaybackState).setState(
                mPlayStatus, mPlayTime,
                mPlaySpeed).build();
    }

    public long getPlayTime() {
        return mPlayTime;
    }

    public void setPlayStatus(int playStatus) {
        if (mPlayTime != PlaybackState.PLAYBACK_POSITION_UNKNOWN) {
            mPlayTime += mPlaySpeed * (SystemClock.elapsedRealtime()
                    - mPlaybackState.getLastPositionUpdateTime());
        }
        mPlayStatus = playStatus;
        switch (mPlayStatus) {
            case PlaybackState.STATE_STOPPED:
                mPlaySpeed = 0;
                break;
            case PlaybackState.STATE_PLAYING:
                mPlaySpeed = 1;
                break;
            case PlaybackState.STATE_PAUSED:
                mPlaySpeed = 0;
                break;
            case PlaybackState.STATE_FAST_FORWARDING:
                mPlaySpeed = 3;
                break;
            case PlaybackState.STATE_REWINDING:
                mPlaySpeed = -3;
                break;
        }

        mPlaybackState = new PlaybackState.Builder(mPlaybackState).setState(
                mPlayStatus, mPlayTime,
                mPlaySpeed).build();
    }

    public synchronized boolean notifyImageDownload(String uuid, Uri imageUri) {
        if (DBG) Log.d(TAG, "Got an image download -- uuid=" + uuid + ", uri=" + imageUri);
        if (uuid == null || imageUri == null || mCurrentTrack == null) return false;
        if (uuid.equals(mCurrentTrack.getCoverArtUuid())) {
            mCurrentTrack.setCoverArtLocation(imageUri);
            if (DBG) Log.d(TAG, "Image UUID '" + uuid + "' was added to current track.");
            return true;
        }
        return false;
    }

    public void setSupportedPlayerApplicationSettings(
            PlayerApplicationSettings playerApplicationSettings) {
        mPlayerAppSetting = playerApplicationSettings;
        updateAvailableActions();
    }

    public int getPlayStatus() {
        return mPlayStatus;
    }

    public boolean supportsFeature(int featureId) {
        int byteNumber = featureId / 8;
        byte bitMask = (byte) (1 << (featureId % 8));
        return (mPlayerFeatures[byteNumber] & bitMask) == bitMask;
    }

    public boolean isAddToNowPlayingSupported() {
        return supportsFeature(FEATURE_ADD_TO_NOWPLAYING);
    }

    public PlaybackState getPlaybackState() {
        if (DBG) {
            Log.d(TAG, "getPlayBackState state " + mPlayStatus + " time " + mPlayTime);
        }
        return mPlaybackState;
    }

    public synchronized void updateCurrentTrack(AvrcpItem update) {
        if (update != null) {
            long trackNumber = update.getTrackNumber();
            mPlaybackState = new PlaybackState.Builder(
                    mPlaybackState).setActiveQueueItemId(
                    trackNumber - 1).build();
        }
        mCurrentTrack = update;
    }

    public synchronized AvrcpItem getCurrentTrack() {
        return mCurrentTrack;
    }

    private void updateAvailableActions() {
        if (supportsFeature(FEATURE_PLAY)) {
            mAvailableActions = mAvailableActions | PlaybackState.ACTION_PLAY;
        }
        if (supportsFeature(FEATURE_STOP)) {
            mAvailableActions = mAvailableActions | PlaybackState.ACTION_STOP;
        }
        if (supportsFeature(FEATURE_PAUSE)) {
            mAvailableActions = mAvailableActions | PlaybackState.ACTION_PAUSE;
        }
        if (supportsFeature(FEATURE_REWIND)) {
            mAvailableActions = mAvailableActions | PlaybackState.ACTION_REWIND;
        }
        if (supportsFeature(FEATURE_FAST_FORWARD)) {
            mAvailableActions = mAvailableActions | PlaybackState.ACTION_FAST_FORWARD;
        }
        if (supportsFeature(FEATURE_FORWARD)) {
            mAvailableActions = mAvailableActions | PlaybackState.ACTION_SKIP_TO_NEXT;
        }
        if (supportsFeature(FEATURE_PREVIOUS)) {
            mAvailableActions = mAvailableActions | PlaybackState.ACTION_SKIP_TO_PREVIOUS;
        }
        if (DBG) Log.d(TAG, "Supported Actions = " + mAvailableActions);
        mPlaybackState = new PlaybackState.Builder(mPlaybackState)
                .setActions(mAvailableActions).build();
    }

    public synchronized void updateCurrentTrackInfo(TrackInfo update) {
        mCurrentTrackInfo = update;
    }

    public synchronized TrackInfo getCurrentTrackInfo() {
        return mCurrentTrackInfo;
    }

    /**
     * A Builder object for an AvrcpPlayer
     */
    public static class Builder {
        private static final String TAG = "AvrcpPlayer.Builder";
        private static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);

        private BluetoothDevice mDevice = null;
        private int mPlayerId = AvrcpPlayer.DEFAULT_ID;
        private int mPlayerType = AvrcpPlayer.TYPE_UNKNOWN;
        private int mPlayerSubType = AvrcpPlayer.SUB_TYPE_UNKNOWN;
        private String mPlayerName = null;
        private byte[] mSupportedFeatures = new byte[16];

        private int mPlayStatus = PlaybackState.STATE_NONE;
        private long mPlayTime = PlaybackState.PLAYBACK_POSITION_UNKNOWN;
        private float mPlaySpeed = 1;
        private long mPlayTimeUpdate = 0;

        private AvrcpItem mTrack = null;

        /**
         * Set the device that this Player came from
         *
         * @param device The BleutoothDevice representing the remote device
         * @return This object, so you can continue building
         */
        public Builder setDevice(BluetoothDevice device) {
            mDevice = device;
            return this;
        }

        /**
         * Set the Player ID for this Player
         *
         * @param playerId The ID for this player, defined in AVRCP 6.10.2.1
         * @return This object, so you can continue building
         */
        public Builder setPlayerId(int playerId) {
            mPlayerId = playerId;
            return this;
        }

        /**
         * Set the Player Type for this Player
         *
         * @param playerType The type for this player, defined in AVRCP 6.10.2.1
         * @return This object, so you can continue building
         */
        public Builder setPlayerType(int playerType) {
            mPlayerType = playerType;
            return this;
        }

        /**
         * Set the Player Sub-type for this Player
         *
         * @param playerSubType The sub-type for this player, defined in AVRCP 6.10.2.1
         * @return This object, so you can continue building
         */
        public Builder setPlayerSubType(int playerSubType) {
            mPlayerSubType = playerSubType;
            return this;
        }

        /**
         * Set the name for this Player. This is what users will see when browsing.
         *
         * @param name The name for this player, defined in AVRCP 6.10.2.1
         * @return This object, so you can continue building
         */
        public Builder setName(String name) {
            mPlayerName = name;
            return this;
        }

        /**
         * Set the entire set of supported features for this Player.
         *
         * @param features The feature set for this player, defined in AVRCP 6.10.2.1
         * @return This object, so you can continue building
         */
        public Builder setSupportedFeatures(byte[] supportedFeatures) {
            mSupportedFeatures = supportedFeatures;
            return this;
        }

        /**
         * Set a single features as supported for this Player.
         *
         * @param feature The feature for this player, defined in AVRCP 6.10.2.1
         * @return This object, so you can continue building
         */
        public Builder setSupportedFeature(int feature) {
            int byteNumber = feature / 8;
            byte bitMask = (byte) (1 << (feature % 8));
            mSupportedFeatures[byteNumber] = (byte) (mSupportedFeatures[byteNumber] | bitMask);
            return this;
        }

        /**
         * Set the initial play status of the Player.
         *
         * @param playStatus The play state for this player as a PlaybackState.STATE_* value
         * @return This object, so you can continue building
         */
        public Builder setPlayStatus(int playStatus) {
            mPlayStatus = playStatus;
            return this;
        }

        /**
         * Set the initial play status of the Player.
         *
         * @param track The initial track for this player
         * @return This object, so you can continue building
         */
        public Builder setCurrentTrack(AvrcpItem track) {
            mTrack = track;
            return this;
        }

        public AvrcpPlayer build() {
            AvrcpPlayer player = new AvrcpPlayer(mDevice, mPlayerId, mPlayerType, mPlayerSubType,
                    mPlayerName, mSupportedFeatures, mPlayStatus);
            player.updateCurrentTrack(mTrack);
            return player;
        }
    }
}
