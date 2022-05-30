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
import android.os.Message;
import android.os.SystemProperties;
import android.util.Log;

import com.android.bluetooth.R;
import com.android.bluetooth.telephony.BluetoothInCallService;
import com.android.bluetooth.statemachine.State;
import com.android.bluetooth.statemachine.StateMachine;
import com.android.bluetooth.Utils;


/**
 * This state machine handles Bluetooth Adapter State.
 * Stable States:
 *      {@link OffState}: Initial State
 *      {@link BleOnState} : Bluetooth Low Energy, Including GATT, is on
 *      {@link OnState} : Bluetooth is on (All supported profiles)
 *
 * Transition States:
 *      {@link TurningBleOnState} : OffState to BleOnState
 *      {@link TurningBleOffState} : BleOnState to OffState
 *      {@link TurningOnState} : BleOnState to OnState
 *      {@link TurningOffState} : OnState to BleOnState
 *
 *        +------   Off  <-----+
 *        |                    |
 *        v                    |
 * TurningBleOn   TO--->   TurningBleOff
 *        |                  ^ ^
 *        |                  | |
 *        +----->        ----+ |
 *                 BleOn       |
 *        +------        <---+ O
 *        v                  | T
 *    TurningOn  TO---->  TurningOff
 *        |                    ^
 *        |                    |
 *        +----->   On   ------+
 *
 */

final class AdapterState extends StateMachine {
    private static final boolean DBG = true;
    private static final String TAG = AdapterState.class.getSimpleName();

    static final int USER_TURN_ON = 1;
    static final int USER_TURN_OFF = 2;
    static final int BLE_TURN_ON = 3;
    static final int BLE_TURN_OFF = 4;
    static final int BREDR_STARTED = 5;
    static final int BREDR_STOPPED = 6;
    static final int BLE_STARTED = 7;
    static final int BLE_STOPPED = 8;
    static final int BREDR_START_TIMEOUT = 9;
    static final int BREDR_STOP_TIMEOUT = 10;
    static final int BLE_STOP_TIMEOUT = 11;
    static final int BLE_START_TIMEOUT = 12;
    static final int NEW_ADAPTER_STATE_CHANGED = 13;
    static final int NEW_ADAPTER_ENABLE_TIMEOUT = 14;
    static final int NEW_ADAPTER_DISABLE_TIMEOUT = 15;

    static final int BLE_START_TIMEOUT_DELAY = 4000;
    static final int BLE_STOP_TIMEOUT_DELAY = 4000;
    static final int BREDR_START_TIMEOUT_DELAY = 4000;
    static final int BREDR_STOP_TIMEOUT_DELAY = 4000;
    static final int NEW_ADAPTER_ENABLE_TIMEOUT_DELAY = 2000;
    static final int NEW_ADAPTER_DISABLE_TIMEOUT_DELAY = 2000;

    private AdapterService mAdapterService;
    private TurningOnState mTurningOnState = new TurningOnState();
    private TurningBleOnState mTurningBleOnState = new TurningBleOnState();
    private TurningOffState mTurningOffState = new TurningOffState();
    private TurningBleOffState mTurningBleOffState = new TurningBleOffState();
    private OnState mOnState = new OnState();
    private OffState mOffState = new OffState();
    private BleOnState mBleOnState = new BleOnState();
    private NewAdapterState mNewAdapterState = new NewAdapterState();

    private int mPrevState = BluetoothAdapter.STATE_OFF;

    private boolean mPendingOn = false;
    private boolean mPendingOff = false;
    private final int mAdapterIndex;

    private AdapterState(AdapterService service) {
        super(TAG);
        addState(mOnState);
        addState(mBleOnState);
        addState(mOffState);
        addState(mTurningOnState);
        addState(mTurningOffState);
        addState(mTurningBleOnState);
        addState(mTurningBleOffState);
        if (isDualAdapterMode()) {
            addState(mNewAdapterState);
        }
        mAdapterService = service;
        mAdapterIndex = AdapterUtil.getAdapterIndex();
        setInitialState(mOffState);
    }

    private String messageString(int message) {
        switch (message) {
            case BLE_TURN_ON: return "BLE_TURN_ON";
            case USER_TURN_ON: return "USER_TURN_ON";
            case BREDR_STARTED: return "BREDR_STARTED";
            case BLE_STARTED: return "BLE_STARTED";
            case USER_TURN_OFF: return "USER_TURN_OFF";
            case BLE_TURN_OFF: return "BLE_TURN_OFF";
            case BLE_STOPPED: return "BLE_STOPPED";
            case BREDR_STOPPED: return "BREDR_STOPPED";
            case BLE_START_TIMEOUT: return "BLE_START_TIMEOUT";
            case BLE_STOP_TIMEOUT: return "BLE_STOP_TIMEOUT";
            case BREDR_START_TIMEOUT: return "BREDR_START_TIMEOUT";
            case BREDR_STOP_TIMEOUT: return "BREDR_STOP_TIMEOUT";
            case NEW_ADAPTER_STATE_CHANGED: return "NEW_ADAPTER_STATE_CHANGED";
            case NEW_ADAPTER_ENABLE_TIMEOUT: return "NEW_ADAPTER_ENABLE_TIMEOUT";
            case NEW_ADAPTER_DISABLE_TIMEOUT: return "NEW_ADAPTER_DISABLE_TIMEOUT";
            default: return "Unknown message (" + message + ")";
        }
    }

    public static AdapterState make(AdapterService service) {
        Log.d(TAG, "make() - Creating AdapterState");
        AdapterState as = new AdapterState(service);
        as.start();
        return as;
    }

    public void doQuit() {
        quitNow();
    }

    private void cleanup() {
        if (mAdapterService != null) {
            mAdapterService = null;
        }
    }

    private boolean isDualAdapterMode() {
        // Dual adapter mode is only valid with default adapter.
        return AdapterUtil.isDualAdapterMode() &&
                AdapterUtil.isAdapterDefault();
    }

    @Override
    protected void onQuitting() {
        cleanup();
    }

    @Override
    protected String getLogRecString(Message msg) {
        return messageString(msg.what);
    }

    private abstract class BaseAdapterState extends State {

        abstract int getStateValue();

        @Override
        public void enter() {
            int currState = getStateValue();
            infoLog("entered ");
            mAdapterService.updateAdapterState(mPrevState, currState);
            mPrevState = currState;
        }

        void infoLog(String msg) {
            if (DBG) {
                Log.i(TAG + mAdapterIndex, BluetoothAdapter.nameForState(getStateValue()) + " : " + msg);
            }
        }

        void errorLog(String msg) {
            Log.e(TAG + mAdapterIndex, BluetoothAdapter.nameForState(getStateValue()) + " : " + msg);
        }
    }

    private class OffState extends BaseAdapterState {

        @Override
        int getStateValue() {
            return BluetoothAdapter.STATE_OFF;
        }

        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case BLE_TURN_ON:
                    transitionTo(mTurningBleOnState);
                    break;

                default:
                    infoLog("Unhandled message - " + messageString(msg.what));
                    return false;
            }
            return true;
        }
    }

    private class BleOnState extends BaseAdapterState {

        @Override
        int getStateValue() {
            return BluetoothAdapter.STATE_BLE_ON;
        }

        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case USER_TURN_ON:
                    transitionTo(mTurningOnState);
                    break;

                case BLE_TURN_OFF:
                    transitionTo(mTurningBleOffState);
                    break;

                default:
                    infoLog("Unhandled message - " + messageString(msg.what));
                    return false;
            }
            return true;
        }
    }

    private class OnState extends BaseAdapterState {

        @Override
        int getStateValue() {
            return BluetoothAdapter.STATE_ON;
        }

        @Override
        public void enter() {
            super.enter();
            mAdapterService.enableBluetoothInCallService(true);

            // This is to ensure property value is reset in followng corner case
            // Bluetooth proecess is crashed when SCO is connected
            SystemProperties.set(Utils.PROP_SCO_CONNECTION_STATUS, "false");
        }

        @Override
        public void exit() {
            BluetoothInCallService bluetoothInCallService = BluetoothInCallService.getInstance();
            if (bluetoothInCallService == null) {
                mAdapterService.enableBluetoothInCallService(false);
            }
            super.exit();
        }

        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case USER_TURN_OFF:
                    transitionTo(mTurningOffState);
                    break;

                default:
                    infoLog("Unhandled message - " + messageString(msg.what));
                    return false;
            }
            return true;
        }
    }

    private class TurningBleOnState extends BaseAdapterState {

        @Override
        int getStateValue() {
            return BluetoothAdapter.STATE_BLE_TURNING_ON;
        }

        @Override
        public void enter() {
            super.enter();
            sendMessageDelayed(BLE_START_TIMEOUT, BLE_START_TIMEOUT_DELAY);
            mAdapterService.bringUpBle();
        }

        @Override
        public void exit() {
            removeMessages(BLE_START_TIMEOUT);
            super.exit();
        }

        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case BLE_STARTED:
                    transitionTo(mBleOnState);
                    break;

                case BLE_START_TIMEOUT:
                    errorLog(messageString(msg.what));
                    transitionTo(mTurningBleOffState);
                    break;

                default:
                    infoLog("Unhandled message - " + messageString(msg.what));
                    return false;
            }
            return true;
        }
    }

    private class TurningOnState extends BaseAdapterState {

        @Override
        int getStateValue() {
            return BluetoothAdapter.STATE_TURNING_ON;
        }

        @Override
        public void enter() {
            super.enter();
            sendMessageDelayed(BREDR_START_TIMEOUT, BREDR_START_TIMEOUT_DELAY);
            mAdapterService.startProfileServices();
        }

        @Override
        public void exit() {
            removeMessages(BREDR_START_TIMEOUT);
            super.exit();
        }

        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case BREDR_STARTED:
                    handleOn();
                    break;

                case BREDR_START_TIMEOUT:
                    errorLog(messageString(msg.what));
                    transitionTo(mTurningOffState);
                    break;

                default:
                    infoLog("Unhandled message - " + messageString(msg.what));
                    return false;
            }
            return true;
        }

        private void handleOn() {
            if (isDualAdapterMode()) {
                mPendingOn = true;
                transitionTo(mNewAdapterState);
            } else {
                transitionTo(mOnState);
            }
        }
    }

    private class TurningOffState extends BaseAdapterState {

        @Override
        int getStateValue() {
            return BluetoothAdapter.STATE_TURNING_OFF;
        }

        @Override
        public void enter() {
            super.enter();
            sendMessageDelayed(BREDR_STOP_TIMEOUT, BREDR_STOP_TIMEOUT_DELAY);
            mAdapterService.stopProfileServices();
        }

        @Override
        public void exit() {
            removeMessages(BREDR_STOP_TIMEOUT);
            super.exit();
        }

        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case BREDR_STOPPED:
                    transitionTo(mBleOnState);
                    break;

                case BREDR_STOP_TIMEOUT:
                    errorLog(messageString(msg.what));
                    transitionTo(mTurningBleOffState);
                    break;

                default:
                    infoLog("Unhandled message - " + messageString(msg.what));
                    return false;
            }
            return true;
        }
    }

    private class TurningBleOffState extends BaseAdapterState {

        @Override
        int getStateValue() {
            return BluetoothAdapter.STATE_BLE_TURNING_OFF;
        }

        @Override
        public void enter() {
            super.enter();
            mAdapterService.enableBluetoothInCallService(false);
            sendMessageDelayed(BLE_STOP_TIMEOUT, BLE_STOP_TIMEOUT_DELAY);
            mAdapterService.bringDownBle();
        }

        @Override
        public void exit() {
            removeMessages(BLE_STOP_TIMEOUT);
            super.exit();
        }

        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case BLE_STOPPED:
                    handleOff();
                    break;

                case BLE_STOP_TIMEOUT:
                    errorLog(messageString(msg.what));
                    transitionTo(mOffState);
                    break;

                default:
                    infoLog("Unhandled message - " + messageString(msg.what));
                    return false;
            }
            return true;
        }

        private void handleOff() {
            if (isDualAdapterMode()) {
                mPendingOff = true;
                transitionTo(mNewAdapterState);
            } else {
                transitionTo(mOffState);
            }
        }
    }

    private class NewAdapterState extends BaseAdapterState {
        private static final String STATE_NAME = "NEW_ADAPTER_STATE";

        @Override
        int getStateValue() {
            // NOT matched with any state in default adapter
            return BluetoothAdapter.ERROR;
        }

        @Override
        public void enter() {
            infoLog("entered ");
            int state = AdapterExt.getState();
            if (AdapterExt.isOn(state)) {
                handleStateChanged(true);
            } else if (AdapterExt.isOff(state)) {
                handleStateChanged(false);
            }

            if (mPendingOn) {
                sendMessageDelayed(NEW_ADAPTER_ENABLE_TIMEOUT,
                        NEW_ADAPTER_ENABLE_TIMEOUT_DELAY);
            } else if (mPendingOff) {
                sendMessageDelayed(NEW_ADAPTER_DISABLE_TIMEOUT,
                        NEW_ADAPTER_DISABLE_TIMEOUT_DELAY);
            }
        }

        @Override
        public void exit() {
            infoLog("exited ");
            removeMessages(NEW_ADAPTER_ENABLE_TIMEOUT);
            removeMessages(NEW_ADAPTER_DISABLE_TIMEOUT);
        }

        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case NEW_ADAPTER_STATE_CHANGED:
                    handleStateChanged(msg.arg1 == 1);
                    break;

                case NEW_ADAPTER_ENABLE_TIMEOUT:
                    errorLog(messageString(msg.what));
                    handleEnableTimeout();
                    break;

                case NEW_ADAPTER_DISABLE_TIMEOUT:
                    errorLog(messageString(msg.what));
                    handleDisableTimeout();
                    break;

                default:
                    infoLog("Unhandled message - " + messageString(msg.what));
                    return false;
            }
            return true;
        }

        private void continueOn() {
            if (mPendingOn) {
                mPendingOn = false;
                transitionTo(mOnState);
            }
        }

        private void continueOff() {
            if (mPendingOff) {
                mPendingOff = false;
                transitionTo(mOffState);
            }
        }

        private void handleStateChanged(boolean isOn) {
            infoLog("handleStateChanged isOn: " + String.valueOf(isOn));

            if (isOn) {
                // If default adapter is off, still disable new adapter
                // although it's on. This is to guarantee that new adapter
                // is kept same off state with default adapter.
                if (mPendingOff) {
                    AdapterExt.disable();
                } else {
                    // Both 2 adapter are enabled. Notify On state to upper layer.
                    continueOn();
                }
            } else {
                // Transit to Off state for default adapter, since new
                // adapter is turned off.
                continueOff();
            }
        }

        private void handleEnableTimeout() {
            // Transit to On state for default adapter, although new adapter
            // can't be enabled in timeout.
            continueOn();
        }

        private void handleDisableTimeout() {
            // Transit to Off state for default adapter, although new adapter
            // can't be disabled in timeout.
            continueOff();
        }

        @Override
        void infoLog(String msg) {
            if (DBG) {
                Log.i(TAG + mAdapterIndex, STATE_NAME + " : " + msg);
            }
        }

        @Override
        void errorLog(String msg) {
            Log.e(TAG + mAdapterIndex, STATE_NAME + " : " + msg);
        }
    }
}
