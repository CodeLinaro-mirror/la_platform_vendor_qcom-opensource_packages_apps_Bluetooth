/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.bluetooth.gatt;

import android.bluetooth.IBluetoothGatt;
import android.bluetooth.IBluetoothGattExt;

import com.android.bluetooth.btservice.ProfileService;

/**
 * Provides Bluetooth Gatt profile, as a service in
 * new Bluetooth application.
 * @hide
 */
public class GattExtService extends GattService {
    private static final boolean DBG = GattServiceConfig.DBG;
    private static final boolean VDBG = GattServiceConfig.VDBG;
    private static final String TAG = GattServiceConfig.TAG_PREFIX + "GattExtService";

    @Override
    protected IProfileServiceBinder initBinder() {
        return new BluetoothGattExtBinder(this);
    }

    public IBluetoothGatt getBluetoothGatt() {
        return IBluetoothGatt.Stub.asInterface(super.initBinder());
    }

    /**
     * Handlers for incoming service calls
     */
    private static class BluetoothGattExtBinder extends IBluetoothGattExt.Stub
            implements IProfileServiceBinder {
        private GattExtService mService;

        BluetoothGattExtBinder(GattExtService svc) {
            mService = svc;
        }

        @Override
        public void cleanup() {
            mService = null;
        }

        @Override
        public synchronized IBluetoothGatt getBluetoothGatt() {
            return mService.getBluetoothGatt();
        }
    }
}
