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
import android.util.Log;

import com.android.bluetooth.pbapclient.ObexAppParameters;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

import android.util.Log;

import com.android.obex.HeaderSet;

final class BluetoothPbapRequestPullPhoneBookSize extends BluetoothPbapRequest {

    private static final boolean VDBG = Utils.VDBG;

    private static final String TAG = "BtPbapReqPullPhoneBookSize";

    public static final String TYPE = "x-bt/phonebook";

    public static final String VCARD_LISTING_TYPE = "x-bt/vcard-listing";

    private int mSize;

    private int mPhonebookSize = 0;

    private int mNewMissedCalls = -1;


    BluetoothPbapRequestPullPhoneBookSize(String pbName, String PB_TYPE, long filter) {
        mHeaderSet.setHeader(HeaderSet.NAME, pbName);

        mHeaderSet.setHeader(HeaderSet.TYPE, PB_TYPE);

        ObexAppParameters oap = new ObexAppParameters();
        // Set MaxListCount in the request to 0 to get PhonebookSize in the response.
        // If a vCardSelector is present in the request, then the result shall
        // contain the number of items that satisfy the selector’s criteria.
        // See PBAP v1.2.3, Sec. 5.1.4.5.
        oap.add(OAP_TAGID_MAX_LIST_COUNT, (short) 0);
        if (filter != 0) {
            oap.add(OAP_TAGID_FILTER, filter);
        }
        oap.addToHeaderSet(mHeaderSet);
    }

    @Override
    protected void readResponseHeaders(HeaderSet headerset) {
        if (VDBG) {
            Log.v(TAG, "readResponseHeaders");
        }

        ObexAppParameters oap = ObexAppParameters.fromHeaderSet(headerset);

        if (oap.exists(OAP_TAGID_PHONEBOOK_SIZE)) {
            mSize = mPhonebookSize = oap.getShort(OAP_TAGID_PHONEBOOK_SIZE);
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

    public int getSize() {
        return mSize;
    }
}
