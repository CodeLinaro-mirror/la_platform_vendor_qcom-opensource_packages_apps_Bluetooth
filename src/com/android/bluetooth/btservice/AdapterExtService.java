/*
 * Copyright (C) 2012 The Android Open Source Project
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
 * Changes from Qualcomm Innovation Center are provided under the following license:
 *
 * Copyright (c) 2022 Qualcomm Innovation Center, Inc. All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.android.bluetooth.btservice;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.IBluetooth;
import android.bluetooth.IBluetoothExt;
import android.content.Intent;
import android.util.Log;
import android.os.IBinder;

public class AdapterExtService extends AdapterService {
    private static final String TAG = "BluetoothAdapterExtService";
    private static final boolean DBG = true;
    private static final boolean VERBOSE = false;

    @Override
    public void onCreate() {
        super.onCreate();
        debugLog("onCreate()");
        mExtBinder = new AdapterExtServiceBinder(this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        debugLog("onBind()");
        return mExtBinder;
    }

    @Override
    void cleanup() {
        super.cleanup();
        debugLog("cleanup()");
        if (mExtBinder != null) {
            mExtBinder.cleanup();
            mExtBinder = null;
        }
    }

    public IBluetooth getBluetooth() {
        return IBluetooth.Stub.asInterface(mBinder);
    }

    private AdapterExtServiceBinder mExtBinder;

    public static class AdapterExtServiceBinder extends IBluetoothExt.Stub {
        private AdapterExtService mService;
        private BluetoothAdapter mAdapter;

        AdapterExtServiceBinder(AdapterExtService svc) {
            mService = svc;
            mService.invalidateBluetoothGetStateCache();
            mAdapter = AdapterUtil.getAdapter();
            mAdapter.disableBluetoothGetStateCache();
        }

        public void cleanup() {
            mService = null;
        }

        // New API to get Bluetooth interface in new Bluetooth adapter
        @Override
        public synchronized IBluetooth getBluetooth() {
            return mService.getBluetooth();
        }
    }

    private void debugLog(String msg) {
        if (DBG) {
            Log.d(TAG, msg);
        }
    }
}