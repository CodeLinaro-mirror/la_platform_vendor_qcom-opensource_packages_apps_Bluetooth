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

/**
 * Bluetooth MAP MCE StateMachine
 *         (Disconnected)
 *             |    ^
 *     CONNECT |    | DISCONNECTED
 *             V    |
 *    (Connecting) (Disconnecting)
 *             |    ^
 *   CONNECTED |    | DISCONNECT
 *             V    |
 *           (Connected)
 *
 * Valid Transitions: State + Event -> Transition:
 *
 * Disconnected + CONNECT -> Connecting
 * Connecting + CONNECTED -> Connected
 * Connecting + TIMEOUT -> Disconnecting
 * Connecting + DISCONNECT/CONNECT -> Defer Message
 * Connected + DISCONNECT -> Disconnecting
 * Connected + CONNECT -> Disconnecting + Defer Message
 * Disconnecting + DISCONNECTED -> (Safe) Disconnected
 * Disconnecting + TIMEOUT -> (Force) Disconnected
 * Disconnecting + DISCONNECT/CONNECT : Defer Message
 */
package com.android.bluetooth.mapclient;

import android.app.Activity;
import android.app.PendingIntent;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothMapClient;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.SdpMasRecord;
import android.content.Intent;
import android.net.Uri;
import android.os.Message;
import android.provider.Telephony;
import android.os.SystemProperties;
import android.telecom.PhoneAccount;
import android.telephony.SmsManager;
import android.util.Log;

import com.android.bluetooth.BluetoothMetricsProto;
import com.android.bluetooth.btservice.MetricsLogger;
import com.android.bluetooth.btservice.ProfileService;
import com.android.bluetooth.map.BluetoothMapbMessageMime;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IState;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.vcard.VCardConstants;
import com.android.vcard.VCardEntry;
import com.android.vcard.VCardProperty;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/* The MceStateMachine is responsible for setting up and maintaining a connection to a single
 * specific Messaging Server Equipment endpoint.  Upon connect command an SDP record is retrieved,
 * a connection to the Message Access Server is created and a request to enable notification of new
 * messages is sent.
 */
final class MceStateMachine extends StateMachine {
    // Messages for events handled by the StateMachine
    static final int MSG_MAS_CONNECTED = 1001;
    static final int MSG_MAS_DISCONNECTED = 1002;
    static final int MSG_MAS_REQUEST_COMPLETED = 1003;
    static final int MSG_MAS_REQUEST_FAILED = 1004;
    static final int MSG_MAS_SDP_DONE = 1005;
    static final int MSG_MAS_SDP_FAILED = 1006;
    static final int MSG_OUTBOUND_MESSAGE = 2001;
    static final int MSG_INBOUND_MESSAGE = 2002;
    static final int MSG_NOTIFICATION = 2003;
    static final int MSG_GET_LISTING = 2004;
    static final int MSG_GET_MESSAGE_LISTING = 2005;

    /* Extend messages */
    // Set message status to read or deleted
    static final int MSG_SET_MESSAGE_STATUS = 3001;
    // To abort
    static final int MSG_ABORT = 3002;
    // Abort over
    static final int MSG_ABORTED = 3003;

    private static final String TAG = "MceSM";
    private static final Boolean DBG = MapClientService.DBG;
    private static final int TIMEOUT = 10000;
    private static final int MAX_MESSAGES = 20;
    private static final int MSG_CONNECT = 1;
    private static final int MSG_DISCONNECT = 2;
    private static final int MSG_CONNECTING_TIMEOUT = 3;
    private static final int MSG_DISCONNECTING_TIMEOUT = 4;
    // Folder names as defined in Bluetooth.org MAP spec V10
    private static final String FOLDER_TELECOM = "telecom";
    private static final String FOLDER_MSG = "msg";
    private static final String FOLDER_OUTBOX = "outbox";
    private static final String FOLDER_INBOX = "inbox";
    private static final String INBOX_PATH = "telecom/msg/inbox";

    /* Properties for MAP filter */
    private final static String BLUETOOTH_MAP_FILTER_USE_PROPERTY = "vendor.bt.pts.mce.useproperty";
    private final static String BLUETOOTH_MAP_FILTER_MESSAGE_TYPE = "vendor.bt.pts.mce.messagetype";
    private final static String BLUETOOTH_MAP_FILTER_READ_STATUS = "vendor.bt.pts.mce.readstatus";
    private final static String BLUETOOTH_MAP_FILTER_PERIODBEGIN = "vendor.bt.pts.mce.periodbegin";
    private final static String BLUETOOTH_MAP_FILTER_PERIODEND = "vendor.bt.pts.mce.periodend";
    private final static String BLUETOOTH_MAP_FILTER_RECIPIENT = "vendor.bt.pts.mce.recipient";
    private final static String BLUETOOTH_MAP_FILTER_ORIGINATOR = "vendor.bt.pts.mce.originator";
    private final static String BLUETOOTH_MAP_FILTER_PRIORITY = "vendor.bt.pts.mce.priority";

    /* Properties for MAP PTS test */
    // Set "vendor.bt.mce.test.upload" to true to test PTS upload feature case
    // MAP/MCE/MMU/BV-01-I
    // When test upload feature with PTS, NotificationRegistration and UpdateInbox
    // should be disabled during enter connected status, or PTS takes the 2 requests
    // as under test requests and reports failure.
    private final static String BLUETOOTH_MAP_TEST_UPLOAD = "vendor.bt.pts.mce.test.upload";
    private final static String BLUETOOTH_MAP_AUTO_GET_NEW_MESSAGE = "vendor.bt.pts.mce.autogetnewmessage";

    private final static String MESSAGES_FILTER_DATE_FORMAT = "yyyy-MM-dd HH:mm:ss";

    // Instance id under pts test
    private final static String BLUETOOTH_MAP_INSTANCE_UNDER_TEST = "vendor.bt.pts.mce.instance";

    private static final int UNSET = -1;

    // Connectivity States
    private int mPreviousState = BluetoothProfile.STATE_DISCONNECTED;
    private State mDisconnected;
    private State mConnecting;
    private State mConnected;
    private State mDisconnecting;

    private final BluetoothDevice mDevice;
    private MapClientService mService;
    private MasClient mMasClient;
    private HashMap<String, Bmessage> mSentMessageLog = new HashMap<>(MAX_MESSAGES);
    private HashMap<Bmessage, PendingIntent> mSentReceiptRequested = new HashMap<>(MAX_MESSAGES);
    private HashMap<Bmessage, PendingIntent> mDeliveryReceiptRequested =
            new HashMap<>(MAX_MESSAGES);
    private HashMap<String, PendingIntent> mSentResult = new HashMap<>(MAX_MESSAGES);
    private Bmessage.Type mDefaultMessageType = Bmessage.Type.SMS_CDMA;
    private boolean mAbort = false;
    private MessagesFilter mFilter = new MessagesFilter();

    /**
     * An object to hold the necessary meta-data for each message so we can broadcast it alongside
     * the message content.
     *
     * This is necessary because the metadata is inferred or received separately from the actual
     * message content.
     *
     * Note: In the future it may be best to use the entries from the MessageListing in full instead
     * of this small subset.
     */
    private class MessageMetadata {
        private final String mHandle;
        private final Long mTimestamp;
        private boolean mRead;

        MessageMetadata(String handle, Long timestamp, boolean read) {
            mHandle = handle;
            mTimestamp = timestamp;
            mRead = read;
        }

        public String getHandle() {
            return mHandle;
        }

        public Long getTimestamp() {
            return mTimestamp;
        }

        public synchronized boolean getRead() {
            return mRead;
        }

        public synchronized void setRead(boolean read) {
            mRead = read;
        }
    }

    // Map each message to its metadata via the handle
    private ConcurrentHashMap<String, MessageMetadata> mMessages =
            new ConcurrentHashMap<String, MessageMetadata>();

    MceStateMachine(MapClientService service, BluetoothDevice device) {
        this(service, device, null);
    }

    @VisibleForTesting
    MceStateMachine(MapClientService service, BluetoothDevice device, MasClient masClient) {
        super(TAG);
        mMasClient = masClient;
        mService = service;

        mPreviousState = BluetoothProfile.STATE_DISCONNECTED;

        mDevice = device;
        mDisconnected = new Disconnected();
        mConnecting = new Connecting();
        mDisconnecting = new Disconnecting();
        mConnected = new Connected();

        addState(mDisconnected);
        addState(mConnecting);
        addState(mDisconnecting);
        addState(mConnected);
        setInitialState(mConnecting);
        start();
    }

    public void doQuit() {
        quitNow();
    }

    @Override
    protected void onQuitting() {
        if (mService != null) {
            mService.cleanupDevice(mDevice);
        }
    }

    synchronized BluetoothDevice getDevice() {
        return mDevice;
    }

    private void onConnectionStateChanged(int prevState, int state) {
        // mDevice == null only at setInitialState
        if (mDevice == null) {
            return;
        }
        if (DBG) {
            Log.d(TAG, "Connection state " + mDevice + ": " + prevState + "->" + state);
        }
        if (prevState != state && state == BluetoothProfile.STATE_CONNECTED) {
            MetricsLogger.logProfileConnectionEvent(BluetoothMetricsProto.ProfileId.MAP_CLIENT);
        }
        Intent intent = new Intent(BluetoothMapClient.ACTION_CONNECTION_STATE_CHANGED);
        intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, prevState);
        intent.putExtra(BluetoothProfile.EXTRA_STATE, state);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, mDevice);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        mService.sendBroadcast(intent, ProfileService.BLUETOOTH_PERM);
    }

    public synchronized int getState() {
        IState currentState = this.getCurrentState();
        if (currentState == null || currentState.getClass() == Disconnected.class) {
            return BluetoothProfile.STATE_DISCONNECTED;
        }
        if (currentState.getClass() == Connected.class) {
            return BluetoothProfile.STATE_CONNECTED;
        }
        if (currentState.getClass() == Connecting.class) {
            return BluetoothProfile.STATE_CONNECTING;
        }
        if (currentState.getClass() == Disconnecting.class) {
            return BluetoothProfile.STATE_DISCONNECTING;
        }
        return BluetoothProfile.STATE_DISCONNECTED;
    }

    public boolean disconnect() {
        if (DBG) {
            Log.d(TAG, "Disconnect Request " + mDevice.getAddress());
        }
        sendMessage(MSG_DISCONNECT, mDevice);
        return true;
    }

    public synchronized boolean sendMapMessage(Uri[] contacts, String message,
            PendingIntent sentIntent, PendingIntent deliveredIntent) {
        if (DBG) {
            Log.d(TAG, "Send Message " + message);
        }
        if (contacts == null || contacts.length <= 0) {
            return false;
        }
        if (this.getCurrentState() == mConnected && !isAbort()) {
            Bmessage bmsg = new Bmessage();
            // Set type and status.
            bmsg.setType(getDefaultMessageType());
            bmsg.setStatus(Bmessage.Status.READ);

            for (Uri contact : contacts) {
                // Who to send the message to.
                VCardEntry destEntry = new VCardEntry();
                VCardProperty destEntryPhone = new VCardProperty();
                if (DBG) {
                    Log.d(TAG, "Scheme " + contact.getScheme());
                }
                if (PhoneAccount.SCHEME_TEL.equals(contact.getScheme())) {
                    destEntryPhone.setName(VCardConstants.PROPERTY_TEL);
                    destEntryPhone.addValues(contact.getSchemeSpecificPart());
                    if (DBG) {
                        Log.d(TAG, "Sending to phone numbers " + destEntryPhone.getValueList());
                    }
                } else {
                    if (DBG) {
                        Log.w(TAG, "Scheme " + contact.getScheme() + " not supported.");
                    }
                    return false;
                }
                destEntry.addProperty(destEntryPhone);
                bmsg.addRecipient(destEntry);
            }

            // Message of the body.
            bmsg.setBodyContent(message);
            if (sentIntent != null) {
                mSentReceiptRequested.put(bmsg, sentIntent);
            }
            if (deliveredIntent != null) {
                mDeliveryReceiptRequested.put(bmsg, deliveredIntent);
            }
            sendMessage(MSG_OUTBOUND_MESSAGE, bmsg);
            return true;
        }
        return false;
    }

    synchronized boolean getMessage(String handle) {
        if (DBG) {
            Log.d(TAG, "getMessage" + handle);
        }
        if (this.getCurrentState() == mConnected && !isAbort()) {
            sendMessage(MSG_INBOUND_MESSAGE, handle);
            return true;
        }
        return false;
    }

    synchronized boolean getUnreadMessages() {
        if (DBG) {
            Log.d(TAG, "getUnreadMessages");
        }
        if (this.getCurrentState() == mConnected && !isAbort()) {
            sendMessage(MSG_GET_MESSAGE_LISTING, FOLDER_INBOX);
            return true;
        }
        return false;
    }

    synchronized int getSupportedFeatures() {
        if (this.getCurrentState() == mConnected && mMasClient != null) {
            if (DBG) Log.d(TAG, "returning getSupportedFeatures from SDP record");
            return mMasClient.getSdpMasRecord().getSupportedFeatures();
        }
        if (DBG) Log.d(TAG, "in getSupportedFeatures, returning 0");
        return 0;
    }

    synchronized boolean setMessageStatus(String handle, int status) {
        if (DBG) {
            Log.d(TAG, "setMessageStatus(" + handle + ", " + status + ")");
        }
        if (this.getCurrentState() == mConnected) {
            // sendMessage(int what, int arg1, int arg2, Object obj)
            sendMessage(MSG_SET_MESSAGE_STATUS, status, 0, handle);
            return true;
        }
        return false;
    }

    synchronized boolean abort() {
        if (DBG) {
            Log.d(TAG, "abort");
        }
        if (this.getCurrentState() == mConnected && !isAbort()) {
            sendMessage(MSG_ABORT);
            setAbort(true);
            return true;
        }
        return false;
    }

    private boolean isAbort() {
        return mAbort;
    }

    private void setAbort(boolean abort) {
        if (DBG) {
            Log.d(TAG, "setAbort " + abort);
        }
        mAbort = abort;
    }

    private String getContactURIFromPhone(String number) {
        return PhoneAccount.SCHEME_TEL + ":" + number;
    }

    Bmessage.Type getDefaultMessageType() {
        synchronized (mDefaultMessageType) {
            return mDefaultMessageType;
        }
    }

    void setDefaultMessageType(SdpMasRecord sdpMasRecord) {
        int supportedMessageTypes = sdpMasRecord.getSupportedMessageTypes();
        synchronized (mDefaultMessageType) {
            if (SystemProperties.getBoolean("vendor.bt.pts.mce.gsm", false)) {
                mDefaultMessageType = Bmessage.Type.SMS_GSM;;
            } else if (SystemProperties.getBoolean("vendor.bt.pts.mce.cdma", false)) {
                mDefaultMessageType = Bmessage.Type.SMS_CDMA;
            } else if (SystemProperties.getBoolean("vendor.bt.pts.mce.mms", false)) {
                mDefaultMessageType = Bmessage.Type.MMS;
            } else if ((supportedMessageTypes & SdpMasRecord.MessageType.MMS) > 0) {
                mDefaultMessageType = Bmessage.Type.MMS;
            } else if ((supportedMessageTypes & SdpMasRecord.MessageType.SMS_CDMA) > 0) {
                mDefaultMessageType = Bmessage.Type.SMS_CDMA;
            } else if ((supportedMessageTypes & SdpMasRecord.MessageType.SMS_GSM) > 0) {
                mDefaultMessageType = Bmessage.Type.SMS_GSM;
            }
        }
    }

    public void dump(StringBuilder sb) {
        ProfileService.println(sb, "mCurrentDevice: " + mDevice.getAddress() + "("
                + mDevice.getName() + ") " + this.toString());
    }

    class Disconnected extends State {
        @Override
        public void enter() {
            if (DBG) {
                Log.d(TAG, "Enter Disconnected: " + getCurrentMessage().what);
            }
            onConnectionStateChanged(mPreviousState, BluetoothProfile.STATE_DISCONNECTED);
            mPreviousState = BluetoothProfile.STATE_DISCONNECTED;
            if (isAbort()) {
                Log.w(TAG, "Abort status is not cleared, do it here ");
                setAbort(false);
            }
            quit();
        }

        @Override
        public void exit() {
            mPreviousState = BluetoothProfile.STATE_DISCONNECTED;
        }
    }

    class Connecting extends State {
        @Override
        public void enter() {
            if (DBG) {
                Log.d(TAG, "Enter Connecting: " + getCurrentMessage().what);
            }
            onConnectionStateChanged(mPreviousState, BluetoothProfile.STATE_CONNECTING);

            // When commanded to connect begin SDP to find the MAS server.
            mDevice.sdpSearch(BluetoothUuid.MAS);
            sendMessageDelayed(MSG_CONNECTING_TIMEOUT, TIMEOUT);
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) {
                Log.d(TAG, "processMessage" + this.getName() + message.what);
            }
            switch (message.what) {
                case MSG_MAS_SDP_DONE:
                    if (DBG) {
                        Log.d(TAG, "SDP Complete");
                    }
                    if (mMasClient == null) {
                        SdpMasRecord record = (SdpMasRecord) message.obj;
                        if (record == null) {
                            Log.e(TAG, "Unexpected: SDP record is null for device "
                                    + mDevice.getName());
                            return NOT_HANDLED;
                        }
                        if (isValidInstance(record.getMasInstanceId())) {
                            mMasClient = new MasClient(mDevice, MceStateMachine.this, record);
                            setDefaultMessageType(record);
                        }
                    }
                    break;

                case MSG_MAS_CONNECTED:
                    transitionTo(mConnected);
                    break;

                case MSG_MAS_DISCONNECTED:
                    if (mMasClient != null) {
                        mMasClient.shutdown();
                    }
                    transitionTo(mDisconnected);
                    break;

                case MSG_CONNECTING_TIMEOUT:
                    transitionTo(mDisconnecting);
                    break;

                case MSG_CONNECT:
                case MSG_DISCONNECT:
                    deferMessage(message);
                    break;

                default:
                    Log.w(TAG, "Unexpected message: " + message.what + " from state:"
                            + this.getName());
                    return NOT_HANDLED;
            }
            return HANDLED;
        }

        @Override
        public void exit() {
            mPreviousState = BluetoothProfile.STATE_CONNECTING;
            removeMessages(MSG_CONNECTING_TIMEOUT);
        }

        // Return true is not in PTS test or it is the specified instance under pts test
        private boolean isValidInstance(int instance) {
            if (DBG) {
                Log.d(TAG, "instance " + instance + ", getUnderTestInstance() " + getUnderTestInstance());
            }
            if (getUnderTestInstance() == UNSET || isInstanceUnderTest(instance)) {
                return true;
            } else {
                return false;
            }
        }

        // For pts test
        // Set the property to the value prompted on PTS
        private int getUnderTestInstance() {
            return SystemProperties.getInt(BLUETOOTH_MAP_INSTANCE_UNDER_TEST, UNSET);
        }

        // For pts test
        private boolean isInstanceUnderTest(int instance) {
            if (getUnderTestInstance() == instance) {
                return true;
            } else {
                return false;
            }
        }
    }

    class Connected extends State {
        @Override
        public void enter() {
            if (DBG) {
                Log.d(TAG, "Enter Connected: " + getCurrentMessage().what);
            }
            onConnectionStateChanged(mPreviousState, BluetoothProfile.STATE_CONNECTED);

            mMasClient.makeRequest(new RequestSetPath(FOLDER_TELECOM));
            mMasClient.makeRequest(new RequestSetPath(FOLDER_MSG));
            mMasClient.makeRequest(new RequestSetPath(FOLDER_INBOX));
            mMasClient.makeRequest(new RequestGetFolderListing(0, 0));
            // Go up
            mMasClient.makeRequest(new RequestSetPath(false));
            if (!isTestUpload()) {
                // SetNotificationRegistration and UpdateInbox
                mMasClient.makeRequest(new RequestSetNotificationRegistration(true));
                mMasClient.makeRequest(new RequestUpdateInbox());
            }
        }

        @Override
        public boolean processMessage(Message message) {
            switch (message.what) {
                case MSG_DISCONNECT:
                    if (mDevice.equals(message.obj)) {
                        transitionTo(mDisconnecting);
                    }
                    break;

                case MSG_MAS_DISCONNECTED:
                    deferMessage(message);
                    transitionTo(mDisconnecting);
                    break;

                case MSG_OUTBOUND_MESSAGE:
                    mMasClient.makeRequest(
                            new RequestPushMessage(FOLDER_OUTBOX, (Bmessage) message.obj, null,
                                    false, false));
                    break;

                case MSG_INBOUND_MESSAGE:
                    mMasClient.makeRequest(
                            new RequestGetMessage((String) message.obj, MasClient.CharsetType.UTF_8,
                                    false));
                    break;

                case MSG_NOTIFICATION:
                    processNotification(message);
                    break;

                case MSG_GET_LISTING:
                    mMasClient.makeRequest(new RequestGetFolderListing(0, 0));
                    break;

                case MSG_GET_MESSAGE_LISTING:
                    if (isFilterPropUsed()) {
                        getFilterFromProperties();
                    } else {
                        // Get latest 50 Unread messages in the last week
                        mFilter.setMessageType((byte) 0);
                        mFilter.setReadStatus(MessagesFilter.READ_STATUS_UNREAD);
                        Calendar calendar = Calendar.getInstance();
                        calendar.add(Calendar.DATE, -7);
                        mFilter.setPeriod(calendar.getTime(), null);
                    }
                    mMasClient.makeRequest(new RequestGetMessagesListing(
                            (String) message.obj, 0, mFilter, 0, 50, 0));
                    break;

                case MSG_SET_MESSAGE_STATUS:
                    RequestSetMessageStatus.StatusIndicator status;
                    int arg = (int)message.arg1;
                    if (arg == BluetoothMapClient.READ) {
                        status = RequestSetMessageStatus.StatusIndicator.READ;
                    } else if (arg == BluetoothMapClient.DELETED) {
                        status = RequestSetMessageStatus.StatusIndicator.DELETED;
                    } else {
                        Log.e(TAG, "Invalid parameter " + arg);
                        break;
                    }
                    mMasClient.makeRequest(new RequestSetMessageStatus(
                            (String)message.obj, status));
                    break;

                case MSG_ABORT:
                    mMasClient.abort();
                    break;

                case MSG_ABORTED:
                    setAbort(false);
                    break;

                case MSG_MAS_REQUEST_COMPLETED:
                    if (DBG) {
                        Log.d(TAG, "Completed request");
                    }
                    if (message.obj instanceof RequestGetMessage) {
                        processInboundMessage((RequestGetMessage) message.obj);
                    } else if (message.obj instanceof RequestPushMessage) {
                        String messageHandle = ((RequestPushMessage) message.obj).getMsgHandle();
                        if (DBG) {
                            Log.d(TAG, "Message Sent......." + messageHandle);
                        }
                        // ignore the top-order byte (converted to string) in the handle for now
                        // some test devices don't populate messageHandle field.
                        // in such cases, no need to wait up for response for such messages.
                        if (messageHandle != null && messageHandle.length() > 2) {
                            mSentMessageLog.put(messageHandle.substring(2),
                                    ((RequestPushMessage) message.obj).getBMsg());
                        }
                    } else if (message.obj instanceof RequestGetMessagesListing) {
                        processMessageListing((RequestGetMessagesListing) message.obj);
                    } else if (message.obj instanceof RequestSetMessageStatus) {
                        processSetMessageStatus((RequestSetMessageStatus) message.obj);
                    } else if (message.obj instanceof RequestUpdateInbox) {
                        processUpdateInbox((RequestUpdateInbox) message.obj);
                    }
                    break;

                case MSG_CONNECT:
                    if (!mDevice.equals(message.obj)) {
                        deferMessage(message);
                        transitionTo(mDisconnecting);
                    }
                    break;

                default:
                    Log.w(TAG, "Unexpected message: " + message.what + " from state:"
                            + this.getName());
                    return NOT_HANDLED;
            }
            return HANDLED;
        }

        @Override
        public void exit() {
            mPreviousState = BluetoothProfile.STATE_CONNECTED;
        }

        /**
         * Given a message notification event, will ensure message caching and updating and update
         * interested applications.
         *
         * Message notifications arrive for both remote message reception and Message-Listing object
         * updates that are triggered by the server side.
         *
         * @param msg - A Message object containing a EventReport object describing the remote event
         */
        private void processNotification(Message msg) {
            if (DBG) {
                Log.d(TAG, "Handler: msg: " + msg.what);
            }

            switch (msg.what) {
                case MSG_NOTIFICATION:
                    EventReport ev = (EventReport) msg.obj;
                    if (ev == null) {
                        Log.w(TAG, "MSG_NOTIFICATION event is null");
                        return;
                    }
                    if (DBG) {
                        Log.d(TAG, "Message Type = " + ev.getType()
                                + ", Message handle = " + ev.getHandle());
                    }
                    switch (ev.getType()) {
                        case NEW_MESSAGE:
                            // To work around PTS test case "MAP/MCE/MMN/BV-03-I", don't send request to get message
                            if (isAutoGetNewMessage()) {
                                // Infer the timestamp for this message as 'now' and read status false
                                // instead of getting the message listing data for it
                                if (!mMessages.contains(ev.getHandle())) {
                                    Calendar calendar = Calendar.getInstance();
                                    MessageMetadata metadata = new MessageMetadata(ev.getHandle(),
                                            calendar.getTime().getTime(), false);
                                    mMessages.put(ev.getHandle(), metadata);
                                }
                                mMasClient.makeRequest(new RequestGetMessage(ev.getHandle(),
                                        MasClient.CharsetType.UTF_8, false));
                            }
                            break;

                        case DELIVERY_SUCCESS:
                        case SENDING_SUCCESS:
                            notifySentMessageStatus(ev.getHandle(), ev.getType());
                            break;
                        case MESSAGE_DELETED:
                            notifyMessageDeletedStatusChanged(ev.getHandle(), ev.getFolder());
                            break;
                        case READ_STATUS_CHANGED:
                            notifyMessageReadStatusChanged(ev.getHandle(), ev.getFolder(), ev.getReadStatus());
                            break;
                        case MESSAGE_REMOVED:
                            notifyMessageRemoved(ev);
                            break;
                        case MESSAGE_EXTENDED_DATA_CHANGED:
                            notifyMessageExtendedDataChanged(ev);
                            break;
                        case PARTICIPANT_PRESENCE_CHANGED:
                            notifyParticipantPresenceChanged(ev);
                            break;
                        case PARTICIPANT_CHAT_STATE_CHANGED:
                            notifyParticipantChatStateChanged(ev);
                            break;
                        case CONVERSATION_CHANGED:
                            notifyConversationChanged(ev);
                            break;
                        default:
                            Log.e(TAG, "Unknown type " + ev.getType());
                            break;
                    }
            }
        }

        /**
         * Auto get the new message when new message event report received.
         */
        private boolean isAutoGetNewMessage() {
            return SystemProperties.getBoolean(BLUETOOTH_MAP_AUTO_GET_NEW_MESSAGE, true);
        }

        private boolean isFilterPropUsed() {
            return SystemProperties.getBoolean(BLUETOOTH_MAP_FILTER_USE_PROPERTY, false);
        }

        private boolean isTestUpload() {
            return SystemProperties.getBoolean(BLUETOOTH_MAP_TEST_UPLOAD, false);
        }

        private void getFilterFromProperties() {
            mFilter.setMessageType((byte)SystemProperties.getInt(
                    BLUETOOTH_MAP_FILTER_MESSAGE_TYPE,
                    MessagesFilter.MESSAGE_TYPE_ALL));
            mFilter.setReadStatus((byte)SystemProperties.getInt(
                    BLUETOOTH_MAP_FILTER_READ_STATUS,
                    MessagesFilter.READ_STATUS_UNREAD));

            SimpleDateFormat simpleDateFormat = new SimpleDateFormat(MESSAGES_FILTER_DATE_FORMAT);

            Date filterBegin, filterEnd;
            try {
                filterBegin = simpleDateFormat.parse(SystemProperties.get(BLUETOOTH_MAP_FILTER_PERIODBEGIN));
            } catch (ParseException e) {
                filterBegin = null;
                Log.e(TAG, "Exception during parse begin period " + SystemProperties.get(BLUETOOTH_MAP_FILTER_PERIODBEGIN));
            }
            try {
                filterEnd = simpleDateFormat.parse(SystemProperties.get(BLUETOOTH_MAP_FILTER_PERIODEND));
            } catch (ParseException e) {
                filterEnd = null;
                Log.e(TAG, "Exception during parse end period " + SystemProperties.get(BLUETOOTH_MAP_FILTER_PERIODEND));
            }
            mFilter.setPeriod(filterBegin, filterEnd);

            mFilter.setRecipient(SystemProperties.get(BLUETOOTH_MAP_FILTER_RECIPIENT));
            mFilter.setOriginator(SystemProperties.get(BLUETOOTH_MAP_FILTER_ORIGINATOR));
            mFilter.setPriority((byte)SystemProperties.getInt(
                    BLUETOOTH_MAP_FILTER_PRIORITY,
                    MessagesFilter.PRIORITY_ANY));
            if (DBG) Log.d(TAG, "mFilter " + mFilter);
        }

        // Sets the specified message status to "read" (from "unread" status, mostly)
        private void markMessageRead(RequestGetMessage request) {
            if (DBG) Log.d(TAG, "markMessageRead");
            MessageMetadata metadata = mMessages.get(request.getHandle());
            metadata.setRead(true);
            mMasClient.makeRequest(new RequestSetMessageStatus(
                    request.getHandle(), RequestSetMessageStatus.StatusIndicator.READ));
        }

        // Sets the specified message status to "deleted"
        private void markMessageDeleted(RequestGetMessage request) {
            if (DBG) Log.d(TAG, "markMessageDeleted");
            mMasClient.makeRequest(new RequestSetMessageStatus(
                    request.getHandle(), RequestSetMessageStatus.StatusIndicator.DELETED));
        }

        /**
         * Given the result of a Message Listing request, will cache the contents of each Message in
         * the Message Listing Object and kick off requests to retrieve message contents from the
         * remote device.
         *
         * @param request - A request object that has been resolved and returned with a message list
         */
        private void processMessageListing(RequestGetMessagesListing request) {
            if (DBG) {
                Log.d(TAG, "processMessageListing");
            }
            if (isAbort()) {
                Log.i(TAG, "Abort processMessageListing");
                return;
            }
            ArrayList<com.android.bluetooth.mapclient.Message> messageListing = request.getList();
            if (messageListing != null) {
                // Message listings by spec arrive ordered newest first but we wish to broadcast as
                // oldest first. Iterate in reverse order so we initiate requests oldest first.
                for (int i = messageListing.size() - 1; i >= 0; i--) {
                    com.android.bluetooth.mapclient.Message msg = messageListing.get(i);
                    if (DBG) {
                        Log.d(TAG, "getting message for handle " + msg.getHandle());
                    }
                    // A message listing coming from the server should always have up to date data
                    mMessages.put(msg.getHandle(), new MessageMetadata(msg.getHandle(),
                            msg.getDateTime().getTime(), msg.isRead()));
                    getMessage(msg.getHandle());
                }
            }
        }

        /**
         * Given the response of a GetMessage request, will broadcast the bMessage contents on to
         * all registered applications.
         *
         * Inbound messages arrive as bMessage objects following a GetMessage request. GetMessage
         * uses a message handle that can arrive from both a GetMessageListing request or a Message
         * Notification event.
         *
         * @param request - A request object that has been resolved and returned with message data
         */
        private void processSetMessageStatus(RequestSetMessageStatus request) {
            if (DBG) {
                Log.d(TAG, "processSetMessageStatus");
            }
            if (!request.isSuccess()) {
                Log.e(TAG, "Set message status failed");
                return;
            }
            if (request.getStatusIndicator() == RequestSetMessageStatus.StatusIndicator.READ) {
                notifyMessageReadStatusChanged(request.getHandle(), null, null);
            } else if (request.getStatusIndicator() == RequestSetMessageStatus.StatusIndicator.DELETED) {
                notifyMessageDeletedStatusChanged(request.getHandle(), null);
            } else {
                Log.e(TAG, "Unknown status indicator " + request.getStatusIndicator());
            }
        }

        private void processUpdateInbox(RequestUpdateInbox request) {
            if (request.isSuccess()) {
                if (DBG) {
                    Log.d(TAG, "UpdateInbox success");
                }
            } else {
                Log.e(TAG, "UpdateInbox failed");
            }
        }

        private void notifyMessageDeletedStatusChanged(String handle, String folder) {
            if (DBG) {
                Log.d(TAG, "notifyMessageDeletedStatusChanged for " + handle);
            }
            Intent intent = new Intent(BluetoothMapClient.ACTION_EXT_MESSAGE_DELETED_STATUS_CHANGED);
            intent.putExtra(BluetoothMapClient.EXTRA_MESSAGE_HANDLE, handle);
            mService.sendBroadcast(intent);
        }

        private void notifyMessageReadStatusChanged(String handle, String folder, String read) {
            if (DBG) {
                Log.d(TAG, "notifyMessageReadStatusChanged for handle " + handle + " folder " + folder);
            }
            Intent intent = new Intent(BluetoothMapClient.ACTION_MESSAGE_READ_STATUS_CHANGED);
            intent.putExtra(BluetoothMapClient.EXTRA_FOLDER, folder);
            intent.putExtra(BluetoothMapClient.EXTRA_MESSAGE_HANDLE, handle);
            intent.putExtra(BluetoothMapClient.EXTRA_MESSAGE_READ_STATUS, read == null ? false : "READ".equals(read) ? false : true);
            mService.sendBroadcast(intent);
        }

        /* TODO: Notify application when the optinal features are declared support in SDP*/
        private void notifyMessageRemoved(EventReport ev) {
            if (DBG) {
                Log.d(TAG, "notifyMessageRemoved " + ev);
            }
        }

        private void notifyMessageExtendedDataChanged(EventReport ev) {
            if (DBG) {
                Log.d(TAG, "notifyMessageExtendedDataChanged " + ev);
            }
        }

        private void notifyParticipantPresenceChanged(EventReport ev) {
            if (DBG) {
                Log.d(TAG, "notifyParticipantPresenceChanged " + ev);
            }
        }

        private void notifyParticipantChatStateChanged(EventReport ev) {
            if (DBG) {
                Log.d(TAG, "notifyParticipantChatStateChanged " + ev);
            }
        }

        private void notifyConversationChanged(EventReport ev) {
            if (DBG) {
                Log.d(TAG, "notifyConversationChanged " + ev);
            }
        }

        private void processInboundMessage(RequestGetMessage request) {
            Bmessage message = request.getMessage();
            if (DBG) {
                Log.d(TAG, "Notify inbound Message" + message);
            }

            if (message == null) {
                return;
            }
            if (!INBOX_PATH.equalsIgnoreCase(message.getFolder())) {
                if (DBG) {
                    Log.d(TAG, "Ignoring message received in " + message.getFolder() + ".");
                }
                return;
            }
            switch (message.getType()) {
                case SMS_CDMA:
                case SMS_GSM:
                case MMS:
                    if (DBG) {
                        Log.d(TAG, "Body: " + message.getBodyContent());
                    }
                    if (DBG) {
                        Log.d(TAG, message.toString());
                    }
                    if (DBG) {
                        Log.d(TAG, "Recipients" + message.getRecipients().toString());
                    }

                    // Grab the message metadata and update the cached read status from the bMessage
                    MessageMetadata metadata = mMessages.get(request.getHandle());
                    metadata.setRead(request.getMessage().getStatus() == Bmessage.Status.READ);

                    Intent intent = new Intent();
                    intent.setAction(BluetoothMapClient.ACTION_MESSAGE_RECEIVED);
                    intent.putExtra(BluetoothDevice.EXTRA_DEVICE, mDevice);
                    intent.putExtra(BluetoothMapClient.EXTRA_MESSAGE_HANDLE, request.getHandle());
                    intent.putExtra(BluetoothMapClient.EXTRA_MESSAGE_TIMESTAMP,
                            metadata.getTimestamp());
                    intent.putExtra(BluetoothMapClient.EXTRA_MESSAGE_READ_STATUS,
                            metadata.getRead());
                    intent.putExtra(BluetoothMapClient.EXTRA_TYPE, message.getTypeString());
                    intent.putExtra(android.content.Intent.EXTRA_TEXT, message.getBodyContent());
                    VCardEntry originator = message.getOriginator();
                    if (originator != null) {
                        if (DBG) {
                            Log.d(TAG, originator.toString());
                        }
                        List<VCardEntry.PhoneData> phoneData = originator.getPhoneList();
                        if (phoneData != null && phoneData.size() > 0) {
                            String phoneNumber = phoneData.get(0).getNumber();
                            if (DBG) {
                                Log.d(TAG, "Originator number: " + phoneNumber);
                            }
                            intent.putExtra(BluetoothMapClient.EXTRA_SENDER_CONTACT_URI,
                                    getContactURIFromPhone(phoneNumber));
                        }
                        intent.putExtra(BluetoothMapClient.EXTRA_SENDER_CONTACT_NAME,
                                originator.getDisplayName());
                    }
                    if (message.getType() == Bmessage.Type.MMS) {
                        BluetoothMapbMessageMime mmsBmessage = new BluetoothMapbMessageMime();
                        mmsBmessage.parseMsgPart(message.getBodyContent());
                        intent.putExtra(android.content.Intent.EXTRA_TEXT,
                                mmsBmessage.getMessageAsText());
                    }
                    // Only send to the current default SMS app if one exists
                    String defaultMessagingPackage = Telephony.Sms.getDefaultSmsPackage(mService);
                    if (defaultMessagingPackage != null) {
                        if(DBG) {
                            Log.d(TAG, "defaultMessagingPackage " + defaultMessagingPackage);
                        }
                        intent.setPackage(defaultMessagingPackage);
                    }
                    mService.sendBroadcast(intent, android.Manifest.permission.RECEIVE_SMS);
                    break;
                case EMAIL:
                default:
                    Log.e(TAG, "Received unhandled type" + message.getType().toString());
                    break;
            }
        }

        private void notifySentMessageStatus(String handle, EventReport.Type status) {
            if (DBG) {
                Log.d(TAG, "got a status for " + handle + " Status = " + status);
            }
            // some test devices don't populate messageHandle field.
            // in such cases, ignore such messages.
            if (handle == null || handle.length() <= 2) return;
            PendingIntent intentToSend = null;
            // ignore the top-order byte (converted to string) in the handle for now
            String shortHandle = handle.substring(2);
            if (status == EventReport.Type.SENDING_FAILURE
                    || status == EventReport.Type.SENDING_SUCCESS) {
                intentToSend = mSentReceiptRequested.remove(mSentMessageLog.get(shortHandle));
            } else if (status == EventReport.Type.DELIVERY_SUCCESS
                    || status == EventReport.Type.DELIVERY_FAILURE) {
                intentToSend = mDeliveryReceiptRequested.remove(mSentMessageLog.get(shortHandle));
            }

            if (intentToSend != null) {
                try {
                    if (DBG) {
                        Log.d(TAG, "*******Sending " + intentToSend);
                    }
                    int result = Activity.RESULT_OK;
                    if (status == EventReport.Type.SENDING_FAILURE
                            || status == EventReport.Type.DELIVERY_FAILURE) {
                        result = SmsManager.RESULT_ERROR_GENERIC_FAILURE;
                    }
                    intentToSend.send(result);
                } catch (PendingIntent.CanceledException e) {
                    Log.w(TAG, "Notification Request Canceled" + e);
                }
            } else {
                Log.e(TAG, "Received a notification on message with handle = "
                        + handle + ", but it is NOT found in mSentMessageLog! where did it go?");
            }
        }
    }

    class Disconnecting extends State {
        @Override
        public void enter() {
            if (DBG) {
                Log.d(TAG, "Enter Disconnecting: " + getCurrentMessage().what);
            }
            onConnectionStateChanged(mPreviousState, BluetoothProfile.STATE_DISCONNECTING);

            if (mMasClient != null) {
                mMasClient.makeRequest(new RequestSetNotificationRegistration(false));
                mMasClient.shutdown();
                sendMessageDelayed(MSG_DISCONNECTING_TIMEOUT, TIMEOUT);
            } else {
                // MAP was never connected
                transitionTo(mDisconnected);
            }
        }

        @Override
        public boolean processMessage(Message message) {
            switch (message.what) {
                case MSG_DISCONNECTING_TIMEOUT:
                case MSG_MAS_DISCONNECTED:
                    mMasClient = null;
                    transitionTo(mDisconnected);
                    break;

                case MSG_CONNECT:
                case MSG_DISCONNECT:
                    deferMessage(message);
                    break;

                default:
                    Log.w(TAG, "Unexpected message: " + message.what + " from state:"
                            + this.getName());
                    return NOT_HANDLED;
            }
            return HANDLED;
        }

        @Override
        public void exit() {
            mPreviousState = BluetoothProfile.STATE_DISCONNECTING;
            removeMessages(MSG_DISCONNECTING_TIMEOUT);
        }
    }

    void receiveEvent(EventReport ev) {
        if (DBG) {
            Log.d(TAG, "Message Type = " + ev.getType()
                    + ", Message handle = " + ev.getHandle());
        }
        sendMessage(MSG_NOTIFICATION, ev);
    }
}
