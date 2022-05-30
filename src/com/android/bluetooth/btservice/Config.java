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
 * SPDX-License-Identifier: BSD-3-Clause-Clear.
 */

package com.android.bluetooth.btservice;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothAdapterCommon;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.IBluetoothManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import com.android.bluetooth.R;
import com.android.bluetooth.a2dp.A2dpService;
import com.android.bluetooth.a2dpsink.A2dpSinkService;
import com.android.bluetooth.avrcp.AvrcpTargetService;
import com.android.bluetooth.avrcpcontroller.AvrcpControllerService;
import com.android.bluetooth.hearingaid.HearingAidService;
import com.android.bluetooth.hfp.HeadsetService;
import com.android.bluetooth.hfpclient.HeadsetClientService;
import com.android.bluetooth.hid.HidDeviceService;
import com.android.bluetooth.hid.HidHostService;
import com.android.bluetooth.map.BluetoothMapService;
import com.android.bluetooth.mapclient.MapClientService;
import com.android.bluetooth.opp.BluetoothOppService;
import com.android.bluetooth.pan.PanService;
import com.android.bluetooth.pbap.BluetoothPbapService;
import com.android.bluetooth.pbapclient.PbapClientService;
import com.android.bluetooth.sap.SapService;

import java.util.ArrayList;
import java.util.List;

public class Config {
    private static final String TAG = "AdapterServiceConfig";

    private static final int ADAPTER_DEFAULT = BluetoothAdapterCommon.ADAPTER_DEFAULT;
    private static final int ADAPTER_1 = BluetoothAdapterCommon.ADAPTER_1;

    private static class ProfileConfig {
        Class mClass;
        int mSupported;
        int mProfileId;
        long mMask;

        ProfileConfig(Class theClass, int supportedFlag, int profileId) {
            mClass = theClass;
            mSupported = supportedFlag;
            mProfileId = profileId;
            mMask = 1 << profileId;
        }
    }

    /**
     * List of profile services with the profile-supported resource flag and bit mask.
     */
    private static ProfileConfig[] PROFILE_SERVICES_AND_FLAGS = {
            new ProfileConfig(HeadsetService.class, R.bool.profile_supported_hs_hfp,
                    BluetoothProfile.HEADSET),
            new ProfileConfig(A2dpService.class, R.bool.profile_supported_a2dp,
                    BluetoothProfile.A2DP),
            new ProfileConfig(A2dpSinkService.class, R.bool.profile_supported_a2dp_sink,
                    BluetoothProfile.A2DP_SINK),
            new ProfileConfig(HidHostService.class, R.bool.profile_supported_hid_host,
                    BluetoothProfile.HID_HOST),
            new ProfileConfig(PanService.class, R.bool.profile_supported_pan,
                    BluetoothProfile.PAN),
            new ProfileConfig(AdapterUtil.getGattServiceClass(), R.bool.profile_supported_gatt,
                    BluetoothProfile.GATT),
            new ProfileConfig(BluetoothMapService.class, R.bool.profile_supported_map,
                    BluetoothProfile.MAP),
            new ProfileConfig(HeadsetClientService.class, R.bool.profile_supported_hfpclient,
                    BluetoothProfile.HEADSET_CLIENT),
            new ProfileConfig(AvrcpTargetService.class, R.bool.profile_supported_avrcp_target,
                    BluetoothProfile.AVRCP),
            new ProfileConfig(AvrcpControllerService.class,
                    R.bool.profile_supported_avrcp_controller,
                    BluetoothProfile.AVRCP_CONTROLLER),
            new ProfileConfig(SapService.class, R.bool.profile_supported_sap,
                    BluetoothProfile.SAP),
            new ProfileConfig(PbapClientService.class, R.bool.profile_supported_pbapclient,
                    BluetoothProfile.PBAP_CLIENT),
            new ProfileConfig(MapClientService.class, R.bool.profile_supported_mapmce,
                    BluetoothProfile.MAP_CLIENT),
            new ProfileConfig(HidDeviceService.class, R.bool.profile_supported_hid_device,
                    BluetoothProfile.HID_DEVICE),
            new ProfileConfig(BluetoothOppService.class, R.bool.profile_supported_opp,
                    BluetoothProfile.OPP),
            new ProfileConfig(BluetoothPbapService.class, R.bool.profile_supported_pbap,
                    BluetoothProfile.PBAP),
            new ProfileConfig(HearingAidService.class,
                    com.android.internal.R.bool.config_hearing_aid_profile_supported,
                    BluetoothProfile.HEARING_AID)
    };

    private static Class[] sSupportedProfiles = new Class[0];

    private static boolean sIsGdEnabledUptoScanningLayer = false;

    static void init(Context ctx) {
        if (ctx == null) {
            return;
        }
        Resources resources = ctx.getResources();
        if (resources == null) {
            return;
        }
        List<String> enabledProfiles =
                getSystemConfigEnabledProfilesForPackage(ctx.getPackageName());

        ArrayList<Class> profiles = new ArrayList<>(PROFILE_SERVICES_AND_FLAGS.length);
        for (ProfileConfig config : PROFILE_SERVICES_AND_FLAGS) {
            boolean supported = resources.getBoolean(config.mSupported);

            if (!supported && (config.mClass == HearingAidService.class) && isHearingAidSettingsEnabled(ctx)) {
                Log.v(TAG, "Feature Flag enables support for HearingAidService");
                supported = true;
            }

            if (enabledProfiles != null && enabledProfiles.contains(config.mClass.getName())) {
                supported = true;
                Log.v(TAG, config.mClass.getSimpleName() + " Feature Flag set to " + supported
                        + " by components configuration");
            }

            if (supported &&
                !AdapterUtil.isProfileSupported(config.mProfileId)) {
                supported = false;
            }

            if (supported && !isProfileDisabled(ctx, config.mMask)) {
                Log.v(TAG, "Adding " + config.mClass.getSimpleName());
                profiles.add(config.mClass);
            }
        }
        sSupportedProfiles = profiles.toArray(new Class[profiles.size()]);
        sIsGdEnabledUptoScanningLayer = resources.getBoolean(R.bool.enable_gd_up_to_scanning_layer);
    }

    static Class[] getSupportedProfiles() {
        return sSupportedProfiles;
    }

    static boolean isGdEnabledUpToScanningLayer() {
        return sIsGdEnabledUptoScanningLayer;
    }

    private static long getProfileMask(Class profile) {
        for (ProfileConfig config : PROFILE_SERVICES_AND_FLAGS) {
            if (config.mClass == profile) {
                return config.mMask;
            }
        }
        Log.w(TAG, "Could not find profile bit mask for " + profile.getSimpleName());
        return 0;
    }

    static long getSupportedProfilesBitMask() {
        long mask = 0;
        for (final Class profileClass : getSupportedProfiles()) {
            mask |= getProfileMask(profileClass);
        }
        return mask;
    }

    private static boolean isProfileDisabled(Context context, long profileMask) {
        final ContentResolver resolver = context.getContentResolver();
        final long disabledProfilesBitMask =
                Settings.Global.getLong(resolver, Settings.Global.BLUETOOTH_DISABLED_PROFILES, 0);

        return (disabledProfilesBitMask & profileMask) != 0;
    }

    private static boolean isHearingAidSettingsEnabled(Context context) {
        final String flagOverridePrefix = "sys.fflag.override.";
        final String hearingAidSettings = "settings_bluetooth_hearing_aid";

        // Override precedence:
        // Settings.Global -> sys.fflag.override.* -> static list

        // Step 1: check if hearing aid flag is set in Settings.Global.
        String value;
        if (context != null) {
            value = Settings.Global.getString(context.getContentResolver(), hearingAidSettings);
            if (!TextUtils.isEmpty(value)) {
                return Boolean.parseBoolean(value);
            }
        }

        // Step 2: check if hearing aid flag has any override.
        value = SystemProperties.get(flagOverridePrefix + hearingAidSettings);
        if (!TextUtils.isEmpty(value)) {
            return Boolean.parseBoolean(value);
        }

        // Step 3: return default value.
        return false;
    }

    private static List<String> getSystemConfigEnabledProfilesForPackage(String packageName) {
        IBinder b = ServiceManager.getService(BluetoothAdapter.BLUETOOTH_MANAGER_SERVICE);
        if (b == null) {
            Log.e(TAG, "Bluetooth binder is null");
        }

        IBluetoothManager managerService = IBluetoothManager.Stub.asInterface(b);
        try {
            return managerService.getSystemConfigEnabledProfilesForPackage(packageName);
        } catch (RemoteException e) {
            return null;
        }

    }
}
