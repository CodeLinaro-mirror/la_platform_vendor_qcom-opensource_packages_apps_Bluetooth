/*
 * Copyright (c) 2022 Qualcomm Innovation Center, Inc. All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.android.bluetooth.btservice;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothAdapterCommon;
import android.bluetooth.BluetoothAdapterExt;
import android.bluetooth.BluetoothAdapterUtil;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

public final class AdapterExt {
    private static final String TAG = "AdapterExt";
    private static final boolean DBG = true;

    public static final int ENABLE_TIMEOUT = 2000;
    public static final int DISABLE_TIMEOUT = 2000;

    private static final int ADAPTER_1 = BluetoothAdapterCommon.ADAPTER_1;

    private static Context sContext;

    private static int sNewAdapterState = BluetoothAdapter.STATE_OFF;

    private static final BroadcastReceiver sReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothAdapterExt.ACTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                        BluetoothAdapter.ERROR);
                int prevState = intent.getIntExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE,
                        BluetoothAdapter.ERROR);
                handleActionStateChanged(state, prevState);

                // Bluetooth adapter is in BluetoothAdpater.STATE_BLE_TURNING_OFF actually
                // when state retrieved from intent is BluetoothAdapter.STATE_OFF.
                // Hence don't update sNewAdapterState in this case.
                if (state != BluetoothAdapter.STATE_OFF) {
                    sNewAdapterState = state;
                }
            } else if (BluetoothAdapterExt.ACTION_BLE_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                        BluetoothAdapter.ERROR);

                sNewAdapterState = state;
            }
        }
    };

    public static void create(Context context) {
        sContext = context;
        init();
    }

    private static void init() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapterExt.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothAdapterExt.ACTION_BLE_STATE_CHANGED);
        sContext.registerReceiver(sReceiver, filter);
    }

    private static void handleActionStateChanged(int state, int prevState) {
        debugLog("handleActionStateChanged state: " + state + "(" +
                BluetoothAdapter.nameForState(state) + ")" + ", prevState: " +
                prevState + "(" + BluetoothAdapter.nameForState(prevState) + ")");
        AdapterService adapterService = AdapterService.getAdapterService();
        if (adapterService != null) {
            if (isOn(state)) {
                adapterService.notifyNewAdapterState(true);
            } else if (isOff(state)) {
                adapterService.notifyNewAdapterState(false);
            }
        }
    }

    private static BluetoothAdapter getAdapter() {
        return BluetoothAdapterUtil.getAdapter(ADAPTER_1);
    }

    public static boolean enable() {
        BluetoothAdapter adapter = getAdapter();
        return (adapter != null) ? adapter.enable() : false;
    }

    public static boolean disable() {
        BluetoothAdapter adapter = getAdapter();
        return (adapter != null) ? adapter.disable() : false;
    }

    public static int getState() {
        return sNewAdapterState;
    }

    public static boolean isOn(int state) {
        return state == BluetoothAdapter.STATE_ON;
    }

    public static boolean isOff(int state) {
        return state == BluetoothAdapter.STATE_OFF;
    }

    private static void debugLog(String msg) {
        if (DBG) {
            Log.d(TAG, msg);
        }
    }
}
