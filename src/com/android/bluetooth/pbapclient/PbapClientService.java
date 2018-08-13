/*
 * Copyright (c) 2016 The Android Open Source Project
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
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.IBluetoothPbapClient;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.util.Log;
import android.os.Bundle;

import com.android.bluetooth.R;
import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.ProfileService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provides Bluetooth Phone Book Access Profile Client profile.
 *
 * @hide
 */
public class PbapClientService extends ProfileService {
    private static final boolean DBG = false;
    private static final String TAG = "PbapClientService";
    // MAXIMUM_DEVICES set to 10 to prevent an excessive number of simultaneous devices.
    private static final int MAXIMUM_DEVICES = 10;
    private Map<BluetoothDevice, PbapClientStateMachine> mPbapClientStateMachineMap =
            new ConcurrentHashMap<>();
    private static PbapClientService sPbapClientService;
    private PbapBroadcastReceiver mPbapBroadcastReceiver = new PbapBroadcastReceiver();
    private PbapClientHandler mHandler = null;
    private boolean mIsRegistered = false;

    @Override
    public IProfileServiceBinder initBinder() {
        return new BluetoothPbapClientBinder(this);
    }

    @Override
    protected boolean start() {
        if (DBG) {
            Log.d(TAG, "onStart");
        }
        initHandler();
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        // delay initial download until after the user is unlocked to add an account.
        filter.addAction(Intent.ACTION_USER_UNLOCKED);
        if (isPtsEnabled()) {
            if (DBG) Log.d(TAG, "register custom action");
            filter.addAction(PbapClientHandler.ACTION_CUSTOM_ACTION);
        }
        try {
            registerReceiver(mPbapBroadcastReceiver, filter);
            mIsRegistered = true;
        } catch (Exception e) {
            Log.w(TAG, "Unable to register pbapclient receiver", e);
        }
        removeUncleanAccounts();
        setPbapClientService(this);
        return true;
    }

    @Override
    protected boolean stop() {
        if (!mIsRegistered) {
            Log.i(TAG, "Avoid unregister when receiver is not registered");
            return true;
        }
        try {
            unregisterReceiver(mPbapBroadcastReceiver);
            mIsRegistered = false;
        } catch (Exception e) {
            Log.w(TAG, "Unable to unregister pbapclient receiver", e);
        }
        for (PbapClientStateMachine pbapClientStateMachine : mPbapClientStateMachineMap.values()) {
            pbapClientStateMachine.doQuit();
        }
        return true;
    }

    @Override
    protected void cleanup() {
        removeUncleanAccounts();
        // TODO: Should move to stop()
        setPbapClientService(null);
    }

    void cleanupDevice(BluetoothDevice device) {
        Log.w(TAG, "Cleanup device: " + device);
        synchronized (mPbapClientStateMachineMap) {
            PbapClientStateMachine pbapClientStateMachine = mPbapClientStateMachineMap.get(device);
            if (pbapClientStateMachine != null) {
                mPbapClientStateMachineMap.remove(device);
            }
        }
    }

    private void removeUncleanAccounts() {
        // Find all accounts that match the type "pbap" and delete them.
        AccountManager accountManager = AccountManager.get(this);
        Account[] accounts =
                accountManager.getAccountsByType(getString(R.string.pbap_account_type));
        Log.w(TAG, "Found " + accounts.length + " unclean accounts");
        for (Account acc : accounts) {
            Log.w(TAG, "Deleting " + acc);
            // The device ID is the name of the account.
            accountManager.removeAccountExplicitly(acc);
        }
        try {
            getContentResolver().delete(CallLog.Calls.CONTENT_URI, null, null);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Call Logs could not be deleted, they may not exist yet.");
        } catch (SecurityException se) {
            Log.w(TAG, "Call Logs could not be deleted due to SecurityException", se);
        }
    }

    private class PbapBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.v(TAG, "onReceive" + action);
            if (action.equals(BluetoothDevice.ACTION_ACL_DISCONNECTED)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device == null) {
                    Log.e(TAG, "BluetoothDevice is null!");
                    return;
                }
                if (getConnectionState(device) == BluetoothProfile.STATE_CONNECTED) {
                    disconnect(device);
                }
            } else if (action.equals(Intent.ACTION_USER_UNLOCKED)) {
                ContentResolver resolver = getContentResolver();
                if (resolver == null) {
                    Log.e(TAG, "ContentResolver is null!");
                    return;
                }
                try {
                    resolver.delete(ContactsContract.RawContacts.CONTENT_URI, null, null);
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "Contacts could not be deleted, they may not exist yet.");
                } catch (SecurityException se) {
                    Log.w(TAG, "Contacts could not be deleted due to SecurityException", se);
                }
                try {
                    resolver.delete(CallLog.Calls.CONTENT_URI, null, null);
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "Call Logs could not be deleted, they may not exist yet.");
                } catch (SecurityException se) {
                    Log.w(TAG, "Call Logs could not be deleted due to SecurityException", se);
                }
                for (PbapClientStateMachine stateMachine : mPbapClientStateMachineMap.values()) {
                    stateMachine.resumeDownload();
                }
            }
        }
    }

    /**
     * Handler for incoming service calls
     */
    private static class BluetoothPbapClientBinder extends IBluetoothPbapClient.Stub
            implements IProfileServiceBinder {
        private PbapClientService mService;

        BluetoothPbapClientBinder(PbapClientService svc) {
            mService = svc;
        }

        @Override
        public void cleanup() {
            mService = null;
        }

        private PbapClientService getService() {
            if (!Utils.checkCaller()) {
                Log.w(TAG, "PbapClient call not allowed for non-active user");
                return null;
            }

            if (mService != null && mService.isAvailable()) {
                return mService;
            }
            return null;
        }

        @Override
        public boolean connect(BluetoothDevice device) {
            PbapClientService service = getService();
            if (DBG) {
                Log.d(TAG, "PbapClient Binder connect ");
            }
            if (service == null) {
                Log.e(TAG, "PbapClient Binder connect no service");
                return false;
            }
            return service.connect(device);
        }

        @Override
        public boolean disconnect(BluetoothDevice device) {
            PbapClientService service = getService();
            if (service == null) {
                return false;
            }
            return service.disconnect(device);
        }

        @Override
        public List<BluetoothDevice> getConnectedDevices() {
            PbapClientService service = getService();
            if (service == null) {
                return new ArrayList<BluetoothDevice>(0);
            }
            return service.getConnectedDevices();
        }

        @Override
        public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
            PbapClientService service = getService();
            if (service == null) {
                return new ArrayList<BluetoothDevice>(0);
            }
            return service.getDevicesMatchingConnectionStates(states);
        }

        @Override
        public int getConnectionState(BluetoothDevice device) {
            PbapClientService service = getService();
            if (service == null) {
                return BluetoothProfile.STATE_DISCONNECTED;
            }
            return service.getConnectionState(device);
        }

        @Override
        public boolean setPriority(BluetoothDevice device, int priority) {
            PbapClientService service = getService();
            if (service == null) {
                return false;
            }
            return service.setPriority(device, priority);
        }

        @Override
        public int getPriority(BluetoothDevice device) {
            PbapClientService service = getService();
            if (service == null) {
                return BluetoothProfile.PRIORITY_UNDEFINED;
            }
            return service.getPriority(device);
        }

        @Override
        public boolean pullPhonebook(BluetoothDevice device, String pbName, long filter,
                int listStartOffset, int maxListCount) {
            PbapClientService service = getService();
            if (service == null) {
                return false;
            }
            return service.pullPhonebook(device, pbName, filter,
                    listStartOffset, maxListCount);
        }

        public boolean pullVcardListing(BluetoothDevice device, String pbName, byte order,
            byte searchProp, String searchValue, int maxListCount, int listStartOffset) {
            PbapClientService service = getService();
            if (service == null) {
                return false;
            }
            return service.pullVcardListing(device, pbName, order, searchProp,
                    searchValue, maxListCount, listStartOffset);
        }

        public boolean setPhonebook(BluetoothDevice device, String pbName) {
            PbapClientService service = getService();
            if (service == null) {
                return false;
            }
            return service.setPhonebook(device, pbName);
        }

    }

    // API methods
    public static synchronized PbapClientService getPbapClientService() {
        if (sPbapClientService == null) {
            Log.w(TAG, "getPbapClientService(): service is null");
            return null;
        }
        if (!sPbapClientService.isAvailable()) {
            Log.w(TAG, "getPbapClientService(): service is not available");
            return null;
        }
        return sPbapClientService;
    }

    private static synchronized void setPbapClientService(PbapClientService instance) {
        if (DBG) {
            Log.d(TAG, "setPbapClientService(): set to: " + instance);
        }
        sPbapClientService = instance;
    }

    public boolean connect(BluetoothDevice device) {
        if (device == null) {
            throw new IllegalArgumentException("Null device");
        }
        enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM, "Need BLUETOOTH ADMIN permission");
        Log.d(TAG, "Received request to ConnectPBAPPhonebook " + device.getAddress());
        if (getPriority(device) <= BluetoothProfile.PRIORITY_OFF) {
            return false;
        }
        synchronized (mPbapClientStateMachineMap) {
            PbapClientStateMachine pbapClientStateMachine = mPbapClientStateMachineMap.get(device);
            if (pbapClientStateMachine == null
                    && mPbapClientStateMachineMap.size() < MAXIMUM_DEVICES) {
                pbapClientStateMachine = new PbapClientStateMachine(this, device);
                pbapClientStateMachine.start();
                mPbapClientStateMachineMap.put(device, pbapClientStateMachine);
                return true;
            } else {
                Log.w(TAG, "Received connect request while already connecting/connected.");
                return false;
            }
        }
    }

    boolean disconnect(BluetoothDevice device) {
        if (device == null) {
            throw new IllegalArgumentException("Null device");
        }
        enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM, "Need BLUETOOTH ADMIN permission");
        PbapClientStateMachine pbapClientStateMachine = mPbapClientStateMachineMap.get(device);
        if (pbapClientStateMachine != null) {
            pbapClientStateMachine.disconnect(device);
            return true;

        } else {
            Log.w(TAG, "disconnect() called on unconnected device.");
            return false;
        }
    }

    public List<BluetoothDevice> getConnectedDevices() {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        int[] desiredStates = {BluetoothProfile.STATE_CONNECTED};
        return getDevicesMatchingConnectionStates(desiredStates);
    }

    private List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        List<BluetoothDevice> deviceList = new ArrayList<BluetoothDevice>(0);
        for (Map.Entry<BluetoothDevice, PbapClientStateMachine> stateMachineEntry :
                mPbapClientStateMachineMap
                .entrySet()) {
            int currentDeviceState = stateMachineEntry.getValue().getConnectionState();
            for (int state : states) {
                if (currentDeviceState == state) {
                    deviceList.add(stateMachineEntry.getKey());
                    break;
                }
            }
        }
        return deviceList;
    }

    int getConnectionState(BluetoothDevice device) {
        if (device == null) {
            throw new IllegalArgumentException("Null device");
        }
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        PbapClientStateMachine pbapClientStateMachine = mPbapClientStateMachineMap.get(device);
        if (pbapClientStateMachine == null) {
            return BluetoothProfile.STATE_DISCONNECTED;
        } else {
            return pbapClientStateMachine.getConnectionState(device);
        }
    }

    public boolean setPriority(BluetoothDevice device, int priority) {
        if (device == null) {
            throw new IllegalArgumentException("Null device");
        }
        enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM, "Need BLUETOOTH_ADMIN permission");
        Settings.Global.putInt(getContentResolver(),
                Settings.Global.getBluetoothPbapClientPriorityKey(device.getAddress()), priority);
        if (DBG) {
            Log.d(TAG, "Saved priority " + device + " = " + priority);
        }
        return true;
    }

    public int getPriority(BluetoothDevice device) {
        if (device == null) {
            throw new IllegalArgumentException("Null device");
        }
        enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM, "Need BLUETOOTH_ADMIN permission");
        int priority = Settings.Global.getInt(getContentResolver(),
                Settings.Global.getBluetoothPbapClientPriorityKey(device.getAddress()),
                BluetoothProfile.PRIORITY_UNDEFINED);
        return priority;
    }

    @Override
    public void dump(StringBuilder sb) {
        super.dump(sb);
        for (PbapClientStateMachine stateMachine : mPbapClientStateMachineMap.values()) {
            stateMachine.dump(sb);
        }
    }

    public boolean isPtsEnabled() {
        return PbapClientHandler.isPtsEnabled();
    }

    private void initHandler() {
        if (DBG) Log.d(TAG, "initHandler");
        mHandler = new PbapClientHandler.Builder()
                                .setContext(this)
                                .build();
    }

    public boolean pullPhonebook(BluetoothDevice device, String pbName, long filter,
            int listStartOffset, int maxListCount) {
        if (device == null) {
            throw new IllegalArgumentException("Null device");
        }
        enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM, "Need BLUETOOTH ADMIN permission");
        PbapClientStateMachine pbapClientStateMachine = mPbapClientStateMachineMap.get(device);
        if (pbapClientStateMachine != null) {
            pbapClientStateMachine.pullPhonebook(device, pbName, filter,
                    PbapClientConnectionHandler.VCARD_TYPE_30, maxListCount, listStartOffset);
            return true;
        } else {
            Log.w(TAG, "pullPhonebook() called on unconnected device.");
            return false;
        }
    }
    public boolean pullVcardListing(BluetoothDevice device, String pbName, byte order,
            byte searchProp, String searchValue, int maxListCount, int listStartOffset) {
        if (device == null) {
            throw new IllegalArgumentException("Null device");
        }
        enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM, "Need BLUETOOTH ADMIN permission");
        PbapClientStateMachine pbapClientStateMachine = mPbapClientStateMachineMap.get(device);
        if (pbapClientStateMachine != null) {
            pbapClientStateMachine.pullVcardListing(device, pbName, order, searchProp,
                searchValue, maxListCount, listStartOffset);
            return true;
        } else {
            Log.w(TAG, "pullVcardListing() called on unconnected device.");
            return false;
        }
    }

    public boolean setPhonebook(BluetoothDevice device, String pbName) {
        if (device == null) {
            throw new IllegalArgumentException("Null device");
        }
        enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM, "Need BLUETOOTH ADMIN permission");
        PbapClientStateMachine pbapClientStateMachine = mPbapClientStateMachineMap.get(device);
        if (pbapClientStateMachine != null) {
            pbapClientStateMachine.setPhonebook(device, pbName);
            return true;
        } else {
            Log.w(TAG, "setPhonebook() called on unconnected device.");
            return false;
        }
    }

    public void notifyPullVcardListingResult(int result, int phonebookSize, int newMissedCalls,
        ArrayList<String> vcardListing){
        if (DBG) {
            Log.d(TAG, "notifyPullVcardListing result=" + result + phonebookSize
                + newMissedCalls + vcardListing);
        }
        if (mHandler != null) {
            mHandler.notifyPullVcardListingResult(result, phonebookSize,
                newMissedCalls, vcardListing);
        }
    }

    public void notifySetPhonebookResult(int result) {
        if (DBG) {
            Log.d(TAG, "notifySetPhonebook result=" + result);
        }
        if (mHandler != null) {
            mHandler.notifySetPhonebookResult(result);
        }
    }

}
