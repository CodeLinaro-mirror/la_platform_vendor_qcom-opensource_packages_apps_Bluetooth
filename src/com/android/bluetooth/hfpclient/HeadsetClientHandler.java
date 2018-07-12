/*
 Copyright (c) 2018, The Linux Foundation. All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:
    * Redistributions of source code must retain the above copyright
      notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above
      copyright notice, this list of conditions and the following
      disclaimer in the documentation and/or other materials provided
      with the distribution.
    * Neither the name of The Linux Foundation nor the names of its
      contributors may be used to endorse or promote products derived
      from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.android.bluetooth.hfpclient;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadsetClient;
import android.bluetooth.BluetoothHeadsetClientCall;
import android.bluetooth.BluetoothUuid;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemProperties;
import android.provider.CallLog;
import android.util.Log;

import com.android.bluetooth.btservice.ProfileService;
import com.android.bluetooth.R;

import java.io.IOException;


class HeadsetClientHandler extends Handler {
    static final String TAG = "HeadsetClientHandler";
    static final boolean DBG = true;
    static final int MSG_CUSTOM_ACTION = 1;

    public static final String HEADSET_CLIENT_ENABLE_PTS_PROPERTY =
        "persist.bt.headsetclient.enable_pts";

    // +++ Custom action definition for headset client

    /**
     * Broadcast Action: Indicates custom action(request) from application.
     *
     * <p>Always contains the extra field {@link #EXTRA_CUSTOM_ACTION}.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} to receive.
     */
    public static final String ACTION_CUSTOM_ACTION =
        "android.bluetooth.headsetclient.action.CUSTOM_ACTION";

    public static final String EXTRA_CUSTOM_ACTION =
        "android.bluetooth.headsetclient.extra.CUSTOM_ACTION";

    public static final String KEY_COMMAND = "command";

    /**
     * Custom action to release specific active call
     *
     * @param Bundle wrapped with
     *  {@link #KEY_COMMAND}
     *  {@link #BluetoothDevice.EXTRA_DEVICE}
     *  {@link #KEY_CALL_INDEX}
     */
    public static final String CUSTOM_ACTION_RELEASE_CALL =
        "android.bluetooth.headsetclient.CUSTOM_ACTION_RELEASE_CALL";
    public static final String KEY_CALL_INDEX = "index";

    // + Response for custom action

    /**
     * Intent used to broadcast headset client custom action result
     *
     * <p>This intent will have 1 extras at least:
     * <ul>
     *   <li> {@link #EXTRA_CUSTOM_ACTION_RESULT} - custom action result. </li>
     *
     * </ul>
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission to
     * receive.
     */
    public static final String ACTION_CUSTOM_ACTION_RESULT =
        "android.bluetooth.headsetclient.action.CUSTOM_ACTION_RESULT";

    public static final String EXTRA_CUSTOM_ACTION_RESULT =
        "android.bluetooth.headsetclient.extra.CUSTOM_ACTION_RESULT";

    public static final String KEY_RESULT = "result";

    // - Response for custom action

    // --- Custom action definition for headset client

    private HeadsetClientService mService;

    HeadsetClientHandler(Context context) {
        mService = (HeadsetClientService) context;
    }

    /**
     * Constructs HeadsetClientHandler object
     *
     * @param Builder To build  HeadsetClientHandler Instance.
     */
    HeadsetClientHandler(Builder pceHandlerbuild) {
        mService = (HeadsetClientService) pceHandlerbuild.context;
    }

    public static class Builder {

        private Looper looper;
        private Context context;
        private HeadsetClientStateMachine clientStateMachine;

        public Builder setLooper(Looper loop) {
            this.looper = loop;
            return this;
        }

        public Builder setClientSM(HeadsetClientStateMachine clientStateMachine) {
            this.clientStateMachine = clientStateMachine;
            return this;
        }

        public Builder setContext(Context context) {
            this.context = context;
            return this;
        }

        public HeadsetClientHandler build() {
            HeadsetClientHandler headsetClientHandler = new HeadsetClientHandler(this);
            return headsetClientHandler;
        }

    }

    public static boolean isPtsEnabled() {
        return SystemProperties.getBoolean(HEADSET_CLIENT_ENABLE_PTS_PROPERTY, false);
    }

    @Override
    public void handleMessage(Message msg) {
        if (DBG) Log.d(TAG, "Handling Message = " + msg.what);
        switch (msg.what) {
            case MSG_CUSTOM_ACTION:
                handleCustomAction((Bundle) msg.obj);
                break;

            default:
                Log.w(TAG, "Received Unexpected Message");
                break;
        }
        return;
    }

    private void handleCustomAction(Bundle extras) {
        if (DBG) Log.d(TAG, "handleCustomAction extras=" + extras);
        if (extras == null) {
            return;
        }

        String cmd = extras.getString(KEY_COMMAND);
        BluetoothDevice device = (BluetoothDevice) extras.get(BluetoothDevice.EXTRA_DEVICE);
        if (CUSTOM_ACTION_RELEASE_CALL.equals(cmd)) {
            handleReleaseCall(device, extras);
        } else {
            Log.w(TAG, "Custom action " + cmd + " not supported.");
        }
    }

    private void handleReleaseCall(BluetoothDevice device, Bundle extras) {
        if (DBG) Log.d(TAG, "handleReleaseCall");
        int index = extras.getInt(KEY_CALL_INDEX);
        boolean result = mService.releaseCall(device, index);

        notifyCustomActionResult(CUSTOM_ACTION_RELEASE_CALL,
            result ? BluetoothHeadsetClient.ACTION_RESULT_OK :
            BluetoothHeadsetClient.ACTION_RESULT_ERROR);
    }

    public void notifyCustomActionResult(String cmd, int result) {
        if (DBG) Log.d(TAG, "notifyCustomActionResult cmd: " + cmd + ", result: " + result);
        Bundle extras = new Bundle();
        extras.putString(KEY_COMMAND, cmd);
        extras.putInt(KEY_RESULT, result);
        notifyCustomActionResult(extras);
    }

    public void notifyCustomActionResult(Bundle extras) {
        if (DBG) Log.d(TAG, "notifyCustomActionResult");
        Intent intent = new Intent(ACTION_CUSTOM_ACTION_RESULT);
        intent.putExtra(EXTRA_CUSTOM_ACTION_RESULT, extras);
        mService.sendBroadcast(intent, ProfileService.BLUETOOTH_PERM);
    }

    // Get memory dial location
    public static int getLocation(BluetoothHeadsetClientCall call) {
        int location = 0;
        if (call != null) {
            String number = call.getNumber();
            if ((number != null) && !number.isEmpty()) {
                location = Integer.parseInt(number, 10);
            }
        }
        return location;
    }
}
