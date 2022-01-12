/*
 * Copyright (c) 2018, The Linux Foundation. All rights reserved.
 * Not a contribution
 *
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

package com.android.bluetooth.pbapclient;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.SdpPseRecord;
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
import com.android.bluetooth.BluetoothObexTransport;
import com.android.bluetooth.R;

import java.io.IOException;

import javax.obex.ClientSession;
import javax.obex.HeaderSet;
import javax.obex.ResponseCodes;

/* Bluetooth/pbapclient/PbapClientHandler is responsible
 * for connecting, disconnecting and downloading contacts from the
 * PBAP PSE when commanded. It receives all direction from the
 * controlling state machine.
 */
class PbapClientHandler extends Handler {
    static final String TAG = "PbapClientHandler";
    static final boolean DBG = true;
    static final int MSG_CUSTOM_ACTION = 1;

    public static final String PBAP_CLIENT_ENABLE_PTS_PROPERTY =
        "bt.pbapclient.enable_pts";

    // +++ Custom action definition for PBAP client

    /**
     * Broadcast Action: Indicates custom action(request) from application.
     * This is mainly for PTS.
     *
     * <p>Always contains the extra field {@link #EXTRA_CUSTOM_ACTION}.
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} to receive.
     */
    public static final String ACTION_CUSTOM_ACTION =
        "android.bluetooth.pbapclient.action.CUSTOM_ACTION";

    public static final String EXTRA_CUSTOM_ACTION =
        "android.bluetooth.pbapclient.extra.CUSTOM_ACTION";

    public static final String KEY_COMMAND = "command";

    /**
     * Custom action to pull phonebook
     *
     * @param Bundle wrapped with
     *  {@link #KEY_COMMAND}
     *  {@link #BluetoothDevice.EXTRA_DEVICE}
     *  {@link #KEY_PB_NAME}
     *  {@link #KEY_FILTER}
     *  {@link #KEY_VCARD_TYPE}
     *  {@link #KEY_MAX_LIST_COUNT}
     *  {@link #KEY_LIST_START_OFFSET}
     */
    public static final String CUSTOM_ACTION_PULL_PHONEBOOK =
        "android.bluetooth.pbapclient.CUSTOM_ACTION_PULL_PHONEBOOK";
    public static final String KEY_PB_NAME = "pb_name";
    public static final String KEY_FILTER = "filter";
    public static final String KEY_VCARD_TYPE = "vcard_type";
    public static final String KEY_MAX_LIST_COUNT = "max_list_count";
    public static final String KEY_LIST_START_OFFSET = "list_start_offset";

    /**
     * Custom action to pull vCard listing
     *
     * @param Bundle wrapped with
     *  {@link #KEY_COMMAND}
     *  {@link #BluetoothDevice.EXTRA_DEVICE}
     *  {@link #KEY_PB_NAME}
     *  {@link #KEY_ORDER}
     *  {@link #KEY_SEARCH_PROP}
     *  {@link #KEY_SEARCH_VALUE}
     *  {@link #KEY_MAX_LIST_COUNT}
     *  {@link #KEY_LIST_START_OFFSET}
     */
    public static final String CUSTOM_ACTION_PULL_VCARD_LISTING =
        "android.bluetooth.pbapclient.CUSTOM_ACTION_PULL_VCARD_LISTING";
    public static final String KEY_ORDER = "order";
    public static final String KEY_SEARCH_PROP = "search_property";
    public static final String KEY_SEARCH_VALUE = "search_value";

    /**
     * Custom action to pull vCard entry
     *
     * @param Bundle wrapped with
     *  {@link #KEY_COMMAND}
     *  {@link #BluetoothDevice.EXTRA_DEVICE}
     *  {@link #KEY_VCARD_HANDLE}
     *  {@link #KEY_FILTER}
     *  {@link #KEY_VCARD_TYPE}
     */
    public static final String CUSTOM_ACTION_PULL_VCARD_ENTRY =
        "android.bluetooth.pbapclient.CUSTOM_ACTION_PULL_VCARD_ENTRY";
    public static final String KEY_VCARD_HANDLE = "vcard_handle";

    /**
     * Custom action to set phonebook folder path
     *
     * @param Bundle wrapped with
     *  {@link #KEY_COMMAND}
     *  {@link #BluetoothDevice.EXTRA_DEVICE}
     *  {@link #KEY_PB_NAME}
     */
    public static final String CUSTOM_ACTION_SET_PHONEBOOK =
        "android.bluetooth.pbapclient.CUSTOM_ACTION_SET_PHONEBOOK";

    /**
     * Custom action to abort
     *
     * @param Bundle wrapped with
     *  {@link #KEY_COMMAND}
     *  {@link #BluetoothDevice.EXTRA_DEVICE}
     */
    public static final String CUSTOM_ACTION_ABORT =
        "android.bluetooth.pbapclient.CUSTOM_ACTION_ABORT";

    // + Response for custom action

    /**
     * Intent used to broadcast PBAP client custom action result
     *
     * <p>This intent will have 2 extras at least:
     * <ul>
     *   <li> {@link #EXTRA_CUSTOM_ACTION_RESULT} - custom action result. </li>
     *
     * </ul>
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission to
     * receive.
     */
    public static final String ACTION_CUSTOM_ACTION_RESULT =
        "android.bluetooth.pbapclient.action.CUSTOM_ACTION_RESULT";

    public static final String EXTRA_CUSTOM_ACTION_RESULT =
        "android.bluetooth.pbapclient.extra.CUSTOM_ACTION_RESULT";

    public static final String KEY_RESULT = "result";

    public static final String KEY_PHONEBOOK_SIZE = "phonebook_size";
    public static final String KEY_NEW_MISSED_CALLS = "new_missed_calls";

    public static final String KEY_VCARD_LISTING = "vcard_listing";

    public static final String KEY_VCARD_ENTRY = "vcard_entry";

    // Result code
    public static final int RESULT_SUCCESS = 0;
    public static final int RESULT_ERROR = 1;
    public static final int RESULT_INVALID_PARAMETER = 2;
    public static final int RESULT_NOT_SUPPORTED = 3;
    public static final int RESULT_TIMEOUT = 4;
    public static final int RESULT_BUSY = 5;
    public static final int RESULT_NOT_FOUND = 6;

    // - Response for custom action

    // --- Custom action definition for PBAP client

    private PbapClientService mService;

    PbapClientHandler(Context context) {
        mService = (PbapClientService) context;
    }

    /**
     * Constructs PbapClientHandler object
     *
     * @param Builder To build  PbapClientHandler Instance.
     */
    PbapClientHandler(Builder pceHandlerbuild) {
        mService = (PbapClientService) pceHandlerbuild.context;
    }

    public static class Builder {

        private Looper looper;
        private Context context;
        private PbapClientStateMachine clientStateMachine;

        public Builder setLooper(Looper loop) {
            this.looper = loop;
            return this;
        }

        public Builder setClientSM(PbapClientStateMachine clientStateMachine) {
            this.clientStateMachine = clientStateMachine;
            return this;
        }

        public Builder setContext(Context context) {
            this.context = context;
            return this;
        }

        public PbapClientHandler build() {
            PbapClientHandler pbapClientHandler = new PbapClientHandler(this);
            return pbapClientHandler;
        }

    }

    public static boolean isPtsEnabled() {
        return SystemProperties.getBoolean(PBAP_CLIENT_ENABLE_PTS_PROPERTY, false);
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
        if (CUSTOM_ACTION_PULL_PHONEBOOK.equals(cmd)) {
            handlePullPhonebook(device, extras);
        } else if (CUSTOM_ACTION_PULL_VCARD_LISTING.equals(cmd)) {
            handlePullVcardListing(device, extras);
        } else if (CUSTOM_ACTION_PULL_VCARD_ENTRY.equals(cmd)) {
            handlePullVcardEntry(device, extras);
        } else if (CUSTOM_ACTION_SET_PHONEBOOK.equals(cmd)) {
            handleSetPhonebook(device, extras);
        } else if (CUSTOM_ACTION_ABORT.equals(cmd)) {
            handleAbort(device, extras);
        } else {
            Log.w(TAG, "Custom action " + cmd + " not supported.");
        }
    }

    private void handlePullPhonebook(BluetoothDevice device, Bundle extras) {
        if (DBG) Log.d(TAG, "handlePullPhonebook");
        mService.pullPhonebook(device, extras);
    }

    private void handlePullVcardListing(BluetoothDevice device, Bundle extras) {
        if (DBG) Log.d(TAG, "handlePullVcardListing");
        mService.pullVcardListing(device, extras);
    }

    private void handlePullVcardEntry(BluetoothDevice device, Bundle extras) {
        if (DBG) Log.d(TAG, "handlePullVcardEntry");
        mService.pullVcardEntry(device, extras);
    }

    private void handleSetPhonebook(BluetoothDevice device, Bundle extras) {
        if (DBG) Log.d(TAG, "handleSetPhonebook");
        mService.setPhonebook(device, extras);
    }

    private void handleAbort(BluetoothDevice device, Bundle extras) {
        if (DBG) Log.d(TAG, "handleAbort");
        mService.abort(device);
    }

    public void notifyCustomActionResult(Bundle extras) {
        if (DBG) Log.d(TAG, "notifyCustomActionResult");
        Intent intent = new Intent(ACTION_CUSTOM_ACTION_RESULT);
        intent.putExtra(EXTRA_CUSTOM_ACTION_RESULT, extras);
        mService.sendBroadcast(intent, ProfileService.BLUETOOTH_PERM);
    }

    public static int Map2CustomActionResult(int responseCode) {
        switch (responseCode) {
            case ResponseCodes.OBEX_HTTP_OK:
                return RESULT_SUCCESS;

            case ResponseCodes.OBEX_HTTP_NOT_IMPLEMENTED:
                return RESULT_NOT_SUPPORTED;

            case ResponseCodes.OBEX_HTTP_NOT_FOUND:
                return RESULT_NOT_FOUND;

            default:
                return RESULT_ERROR;
        }
    }
}
