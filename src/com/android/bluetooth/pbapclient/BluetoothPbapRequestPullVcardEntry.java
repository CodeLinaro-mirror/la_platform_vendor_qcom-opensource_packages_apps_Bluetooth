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

import com.android.vcard.VCardEntry;
import com.android.bluetooth.pbapclient.ObexAppParameters;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

import javax.obex.HeaderSet;

final class BluetoothPbapRequestPullVcardEntry extends BluetoothPbapRequest {

    private static final boolean VDBG = true;

    private static final String TAG = "BluetoothPbapRequestPullVcardEntry";

    private static final String TYPE = "x-bt/vcard";

    private BluetoothPbapVcardList mResponse;

    private Account mAccount;

    private final byte mFormat;

    public BluetoothPbapRequestPullVcardEntry(
            String pbName, Account account, long filter, byte format) {
        mAccount = account;

        mHeaderSet.setHeader(HeaderSet.NAME, pbName);

        mHeaderSet.setHeader(HeaderSet.TYPE, TYPE);

        ObexAppParameters oap = new ObexAppParameters();

        /* make sure format is one of allowed values */
        if ((format != PbapClientConnectionHandler.VCARD_TYPE_21) &&
            (format != PbapClientConnectionHandler.VCARD_TYPE_30)) {
            format = PbapClientConnectionHandler.VCARD_TYPE_21;
        }

        if (filter != 0) {
            oap.add(OAP_TAGID_FILTER, filter);
        }

        oap.add(OAP_TAGID_FORMAT, format);

        oap.addToHeaderSet(mHeaderSet);

        mFormat = format;
    }

    @Override
    protected void readResponse(InputStream stream) throws IOException {
        Log.v(TAG, "readResponse");

        mResponse = new BluetoothPbapVcardList(mAccount, stream, mFormat);
        if (VDBG) {
            Log.d(TAG, "Read " + mResponse.getCount() + " entries.");
        }
    }

    public int getCount() {
        return mResponse.getCount();
    }

    public ArrayList<VCardEntry> getList() {
        return mResponse.getList();
    }

    public VCardEntry getFirst() {
        return mResponse.getFirst();
    }

    public String getVcard() {
        VCardEntry vcard = getFirst();
        PhonebookEntry pb = new PhonebookEntry(vcard);
        return pb.toString();
    }
}
