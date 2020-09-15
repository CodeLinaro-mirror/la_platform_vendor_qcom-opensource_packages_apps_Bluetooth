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

import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.util.Log;

import java.util.Arrays;

/*
 * Contains information about remote player
 */
class AvrcpPlayer {
    private static final String TAG = "AvrcpPlayer";
    private static final boolean DBG = true;

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

    private int mPlayStatus = PlaybackState.STATE_NONE;
    private long mPlayTime = PlaybackState.PLAYBACK_POSITION_UNKNOWN;
    //TODO: RAMESH
    //private int mPlayStatus = PlaybackStateCompat.STATE_NONE;
    //private long mPlayTime = PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN;

    private long mPlayTimeUpdate = 0;
    private float mPlaySpeed = 1;
    private int mId;
    private String mName = "";
    private int mPlayerType;
    private byte[] mPlayerFeatures = new byte[FEATURE_BIT_MASK_SIZE];
    private long mAvailableActions;
    private MediaMetadata mCurrentTrack;
    private PlaybackState mPlaybackState;

    private TrackInfo mCurrentTrackInfo = new TrackInfo();

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

    AvrcpPlayer(int id, String name, byte[] playerFeatures, int playStatus, int playerType) {
        mId = id;
        mName = name;
        mPlayStatus = playStatus;
        mPlayerType = playerType;
        mPlayerFeatures = Arrays.copyOf(playerFeatures, playerFeatures.length);
        updateAvailableActions();
        PlaybackState.Builder playbackStateBuilder = new PlaybackState.Builder()
                .setActions(mAvailableActions);
        mPlaybackState = playbackStateBuilder.build();
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
    }

    public byte[] getPlayerFeatures() {
        return mPlayerFeatures;
    }

    public boolean isSearchingSupported() {
        return isFeatureSupported(SEARCHING_OCTECT_VALUE, SEARCHING_BIT_VALUE);
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
        mPlaybackState = new PlaybackState.Builder(mPlaybackState).setState(mPlayStatus, mPlayTime,
                mPlaySpeed).build();
    }

    public long getPlayTime() {
        return mPlayTime;
    }

    public void setPlayStatus(int playStatus) {
        mPlayTime += mPlaySpeed * (SystemClock.elapsedRealtime()
                - mPlaybackState.getLastPositionUpdateTime());
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

        mPlaybackState = new PlaybackState.Builder(mPlaybackState).setState(mPlayStatus, mPlayTime,
                mPlaySpeed).build();
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

    public synchronized void updateCurrentTrack(MediaMetadata update) {
        if (update != null) {
            long trackNumber = update.getLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER);
            mPlaybackState = new PlaybackState.Builder(mPlaybackState).setActiveQueueItemId(
                    trackNumber - 1).build();
        }
        mCurrentTrack = update;
    }

    public synchronized MediaMetadata getCurrentTrack() {
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
    }

    public synchronized void updateCurrentTrackInfo(TrackInfo update) {
        mCurrentTrackInfo = update;
    }

    public synchronized TrackInfo getCurrentTrackInfo() {
        return mCurrentTrackInfo;
    }
}
