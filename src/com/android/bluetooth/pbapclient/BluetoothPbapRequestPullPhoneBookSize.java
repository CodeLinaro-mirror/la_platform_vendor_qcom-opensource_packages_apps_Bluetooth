/*
 * Copyright (c) 2018, The Linux Foundation. All rights reserved.
 * Not a contribution
 *
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
import android.util.Log;

import com.android.bluetooth.pbapclient.ObexAppParameters;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

import javax.obex.HeaderSet;

final class BluetoothPbapRequestPullPhoneBookSize extends BluetoothPbapRequest {

    private static final boolean VDBG = true;

    private static final String TAG = "BluetoothPbapRequestPullPhoneBookSize";

    public static final String PHONEBOOK_TYPE = "x-bt/phonebook";

    public static final String VCARD_LISTING_TYPE = "x-bt/vcard-listing";

    private Account mAccount;

    private int mPhonebookSize = 0;

    private int mNewMissedCalls = -1;

    public BluetoothPbapRequestPullPhoneBookSize(String pbName, Account account, String type) {
        mAccount = account;

        mHeaderSet.setHeader(HeaderSet.NAME, pbName);

        mHeaderSet.setHeader(HeaderSet.TYPE, type);

        ObexAppParameters oap = new ObexAppParameters();

        oap.add(OAP_TAGID_MAX_LIST_COUNT, (short) 0);

        oap.addToHeaderSet(mHeaderSet);
    }

    @Override
    protected void readResponseHeaders(HeaderSet headerset) {
        Log.v(TAG, "readResponseHeaders");

        ObexAppParameters oap = ObexAppParameters.fromHeaderSet(headerset);

        if (oap.exists(OAP_TAGID_PHONEBOOK_SIZE)) {
            mPhonebookSize = oap.getShort(OAP_TAGID_PHONEBOOK_SIZE);
        } else if (oap.exists(OAP_TAGID_NEW_MISSED_CALLS)) {
            mNewMissedCalls = oap.getByte(OAP_TAGID_NEW_MISSED_CALLS);
        }
    }

    public int getPhonebookSize() {
        return mPhonebookSize;
    }

    public int getNewMissedCalls() {
        return mNewMissedCalls;
    }
}
