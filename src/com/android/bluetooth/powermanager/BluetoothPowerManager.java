/*
 * Copyright (c) 2020, The Linux Foundation. All rights reserved.
 * Not a contribution.

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
 * limitations under the License
 */
package com.android.bluetooth.powermanager;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.bluetooth.BluetoothAdapter;
import android.car.Car;
import android.car.hardware.power.CarPowerManager;
import android.car.hardware.power.CarPowerManager.CarPowerStateListener;
import android.content.Context;
import android.hardware.bluetooth.V1_0.IBluetoothHci;
import android.os.RemoteException;
import android.util.Log;

import java.util.NoSuchElementException;


/**
 * BluetoothPowerManager for Bluetooth power management in Automotive SP,
 * which is based on CPM(CarPowerManager).
 *
 * @hide
 */
public class BluetoothPowerManager implements CarPowerStateListener {
    private static final String TAG = "BluetoothPowerManager";
    private static final boolean DBG = true;

    private static BluetoothPowerManager mBluetoothPowerManager = null;
    private final BluetoothAdapter mBluetoothAdapter;
    private Car mCar;
    private CarPowerManager mCarPowerManager;

    public BluetoothPowerManager(Context context) {
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    public static BluetoothPowerManager createInstance(Context context) {
        if (mBluetoothPowerManager == null) {
            mBluetoothPowerManager = new BluetoothPowerManager(context);
            mBluetoothPowerManager.init(context);
        }
        return mBluetoothPowerManager;
    }

    public void init(Context context) {
        logd("init");
        initCar(context);
    }

    public void deinit() {
        logd("deinit");
        deinitCar();
    }

    private void initCar(Context context) {
        logd("initCar");
        deinitCar();

        mCar = Car.createCar(context, null, Car.CAR_WAIT_TIMEOUT_WAIT_FOREVER,
                (Car car, boolean ready) -> {
                    if (ready) {
                        initCarPowerManager(car);
                    }
                });

        logd("initCar done, mCar: " + mCar);
    }

    private void initCarPowerManager(Car car) {
        logd("initCarPowerManager");
        mCarPowerManager = (CarPowerManager) car.getCarManager(
                android.car.Car.POWER_SERVICE);

        if (mCarPowerManager != null) {
            try {
                mCarPowerManager.setListener(this);
            } catch (IllegalStateException e) {
                loge("Can't set CarPowerManager listener");
            }
        } else {
            loge("Fail to get CarPowerManager");
        }

        logd("initCarPowerManager done, mCarPowerManager: " + mCarPowerManager);
    }

    private void deinitCarPowerManager() {
        logd("deinitCarPowerManager, mCarPowerManager: " + mCarPowerManager);
        if (mCarPowerManager != null) {
            mCarPowerManager.clearListener();
            mCarPowerManager = null;
        }
    }

    private void deinitCar() {
        deinitCarPowerManager();

        if (mCar != null) {
            if (mCar.isConnected()) {
                mCar.disconnect();
            }
            mCar = null;
        }
    }

    private int getBluetoothState() {
        if (mBluetoothAdapter != null) {
            return mBluetoothAdapter.getState();
        } else {
            return BluetoothAdapter.ERROR;
        }
    }

    @Override
    public void onStateChanged(int state) {
        logd("onStateChanged state: " + state);
        switch (state) {
            case CarPowerStateListener.SHUTDOWN_CANCELLED:
                logd("CPM state: SHUTDOWN_CANCELLED");
                break;
            case CarPowerStateListener.SHUTDOWN_ENTER:
                logd("CPM state: SHUTDOWN_ENTER");
                break;
            case CarPowerStateListener.SHUTDOWN_PREPARE:
                logd("CPM state: SHUTDOWN_PREPARE");
                break;
            case CarPowerStateListener.SUSPEND_ENTER:
                logd("CPM state: SUSPEND_ENTER");
                handleSuspendEnter();
                break;
            case CarPowerStateListener.SUSPEND_EXIT:
                logd("CPM state: SUSPEND_EXIT");
                break;
            default:
                break;
        }
    }

    private void handleSuspendEnter() {
        int state = getBluetoothState();
        logd("handleSuspendEnter, bluetooth state: " + state +
                "(" + mapBluetoothState2String(state) + ")");
        closeHci();
    }

    private void closeHci() {
        final IBluetoothHci bluetoothHci = getBluetoothHci();
        if (bluetoothHci == null) {
            loge("IBluetoothHci null");
            return;
        }

        try {
            bluetoothHci.close();
            logd("Bluetooth hci closed");
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to close Bluetooth hci", e);
        }
    }

    @Nullable
    private IBluetoothHci getBluetoothHci() {
        try {
            return IBluetoothHci.getService("default");
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to get IBluetoothHci service", e);
        } catch (NoSuchElementException e) {
            Log.e(TAG, "IBluetoothHci service not registered yet", e);
        }
        return null;
    }

    private String mapBluetoothState2String(int state) {
        switch (state) {
            case BluetoothAdapter.STATE_OFF:
                return "STATE_OFF";
            case BluetoothAdapter.STATE_TURNING_ON:
                return "STATE_TURNING_ON";
            case BluetoothAdapter.STATE_ON:
                return "STATE_ON";
            case BluetoothAdapter.STATE_TURNING_OFF:
                return "STATE_TURNING_OFF";
            case BluetoothAdapter.STATE_BLE_TURNING_ON:
                return "STATE_BLE_TURNING_ON";
            case BluetoothAdapter.STATE_BLE_ON:
                return "STATE_BLE_ON";
            case BluetoothAdapter.STATE_BLE_TURNING_OFF:
                return "STATE_BLE_TURNING_OFF";
            default:
                return "Unknown Bluetooth state";
        }
    }

    private void logd(String msg) {
        if (DBG) {
            Log.d(TAG, msg);
        }
    }

    private void logw(String msg) {
        Log.w(TAG, msg);
    }

    private void loge(String msg) {
        Log.e(TAG, msg);
    }
}
