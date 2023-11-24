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
import java.util.HashMap;

import javax.obex.HeaderSet;

final class BluetoothPbapRequestPullVcardListing extends BluetoothPbapRequest {

    private static final boolean VDBG = true;

    private static final String TAG = "BluetoothPbapRequestPullVcardListing";

    private static final String TYPE = "x-bt/vcard-listing";

    private BluetoothPbapVcardListing mResponse = null;

    private ArrayList<String> mList = null;

    private Account mAccount;

    private int mNewMissedCalls = -1;

    public BluetoothPbapRequestPullVcardListing(
            String pbName, Account account, byte order, byte searchProp,
            String searchValue, int maxListCount, int listStartOffset) {
        mAccount = account;
        if (maxListCount < 0 || maxListCount > 65535) {
            throw new IllegalArgumentException("maxListCount should be [0..65535]");
        }

        if (listStartOffset < 0 || listStartOffset > 65535) {
            throw new IllegalArgumentException("listStartOffset should be [0..65535]");
        }

        mHeaderSet.setHeader(HeaderSet.NAME, pbName);

        mHeaderSet.setHeader(HeaderSet.TYPE, TYPE);

        ObexAppParameters oap = new ObexAppParameters();

        oap.add(OAP_TAGID_ORDER, order);
        oap.add(OAP_TAGID_SEARCH_ATTRIBUTE, searchProp);
        if ((searchValue != null) && (!searchValue.isEmpty())) {
            oap.add(OAP_TAGID_SEARCH_VALUE, searchValue);
        }

        /*
         * maxListCount is a special case which is handled in
         * BluetoothPbapRequestPullPhoneBookSize
         */
        if (maxListCount > 0) {
            oap.add(OAP_TAGID_MAX_LIST_COUNT, (short) maxListCount);
        } else {
            oap.add(OAP_TAGID_MAX_LIST_COUNT, (short) 65535);
        }

        if (listStartOffset > 0) {
            oap.add(OAP_TAGID_LIST_START_OFFSET, (short) listStartOffset);
        }

        oap.addToHeaderSet(mHeaderSet);
    }

    @Override
    protected void readResponse(InputStream stream) throws IOException {
        Log.v(TAG, "readResponse");

        mResponse = new BluetoothPbapVcardListing(stream);
        if (VDBG) {
            Log.d(TAG, "Read " + mResponse.getCount() + " entries.");
        }

        ArrayList<HashMap<String, String>> list = mResponse.getList();
        mList = new ArrayList<String>();

        for (HashMap<String, String> attrs : list) {
            String vcardString = constructVcardString(attrs);
            mList.add(vcardString);
        }
    }

    @Override
    protected void readResponseHeaders(HeaderSet headerset) {
        Log.v(TAG, "readResponseHeaders");

        ObexAppParameters oap = ObexAppParameters.fromHeaderSet(headerset);

        if (oap.exists(OAP_TAGID_NEW_MISSED_CALLS)) {
            mNewMissedCalls = oap.getByte(OAP_TAGID_NEW_MISSED_CALLS);
        }
    }

    public int getCount() {
        return (mList != null) ? mList.size() : 0;
    }

    public ArrayList<String> getList() {
        return mList;
    }

    public int getNewMissedCalls() {
        return mNewMissedCalls;
    }

    private String constructVcardString(HashMap<String, String> attrs) {
        if (attrs != null) {
            StringBuilder sb = new StringBuilder();

            // E.g. handle="1.vcf" name="1234567890"
            sb.append(BluetoothPbapVcardListing.ATTR_HANDLE)
                .append("=\"")
                .append(attrs.get(BluetoothPbapVcardListing.ATTR_HANDLE))
                .append("\" ");

            if (attrs.containsKey(BluetoothPbapVcardListing.ATTR_NAME)) {
                sb.append(BluetoothPbapVcardListing.ATTR_NAME)
                    .append("=\"")
                    .append(attrs.get(BluetoothPbapVcardListing.ATTR_NAME))
                    .append("\"");
            }

            return sb.toString();
        } else {
            return null;
        }
    }
}
