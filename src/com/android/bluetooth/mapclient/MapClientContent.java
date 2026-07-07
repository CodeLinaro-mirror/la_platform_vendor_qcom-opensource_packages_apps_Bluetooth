/*
 * Copyright (C) 2020 The Android Open Source Project
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
 * Changes from Qualcomm Technologies, Inc. are provided under the following license:
 * Copyright (c) Qualcomm Technologies, Inc. and/or its subsidiaries.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 *
 */


package com.android.bluetooth.mapclient;

import android.app.AppOpsManager;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothMapClient;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.database.StaleDataException;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Telephony;
import android.provider.Telephony.Mms;
import android.provider.Telephony.MmsSms;
import android.provider.Telephony.Sms;
import android.provider.Telephony.Threads;
import android.telephony.PhoneNumberUtils;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.ArraySet;
import android.util.Log;

import com.android.bluetooth.Utils;
import com.android.bluetooth.map.BluetoothMapbMessageMime;
import com.android.bluetooth.map.BluetoothMapbMessageMime.MimePart;
import com.android.vcard.VCardConstants;
import com.android.vcard.VCardEntry;
import com.android.vcard.VCardProperty;

import com.google.android.mms.pdu.PduHeaders;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

class MapClientContent {

    private static final String INBOX_PATH = "telecom/msg/inbox";
    private static final String TAG = "MapClientContent";
    private static final int DEFAULT_CHARSET = 106;
    private static final int ORIGINATOR_ADDRESS_TYPE = 137;
    private static final int RECIPIENT_ADDRESS_TYPE = 151;

    // OP_WRITE_SMS defaults to MODE_IGNORED for every uid and is only flipped to
    // MODE_ALLOWED by SmsApplication's exclusive-appops fixup, which runs off the
    // telephony/RoleService boot sequence with no documented latency bound (observed
    // anywhere from immediate to multiple minutes). There is no safe fixed timeout to
    // poll against, so writes attempted before the grant are queued and released by
    // AppOpsManager.startWatchingMode()'s callback instead of guessing a delay — see
    // runWhenWriteSmsReady(). Purely diagnostic heartbeat while a wait is in progress:
    private static final long WRITE_SMS_WAIT_LOG_INTERVAL_MS = 10_000;

    /**
     * Broadcast sent to all users when the BT MAP messaging session becomes
     * ready (connected) or ends (disconnected). MMS/SMS apps should use this
     * to update send-capability UI rather than querying Bluetooth profile proxies.
     *
     * Extras:
     *   {@link #EXTRA_MESSAGING_STATE} — int, STATE_CONNECTED or STATE_DISCONNECTED
     *   {@link BluetoothDevice#EXTRA_DEVICE} — the MAP device
     */
    static final String ACTION_MESSAGING_STATE_CHANGED =
            "com.android.bluetooth.mapclient.action.MESSAGING_STATE_CHANGED";
    static final String EXTRA_MESSAGING_STATE = "state";
    static final int MESSAGING_STATE_CONNECTED = 1;
    static final int MESSAGING_STATE_DISCONNECTED = 0;

    final BluetoothDevice mDevice;
    private final Context mContext;
    private final Callbacks mCallbacks;
    private final ContentResolver mResolver;
    ContentObserver mContentObserver;
    String mPhoneNumber = null;
    private int mSubscriptionId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    private SubscriptionManager mSubscriptionManager;
    private TelephonyManager mTelephonyManager;
    private HashMap<String, Uri> mHandleToUriMap = new HashMap<>();
    private HashMap<Uri, MessageStatus> mUriToHandleMap = new HashMap<>();
    private volatile boolean mCleanedUp = false;

    // Single gate shared by every write this session, instead of each write polling
    // independently: one AppOpsManager listener registration backs a FIFO queue of
    // pending write actions, all drained together the instant OP_WRITE_SMS is granted.
    // mCallbackHandler is bound to whichever thread first blocks on the gate (in
    // practice always MceStateMachine's handler thread, the same thread every real
    // caller of storeMessage()/etc. runs on) -- see runWhenWriteSmsReady().
    // storeSms()/storeMms() mutate mHandleToUriMap/mUriToHandleMap with no locking,
    // relying on every call happening on that one thread; the AppOps listener fires
    // on a binder thread and must not touch the queue or maps directly, only via a
    // post to mCallbackHandler.
    private Handler mCallbackHandler;
    private final ArrayDeque<Runnable> mPendingWrites = new ArrayDeque<>();
    private AppOpsManager.OnOpChangedListener mWriteSmsListener;
    private long mWriteSmsWaitStartMs = -1;
    private final Runnable mWriteSmsWaitHeartbeat = new Runnable() {
        @Override
        public void run() {
            if (mWriteSmsListener == null) {
                return;
            }
            long waitedMs = SystemClock.elapsedRealtime() - mWriteSmsWaitStartMs;
            Log.w(TAG, "runWhenWriteSmsReady: still waiting for OP_WRITE_SMS after "
                    + (waitedMs / 1000) + "s, " + mPendingWrites.size() + " write(s) queued");
            mCallbackHandler.postDelayed(this, WRITE_SMS_WAIT_LOG_INTERVAL_MS);
        }
    };

    /**
     * Callbacks
     * API to notify about statusChanges as observed from the content provider
     */
    interface Callbacks {
        void onMessageStatusChanged(String handle, int status);
    }

    /**
     * MapClientContent manages all interactions between Bluetooth and the messaging provider.
     *
     * Changes to the database are mirrored between the remote and local providers, specifically new
     * messages, changes to read status, and removal of messages.
     *
     * Object is invalid after cleanUp() is called.
     *
     * context: the context that all content provider interactions are conducted
     * MceStateMachine:  the interface to send outbound updates such as when a message is read
     * locally
     * device: the associated Bluetooth device used for associating messages with a subscription
     */
    MapClientContent(Context context, Callbacks callbacks,
            BluetoothDevice device) {
        mContext = context;
        mDevice = device;
        mCallbacks = callbacks;
        mResolver = mContext.getContentResolver();

        mSubscriptionManager = mContext.getSystemService(SubscriptionManager.class);
        mTelephonyManager = mContext.getSystemService(TelephonyManager.class);

        // Use SLOT_INDEX_FOR_REMOTE_SIM_SUB (-1), not a real slot index like 0. Passing a
        // real slot collides with whatever LOCAL_SIM subscription already occupies that
        // slot (SubscriptionManagerService.addSubInfo() tracks slot occupancy in a single
        // type-agnostic map): addSubInfo() then logs "Already a subscription on slot N" and
        // returns an error, no REMOTE_SIM subscription is ever created, and every message
        // this session stores silently falls back to mSubscriptionId=INVALID_SUBSCRIPTION_ID
        // (-1) below. Rows with sub_id=-1 are invisible to clearAllContent()'s reboot cleanup
        // (which only iterates real subscriptions from getAllSubscriptionInfoList()), so they
        // leak permanently across every future reboot instead of being purged.
        mSubscriptionManager
                .addSubscriptionInfoRecord(mDevice.getAddress(), Utils.getName(mDevice),
                        SubscriptionManager.SLOT_INDEX_FOR_REMOTE_SIM_SUB,
                        SubscriptionManager.SUBSCRIPTION_TYPE_REMOTE_SIM);

        SubscriptionInfo info = mSubscriptionManager
                .getActiveSubscriptionInfoForIcc(mDevice.getAddress());
        if (info != null) {
            mSubscriptionId = info.getSubscriptionId();
        } else {
            Log.w(TAG, "SubscriptionInfo still null after retries for " + mDevice.getAddress()
                    + "; using INVALID_SUBSCRIPTION_ID");
        }

        mContentObserver = new ContentObserver(null) {
            @Override
            public boolean deliverSelfNotifications() {
                return false;
            }

            @Override
            public void onChange(boolean selfChange) {
                logV("onChange(self=" + selfChange + ")");
                findChangeInDatabase();
            }

            @Override
            public void onChange(boolean selfChange, Uri uri) {
                logV("onChange(self=" + selfChange + ", uri=" + uri.toString() + ")");
                findChangeInDatabase();
            }
        };

        int subscriptionIdForCleanup = mSubscriptionId;
        runWhenWriteSmsReady(() -> clearMessages(mContext, subscriptionIdForCleanup));
        mResolver.registerContentObserver(Sms.CONTENT_URI, true, mContentObserver);
        mResolver.registerContentObserver(Mms.CONTENT_URI, true, mContentObserver);
        mResolver.registerContentObserver(MmsSms.CONTENT_URI, true, mContentObserver);
        broadcastMessagingState(MESSAGING_STATE_CONNECTED);
    }

    /**
     * Returns true if this process's OP_WRITE_SMS AppOps mode is currently
     * MODE_ALLOWED. OP_WRITE_SMS has no backing permission — it defaults to
     * MODE_IGNORED and is only granted via SmsApplication's exclusive-appops
     * fixup, so writes attempted before that fixup runs get silently rejected
     * by the ContentProvider framework layer (a non-null placeholder Uri is
     * returned instead of throwing). Checking our own uid/package needs no
     * extra permission.
     */
    private boolean isWriteSmsAppOpAllowed() {
        AppOpsManager appOps = mContext.getSystemService(AppOpsManager.class);
        if (appOps == null) {
            return true;
        }
        return appOps.checkOpNoThrow(AppOpsManager.OPSTR_WRITE_SMS, Process.myUid(),
                mContext.getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    /**
     * Runs action immediately if OP_WRITE_SMS is already granted. Otherwise queues it
     * and, if not already watching, registers an AppOpsManager listener for our own
     * uid/package (self-watches need no extra permission) that drains the whole queue
     * the instant the mode flips to MODE_ALLOWED.
     *
     * There is no bounded worst-case latency for the platform-side grant (observed
     * anywhere from immediate to multiple minutes after connect, tied to unrelated
     * telephony/RoleService boot timing) -- so unlike a fixed-attempt poll, this never
     * gives up and writes anyway. A message that arrives before the grant is delayed,
     * never dropped; only a session cleanUp() (disconnect) discards pending writes.
     *
     * Must be called on mCallbackHandler's thread (MceStateMachine's handler thread for
     * every real caller) -- the queue and mHandleToUriMap/mUriToHandleMap are mutated
     * with no locking, relying on all access happening on that one thread. The AppOps
     * listener itself fires on a binder thread and only ever posts back to that thread.
     * The first caller's looper wins and binds mCallbackHandler for the lifetime of this
     * object; subsequent calls from a different thread are logged loudly since they'd
     * otherwise silently corrupt the unsynchronized queue/maps above.
     */
    private void runWhenWriteSmsReady(Runnable action) {
        if (mCallbackHandler == null) {
            mCallbackHandler = new Handler(Looper.myLooper() != null
                    ? Looper.myLooper() : Looper.getMainLooper());
        } else if (Looper.myLooper() != mCallbackHandler.getLooper()) {
            Log.wtf(TAG, "runWhenWriteSmsReady: called from unexpected thread "
                    + Thread.currentThread().getName() + ", expected "
                    + mCallbackHandler.getLooper().getThread().getName());
        }
        if (isWriteSmsAppOpAllowed()) {
            action.run();
            return;
        }
        mPendingWrites.add(action);
        if (mWriteSmsListener != null) {
            return;
        }
        Log.w(TAG, "runWhenWriteSmsReady: OP_WRITE_SMS not allowed yet, waiting for grant");
        mWriteSmsWaitStartMs = SystemClock.elapsedRealtime();
        mCallbackHandler.postDelayed(mWriteSmsWaitHeartbeat, WRITE_SMS_WAIT_LOG_INTERVAL_MS);
        AppOpsManager appOps = mContext.getSystemService(AppOpsManager.class);
        mWriteSmsListener = (String op, String packageName) ->
                mCallbackHandler.post(this::drainPendingWritesIfReady);
        appOps.startWatchingMode(AppOpsManager.OPSTR_WRITE_SMS, mContext.getPackageName(),
                mWriteSmsListener);
    }

    /** Runs on mCallbackHandler's thread only -- see runWhenWriteSmsReady(). */
    private void drainPendingWritesIfReady() {
        if (mWriteSmsListener == null || !isWriteSmsAppOpAllowed()) {
            return;
        }
        long waitedMs = SystemClock.elapsedRealtime() - mWriteSmsWaitStartMs;
        Log.i(TAG, "runWhenWriteSmsReady: OP_WRITE_SMS granted after " + waitedMs
                + "ms, draining " + mPendingWrites.size() + " queued write(s)");
        AppOpsManager appOps = mContext.getSystemService(AppOpsManager.class);
        appOps.stopWatchingMode(mWriteSmsListener);
        mWriteSmsListener = null;
        mCallbackHandler.removeCallbacks(mWriteSmsWaitHeartbeat);
        while (!mPendingWrites.isEmpty()) {
            mPendingWrites.poll().run();
        }
    }

    /**
     * ContentProvider.rejectInsert() (the framework's default response when the
     * write permission/appop check fails) returns a non-null Uri with "0" appended
     * as the last path segment instead of throwing, so a permission/appops rejection
     * looks identical to a real insert unless this is checked explicitly.
     */
    private static boolean isPlaceholderInsertResult(Uri uri) {
        return "0".equals(uri.getLastPathSegment());
    }

    /**
     * Broadcast messaging state to all users so UI components (e.g. the default
     * SMS app running in a managed profile) can react without polling Bluetooth.
     */
    private void broadcastMessagingState(int state) {
        Intent intent = new Intent(ACTION_MESSAGING_STATE_CHANGED);
        String smsPackage = Telephony.Sms.getDefaultSmsPackage(mContext);
        String targetPackage = smsPackage != null ? smsPackage : "com.android.mms";
        logD("Broadcasting messaging state to package: " + targetPackage);
        intent.setPackage(targetPackage);
        intent.putExtra(EXTRA_MESSAGING_STATE, state);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, mDevice);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT
                | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        mContext.sendBroadcastAsUser(intent, android.os.UserHandle.ALL,
                android.Manifest.permission.RECEIVE_SMS);
        logD("broadcastMessagingState: state=" + state + " device=" + mDevice.getAddress());
    }

    /**
     * Removes stale REMOTE_SIM subscriptions and their SMS/MMS content.
     *
     * @return the number of REMOTE_SIM subscriptions found and cleared. Callers use this
     * to tell "nothing to clean up" apart from "subscription list not loaded yet" when
     * deciding whether to retry.
     */
    static int clearAllContent(Context context) {
        SubscriptionManager subscriptionManager =
                context.getSystemService(SubscriptionManager.class);
        // Use clearCallingIdentity so getAllSubscriptionInfoList() runs as the BT process
        // identity (not user 10). REMOTE_SIM subscriptions have userId=USER_NULL and are
        // invisible when queried from user 10's context without clearing the calling identity.
        final long token = Binder.clearCallingIdentity();
        List<SubscriptionInfo> subscriptions;
        try {
            subscriptions = subscriptionManager.getAllSubscriptionInfoList();
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        if (subscriptions == null) {
            Log.w(TAG, "clearAllContent: subscription list unavailable");
            return 0;
        }
        int clearedCount = 0;
        for (SubscriptionInfo info : subscriptions) {
            if (info.getSubscriptionType() == SubscriptionManager.SUBSCRIPTION_TYPE_REMOTE_SIM) {
                Log.d(TAG, "clearAllContent: removing subId=" + info.getSubscriptionId()
                        + " iccId=" + info.getIccId());
                clearMessages(context, info.getSubscriptionId());
                try {
                    subscriptionManager.removeSubscriptionInfoRecord(info.getIccId(),
                            SubscriptionManager.SUBSCRIPTION_TYPE_REMOTE_SIM);
                } catch (Exception e) {
                    Log.w(TAG, "clearAllContent: removeSubscriptionInfoRecord failed: " + e);
                }
                clearedCount++;
            }
        }
        return clearedCount;
    }

    private static void logI(String message) {
        Log.i(TAG, message);
    }

    private static void logD(String message) {
        if (MapClientService.DBG) {
            Log.d(TAG, message);
        }
    }

    private static void logV(String message) {
        if (MapClientService.VDBG) {
            Log.v(TAG, message);
        }
    }

    /**
     * This number is necessary for thread_id to work properly. thread_id is needed for
     * (group) MMS messages to be displayed/stitched correctly.
     */
    void setRemoteDeviceOwnNumber(String phoneNumber) {
        mPhoneNumber = phoneNumber;
    }

    /**
     * storeMessage
     *
     * Store a message in database with the associated handle and timestamp.
     * The handle is used to associate the local message with the remote message.
     */
    void storeMessage(Bmessage message, String handle, Long timestamp, boolean seen) {
        if (mCleanedUp) {
            logD("storeMessage: session already cleaned up, dropping handle=" + handle);
            return;
        }
        logI("storeMessage(device=" + Utils.getLoggableAddress(mDevice) + ", time=" + timestamp
                + ", handle=" + handle + ", type=" + message.getType()
                + ", folder=" + message.getFolder());

        runWhenWriteSmsReady(() -> {
            if (mCleanedUp) {
                logD("storeMessage: session cleaned up during retry wait, dropping handle="
                        + handle);
                return;
            }
            switch (message.getType()) {
                case MMS:
                    storeMms(message, handle, timestamp, seen);
                    return;
                case SMS_CDMA:
                case SMS_GSM:
                    storeSms(message, handle, timestamp, seen);
                    return;
                default:
                    logD("Request to store unsupported message type: " + message.getType());
            }
        });
    }

    private void storeSms(Bmessage message, String handle, Long timestamp, boolean seen) {
        logD("storeSms");
        logV(message.toString());
        VCardEntry originator = message.getOriginator();
        String recipients;
        if (INBOX_PATH.equals(message.getFolder())) {
            recipients = getOriginatorNumber(message);
        } else {
            recipients = getFirstRecipientNumber(message);
            if (recipients == null || recipients.isEmpty()) {
                logD("storeSms: sent message has no recipient address, dropping");
                return;
            }
        }
        Uri contentUri = INBOX_PATH.equalsIgnoreCase(message.getFolder()) ? Sms.Inbox.CONTENT_URI
                : Sms.Sent.CONTENT_URI;
        ContentValues values = new ContentValues();
        long threadId = getThreadId(message);
        int readStatus = message.getStatus() == Bmessage.Status.READ ? 1 : 0;

        String body = message.getBodyContent();

        values.put(Sms.THREAD_ID, threadId);
        values.put(Sms.ADDRESS, recipients);
        values.put(Sms.BODY, body);
        values.put(Sms.SUBSCRIPTION_ID, mSubscriptionId);
        values.put(Sms.DATE, timestamp);
        values.put(Sms.READ, readStatus);
        values.put(Sms.SEEN, seen);

        Uri results = mResolver.insert(contentUri, values);
        logD("SMS INSERT result "+ results);
        if (results == null || isPlaceholderInsertResult(results)) {
            Log.e(TAG, "storeSms: insert failed for handle=" + handle + " sub=" + mSubscriptionId
                    + " result=" + results);
            return;
        }
        mHandleToUriMap.put(handle, results);
        mUriToHandleMap.put(results, new MessageStatus(handle, readStatus));
    }

    /**
     * deleteMessage
     * remove a message from the local provider based on a remote change
     */
    void deleteMessage(String handle) {
        logD("deleting handle" + handle);
        Uri messageToChange = mHandleToUriMap.get(handle);
        if (messageToChange != null) {
            mResolver.delete(messageToChange, null);
        }
    }


    /**
     * markRead
     * mark a message read in the local provider based on a remote change
     */
    void markRead(String handle) {
        logD("marking read " + handle);
        Uri messageToChange = mHandleToUriMap.get(handle);
        if (messageToChange != null) {
            ContentValues values = new ContentValues();
            values.put(Sms.READ, 1);
            mResolver.update(messageToChange, values, null);
        }
    }

    /**
     * findChangeInDatabase
     * compare the current state of the local content provider to the expected state and propagate
     * changes to the remote.
     */
    private void findChangeInDatabase() {
        HashMap<Uri, MessageStatus> originalUriToHandleMap;
        HashMap<Uri, MessageStatus> duplicateUriToHandleMap;

        originalUriToHandleMap = mUriToHandleMap;
        duplicateUriToHandleMap = new HashMap<>(originalUriToHandleMap);
        for (Uri uri : new Uri[]{Mms.CONTENT_URI, Sms.CONTENT_URI}) {
            Cursor cursor = mResolver.query(uri, null, null, null, null);
            if (cursor == null) continue;
            try {
                while (cursor.moveToNext()) {
                    Uri index = Uri
                            .withAppendedPath(uri, cursor.getString(cursor.getColumnIndex("_id")));
                    int readStatus = cursor.getInt(cursor.getColumnIndex(Sms.READ));
                    MessageStatus currentMessage = duplicateUriToHandleMap.remove(index);
                    if (currentMessage != null && currentMessage.mRead != readStatus) {
                        logV(currentMessage.mHandle);
                        currentMessage.mRead = readStatus;
                        mCallbacks.onMessageStatusChanged(currentMessage.mHandle,
                                BluetoothMapClient.READ);
                    }
                }
            } finally {
                cursor.close();
            }
        }
        for (HashMap.Entry record : duplicateUriToHandleMap.entrySet()) {
            logV("Deleted " + ((MessageStatus) record.getValue()).mHandle);
            originalUriToHandleMap.remove(record.getKey());
            mCallbacks.onMessageStatusChanged(((MessageStatus) record.getValue()).mHandle,
                    BluetoothMapClient.DELETED);
        }
    }

    private void storeMms(Bmessage message, String handle, Long timestamp, boolean seen) {
        logD("storeMms");
        logV(message.toString());
        try {
            ContentValues values = new ContentValues();
            long threadId = getThreadId(message);
            BluetoothMapbMessageMime mmsBmessage = new BluetoothMapbMessageMime();
            mmsBmessage.parseMsgPart(message.getBodyContent());
            int read = message.getStatus() == Bmessage.Status.READ ? 1 : 0;
            Uri contentUri;
            int messageBox;
            if (INBOX_PATH.equalsIgnoreCase(message.getFolder())) {
                contentUri = Mms.Inbox.CONTENT_URI;
                messageBox = Mms.MESSAGE_BOX_INBOX;
            } else {
                contentUri = Mms.Sent.CONTENT_URI;
                messageBox = Mms.MESSAGE_BOX_SENT;
            }
            logD("Parsed");
            values.put(Mms.SUBSCRIPTION_ID, mSubscriptionId);
            values.put(Mms.THREAD_ID, threadId);
            values.put(Mms.DATE, timestamp / 1000L);
            values.put(Mms.TEXT_ONLY, true);
            values.put(Mms.MESSAGE_BOX, messageBox);
            values.put(Mms.READ, read);
            values.put(Mms.SEEN, seen);
            values.put(Mms.MESSAGE_TYPE, PduHeaders.MESSAGE_TYPE_SEND_REQ);
            values.put(Mms.MMS_VERSION, PduHeaders.CURRENT_MMS_VERSION);
            values.put(Mms.PRIORITY, PduHeaders.PRIORITY_NORMAL);
            values.put(Mms.READ_REPORT, PduHeaders.VALUE_NO);
            values.put(Mms.TRANSACTION_ID, "T" + Long.toHexString(System.currentTimeMillis()));
            values.put(Mms.DELIVERY_REPORT, PduHeaders.VALUE_NO);
            values.put(Mms.LOCKED, 0);
            values.put(Mms.CONTENT_TYPE, "application/vnd.wap.multipart.related");
            values.put(Mms.MESSAGE_CLASS, PduHeaders.MESSAGE_CLASS_PERSONAL_STR);
            values.put(Mms.MESSAGE_SIZE, mmsBmessage.getSize());

            Uri results = mResolver.insert(contentUri, values);
            if (results == null || isPlaceholderInsertResult(results)) {
                Log.e(TAG, "storeMms: insert failed for handle=" + handle + " sub="
                        + mSubscriptionId + " threadId=" + threadId + " result=" + results);
                return;
            }
            mHandleToUriMap.put(handle, results);
            mUriToHandleMap.put(results, new MessageStatus(handle, read));

            logD("Map InsertedThread" + results);

            for (MimePart part : mmsBmessage.getMimeParts()) {
                storeMmsPart(part, results);
            }

            storeAddressPart(message, results);
            String messageContent = mmsBmessage.getMessageAsText();

            values.put(Mms.Part.CONTENT_TYPE, "plain/text");
            values.put(Mms.SUBSCRIPTION_ID, mSubscriptionId);
        } catch (Exception e) {
            Log.e(TAG, e.toString());
            throw e;
        }
    }

    private Uri storeMmsPart(MimePart messagePart, Uri messageUri) {
        ContentValues values = new ContentValues();
        values.put(Mms.Part.CONTENT_TYPE, "text/plain");
        values.put(Mms.Part.CHARSET, DEFAULT_CHARSET);
        values.put(Mms.Part.FILENAME, "text_1.txt");
        values.put(Mms.Part.NAME, "text_1.txt");
        values.put(Mms.Part.CONTENT_ID, messagePart.mContentId);
        values.put(Mms.Part.CONTENT_LOCATION, messagePart.mContentLocation);
        values.put(Mms.Part.TEXT, messagePart.getDataAsString());

        Uri contentUri = Uri.parse(messageUri.toString() + "/part");
        Uri results = mResolver.insert(contentUri, values);
        logD("Inserted" + results);
        return results;
    }

    private void storeAddressPart(Bmessage message, Uri messageUri) {
        ContentValues values = new ContentValues();
        Uri contentUri = Uri.parse(messageUri.toString() + "/addr");
        String originator = getOriginatorNumber(message);
        values.put(Mms.Addr.CHARSET, DEFAULT_CHARSET);

        values.put(Mms.Addr.ADDRESS, originator);
        values.put(Mms.Addr.TYPE, ORIGINATOR_ADDRESS_TYPE);
        mResolver.insert(contentUri, values);

        Set<String> messageContacts = new ArraySet<>();
        getRecipientsFromMessage(message, messageContacts);
        for (String recipient : messageContacts) {
            values.put(Mms.Addr.ADDRESS, recipient);
            values.put(Mms.Addr.TYPE, RECIPIENT_ADDRESS_TYPE);
            mResolver.insert(contentUri, values);
        }
    }

    private Uri insertIntoMmsTable(String subject) {
        ContentValues mmsValues = new ContentValues();
        mmsValues.put(Mms.TEXT_ONLY, 1);
        mmsValues.put(Mms.MESSAGE_TYPE, 128);
        mmsValues.put(Mms.SUBJECT, subject);
        return mResolver.insert(Mms.CONTENT_URI, mmsValues);
    }

    /**
     * cleanUp
     * clear the subscription info and content on shutdown
     */
    void cleanUp() {
        logD("cleanUp(device=" + Utils.getLoggableAddress(mDevice)
                + "subscriptionId=" + mSubscriptionId);
        mCleanedUp = true;
        if (mWriteSmsListener != null) {
            mContext.getSystemService(AppOpsManager.class).stopWatchingMode(mWriteSmsListener);
            mWriteSmsListener = null;
            if (mCallbackHandler != null) {
                mCallbackHandler.removeCallbacks(mWriteSmsWaitHeartbeat);
            }
            Log.w(TAG, "cleanUp: discarding " + mPendingWrites.size()
                    + " write(s) still queued for OP_WRITE_SMS grant");
            mPendingWrites.clear();
        }
        broadcastMessagingState(MESSAGING_STATE_DISCONNECTED);
        mResolver.unregisterContentObserver(mContentObserver);
        clearMessages(mContext, mSubscriptionId);
        try {
            mSubscriptionManager.removeSubscriptionInfoRecord(mDevice.getAddress(),
                    SubscriptionManager.SUBSCRIPTION_TYPE_REMOTE_SIM);
            mSubscriptionId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        } catch (Exception e) {
            Log.w(TAG, "cleanUp failed: " + e.toString());
        }
    }

    /**
     * clearMessages
     * clean up the content provider on startup
     */
    private static void clearMessages(Context context, int subscriptionId) {
        logD("clearMessages(subscriptionId=" + subscriptionId);

        ContentResolver resolver = context.getContentResolver();

        // Collect thread IDs that belong to this subscription BEFORE deleting,
        // so we only clean up threads that actually had BT MAP messages.
        // Querying all threads and deleting all of them (original approach) would
        // destroy threads from physical SIM messages too.
        Set<String> btThreadIds = new ArraySet<>();
        String subWhere = Sms.SUBSCRIPTION_ID + " = ?";
        String[] subArgs = new String[]{Integer.toString(subscriptionId)};
        try {
            Cursor c = resolver.query(Sms.CONTENT_URI,
                    new String[]{Sms.THREAD_ID}, subWhere, subArgs, null);
            if (c != null) {
                try {
                    while (c.moveToNext()) {
                        btThreadIds.add(String.valueOf(c.getInt(0)));
                    }
                } catch (StaleDataException e) {
                    Log.w(TAG, "clearMessages: SMS thread cursor stale: " + e);
                } finally {
                    c.close();
                }
            }
            c = resolver.query(Mms.CONTENT_URI,
                    new String[]{Mms.THREAD_ID}, subWhere, subArgs, null);
            if (c != null) {
                try {
                    while (c.moveToNext()) {
                        btThreadIds.add(String.valueOf(c.getInt(0)));
                    }
                } catch (StaleDataException e) {
                    Log.w(TAG, "clearMessages: MMS thread cursor stale: " + e);
                } finally {
                    c.close();
                }
            }
        } catch (IllegalStateException e) {
            Log.w(TAG, "clearMessages: CE storage unavailable (pre-unlock boot), skipping: " + e);
            return;
        }

        resolver.delete(Sms.CONTENT_URI, subWhere, subArgs);
        resolver.delete(Mms.CONTENT_URI, subWhere, subArgs);

        if (!btThreadIds.isEmpty()) {
            String inClause = android.text.TextUtils.join(", ", btThreadIds);
            resolver.delete(Threads.CONTENT_URI,
                    Threads._ID + " IN (" + inClause + ")", null);
        }
        logD("clearMessages: done for subscriptionId=" + subscriptionId);
    }

    /**
     * getThreadId
     * utilize the originator and recipients to obtain the thread id
     */
    private long getThreadId(Bmessage message) {

        Set<String> messageContacts = new ArraySet<>();
        // getOriginatorNumber() already extracts the network portion, falling back to the
        // raw sender id for alphanumeric senders (e.g. bank/OTP senders like "VM-HDFCBK-T").
        // Do not re-run extractNetworkPortion() here, since that would strip such ids back
        // to an empty string and route them to COMMON_THREAD (thread_id=0), which has no
        // corresponding threads row and never displays in the Mms UI.
        String originator = getOriginatorNumber(message);
        if (originator != null && !originator.isEmpty()) {
            messageContacts.add(originator);
        }
        getRecipientsFromMessage(message, messageContacts);
        // If there is only one contact don't remove it.
        if (messageContacts.isEmpty()) {
            return Telephony.Threads.COMMON_THREAD;
        } else if (messageContacts.size() > 1) {
            if (mPhoneNumber == null) {
                Log.w(TAG, "getThreadId called, mPhoneNumber never found.");
            }
            messageContacts.removeIf(number -> (PhoneNumberUtils.areSamePhoneNumber(number,
                    mPhoneNumber, mTelephonyManager.getNetworkCountryIso())));
            // After removing own number, the set might be empty if all contacts matched.
            // Fall back to COMMON_THREAD to avoid creating a canonical_address with empty address
            // (which causes mConversation.getRecipients() to return an empty ContactList,
            // making recipientCount=0 and disabling the send button).
            if (messageContacts.isEmpty()) {
                return Telephony.Threads.COMMON_THREAD;
            }
        }

        logV("Contacts = " + messageContacts.toString());
        return Telephony.Threads.getOrCreateThreadId(mContext, messageContacts);
    }

    private void getRecipientsFromMessage(Bmessage message, Set<String> messageContacts) {
        List<VCardEntry> recipients = message.getRecipients();
        for (VCardEntry recipient : recipients) {
            List<VCardEntry.PhoneData> phoneData = recipient.getPhoneList();
            if (phoneData != null && !phoneData.isEmpty()) {
                String number = extractNetworkPortionOrRaw(phoneData.get(0).getNumber());
                if (number != null && !number.isEmpty()) {
                    messageContacts.add(number);
                }
            }
        }
    }

    private String getOriginatorNumber(Bmessage message) {
        VCardEntry originator = message.getOriginator();
        if (originator == null) {
            return null;
        }

        List<VCardEntry.PhoneData> phoneData = originator.getPhoneList();
        if (phoneData == null || phoneData.isEmpty()) {
            return null;
        }

        return extractNetworkPortionOrRaw(phoneData.get(0).getNumber());
    }

    private String getFirstRecipientNumber(Bmessage message) {
        List<VCardEntry> recipients = message.getRecipients();
        if (recipients == null || recipients.isEmpty()) {
            return null;
        }

        List<VCardEntry.PhoneData> phoneData = recipients.get(0).getPhoneList();
        if (phoneData == null || phoneData.isEmpty()) {
            return null;
        }

        return extractNetworkPortionOrRaw(phoneData.get(0).getNumber());
    }

    /**
     * Extracts the dialable network portion of a sender/recipient id, falling back to the
     * raw id when the id is not a phone number. Alphanumeric sender ids used by bank/OTP
     * senders (e.g. "VM-HDFCBK-T") have no dialable characters, so extractNetworkPortion()
     * would otherwise strip them down to an empty string, causing storeSms() to insert an
     * empty address and getThreadId() to fall back to COMMON_THREAD (thread_id=0) -- a value
     * with no corresponding threads row, so the message never surfaces in the Mms UI.
     *
     * extractNetworkPortion() cannot be trusted to detect this itself: it treats some letters
     * (e.g. 'N', matched against PhoneNumberUtils.WILD) as dialable, so a sender id that
     * happens to contain one of those letters (e.g. "JX-BPCLIN-S") extracts to a bogus
     * non-empty string (e.g. "N") instead of falling back. Since real phone numbers never
     * contain letters, treat any letter in the raw id as proof it isn't one and skip
     * extraction entirely.
     */
    private static String extractNetworkPortionOrRaw(String number) {
        if (number == null) {
            return null;
        }
        if (containsAlphaCharacter(number)) {
            return number;
        }
        String extracted = PhoneNumberUtils.extractNetworkPortion(number);
        boolean fellBackToRaw = extracted == null || extracted.isEmpty();
        return fellBackToRaw ? number : extracted;
    }

    private static boolean containsAlphaCharacter(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetter(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * addThreadContactToEntries
     * utilizing the thread id fill in the appropriate fields of bmsg with the intended recipients
     */
    boolean addThreadContactsToEntries(Bmessage bmsg, String thread) {
        String threadId = Uri.parse(thread).getLastPathSegment();

        logD("MATCHING THREAD" + threadId);
        logD(MmsSms.CONTENT_CONVERSATIONS_URI + threadId + "/recipients");

        Cursor cursor = mResolver
                .query(Uri.withAppendedPath(MmsSms.CONTENT_CONVERSATIONS_URI,
                        threadId + "/recipients"),
                        null, null,
                        null, null);

        if (cursor == null) {
            Log.w(TAG, "addThreadContactsToEntries: null cursor for thread " + threadId);
            return false;
        }
        try {
            if (cursor.moveToNext()) {
                logD("Columns" + Arrays.toString(cursor.getColumnNames()));
                logV("CONTACT LIST: " + cursor.getString(cursor.getColumnIndex("recipient_ids")));
                addRecipientsToEntries(bmsg,
                        cursor.getString(cursor.getColumnIndex("recipient_ids")).split(" "));
                return true;
            } else {
                Log.w(TAG, "Thread Not Found");
                return false;
            }
        } finally {
            cursor.close();
        }
    }


    private void addRecipientsToEntries(Bmessage bmsg, String[] recipients) {
    logV("CONTACT LIST: " + Arrays.toString(recipients));
    for (String recipient : recipients) {
        Cursor cursor = mResolver.query(
                Uri.parse("content://mms-sms/canonical-address/" + recipient),
                null, null, null, null
        );

        if (cursor != null) {
            while (cursor.moveToNext()) {
                try {
                    int index = cursor.getColumnIndex(Mms.Addr.ADDRESS);
                    if (index != -1) {
                        String number = cursor.getString(index);
                        VCardEntry destEntry = new VCardEntry();
                        VCardProperty destEntryPhone = new VCardProperty();
                        destEntryPhone.setName(VCardConstants.PROPERTY_TEL);
                        destEntryPhone.addValues(number);
                        destEntry.addProperty(destEntryPhone);
                        bmsg.addRecipient(destEntry);
                    } else {
                        logV("ADDRESS column not found in cursor for recipient: ");
                    }
                } catch (Exception e) {
                    logV("Exception while reading cursor for recipient: " + e.getMessage());
                    e.printStackTrace();
                }
            }
            cursor.close();
        } else {
            logV("Cursor is null for recipient: ");
        }
    }}

    /**
     * Get the total number of messages we've stored under this device's subscription ID, for a
     * given message source, provided by the "uri" parameter.
     */
        private int getStoredMessagesCount(Uri uri) {
            if (mSubscriptionId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                logV("getStoredMessagesCount(uri=" + uri + "): Failed, no subscription ID");
                return 0;
            }

            Cursor cursor = null;
            if (Sms.CONTENT_URI.equals(uri) || Sms.Inbox.CONTENT_URI.equals(uri)
                    || Sms.Sent.CONTENT_URI.equals(uri)) {
                cursor = mResolver.query(uri, new String[] {"count(*)"}, Sms.SUBSCRIPTION_ID + " =? ",
                        new String[]{Integer.toString(mSubscriptionId)}, null);
            } else if (Mms.CONTENT_URI.equals(uri) || Mms.Inbox.CONTENT_URI.equals(uri)
                    || Mms.Sent.CONTENT_URI.equals(uri)) {
                cursor = mResolver.query(uri, new String[] {"count(*)"}, Mms.SUBSCRIPTION_ID + " =? ",
                        new String[]{Integer.toString(mSubscriptionId)}, null);
            } else if (Threads.CONTENT_URI.equals(uri)) {
                uri = Threads.CONTENT_URI.buildUpon().appendQueryParameter("simple", "true").build();
                cursor = mResolver.query(uri, new String[] {"count(*)"}, null, null, null);
            }

            if (cursor == null) {
                return 0;
            }

            cursor.moveToFirst();
            int count = cursor.getInt(0);
            cursor.close();

            return count;
        }
        private boolean isValidSubscription(int subId) {
        SubscriptionManager sm = mContext.getSystemService(SubscriptionManager.class);
        if (sm == null) {
            Log.d(TAG, "SubscriptionManager is null");
            return false;
        }
        List<SubscriptionInfo> infos = sm.getActiveSubscriptionInfoList();
        if (infos == null || infos.isEmpty()) {
            Log.d(TAG, "No active subscriptions found");
            return false;
        }
        for (SubscriptionInfo info : infos) {
            if (info.getSubscriptionId() == subId) {
                Log.d(TAG, "Valid subscription found: subId=" + subId);
                return true;
            }
        }
        Log.d(TAG, "Invalid subscription: subId=" + subId);
        return false;
    }

    public void dump(StringBuilder sb) {
        sb.append("    Device Message DB:");
        sb.append("\n      Subscription ID: " + mSubscriptionId);
        Log.d(TAG, "Dump called for subId=" + mSubscriptionId);

        if (isValidSubscription(mSubscriptionId)) {
            try {
                int smsInbox = getStoredMessagesCount(Sms.Inbox.CONTENT_URI);
                int smsSent = getStoredMessagesCount(Sms.Sent.CONTENT_URI);
                int smsTotal = getStoredMessagesCount(Sms.CONTENT_URI);
                sb.append("\n      SMS Messages (Inbox/Sent/Total): " 
                            + smsInbox + " / " + smsSent + " / " + smsTotal);
                Log.d(TAG, "SMS Messages count: Inbox=" + smsInbox + ", Sent=" + smsSent + ", Total=" + smsTotal);

                int mmsInbox = getStoredMessagesCount(Mms.Inbox.CONTENT_URI);
                int mmsSent = getStoredMessagesCount(Mms.Sent.CONTENT_URI);
                int mmsTotal = getStoredMessagesCount(Mms.CONTENT_URI);
                sb.append("\n      MMS Messages (Inbox/Sent/Total): " 
                            + mmsInbox + " / " + mmsSent + " / " + mmsTotal);
                Log.d(TAG, "MMS Messages count: Inbox=" + mmsInbox + ", Sent=" + mmsSent + ", Total=" + mmsTotal);

                int threads = getStoredMessagesCount(Threads.CONTENT_URI);
                sb.append("\n      Threads: " + threads);
                Log.d(TAG, "Threads count: " + threads);

            } catch (Exception e) {
                sb.append("\n      [Message DB not accessible: " + e.getMessage() + "]");
                Log.d(TAG, "Exception while dumping message DB for subId=" + mSubscriptionId, e);
            }
        } else {
            sb.append("\n      [Invalid subscription ID: " + mSubscriptionId + "]");
            Log.d(TAG, "Dump skipped due to invalid subscription: subId=" + mSubscriptionId);
        }
    }

    /**
     * MessageStatus
     *
     * Helper class to store associations between remote and local provider based on message handle
     * and read status
     */
    class MessageStatus {

        String mHandle;
        int mRead;

        MessageStatus(String handle, int read) {
            mHandle = handle;
            mRead = read;
        }

        @Override
        public boolean equals(Object other) {
            return ((other instanceof MessageStatus) && ((MessageStatus) other).mHandle
                    .equals(mHandle));
        }
    }
}