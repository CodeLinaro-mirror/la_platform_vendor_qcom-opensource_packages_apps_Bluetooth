/*
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
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.CallLog;
import android.provider.CallLog.Calls;
import android.provider.ContactsContract;
import android.util.Log;

import com.android.bluetooth.BluetoothObexTransport;
import com.android.bluetooth.R;
import com.android.vcard.VCardEntry;

import java.io.IOException;
import java.util.HashMap;
import java.util.ArrayList;

import javax.obex.ClientSession;
import javax.obex.HeaderSet;
import javax.obex.ResponseCodes;

/* Bluetooth/pbapclient/PbapClientConnectionHandler is responsible
 * for connecting, disconnecting and downloading contacts from the
 * PBAP PSE when commanded. It receives all direction from the
 * controlling state machine.
 */
class PbapClientConnectionHandler extends Handler {
    static final String TAG = "PbapClientConnHandler";
    static final boolean DBG = Utils.DBG;
    static final boolean VDBG = Utils.VDBG;
    static final int MSG_CONNECT = 1;
    static final int MSG_DISCONNECT = 2;
    static final int MSG_DOWNLOAD = 3;
    static final int MSG_DOWNLOAD_EXT = 0xF0;   // Vendor extension
    static final int MSG_PULL_VCARD_LISTING = 0xF1;
    static final int MSG_PULL_VCARD_ENTRY = 0xF2;
    static final int MSG_SET_PHONEBOOK = 0xF3;

    // The following constants are pulled from the Bluetooth Phone Book Access Profile specification
    // 1.1
    private static final byte[] PBAP_TARGET = new byte[]{
            0x79,
            0x61,
            0x35,
            (byte) 0xf0,
            (byte) 0xf0,
            (byte) 0xc5,
            0x11,
            (byte) 0xd8,
            0x09,
            0x66,
            0x08,
            0x00,
            0x20,
            0x0c,
            (byte) 0x9a,
            0x66
    };

    private static final int PBAP_FEATURE_DEFAULT_IMAGE_FORMAT = 0x00000200;
    private static final int PBAP_FEATURE_BROWSING = 0x00000002;
    private static final int PBAP_FEATURE_DOWNLOADING = 0x00000001;

    private static final long PBAP_FILTER_VERSION = 1 << 0;
    private static final long PBAP_FILTER_FN = 1 << 1;
    private static final long PBAP_FILTER_N = 1 << 2;
    private static final long PBAP_FILTER_PHOTO = 1 << 3;
    private static final long PBAP_FILTER_ADR = 1 << 5;
    private static final long PBAP_FILTER_TEL = 1 << 7;
    private static final long PBAP_FILTER_EMAIL = 1 << 8;
    private static final long PBAP_FILTER_NICKNAME = 1 << 23;

    private static final int PBAP_SUPPORTED_FEATURE =
            PBAP_FEATURE_DEFAULT_IMAGE_FORMAT | PBAP_FEATURE_BROWSING | PBAP_FEATURE_DOWNLOADING;
    private static final long PBAP_REQUESTED_FIELDS =
            PBAP_FILTER_VERSION | PBAP_FILTER_FN | PBAP_FILTER_N | PBAP_FILTER_PHOTO
                    | PBAP_FILTER_ADR | PBAP_FILTER_EMAIL | PBAP_FILTER_TEL | PBAP_FILTER_NICKNAME;
    private static final int L2CAP_INVALID_PSM = -1;

    public static final String ROOT_PATH = "/root";
    public static final String PB_PATH = "telecom/pb.vcf";
    public static final String MCH_PATH = "telecom/mch.vcf";
    public static final String ICH_PATH = "telecom/ich.vcf";
    public static final String OCH_PATH = "telecom/och.vcf";

    public static final int PBAP_V1_2 = 0x0102;
    public static final byte VCARD_TYPE_21 = 0;
    public static final byte VCARD_TYPE_30 = 1;

    private Account mAccount;
    private AccountManager mAccountManager;
    private BluetoothSocket mSocket;
    private final BluetoothAdapter mAdapter;
    private final BluetoothDevice mDevice;
    // PSE SDP Record for current device.
    private SdpPseRecord mPseRec = null;
    private ClientSession mObexSession;
    private Context mContext;
    private BluetoothPbapObexAuthenticator mAuth = null;
    private final PbapClientStateMachine mPbapClientStateMachine;
    private boolean mAccountCreated;
    private BluetoothPbapRequest mCurrentRequest = null;
    private final Object mLock = new Object();

    PbapClientConnectionHandler(Looper looper, Context context, PbapClientStateMachine stateMachine,
            BluetoothDevice device) {
        super(looper);
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mDevice = device;
        mContext = context;
        mPbapClientStateMachine = stateMachine;
        mAuth = new BluetoothPbapObexAuthenticator(this);
        mAccountManager = AccountManager.get(mPbapClientStateMachine.getContext());
        mAccount =
                new Account(mDevice.getAddress(), mContext.getString(R.string.pbap_account_type));
    }

    /**
     * Constructs PCEConnectionHandler object
     *
     * @param Builder To build  BluetoothPbapClientHandler Instance.
     */
    PbapClientConnectionHandler(Builder pceHandlerbuild) {
        super(pceHandlerbuild.mLooper);
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mDevice = pceHandlerbuild.mDevice;
        mContext = pceHandlerbuild.mContext;
        mPbapClientStateMachine = pceHandlerbuild.mClientStateMachine;
        mAuth = new BluetoothPbapObexAuthenticator(this);
        mAccountManager = AccountManager.get(mPbapClientStateMachine.getContext());
        mAccount =
                new Account(mDevice.getAddress(), mContext.getString(R.string.pbap_account_type));
    }

    public static class Builder {

        private Looper mLooper;
        private Context mContext;
        private BluetoothDevice mDevice;
        private PbapClientStateMachine mClientStateMachine;

        public Builder setLooper(Looper loop) {
            this.mLooper = loop;
            return this;
        }

        public Builder setClientSM(PbapClientStateMachine clientStateMachine) {
            this.mClientStateMachine = clientStateMachine;
            return this;
        }

        public Builder setRemoteDevice(BluetoothDevice device) {
            this.mDevice = device;
            return this;
        }

        public Builder setContext(Context context) {
            this.mContext = context;
            return this;
        }

        public PbapClientConnectionHandler build() {
            PbapClientConnectionHandler pbapClientHandler = new PbapClientConnectionHandler(this);
            return pbapClientHandler;
        }

    }

    @Override
    public void handleMessage(Message msg) {
        if (DBG) {
            Log.d(TAG, "Handling Message = " + msg.what);
        }
        switch (msg.what) {
            case MSG_CONNECT:
                mPseRec = (SdpPseRecord) msg.obj;
                /* To establish a connection, first open a socket and then create an OBEX session */
                if (connectSocket()) {
                    if (DBG) {
                        Log.d(TAG, "Socket connected");
                    }
                } else {
                    Log.w(TAG, "Socket CONNECT Failure ");
                    mPbapClientStateMachine.sendMessage(
                            PbapClientStateMachine.MSG_CONNECTION_FAILED);
                    return;
                }

                if (connectObexSession()) {
                    mPbapClientStateMachine.sendMessage(
                            PbapClientStateMachine.MSG_CONNECTION_COMPLETE);
                } else {
                    mPbapClientStateMachine.sendMessage(
                            PbapClientStateMachine.MSG_CONNECTION_FAILED);
                }
                break;

            case MSG_DISCONNECT:
                if (DBG) {
                    Log.d(TAG, "Starting Disconnect");
                }
                try {
                    if (mObexSession != null) {
                        if (DBG) {
                            Log.d(TAG, "obexSessionDisconnect" + mObexSession);
                        }
                        mObexSession.disconnect(null);
                        mObexSession.close();
                    }

                    if (DBG) {
                        Log.d(TAG, "Closing Socket");
                    }
                    closeSocket();
                } catch (IOException e) {
                    Log.w(TAG, "DISCONNECT Failure ", e);
                }
                if (DBG) {
                    Log.d(TAG, "Completing Disconnect");
                }
                removeAccount(mAccount);
                removeCallLog(mAccount);

                mPbapClientStateMachine.sendMessage(PbapClientStateMachine.MSG_CONNECTION_CLOSED);
                break;

            case MSG_DOWNLOAD:
                try {
                    mAccountCreated = addAccount(mAccount);
                    if (!mAccountCreated) {
                        Log.e(TAG, "Account creation failed.");
                        return;
                    }
                    // Start at contact 1 to exclued Owner Card PBAP 1.1 sec 3.1.5.2
                    BluetoothPbapRequestPullPhoneBook request =
                            new BluetoothPbapRequestPullPhoneBook(PB_PATH, mAccount,
                                    PBAP_REQUESTED_FIELDS, VCARD_TYPE_30, 0, 1);
                    request.execute(mObexSession);
                    PhonebookPullRequest processor =
                            new PhonebookPullRequest(mPbapClientStateMachine.getContext(),
                                    mAccount);
                    processor.setResults(request.getList());
                    processor.onPullComplete();
                    HashMap<String, Integer> callCounter = new HashMap<>();
                    downloadCallLog(MCH_PATH, callCounter);
                    downloadCallLog(ICH_PATH, callCounter);
                    downloadCallLog(OCH_PATH, callCounter);
                } catch (IOException e) {
                    Log.w(TAG, "DOWNLOAD_CONTACTS Failure" + e.toString());
                }
                break;

            case MSG_DOWNLOAD_EXT:
                handlePullPhonebook((Bundle) msg.obj);
                break;

            case MSG_PULL_VCARD_LISTING:
                handlePullVcardListing((Bundle) msg.obj);
                break;

            case MSG_PULL_VCARD_ENTRY:
                handlePullVcardEntry((Bundle) msg.obj);
                break;

            case MSG_SET_PHONEBOOK:
                handleSetPhonebook((Bundle) msg.obj);
                break;

            default:
                Log.w(TAG, "Received Unexpected Message");
        }
        return;
    }

    /* Utilize SDP, if available, to create a socket connection over L2CAP, RFCOMM specified
     * channel, or RFCOMM default channel. */
    private synchronized boolean connectSocket() {
        try {
            /* Use BluetoothSocket to connect */
            if (mPseRec == null) {
                // BackWardCompatability: Fall back to create RFCOMM through UUID.
                if (VDBG) Log.v(TAG, "connectSocket: UUID: " + BluetoothUuid.PBAP_PSE.getUuid());
                mSocket =
                        mDevice.createRfcommSocketToServiceRecord(BluetoothUuid.PBAP_PSE.getUuid());
            } else if (mPseRec.getL2capPsm() != L2CAP_INVALID_PSM) {
                if (VDBG) Log.v(TAG, "connectSocket: PSM: " + mPseRec.getL2capPsm());
                mSocket = mDevice.createL2capSocket(mPseRec.getL2capPsm());
            } else {
                if (VDBG) Log.v(TAG, "connectSocket: channel: " + mPseRec.getRfcommChannelNumber());
                mSocket = mDevice.createRfcommSocket(mPseRec.getRfcommChannelNumber());
            }

            if (mSocket != null) {
                mSocket.connect();
                return true;
            } else {
                Log.w(TAG, "Could not create socket");
            }
        } catch (IOException e) {
            Log.e(TAG, "Error while connecting socket", e);
        }
        return false;
    }

    /* Connect an OBEX session over the already connected socket.  First establish an OBEX Transport
     * abstraction, then establish a Bluetooth Authenticator, and finally issue the connect call */
    private boolean connectObexSession() {
        boolean connectionSuccessful = false;

        try {
            if (VDBG) {
                Log.v(TAG, "Start Obex Client Session");
            }
            BluetoothObexTransport transport = new BluetoothObexTransport(mSocket);
            mObexSession = new ClientSession(transport);
            mObexSession.setAuthenticator(mAuth);

            HeaderSet connectionRequest = new HeaderSet();
            connectionRequest.setHeader(HeaderSet.TARGET, PBAP_TARGET);

            if (mPseRec != null) {
                if (DBG) {
                    Log.d(TAG, "Remote PbapSupportedFeatures " + mPseRec.getSupportedFeatures());
                }

                ObexAppParameters oap = new ObexAppParameters();

                if (mPseRec.getProfileVersion() >= PBAP_V1_2) {
                    oap.add(BluetoothPbapRequest.OAP_TAGID_PBAP_SUPPORTED_FEATURES,
                            PBAP_SUPPORTED_FEATURE);
                }

                oap.addToHeaderSet(connectionRequest);
            }
            HeaderSet connectionResponse = mObexSession.connect(connectionRequest);

            connectionSuccessful =
                    (connectionResponse.getResponseCode() == ResponseCodes.OBEX_HTTP_OK);
            if (DBG) {
                Log.d(TAG, "Success = " + Boolean.toString(connectionSuccessful));
            }
        } catch (IOException e) {
            Log.w(TAG, "CONNECT Failure " + e.toString());
            closeSocket();
        }
        return connectionSuccessful;
    }

    public void abort() {
        // Perform forced cleanup, it is ok if the handler throws an exception this will free the
        // handler to complete what it is doing and finish with cleanup.
        closeSocket();
        this.getLooper().getThread().interrupt();
    }

    private synchronized void closeSocket() {
        try {
            if (mSocket != null) {
                if (DBG) {
                    Log.d(TAG, "Closing socket" + mSocket);
                }
                mSocket.close();
                mSocket = null;
            }
        } catch (IOException e) {
            Log.e(TAG, "Error when closing socket", e);
            mSocket = null;
        }
    }

    void downloadCallLog(String path, HashMap<String, Integer> callCounter) {
        try {
            BluetoothPbapRequestPullPhoneBook request =
                    new BluetoothPbapRequestPullPhoneBook(path, mAccount, 0, VCARD_TYPE_30, 0, 0);
            request.execute(mObexSession);
            CallLogPullRequest processor =
                    new CallLogPullRequest(mPbapClientStateMachine.getContext(), path,
                        callCounter, mAccount);
            processor.setResults(request.getList());
            processor.onPullComplete();
        } catch (IOException e) {
            Log.w(TAG, "Download call log failure");
        }
    }

    private boolean addAccount(Account account) {
        if (mAccountManager.addAccountExplicitly(account, null, null)) {
            if (DBG) {
                Log.d(TAG, "Added account " + mAccount);
            }
            return true;
        }
        return false;
    }

    private void removeAccount(Account account) {
        if (mAccountManager.removeAccountExplicitly(account)) {
            if (DBG) {
                Log.d(TAG, "Removed account " + account);
            }
        } else {
            Log.e(TAG, "Failed to remove account " + mAccount);
        }
    }

    private void removeCallLog(Account account) {
        try {
            // need to check call table is exist ?
            if (mContext.getContentResolver() == null) {
                if (DBG) {
                    Log.d(TAG, "CallLog ContentResolver is not found");
                }
                return;
            }
            mContext.getContentResolver().delete(CallLog.Calls.CONTENT_URI,
                    Calls.PHONE_ACCOUNT_ID + "=?", new String[]{mAccount.name});
        } catch (IllegalArgumentException e) {
            Log.d(TAG, "Call Logs could not be deleted, they may not exist yet.");
        }
    }

    public void abortRequest() {
        boolean handled = true;
        synchronized (mLock) {
            if (mCurrentRequest != null) {
                if ((mCurrentRequest instanceof BluetoothPbapRequestPullPhoneBook) ||
                    (mCurrentRequest instanceof BluetoothPbapRequestPullPhoneBookSize) ||
                    (mCurrentRequest instanceof BluetoothPbapRequestPullVcardListing) ||
                    (mCurrentRequest instanceof BluetoothPbapRequestPullVcardEntry)) {
                    if (DBG) Log.d(TAG, "Abort PBAP request");
                    mCurrentRequest.abort();
                } else {
                    // Unknown request
                    Log.e(TAG, "Unknown PBAP request");
                    handled = false;
                }
                mCurrentRequest = null;
            } else {
                handled = false;
            }
        }

        notifyCustomActionResult(PbapClientHandler.CUSTOM_ACTION_ABORT,
            handled ? PbapClientHandler.RESULT_SUCCESS : PbapClientHandler.RESULT_ERROR);
    }

    private void storeRequest(BluetoothPbapRequest request) {
        synchronized (mLock) {
            mCurrentRequest = request;
        }
    }

    private void handlePullPhonebook(Bundle extras) {
        String pbName = extras.getString(PbapClientHandler.KEY_PB_NAME);
        long filter = extras.getLong(PbapClientHandler.KEY_FILTER);
        byte format = extras.getByte(PbapClientHandler.KEY_VCARD_TYPE);
        int maxListCount = extras.getInt(PbapClientHandler.KEY_MAX_LIST_COUNT);
        int listStartOffset = extras.getInt(PbapClientHandler.KEY_LIST_START_OFFSET);

        if (DBG) {
            Log.d(TAG, "handlePullPhonebook pbName: " + pbName + ", filter: " +
                Long.toHexString(filter) + ", format: " + format + ", maxListCount: " +
                maxListCount + ", listStartOffset: " + listStartOffset);
        }

        if ((pbName == null) || pbName.isEmpty()) {
            // Invalid phonebook name.
            notifyCustomActionResult(PbapClientHandler.CUSTOM_ACTION_PULL_PHONEBOOK,
                PbapClientHandler.RESULT_INVALID_PARAMETER, 0, 0);
            return;
        }

        if (maxListCount == 0) {
            // Get phonebook size
            handlePullPhonebookSize(extras);
            return;
        }

        try {
            BluetoothPbapRequestPullPhoneBook request =
                new BluetoothPbapRequestPullPhoneBook(pbName, mAccount,
                    filter, format, maxListCount, listStartOffset);
            storeRequest(request);

            request.execute(mObexSession);

            if (DBG) {
                Log.d(TAG, "handlePullPhonebook Found size: " + request.getCount());
            }

            // Delete old contacts
            mContext.getContentResolver().delete(ContactsContract.RawContacts.CONTENT_URI, null, null);
            mContext.getContentResolver().delete(CallLog.Calls.CONTENT_URI, null, null);

            // Store phonebook into Contact DB if it's not to get phonebook size.
            PhonebookPullRequest processor =
                new PhonebookPullRequest(mPbapClientStateMachine.getContext(), mAccount);
            processor.setResults(request.getList());
            processor.onPullComplete();

            processPullPhonebookResp(request);
        } catch (IOException e) {
            Log.w(TAG, "Fail to pull phonebook" + e.toString());
            processPullPhonebookResp(null);
        }
    }

    private void handlePullPhonebookSize(Bundle extras) {
        String pbName = extras.getString(PbapClientHandler.KEY_PB_NAME);

        if (DBG) {
            Log.d(TAG, "handlePullPhonebookSize pbName: " + pbName);
        }

        try {
            BluetoothPbapRequestPullPhoneBookSize request =
                new BluetoothPbapRequestPullPhoneBookSize(pbName, mAccount,
                    BluetoothPbapRequestPullPhoneBookSize.PHONEBOOK_TYPE);
            storeRequest(request);

            request.execute(mObexSession);

            if (DBG) {
                Log.d(TAG, "handlePullPhonebookSize Found size: " + request.getPhonebookSize());
            }

            processPullPhonebookSizeResp(request);
        } catch (IOException e) {
            Log.w(TAG, "Fail to pull phonebook size" + e.toString());
            processPullPhonebookSizeResp(null);
        }
    }

    private void handlePullVcardListing(Bundle extras) {
        String pbName = extras.getString(PbapClientHandler.KEY_PB_NAME);
        byte order = extras.getByte(PbapClientHandler.KEY_ORDER);
        byte searchProp = extras.getByte(PbapClientHandler.KEY_SEARCH_PROP);
        String searchValue = extras.getString(PbapClientHandler.KEY_SEARCH_VALUE);
        int maxListCount = extras.getInt(PbapClientHandler.KEY_MAX_LIST_COUNT);
        int listStartOffset = extras.getInt(PbapClientHandler.KEY_LIST_START_OFFSET);

        if (DBG) {
            Log.d(TAG, "handlePullVcardListing pbName: " + pbName + ", order: " +
                order + ", searchProp: " + searchProp + ", searchValue: " + searchValue +
                ", maxListCount: " + maxListCount + ", listStartOffset: " + listStartOffset);
        }

        if ((pbName == null) || pbName.isEmpty()) {
            // Invalid phonebook name.
            notifyCustomActionResult(PbapClientHandler.CUSTOM_ACTION_PULL_VCARD_LISTING,
                PbapClientHandler.RESULT_INVALID_PARAMETER, 0, 0);
            return;
        }

        if (maxListCount == 0) {
            // Get vCard listing size
            handlePullVcardListingSize(extras);
            return;
        }

        try {
            BluetoothPbapRequestPullVcardListing request =
                new BluetoothPbapRequestPullVcardListing(pbName, mAccount,
                    order, searchProp, searchValue, maxListCount, listStartOffset);
            storeRequest(request);

            request.execute(mObexSession);

            if (DBG) {
                Log.d(TAG, "handlePullVcardListing Found size: " + request.getCount());
            }

            processPullVcardListingResp(request);
        } catch (IOException e) {
            Log.w(TAG, "Fail to pull vcard list" + e.toString());
            processPullVcardListingResp(null);
        }
    }

    private void handlePullVcardListingSize(Bundle extras) {
        String pbName = extras.getString(PbapClientHandler.KEY_PB_NAME);

        if (DBG) {
            Log.d(TAG, "handlePullVcardListingSize pbName: " + pbName);
        }

        try {
            BluetoothPbapRequestPullPhoneBookSize request =
                new BluetoothPbapRequestPullPhoneBookSize(pbName, mAccount,
                    BluetoothPbapRequestPullPhoneBookSize.VCARD_LISTING_TYPE);
            storeRequest(request);

            request.execute(mObexSession);

            if (DBG) {
                Log.d(TAG, "handlePullPhonebookSize Found size: " + request.getPhonebookSize());
            }

            processPullVcardListingSizeResp(request);
        } catch (IOException e) {
            Log.w(TAG, "Fail to pull phonebook size" + e.toString());
            processPullVcardListingSizeResp(null);
        }
    }

    private void handlePullVcardEntry(Bundle extras) {
        String vcardHandle = extras.getString(PbapClientHandler.KEY_VCARD_HANDLE);
        long filter = extras.getLong(PbapClientHandler.KEY_FILTER);
        byte format = extras.getByte(PbapClientHandler.KEY_VCARD_TYPE);

        if (DBG) {
            Log.d(TAG, "handlePullVcardEntry vcardHandle: " + vcardHandle +
                ", filter: " + Long.toHexString(filter) + ", format: " + format);
        }

        if ((vcardHandle == null) || vcardHandle.isEmpty()) {
            // Invalid vCard handle.
            notifyCustomActionResult(PbapClientHandler.CUSTOM_ACTION_PULL_VCARD_ENTRY,
                PbapClientHandler.RESULT_INVALID_PARAMETER);
            return;
        }

        try {
            BluetoothPbapRequestPullVcardEntry request =
                new BluetoothPbapRequestPullVcardEntry(vcardHandle, mAccount, filter, format);
            storeRequest(request);

            request.execute(mObexSession);

            PhonebookPullRequest processor =
                new PhonebookPullRequest(mPbapClientStateMachine.getContext(), mAccount);
            processor.setResults(request.getList());
            processor.onPullComplete();
            processPullVcardEntryResp(request);
        } catch (IOException e) {
            Log.w(TAG, "Fail to pull vcard entry" + e.toString());
            processPullVcardEntryResp(null);
        }
    }

    private void handleSetPhonebook(Bundle extras) {
        boolean result = false;
        String pbName = extras.getString(PbapClientHandler.KEY_PB_NAME);
        String dstPath = pbName;

        if (DBG) {
            Log.d(TAG, "handleSetPhonebook pbName: " + pbName);
        }

        if ((pbName == null) || pbName.isEmpty()) {
            // Go up 1 level
            result = setPath(null);
            processSetPhonebookResp(result ? PbapClientHandler.RESULT_SUCCESS :
                PbapClientHandler.RESULT_ERROR);
            return;
        }

        if (dstPath.equals(ROOT_PATH)) {
            result = setPath(ROOT_PATH);
        } else {
            String[] folders = pbName.split("/");
            int length = folders.length;

            if (length > 0) {
                do {
                    // Go back to root path
                    if (!setPath(ROOT_PATH)) {
                        break;
                    }

                    for (int index = 0; index < length; index++) {
                        // Go down 1 level
                        result = setPath(folders[index]);
                        if (!result) {
                            break;
                        }
                    }
                } while (false);
            }
        }

        processSetPhonebookResp(result ? PbapClientHandler.RESULT_SUCCESS :
                                PbapClientHandler.RESULT_ERROR);
    }

    private boolean setPath(String folder) {
        boolean result = false;
        BluetoothPbapRequest request;
        if (DBG) Log.d(TAG, "setPath folder: " + folder);
        try {
            if (folder != null) {
                if (folder.equals(ROOT_PATH)) {
                    // Go back to root
                    request = new BluetoothPbapRequestSetPath(true);
                } else {
                    // Go down 1 level
                    request = new BluetoothPbapRequestSetPath(folder);
                }
            } else {
                // Go up 1 level
                request = new BluetoothPbapRequestSetPath(false);
            }

            request.execute(mObexSession);
            result = request.isSuccess();
        } catch (IOException e) {
            Log.w(TAG, "Fail to set path" + e.toString());
        }
        if (DBG) Log.d(TAG, "setPath result: " + result);
        return result;
    }

    private void processPullPhonebookResp(BluetoothPbapRequest pbapRequest) {
        BluetoothPbapRequestPullPhoneBook request =
            (BluetoothPbapRequestPullPhoneBook) pbapRequest;
        int result = PbapClientHandler.RESULT_ERROR;
        int phonebookSize = 0;
        int newMissedCalls = 0;

        if ((request != null) && request.isSuccess()) {
            result = PbapClientHandler.RESULT_SUCCESS;
            phonebookSize = request.getCount();
            newMissedCalls = request.getNewMissedCalls();
        }

        if (DBG) {
            Log.d(TAG, "processPullPhonebookResp result: " + result + ", phonebookSize: " +
                phonebookSize + ", newMissedCalls: " + newMissedCalls);
        }

        // vCard isn't broadcast because it's stored into Contact DB directly.
        notifyCustomActionResult(PbapClientHandler.CUSTOM_ACTION_PULL_PHONEBOOK,
            result, phonebookSize, newMissedCalls);
    }

    private void processCommonPhonebookSizeResp(BluetoothPbapRequest pbapRequest, String cmd) {
        BluetoothPbapRequestPullPhoneBookSize request =
            (BluetoothPbapRequestPullPhoneBookSize) pbapRequest;
        int result = PbapClientHandler.RESULT_ERROR;
        int phonebookSize = 0;
        int newMissedCalls = 0;

        if ((request != null) && request.isSuccess()) {
            result = PbapClientHandler.RESULT_SUCCESS;
            phonebookSize = request.getPhonebookSize();
            newMissedCalls = request.getNewMissedCalls();
        }

        if (DBG) {
            Log.d(TAG, "processCommonPhonebookSizeResp result: " + result + ", phonebookSize: " +
                phonebookSize + ", newMissedCalls: " + newMissedCalls);
        }

        notifyCustomActionResult(cmd, result, phonebookSize, newMissedCalls);
    }

    private void processPullPhonebookSizeResp(BluetoothPbapRequest pbapRequest) {
        processCommonPhonebookSizeResp(pbapRequest,
            PbapClientHandler.CUSTOM_ACTION_PULL_PHONEBOOK);
    }

    private void processPullVcardListingResp(BluetoothPbapRequest pbapRequest) {
        BluetoothPbapRequestPullVcardListing request =
            (BluetoothPbapRequestPullVcardListing) pbapRequest;
        int result = PbapClientHandler.RESULT_ERROR;
        int phonebookSize = 0;
        int newMissedCalls = 0;
        ArrayList<String> vcardListing = new ArrayList<>();

        if ((request != null) && request.isSuccess()) {
            result = PbapClientHandler.RESULT_SUCCESS;
            phonebookSize = request.getCount();
            newMissedCalls = request.getNewMissedCalls();
            vcardListing.addAll(request.getList());
        } else {
            result = PbapClientHandler.Map2CustomActionResult(request.getResponseCode());
        }

        if (DBG) {
            Log.d(TAG, "processPullVcardListingResp result: " + result + ", phonebookSize: " +
                phonebookSize + ", newMissedCalls: " + newMissedCalls);
        }

        Bundle extras = createCustomActionResult(
            PbapClientHandler.CUSTOM_ACTION_PULL_VCARD_LISTING, result);
        extras.putInt(PbapClientHandler.KEY_PHONEBOOK_SIZE, phonebookSize);
        extras.putInt(PbapClientHandler.KEY_NEW_MISSED_CALLS, newMissedCalls);
        extras.putStringArrayList(PbapClientHandler.KEY_VCARD_LISTING, vcardListing);

        notifyCustomActionResult(extras);
    }

    private void processPullVcardListingSizeResp(BluetoothPbapRequest pbapRequest) {
        processCommonPhonebookSizeResp(pbapRequest,
            PbapClientHandler.CUSTOM_ACTION_PULL_VCARD_LISTING);
    }

    private void processPullVcardEntryResp(BluetoothPbapRequest pbapRequest) {
        BluetoothPbapRequestPullVcardEntry request =
            (BluetoothPbapRequestPullVcardEntry) pbapRequest;
        int result = 0;
        String vcard = null;

        if ((request != null) && request.isSuccess()) {
            if (request.getCount() > 0) {
                result = PbapClientHandler.RESULT_SUCCESS;
                vcard = request.getVcard();
            } else {
                result = PbapClientHandler.RESULT_NOT_FOUND;
            }
        } else {
            result = PbapClientHandler.Map2CustomActionResult(request.getResponseCode());
        }

        if (DBG) {
            Log.d(TAG, "processPullVcardEntryResp result: " + result +
                ", vcard: " + vcard);
        }

        notifyCustomActionResult(
            PbapClientHandler.CUSTOM_ACTION_PULL_VCARD_ENTRY, result, vcard);
    }

    private void processSetPhonebookResp(int result) {
        if (DBG) Log.d(TAG, "processSetPhonebookResp " + result);
        notifyCustomActionResult(
            PbapClientHandler.CUSTOM_ACTION_SET_PHONEBOOK, result);
    }

    private void processAbortResp(int result) {
        if (DBG) Log.d(TAG, "processAbortResp " + result);
        notifyCustomActionResult(
            PbapClientHandler.CUSTOM_ACTION_ABORT, result);
    }

    private void notifyCustomActionResult(String cmd, int result,
        int phonebookSize, int newMissedCalls) {
        Bundle extras = createCustomActionResult(cmd, result);
        extras.putInt(PbapClientHandler.KEY_PHONEBOOK_SIZE, phonebookSize);
        extras.putInt(PbapClientHandler.KEY_NEW_MISSED_CALLS, newMissedCalls);

        notifyCustomActionResult(extras);
    }

    private void notifyCustomActionResult(String cmd, int result) {
        Bundle extras = createCustomActionResult(cmd, result);
        notifyCustomActionResult(extras);
    }

    private void notifyCustomActionResult(String cmd, int result, String vcard) {
        Bundle extras = createCustomActionResult(cmd, result, vcard);
        notifyCustomActionResult(extras);
    }

    private void notifyCustomActionResult(Bundle extras) {
        PbapClientService service = (PbapClientService) mContext;
        service.notifyCustomActionResult(extras);
    }

    private Bundle createCustomActionResult(String cmd, int result) {
        Bundle extras = new Bundle();
        extras.putString(PbapClientHandler.KEY_COMMAND, cmd);
        extras.putInt(PbapClientHandler.KEY_RESULT, result);
        return extras;
    }

    private Bundle createCustomActionResult(String cmd, int result, String vcard) {
        Bundle extras = new Bundle();
        extras.putString(PbapClientHandler.KEY_COMMAND, cmd);
        extras.putInt(PbapClientHandler.KEY_RESULT, result);
        extras.putString(PbapClientHandler.KEY_VCARD_ENTRY, vcard);
        return extras;
    }
}
