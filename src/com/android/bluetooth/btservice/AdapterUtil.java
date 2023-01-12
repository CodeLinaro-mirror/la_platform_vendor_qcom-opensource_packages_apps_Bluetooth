/*
 * Copyright (c) 2022 Qualcomm Innovation Center, Inc. All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.android.bluetooth.btservice;

import android.annotation.NonNull;
import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothAdapterCommon;
import android.bluetooth.BluetoothAdapterExt;
import android.bluetooth.BluetoothAdapterUtil;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.os.SystemProperties;
import android.provider.Settings;

import com.android.bluetooth.gatt.GattService;
import com.android.bluetooth.gatt.GattExtService;
import com.android.bluetooth.R;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Bluetooth adapter utility
 */
public final class AdapterUtil {
    private static final String TAG = "AdapterUtil";

    private static final int ADAPTER_DEFAULT = BluetoothAdapterCommon.ADAPTER_DEFAULT;
    private static final int ADAPTER_1 = BluetoothAdapterCommon.ADAPTER_1;
    private static final int ADAPTER_NUMBER = BluetoothAdapterCommon.ADAPTER_NUMBER;

    private static Context sContext = null;
    private static boolean sDualBluetooth = false;
    private static int sAdapterIndex = ADAPTER_DEFAULT;
    private static BluetoothAdapter sAdapter = null;
    private static boolean sDualAdapterMode = false;
    private static boolean sFilterDevice = false;
    private static String sCounterpartAddress = null;
    private static HashMap<Integer, ArrayList<Integer>> sProfiles;
    private static boolean sDualLoopbackTest = false;

    public static void init(@NonNull Context context) {
        sContext = context;
        sDualBluetooth = SystemProperties.getBoolean("ro.vehicle.dual_bt", false);
        sAdapterIndex = Application.getProcessName().equals(sContext.getPackageName()) ?
               ADAPTER_DEFAULT : ADAPTER_1;
        sAdapter = getAdapter(sAdapterIndex);
        sDualAdapterMode = SystemProperties.getBoolean("persist.vendor.service.bt.dual_adapter_mode", false);
        sFilterDevice = getFilterDeviceConfig();
        if (isDualAdapterMode() && isAdapterDefault()) {
            // In dual adapter mode, default adapter needs to monitor
            // new adapter's state.
            AdapterExt.create(sContext);
        }

        // Loopback test in dual Bluetooth
        sDualLoopbackTest = SystemProperties.getBoolean("persist.vendor.service.bt.dual_loopback_test", false);

        // Init profile supported in Bluetooth adapter
        sProfiles = new HashMap<Integer, ArrayList<Integer>>(ADAPTER_NUMBER);
        sProfiles.put(ADAPTER_DEFAULT, new ArrayList<Integer>(Arrays.asList(
                BluetoothProfile.A2DP_SINK,
                BluetoothProfile.AVRCP_CONTROLLER,
                BluetoothProfile.GATT,
                BluetoothProfile.GATT_SERVER,
                BluetoothProfile.HEADSET_CLIENT,
                BluetoothProfile.MAP_CLIENT,
                BluetoothProfile.OPP,
                BluetoothProfile.PBAP_CLIENT)));
        sProfiles.put(ADAPTER_1, new ArrayList<Integer>(Arrays.asList(
                BluetoothProfile.A2DP,
                BluetoothProfile.AVRCP,
                BluetoothProfile.GATT,
                BluetoothProfile.GATT_SERVER,
                BluetoothProfile.HEADSET,
                BluetoothProfile.HID_HOST,
                BluetoothProfile.OPP,
                BluetoothProfile.PBAP)));
    }

    private static boolean getFilterDeviceConfig() {
        return sDualBluetooth &&
                sContext.getResources().getBoolean(R.bool.filter_device);
    }

    public static boolean isDualBluetoothEnabled() {
        return sDualBluetooth;
    }

    public static int getAdapterIndex() {
        return sAdapterIndex;
    }

    public static boolean isAdapterDefault() {
        return isAdapterDefault(getAdapterIndex());
    }

    private static boolean isAdapterDefault(int adapterIndex) {
        return BluetoothAdapterCommon.isAdapterDefault(adapterIndex);
    }

    public static boolean isAdapter1() {
        return isAdapter1(getAdapterIndex());
    }

    private static boolean isAdapter1(int adapterIndex) {
        return BluetoothAdapterCommon.isAdapter1(adapterIndex);
    }

    public static BluetoothAdapter getAdapter() {
        return sAdapter;
    }

    public static Intent newIntent(String action, String newAction) {
        return new Intent(isAdapter1() ? newAction : action);
    }

    public static boolean isProfileSupported(int profileId) {
        return sProfiles.get(sAdapterIndex).contains(profileId);
    }

    public static boolean isProfileSupported(long supportedProfiles, int profileId) {
        return (supportedProfiles & (1 << profileId)) != 0;
    }

    public static Class getGattServiceClass() {
        return isAdapter1() ? GattExtService.class : GattService.class;
    }

    public static boolean isDualAdapterMode() {
        return sDualBluetooth && sDualAdapterMode;
    }

    public static boolean isDualLoopbackTestEnabled() {
        return isDualAdapterMode() && sDualLoopbackTest;
    }

    public static boolean filterDevice(BluetoothDevice device) {
        return sFilterDevice ? isCounterpartDevice(device) : false;
    }

    public static boolean isCounterpartDevice(BluetoothDevice device) {
        if (sCounterpartAddress == null) {
            sCounterpartAddress = getCounterpartAddress();
        }
        return sCounterpartAddress != null ?
                device.getAddress().equals(sCounterpartAddress) :
                false;
    }

    public static boolean allowPbapAccessPermission() {
        return sDualBluetooth &&
                sContext.getResources().getBoolean(R.bool.allow_pbap_access_permission);
    }

    private static String getCounterpartAddress() {
        return isAdapter1() ? getAddress(ADAPTER_DEFAULT) : getAddress(ADAPTER_1);
    }

    private static String getAddress(int adapterIndex) {
        BluetoothAdapter adapter = getAdapter(adapterIndex);
        return adapter != null ? adapter.getAddress() : null;
    }

    private static BluetoothAdapter getAdapter(int adapterIndex) {
        return BluetoothAdapterUtil.getAdapter(adapterIndex);
    }

    public static boolean allowConcurrentA2dpHfAudio() {
        return sContext.getResources().getBoolean(R.bool.concurrent_a2dp_hf_audio);
    }
}
