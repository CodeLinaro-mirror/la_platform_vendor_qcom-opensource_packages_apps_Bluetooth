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

import android.util.Log;

import java.io.IOException;

import javax.obex.ClientOperation;
import javax.obex.ClientSession;
import javax.obex.HeaderSet;
import javax.obex.ResponseCodes;

final class BluetoothPbapRequestSetPath extends BluetoothPbapRequest {

    private static final boolean VDBG = true;

    private static final String TAG = "BluetoothPbapRequestSetPath";

    SetPathDir mDir;

    String mName;

    public BluetoothPbapRequestSetPath(String name) {
        mDir = SetPathDir.DOWN;
        mName = name;

        mHeaderSet.setHeader(HeaderSet.NAME, name);
    }

    public BluetoothPbapRequestSetPath(boolean goRoot) {
        mHeaderSet.setEmptyNameHeader();
        if (goRoot) {
            mDir = SetPathDir.ROOT;
        } else {
            mDir = SetPathDir.UP;
        }
    }

    @Override
    public void execute(ClientSession session) throws IOException {
        HeaderSet hs = null;

        /* in case request is aborted before can be executed */
        if (mAborted) {
            mResponseCode = ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
            return;
        }

        try {
            switch (mDir) {
                case ROOT:
                case DOWN:
                    hs = session.setPath(mHeaderSet, false, false);
                    break;
                case UP:
                    hs = session.setPath(mHeaderSet, true, false);
                    break;
            }

            mResponseCode = hs.getResponseCode();
            if (VDBG) Log.d(TAG, "Response code: " + mResponseCode);
        } catch (IOException e) {
            Log.e(TAG, "IOException occured when processing request", e);
            mResponseCode = ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;

            throw e;
        }
    }

    enum SetPathDir {
        ROOT, UP, DOWN
    }
}
