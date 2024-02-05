/*
 * Copyright (c) 2014 The Android Open Source Project
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

package com.android.bluetooth.hfpclient;

import android.annotation.RequiresPermission;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothHeadsetClient;
import android.bluetooth.BluetoothHeadsetClientCall;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.IBluetoothHeadsetClient;
import android.bluetooth.IBluetoothHeadsetClientScoCallback;
import android.content.AttributionSource;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.Message;
import android.os.SystemProperties;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Log;

import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.ProfileService;
import com.android.bluetooth.btservice.storage.DatabaseManager;
import com.android.bluetooth.hfpclient.connserv.HfpClientConnectionService;
import com.android.modules.utils.SynchronousResultReceiver;
import com.android.bluetooth.hfp.HeadsetService;
import android.bluetooth.BluetoothA2dp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Provides Bluetooth Headset Client (HF Role) profile, as a service in the
 * Bluetooth application.
 *
 * @hide
 */
public class HeadsetClientService extends ProfileService {
    private static final boolean DBG = true;
    private static final String TAG = "HeadsetClientService";

    private static final String ACTION_AUDIO_CONN_DISCONN = "android.bluetooth.action.HFP_CLIENT_AUDIO_ACTION";
    private static final String EXTRA_AUDIO_STATE = "android.bluetooth.extra.audio.STATE";
    private static final String ACTION_QUERY_NETWORK = "android.bluetooth.action.HFP_CLIENT_NETWORK_NAME";
    private HashMap<BluetoothDevice, HeadsetClientStateMachine> mStateMachineMap = new HashMap<>();
    private static HeadsetClientService sHeadsetClientService;
    private NativeInterface mNativeInterface = null;
    private HandlerThread mSmThread = null;
    private HeadsetClientStateMachineFactory mSmFactory = null;
    private DatabaseManager mDatabaseManager;
    private AudioManager mAudioManager = null;
    // Maxinum number of devices we can try connecting to in one session
    private static final int MAX_STATE_MACHINES_POSSIBLE = 100;
    private static final int MAX_HFP_CLIENTS_SUPPORTED = 1;
    public static final String HFP_CLIENT_STOP_TAG = "hfp_client_stop_tag";
    private RemoteCallbackList<IBluetoothHeadsetClientScoCallback> mHeadsetClientScoCallbacks;
    private final Object mCallbackNotifyLock = new Object();
    @Override
    public IProfileServiceBinder initBinder() {
        return new BluetoothHeadsetClientBinder(this);
    }

    @Override
    protected synchronized boolean start() {
        if (DBG) {
            Log.d(TAG, "start()");
        }
        if (sHeadsetClientService != null) {
            Log.w(TAG, "start(): start called without stop");
            return false;
        }

        mDatabaseManager = Objects.requireNonNull(AdapterService.getAdapterService().getDatabase(),
                "DatabaseManager cannot be null when HeadsetClientService starts");

        // Setup the JNI service
        mNativeInterface = NativeInterface.getInstance();
        mNativeInterface.initialize();

        mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (mAudioManager == null) {
            Log.e(TAG, "AudioManager service doesn't exist?");
        } else {
            // start AudioManager in a known state
            mAudioManager.setParameters("hfp_enable=false");
        }

        mSmFactory = new HeadsetClientStateMachineFactory();
        mStateMachineMap.clear();

        IntentFilter filter = new IntentFilter(AudioManager.VOLUME_CHANGED_ACTION);
        filter.addAction(ACTION_AUDIO_CONN_DISCONN);
        filter.addAction(ACTION_QUERY_NETWORK);
        filter.addAction(BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED);
        filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        registerReceiver(mBroadcastReceiver, filter, Context.RECEIVER_EXPORTED);

        // Start the HfpClientConnectionService to create connection with telecom when HFP
        // connection is available.
        Intent startIntent = new Intent(this, HfpClientConnectionService.class);
        startService(startIntent);

        // Create the thread on which all State Machines will run
        mSmThread = new HandlerThread("HeadsetClient.SM");
        mSmThread.start();

        setHeadsetClientService(this);
        mHeadsetClientScoCallbacks = new RemoteCallbackList<IBluetoothHeadsetClientScoCallback>();
        return true;
    }

    @Override
    protected synchronized boolean stop() {
        if (sHeadsetClientService == null) {
            Log.w(TAG, "stop() called without start()");
            return false;
        }

        // Stop the HfpClientConnectionService.
        Intent stopIntent = new Intent(this, HfpClientConnectionService.class);
        sHeadsetClientService.stopService(stopIntent);

        setHeadsetClientService(null);

        unregisterReceiver(mBroadcastReceiver);

        for (Iterator<Map.Entry<BluetoothDevice, HeadsetClientStateMachine>> it =
                mStateMachineMap.entrySet().iterator(); it.hasNext(); ) {
            HeadsetClientStateMachine sm =
                    mStateMachineMap.get((BluetoothDevice) it.next().getKey());
            sm.doQuit();
            it.remove();
        }

        // Stop the handler thread
        mSmThread.quit();
        mSmThread = null;

        mNativeInterface.cleanup();
        mNativeInterface = null;

        return true;
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            // We handle the volume changes for Voice calls here since HFP audio volume control does
            // not go through audio manager (audio mixer). see
            // ({@link HeadsetClientStateMachine#SET_SPEAKER_VOLUME} in
            // {@link HeadsetClientStateMachine} for details.
            if (action.equals(AudioManager.VOLUME_CHANGED_ACTION)) {
                if (DBG) {
                    Log.d(TAG, "Volume changed for stream: " + intent.getExtra(
                            AudioManager.EXTRA_VOLUME_STREAM_TYPE));
                }
                int streamType = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, -1);
                if (streamType == AudioManager.STREAM_VOICE_CALL) {
                    int streamValue =
                            intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_VALUE, -1);
                    int hfVol = HeadsetClientStateMachine.amToHfVol(streamValue);
                    if (DBG) {
                        Log.d(TAG,
                                "Setting volume to audio manager: " + streamValue + " hands free: "
                                        + hfVol);
                    }
                    mAudioManager.setParameters("hfp_volume=" + hfVol);
                    synchronized (this) {
                        for (HeadsetClientStateMachine sm : mStateMachineMap.values()) {
                            if (sm != null) {
                                sm.sendMessage(HeadsetClientStateMachine.SET_SPEAKER_VOLUME,
                                        streamValue);
                            }
                        }
                    }
                }
            } else if (intent.getAction().equals(ACTION_AUDIO_CONN_DISCONN)) {
                /*Audio connect changes to pass pts tests ATAH/BV-01-1, ORR/BV-02-1 */
                Log.e(TAG, "HeadsetClientService -  Received ACTION_AUDIO_CONN_DISCONN");
                int con_status = intent.getIntExtra( EXTRA_AUDIO_STATE, 0);
                Log.d(TAG, " HeadsetClientService con_status" + con_status);
                for (HeadsetClientStateMachine sm : mStateMachineMap.values()) {
                    if (sm != null) {
                        if(con_status == 1){
                            Log.d(TAG, " HeadsetClientService AUDIO_CONNECT message to statemachine");
                            sm.sendMessage(
                                HeadsetClientStateMachine.CONNECT_AUDIO );
                        } else {
                            Log.d(TAG, " HeadsetClientService AUDIO_DISCONNECT message to statemachine");
                            sm.sendMessage(
                                HeadsetClientStateMachine.DISCONNECT_AUDIO );
                        }
                    }
                }
            } else if (action.equals(ACTION_QUERY_NETWORK)) {
              Log.d(TAG, "Received HFP_CLIENT_NETWORK_NAME action");
              for (HeadsetClientStateMachine sm : mStateMachineMap.values()) {
                  if (sm != null) {
                      sm.sendMessage(
                              HeadsetClientStateMachine.QUERY_OPERATOR_NAME);
                  }
              }
           } else if (action.equals(BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED)) {
              Log.d(TAG, "Received BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED");
              int currState = intent.getIntExtra(BluetoothProfile.EXTRA_STATE,
                                       BluetoothA2dp.STATE_NOT_PLAYING);
              for (HeadsetClientStateMachine sm : mStateMachineMap.values()) {
                  if (sm != null) {
                      sm.sendMessage(
                              HeadsetClientStateMachine.ACTION_PLAYING_STATE_CHANGED, currState);
                  }
              }
           } else if (action.equals(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)) {
              Log.d(TAG, "Received BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED");
              int currState = intent.getIntExtra(BluetoothProfile.EXTRA_STATE,
                                       BluetoothProfile.STATE_DISCONNECTED);
              for (HeadsetClientStateMachine sm : mStateMachineMap.values()) {
                  if (sm != null) {
                      sm.sendMessage(
                              HeadsetClientStateMachine.ACTION_CONNECTION_STATE_CHANGED, currState);
                  }
              }
         }
        }
    };

    /**
     * Handlers for incoming service calls
     */
    private static class BluetoothHeadsetClientBinder extends IBluetoothHeadsetClient.Stub
            implements IProfileServiceBinder {
        private HeadsetClientService mService;

        BluetoothHeadsetClientBinder(HeadsetClientService svc) {
            mService = svc;
        }

        @Override
        public void cleanup() {
            mService = null;
        }

        @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
        private HeadsetClientService getService(AttributionSource source) {
            if (!Utils.checkServiceAvailable(mService, TAG)
                    || !Utils.checkCallerIsSystemOrActiveOrManagedUser(mService, TAG)
                    || !Utils.checkConnectPermissionForDataDelivery(mService, source, TAG)) {
                return null;
            }

            if (mService != null && mService.isAvailable()) {
                return mService;
            }

            Log.e(TAG, "HeadsetClientService is not available.");
            return null;
        }

        @Override
        public void connect(BluetoothDevice device, AttributionSource source,
                SynchronousResultReceiver receiver) {
            try {
                HeadsetClientService service = getService(source);
                boolean defaultValue = false;
                if (service != null) {
                    defaultValue = service.connect(device);
                }
                receiver.send(defaultValue);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void disconnect(BluetoothDevice device, AttributionSource source,
                SynchronousResultReceiver receiver) {
            try {
                HeadsetClientService service = getService(source);
                boolean defaultValue = false;
                if (service != null) {
                    defaultValue = service.disconnect(device);
                }
                receiver.send(defaultValue);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void getConnectedDevices(AttributionSource source,
                SynchronousResultReceiver receiver) {
            try {
                HeadsetClientService service = getService(source);
                List<BluetoothDevice> defaultValue = new ArrayList<BluetoothDevice>(0);
                if (service != null) {
                    defaultValue = service.getConnectedDevices();
                }
                receiver.send(defaultValue);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void getDevicesMatchingConnectionStates(int[] states,
                AttributionSource source, SynchronousResultReceiver receiver) {
            try {
                HeadsetClientService service = getService(source);
                List<BluetoothDevice> defaultValue = new ArrayList<BluetoothDevice>(0);
                if (service != null) {
                    defaultValue = service.getDevicesMatchingConnectionStates(states);
                }
                receiver.send(defaultValue);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void getConnectionState(BluetoothDevice device, AttributionSource source,
                SynchronousResultReceiver receiver) {
            try {
                HeadsetClientService service = getService(source);
                int defaultValue = BluetoothProfile.STATE_DISCONNECTED;
                if (service != null) {
                    defaultValue = service.getConnectionState(device);
                }
                receiver.send(defaultValue);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void setConnectionPolicy(BluetoothDevice device, int connectionPolicy,
                AttributionSource source, SynchronousResultReceiver receiver) {
            try {
                HeadsetClientService service = getService(source);
                boolean defaultValue = false;
                if (service != null) {
                    defaultValue = service.setConnectionPolicy(device, connectionPolicy);
                }
                receiver.send(defaultValue);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void getConnectionPolicy(BluetoothDevice device, AttributionSource source,
                SynchronousResultReceiver receiver) {
            try {
                HeadsetClientService service = getService(source);
                int defaultValue = BluetoothProfile.CONNECTION_POLICY_UNKNOWN;
                if (service != null) {
                    defaultValue = service.getConnectionPolicy(device);
                }
                receiver.send(defaultValue);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void startVoiceRecognition(BluetoothDevice device, AttributionSource source,
                SynchronousResultReceiver receiver) {
            try {
                HeadsetClientService service = getService(source);
                boolean defaultValue = false;
                if (service != null) {
                    defaultValue = service.startVoiceRecognition(device);
                }
                receiver.send(defaultValue);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void stopVoiceRecognition(BluetoothDevice device, AttributionSource source,
                SynchronousResultReceiver receiver) {
            try {
                HeadsetClientService service = getService(source);
                boolean defaultValue = false;
                if (service != null) {
                    defaultValue = service.stopVoiceRecognition(device);
                }
                receiver.send(defaultValue);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void getAudioState(BluetoothDevice device, AttributionSource source,
                SynchronousResultReceiver receiver) {
            try {
                HeadsetClientService service = getService(source);
                int defaultValue = BluetoothHeadsetClient.STATE_AUDIO_DISCONNECTED;
                if (service != null) {
                    defaultValue = service.getAudioState(device);
                }
                receiver.send(defaultValue);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void setAudioRouteAllowed(BluetoothDevice device, boolean allowed,
                AttributionSource source, SynchronousResultReceiver receiver) {
            try {
                HeadsetClientService service = getService(source);
                boolean defaultValue = false;
                if (service != null) {
                    Log.d(TAG, "setAudioRouteAllowed " + allowed);
                    defaultValue = service.setAudioRouteAllowed(device, allowed);
                }
                receiver.send(defaultValue);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void getAudioRouteAllowed(BluetoothDevice device, AttributionSource source,
                SynchronousResultReceiver receiver) {
            try {
                HeadsetClientService service = getService(source);
                boolean defaultValue = false;
                if (service != null) {
                    defaultValue = service.getAudioRouteAllowed(device);
                    Log.d(TAG, "getAudioRouteAllowed " + defaultValue);
                }
                receiver.send(defaultValue);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void connectAudio(BluetoothDevice device, AttributionSource source,
                SynchronousResultReceiver receiver) {
            try {
                HeadsetClientService service = getService(source);
                boolean defaultValue = false;
                if (service != null) {
                    defaultValue = service.connectAudio(device);
                }
                receiver.send(defaultValue);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void disconnectAudio(BluetoothDevice device, AttributionSource source,
                SynchronousResultReceiver receiver) {
            try {
                HeadsetClientService service = getService(source);
                boolean defaultValue = false;
                if (service != null) {
                    defaultValue = service.disconnectAudio(device);
                }
                receiver.send(defaultValue);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void acceptCall(BluetoothDevice device, int flag, AttributionSource source,
                SynchronousResultReceiver receiver) {
            try {
                HeadsetClientService service = getService(source);
                boolean defaultValue = false;
                if (service != null) {
                    defaultValue = service.acceptCall(device, flag);
                }
                receiver.send(defaultValue);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void rejectCall(BluetoothDevice device, AttributionSource source,
                SynchronousResultReceiver receiver) {
            try {
                HeadsetClientService service = getService(source);
                boolean defaultValue = false;
                if (service != null) {
                    defaultValue = service.rejectCall(device);
                }
                receiver.send(defaultValue);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void holdCall(BluetoothDevice device, AttributionSource source,
                SynchronousResultReceiver receiver) {
            try {
                HeadsetClientService service = getService(source);
                boolean defaultValue = false;
                if (service != null) {
                    defaultValue = service.holdCall(device);
                }
                receiver.send(defaultValue);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void terminateCall(BluetoothDevice device, BluetoothHeadsetClientCall call,
                AttributionSource source, SynchronousResultReceiver receiver) {
            try {
                HeadsetClientService service = getService(source);
                boolean defaultValue = false;
                if (service != null) {
                    defaultValue = service.terminateCall(device,
                            call != null ? call.getUUID() : null);
                } else {
                    Log.w(TAG, "service is null");
                }
                receiver.send(defaultValue);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void explicitCallTransfer(BluetoothDevice device, AttributionSource source,
                SynchronousResultReceiver receiver) {
            try {
                HeadsetClientService service = getService(source);
                boolean defaultValue = false;
                if (service != null) {
                    defaultValue = service.explicitCallTransfer(device);
                }
                receiver.send(defaultValue);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void enterPrivateMode(BluetoothDevice device, int index,
                AttributionSource source, SynchronousResultReceiver receiver) {
            try {
                HeadsetClientService service = getService(source);
                boolean defaultValue = false;
                if (service != null) {
                    defaultValue = service.enterPrivateMode(device, index);
                }
                receiver.send(defaultValue);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void dial(BluetoothDevice device, String number,
                AttributionSource source, SynchronousResultReceiver receiver) {
            try {
                HeadsetClientService service = getService(source);
                BluetoothHeadsetClientCall defaultValue = null;
                if (service != null) {
                    defaultValue = service.dial(device, number);
                }
                receiver.send(defaultValue);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void getCurrentCalls(BluetoothDevice device,
                AttributionSource source, SynchronousResultReceiver receiver) {
            try {
                HeadsetClientService service = getService(source);
                List<BluetoothHeadsetClientCall> defaultValue = new ArrayList<>();
                if (service != null) {
                    defaultValue = service.getCurrentCalls(device);
                }
                receiver.send(defaultValue);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void sendDTMF(BluetoothDevice device, byte code, AttributionSource source,
                SynchronousResultReceiver receiver) {
            try {
                HeadsetClientService service = getService(source);
                boolean defaultValue = false;
                if (service != null) {
                    defaultValue = service.sendDTMF(device, code);
                }
                receiver.send(defaultValue);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void getLastVoiceTagNumber(BluetoothDevice device, AttributionSource source,
                SynchronousResultReceiver receiver) {
            try {
                HeadsetClientService service = getService(source);
                boolean defaultValue = false;
                if (service != null) {
                    defaultValue = service.getLastVoiceTagNumber(device);
                }
                receiver.send(defaultValue);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void getCurrentAgEvents(BluetoothDevice device, AttributionSource source,
                SynchronousResultReceiver receiver) {
            try {
                HeadsetClientService service = getService(source);
                Bundle defaultValue = null;
                if (service != null) {
                    defaultValue = service.getCurrentAgEvents(device);
                }
                receiver.send(defaultValue);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void sendVendorAtCommand(BluetoothDevice device, int vendorId, String atCommand,
                AttributionSource source, SynchronousResultReceiver receiver) {
            try {
                HeadsetClientService service = getService(source);
                boolean defaultValue = false;
                if (service != null) {
                    defaultValue = service.sendVendorAtCommand(device, vendorId, atCommand);
                }
                receiver.send(defaultValue);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void getCurrentAgFeatures(BluetoothDevice device, AttributionSource source,
                SynchronousResultReceiver receiver) {
            try {
                HeadsetClientService service = getService(source);
                Bundle defaultValue = null;
                if (service != null) {
                    defaultValue = service.getCurrentAgFeatures(device);
                }
                receiver.send(defaultValue);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

        @Override
        public void registerHeadsetClientScoCallback(IBluetoothHeadsetClientScoCallback callback, AttributionSource source,
                SynchronousResultReceiver receiver) {
            try {
                HeadsetClientService service = getService(source);
                if ((service == null)) {
                 receiver.propagateException(new IllegalStateException("Service is unavailable"));
                 return;
                }
                service.registerHeadsetClientScoCallback(callback);
                receiver.send(null);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }
        @Override
        public void unregisterHeadsetClientScoCallback(IBluetoothHeadsetClientScoCallback callback, AttributionSource source,
                SynchronousResultReceiver receiver) {
            try {
                HeadsetClientService service = getService(source);
                if ((service == null)) {
                 receiver.propagateException(new IllegalStateException("Service is unavailable"));
                 return;
                }
                service.unregisterHeadsetClientScoCallback(callback);
                receiver.send(null);
            } catch (RuntimeException e) {
                receiver.propagateException(e);
            }
        }

    }

    ;

    public void notifyHeadsetClientScoStateChanged(int sco_state) {
        if (mHeadsetClientScoCallbacks != null) {
            synchronized (mCallbackNotifyLock) {
                final int n = mHeadsetClientScoCallbacks.beginBroadcast();
                for (int i = 0; i < n; i++) {
                    final IBluetoothHeadsetClientScoCallback callback =
                            mHeadsetClientScoCallbacks.getBroadcastItem(i);
                    try {
                        Log.d(TAG, "Calling onHeadsetClientScoStateChanged: " + i);
                        Log.d(TAG, "onHeadsetClientScoStateChanged sco_state: " + sco_state);
                        callback.onHeadsetClientScoStateChanged(sco_state);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Stack:" + Log.getStackTraceString(e));
                    }
                }
                mHeadsetClientScoCallbacks.finishBroadcast();
            }
        }
    }

    // API methods
    public static synchronized HeadsetClientService getHeadsetClientService() {
        if (sHeadsetClientService == null) {
            Log.w(TAG, "getHeadsetClientService(): service is null");
            return null;
        }
        if (!sHeadsetClientService.isAvailable()) {
            Log.w(TAG, "getHeadsetClientService(): service is not available ");
            return null;
        }
        return sHeadsetClientService;
    }

    private static synchronized void setHeadsetClientService(HeadsetClientService instance) {
        if (DBG) {
            Log.d(TAG, "setHeadsetClientService(): set to: " + instance);
        }
        sHeadsetClientService = instance;
    }

    public boolean connect(BluetoothDevice device) {

        if (DBG) {
            Log.d(TAG, "connect " + device);
        }

        if (getConnectedDevices().size() >= MAX_HFP_CLIENTS_SUPPORTED) {
            Log.w(TAG, "HFPCLIENT Max Devices limit = " + MAX_HFP_CLIENTS_SUPPORTED +
                       "reached, Igonre connect request for " + device);
            return false;
        }

        HeadsetClientStateMachine sm = getStateMachine(device);
        if (sm == null) {
            Log.e(TAG, "Cannot allocate SM for device " + device);
            return false;
        }

        if (getConnectionPolicy(device) == BluetoothProfile.CONNECTION_POLICY_FORBIDDEN) {
            Log.w(TAG, "Connection not allowed: <" + device.getAddress()
                    + "> is CONNECTION_POLICY_FORBIDDEN");
            return false;
        }

        sm.sendMessage(HeadsetClientStateMachine.CONNECT, device);
        return true;
    }

    /**
     * Disconnects hfp client for the remote bluetooth device
     *
     * @param device is the device with which we are attempting to disconnect the profile
     * @return true if hfp client profile successfully disconnected, false otherwise
     */
    public boolean disconnect(BluetoothDevice device) {

        HeadsetClientStateMachine sm = getStateMachine(device);
        if (sm == null) {
            Log.e(TAG, "Cannot allocate SM for device " + device);
            return false;
        }

        int connectionState = sm.getConnectionState(device);
        if (connectionState != BluetoothProfile.STATE_CONNECTED
                && connectionState != BluetoothProfile.STATE_CONNECTING) {
            return false;
        }

        sm.sendMessage(HeadsetClientStateMachine.DISCONNECT, device);
        return true;
    }

    public synchronized List<BluetoothDevice> getConnectedDevices() {

        ArrayList<BluetoothDevice> connectedDevices = new ArrayList<>();
        for (BluetoothDevice bd : mStateMachineMap.keySet()) {
            HeadsetClientStateMachine sm = mStateMachineMap.get(bd);
            if (sm != null && sm.getConnectionState(bd) == BluetoothProfile.STATE_CONNECTED) {
                connectedDevices.add(bd);
            }
        }
        return connectedDevices;
    }

    private synchronized List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {

        List<BluetoothDevice> devices = new ArrayList<BluetoothDevice>();
        for (BluetoothDevice bd : mStateMachineMap.keySet()) {
            for (int state : states) {
                HeadsetClientStateMachine sm = mStateMachineMap.get(bd);
                if (sm != null && sm.getConnectionState(bd) == state) {
                    devices.add(bd);
                }
            }
        }
        return devices;
    }

    /**
     * Get the current connection state of the profile
     *
     * @param device is the remote bluetooth device
     * @return {@link BluetoothProfile#STATE_DISCONNECTED} if this profile is disconnected,
     * {@link BluetoothProfile#STATE_CONNECTING} if this profile is being connected,
     * {@link BluetoothProfile#STATE_CONNECTED} if this profile is connected, or
     * {@link BluetoothProfile#STATE_DISCONNECTING} if this profile is being disconnected
     */
    public synchronized int getConnectionState(BluetoothDevice device) {

        HeadsetClientStateMachine sm = mStateMachineMap.get(device);
        if (sm != null) {
            return sm.getConnectionState(device);
        }
        return BluetoothProfile.STATE_DISCONNECTED;
    }

    /**
     * Set connection policy of the profile and connects it if connectionPolicy is
     * {@link BluetoothProfile#CONNECTION_POLICY_ALLOWED} or disconnects if connectionPolicy is
     * {@link BluetoothProfile#CONNECTION_POLICY_FORBIDDEN}
     *
     * <p> The device should already be paired.
     * Connection policy can be one of:
     * {@link BluetoothProfile#CONNECTION_POLICY_ALLOWED},
     * {@link BluetoothProfile#CONNECTION_POLICY_FORBIDDEN},
     * {@link BluetoothProfile#CONNECTION_POLICY_UNKNOWN}
     *
     * @param device Paired bluetooth device
     * @param connectionPolicy is the connection policy to set to for this profile
     * @return true if connectionPolicy is set, false on error
     */
    public boolean setConnectionPolicy(BluetoothDevice device, int connectionPolicy) {

        if (DBG) {
            Log.d(TAG, "Saved connectionPolicy " + device + " = " + connectionPolicy);
        }

        if (!mDatabaseManager.setProfileConnectionPolicy(device, BluetoothProfile.HEADSET_CLIENT,
                  connectionPolicy)) {
            return false;
        }
        if (connectionPolicy == BluetoothProfile.CONNECTION_POLICY_ALLOWED) {
            connect(device);
        } else if (connectionPolicy == BluetoothProfile.CONNECTION_POLICY_FORBIDDEN) {
            disconnect(device);
        }
        return true;
    }

    /**
     * Get the connection policy of the profile.
     *
     * <p> The connection policy can be any of:
     * {@link BluetoothProfile#CONNECTION_POLICY_ALLOWED},
     * {@link BluetoothProfile#CONNECTION_POLICY_FORBIDDEN},
     * {@link BluetoothProfile#CONNECTION_POLICY_UNKNOWN}
     *
     * @param device Bluetooth device
     * @return connection policy of the device
     * @hide
     */
    public int getConnectionPolicy(BluetoothDevice device) {

        return mDatabaseManager
                .getProfileConnectionPolicy(device, BluetoothProfile.HEADSET_CLIENT);
    }

    boolean startVoiceRecognition(BluetoothDevice device) {

        HeadsetClientStateMachine sm = getStateMachine(device);
        if (sm == null) {
            Log.e(TAG, "Cannot allocate SM for device " + device);
            return false;
        }
        int connectionState = sm.getConnectionState(device);
        if (connectionState != BluetoothProfile.STATE_CONNECTED) {
            return false;
        }
        sm.sendMessage(HeadsetClientStateMachine.VOICE_RECOGNITION_START);
        return true;
    }

    boolean stopVoiceRecognition(BluetoothDevice device) {

        HeadsetClientStateMachine sm = getStateMachine(device);
        if (sm == null) {
            Log.e(TAG, "Cannot allocate SM for device " + device);
            return false;
        }
        int connectionState = sm.getConnectionState(device);
        if (connectionState != BluetoothProfile.STATE_CONNECTED) {
            return false;
        }
        sm.sendMessage(HeadsetClientStateMachine.VOICE_RECOGNITION_STOP);
        return true;
    }

    int getAudioState(BluetoothDevice device) {
        HeadsetClientStateMachine sm = getStateMachine(device);
        if (sm == null) {
            Log.e(TAG, "Cannot allocate SM for device " + device);
            return -1;
        }

        return sm.getAudioState(device);
    }

    public boolean  setAudioRouteAllowed(BluetoothDevice device, boolean allowed) {
        HeadsetClientStateMachine sm = mStateMachineMap.get(device);
        if (sm != null) {
            sm.setAudioRouteAllowed(allowed);
        }
        return true;
    }

    public boolean getAudioRouteAllowed(BluetoothDevice device) {
        HeadsetClientStateMachine sm = mStateMachineMap.get(device);
        if (sm != null) {
            return sm.getAudioRouteAllowed();
        }
        return false;
    }

    boolean connectAudio(BluetoothDevice device) {
        boolean mPts = SystemProperties.getBoolean("vendor.bt.pts.certification", false);
        if(mPts){
          Log.e(TAG, "PTS mode true, connectAudio request rejected");
          return false;
        }

        HeadsetClientStateMachine sm = getStateMachine(device);
        if (sm == null) {
            Log.e(TAG, "Cannot allocate SM for device " + device);
            return false;
        }
        HeadsetService service = HeadsetService.getHeadsetService();
        if (service != null && service.isAudioOn()) {
            Log.e(TAG, "SCO Connected for headsetService ignore connectAudio " + device);
            return false;
        }
        if (!sm.isConnected()) {
            return false;
        }
        if (sm.isAudioOn()) {
            return false;
        }
        /* Sending message with a delay, In most cases SCO will be
         * initiated by AG, In case its not done till 5 sec, DUT
         * ( HFP-Client ) will send SCO request from here
         */
        sm.sendMessage(HeadsetClientStateMachine.CONNECT_AUDIO_WITH_DELAY);
        return true;
    }

    boolean disconnectAudio(BluetoothDevice device) {

        HeadsetClientStateMachine sm = getStateMachine(device);
        if (sm == null) {
            Log.e(TAG, "Cannot allocate SM for device " + device);
            return false;
        }

        if (!sm.isAudioOn()) {
            return false;
        }
        sm.sendMessage(HeadsetClientStateMachine.DISCONNECT_AUDIO);
        return true;
    }

    boolean holdCall(BluetoothDevice device) {

        HeadsetClientStateMachine sm = getStateMachine(device);
        if (sm == null) {
            Log.e(TAG, "Cannot allocate SM for device " + device);
            return false;
        }

        int connectionState = sm.getConnectionState(device);
        if (connectionState != BluetoothProfile.STATE_CONNECTED
                && connectionState != BluetoothProfile.STATE_CONNECTING) {
            return false;
        }
        Message msg = sm.obtainMessage(HeadsetClientStateMachine.HOLD_CALL);
        sm.sendMessage(msg);
        return true;
    }

    boolean acceptCall(BluetoothDevice device, int flag) {

        /* Phonecalls from a single device are supported, hang up any calls on the other phone */
        synchronized (this) {
            for (Map.Entry<BluetoothDevice, HeadsetClientStateMachine> entry : mStateMachineMap
                    .entrySet()) {
                if (entry.getValue() == null || entry.getKey().equals(device)) {
                    continue;
                }
                int connectionState = entry.getValue().getConnectionState(entry.getKey());
                if (DBG) {
                    Log.d(TAG,
                            "Accepting a call on device " + device + ". Possibly disconnecting on "
                                    + entry.getValue());
                }
                if (connectionState == BluetoothProfile.STATE_CONNECTED) {
                    entry.getValue()
                            .obtainMessage(HeadsetClientStateMachine.TERMINATE_CALL)
                            .sendToTarget();
                }
            }
        }
        HeadsetClientStateMachine sm = getStateMachine(device);
        if (sm == null) {
            Log.e(TAG, "Cannot allocate SM for device " + device);
            return false;
        }

        int connectionState = sm.getConnectionState(device);
        if (connectionState != BluetoothProfile.STATE_CONNECTED) {
            return false;
        }
        Message msg = sm.obtainMessage(HeadsetClientStateMachine.ACCEPT_CALL);
        msg.arg1 = flag;
        sm.sendMessage(msg);
        return true;
    }

    boolean rejectCall(BluetoothDevice device) {

        HeadsetClientStateMachine sm = getStateMachine(device);
        if (sm == null) {
            Log.e(TAG, "Cannot allocate SM for device " + device);
            return false;
        }

        int connectionState = sm.getConnectionState(device);
        if (connectionState != BluetoothProfile.STATE_CONNECTED
                && connectionState != BluetoothProfile.STATE_CONNECTING) {
            return false;
        }

        Message msg = sm.obtainMessage(HeadsetClientStateMachine.REJECT_CALL);
        sm.sendMessage(msg);
        return true;
    }

    boolean terminateCall(BluetoothDevice device, UUID uuid) {

        HeadsetClientStateMachine sm = getStateMachine(device);
        if (sm == null) {
            Log.e(TAG, "Cannot allocate SM for device " + device);
            return false;
        }

        int connectionState = sm.getConnectionState(device);
        if (connectionState != BluetoothProfile.STATE_CONNECTED
                && connectionState != BluetoothProfile.STATE_CONNECTING) {
            return false;
        }

        Message msg = sm.obtainMessage(HeadsetClientStateMachine.TERMINATE_CALL);
        msg.obj = uuid;
        sm.sendMessage(msg);
        return true;
    }

    boolean enterPrivateMode(BluetoothDevice device, int index) {

        HeadsetClientStateMachine sm = getStateMachine(device);
        if (sm == null) {
            Log.e(TAG, "Cannot allocate SM for device " + device);
            return false;
        }

        int connectionState = sm.getConnectionState(device);
        if (connectionState != BluetoothProfile.STATE_CONNECTED
                && connectionState != BluetoothProfile.STATE_CONNECTING) {
            return false;
        }

        Message msg = sm.obtainMessage(HeadsetClientStateMachine.ENTER_PRIVATE_MODE);
        msg.arg1 = index;
        sm.sendMessage(msg);
        return true;
    }

    BluetoothHeadsetClientCall dial(BluetoothDevice device, String number) {

        HeadsetClientStateMachine sm = getStateMachine(device);
        if (sm == null) {
            Log.e(TAG, "Cannot allocate SM for device " + device);
            return null;
        }

        int connectionState = sm.getConnectionState(device);
        if (connectionState != BluetoothProfile.STATE_CONNECTED
                && connectionState != BluetoothProfile.STATE_CONNECTING) {
            return null;
        }

        BluetoothHeadsetClientCall call = new BluetoothHeadsetClientCall(device,
                HeadsetClientStateMachine.HF_ORIGINATED_CALL_ID,
                BluetoothHeadsetClientCall.CALL_STATE_DIALING, number, false  /* multiparty */,
                true  /* outgoing */, sm.getInBandRing());
        Message msg = sm.obtainMessage(HeadsetClientStateMachine.DIAL_NUMBER);
        msg.obj = call;
        sm.sendMessage(msg);
        return call;
    }

    public boolean sendDTMF(BluetoothDevice device, byte code) {

        HeadsetClientStateMachine sm = getStateMachine(device);
        if (sm == null) {
            Log.e(TAG, "Cannot allocate SM for device " + device);
            return false;
        }

        int connectionState = sm.getConnectionState(device);
        if (connectionState != BluetoothProfile.STATE_CONNECTED
                && connectionState != BluetoothProfile.STATE_CONNECTING) {
            return false;
        }
        Message msg = sm.obtainMessage(HeadsetClientStateMachine.SEND_DTMF);
        msg.arg1 = code;
        sm.sendMessage(msg);
        return true;
    }

    public boolean getLastVoiceTagNumber(BluetoothDevice device) {
        return false;
    }

    public List<BluetoothHeadsetClientCall> getCurrentCalls(BluetoothDevice device) {

        HeadsetClientStateMachine sm = getStateMachine(device);
        if (sm == null) {
            Log.e(TAG, "Cannot allocate SM for device " + device);
            return null;
        }

        int connectionState = sm.getConnectionState(device);
        if (connectionState != BluetoothProfile.STATE_CONNECTED) {
            return null;
        }
        return sm.getCurrentCalls();
    }

    public boolean explicitCallTransfer(BluetoothDevice device) {

        HeadsetClientStateMachine sm = getStateMachine(device);
        if (sm == null) {
            Log.e(TAG, "Cannot allocate SM for device " + device);
            return false;
        }

        int connectionState = sm.getConnectionState(device);
        if (connectionState != BluetoothProfile.STATE_CONNECTED
                && connectionState != BluetoothProfile.STATE_CONNECTING) {
            return false;
        }
        Message msg = sm.obtainMessage(HeadsetClientStateMachine.EXPLICIT_CALL_TRANSFER);
        sm.sendMessage(msg);
        return true;
    }

    /** Send vendor AT command. */
    public boolean sendVendorAtCommand(BluetoothDevice device, int vendorId, String atCommand) {

        HeadsetClientStateMachine sm = getStateMachine(device);
        if (sm == null) {
            Log.e(TAG, "Cannot allocate SM for device " + device);
            return false;
        }

        int connectionState = sm.getConnectionState(device);
        if (connectionState != BluetoothProfile.STATE_CONNECTED) {
            return false;
        }

        Message msg = sm.obtainMessage(HeadsetClientStateMachine.SEND_VENDOR_AT_COMMAND,
                                       vendorId, 0, atCommand);
        sm.sendMessage(msg);
        return true;
    }

    public Bundle getCurrentAgEvents(BluetoothDevice device) {

        HeadsetClientStateMachine sm = getStateMachine(device);
        if (sm == null) {
            Log.e(TAG, "Cannot allocate SM for device " + device);
            return null;
        }

        int connectionState = sm.getConnectionState(device);
        if (connectionState != BluetoothProfile.STATE_CONNECTED) {
            return null;
        }
        return sm.getCurrentAgEvents();
    }

    public boolean isHeadsetClientCallPresent() {
        boolean mIsInCall = false;
        for (HeadsetClientStateMachine sm : mStateMachineMap.values()) {
            if (sm != null) {
               mIsInCall |= sm.IsInCall();
            }
        }
        return mIsInCall;
    }

    public Bundle getCurrentAgFeatures(BluetoothDevice device) {

        HeadsetClientStateMachine sm = getStateMachine(device);
        if (sm == null) {
            Log.e(TAG, "Cannot allocate SM for device " + device);
            return null;
        }
        int connectionState = sm.getConnectionState(device);
        if (connectionState != BluetoothProfile.STATE_CONNECTED) {
            return null;
        }
        return sm.getCurrentAgFeatures();
    }

    public void registerHeadsetClientScoCallback(IBluetoothHeadsetClientScoCallback callback) {
        if (mHeadsetClientScoCallbacks != null) {
            mHeadsetClientScoCallbacks.register(callback);
        }
    }

    public void unregisterHeadsetClientScoCallback(IBluetoothHeadsetClientScoCallback callback) {
        if (mHeadsetClientScoCallbacks != null) {
            mHeadsetClientScoCallbacks.unregister(callback);
        }
    }

    // Handle messages from native (JNI) to java
    public void messageFromNative(StackEvent stackEvent) {
        HeadsetClientStateMachine sm = getStateMachine(stackEvent.device);
        if (sm == null) {
            Log.w(TAG, "No SM found for event " + stackEvent);
        }

        sm.sendMessage(StackEvent.STACK_EVENT, stackEvent);
    }

    // State machine management
    private synchronized HeadsetClientStateMachine getStateMachine(BluetoothDevice device) {
        if (device == null) {
            Log.e(TAG, "getStateMachine failed: Device cannot be null");
            return null;
        }

        HeadsetClientStateMachine sm = mStateMachineMap.get(device);
        if (sm != null) {
            if (DBG) {
                Log.d(TAG, "Found SM for device " + device);
            }
            return sm;
        }

        // There is a possibility of a DOS attack if someone populates here with a lot of fake
        // BluetoothAddresses. If it so happens instead of blowing up we can atleast put a limit on
        // how long the attack would survive
        if (mStateMachineMap.keySet().size() > MAX_STATE_MACHINES_POSSIBLE) {
            Log.e(TAG, "Max state machines reached, possible DOS attack "
                    + MAX_STATE_MACHINES_POSSIBLE);
            return null;
        }

        // Allocate a new SM
        Log.d(TAG, "Creating a new state machine");
        sm = mSmFactory.make(this, mSmThread, mNativeInterface);
        mStateMachineMap.put(device, sm);
        return sm;
    }

    // Check if any of the state machines have routed the SCO audio stream.
    synchronized boolean isScoRouted() {
        for (Map.Entry<BluetoothDevice, HeadsetClientStateMachine> entry : mStateMachineMap
                .entrySet()) {
            if (entry.getValue() != null) {
                int audioState = entry.getValue().getAudioState(entry.getKey());
                if (audioState == BluetoothHeadsetClient.STATE_AUDIO_CONNECTED) {
                    if (DBG) {
                        Log.d(TAG, "Device " + entry.getKey() + " audio state " + audioState
                                + " Connected");
                    }
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public synchronized void dump(StringBuilder sb) {
        super.dump(sb);
        for (HeadsetClientStateMachine sm : mStateMachineMap.values()) {
            if (sm != null) {
                sm.dump(sb);
            }
        }
    }

    // For testing
    protected synchronized Map<BluetoothDevice, HeadsetClientStateMachine> getStateMachineMap() {
        return mStateMachineMap;
    }

    protected void setSMFactory(HeadsetClientStateMachineFactory factory) {
        mSmFactory = factory;
    }

    protected AudioManager getAudioManager() {
        return mAudioManager;
    }
}
