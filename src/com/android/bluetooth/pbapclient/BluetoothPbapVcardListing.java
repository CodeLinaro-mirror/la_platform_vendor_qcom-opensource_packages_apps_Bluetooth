/*
 * Copyright (c) 2018, The Linux Foundation. All rights reserved.
 * Not a contribution
 *
 * Copyright (C) 2014 The Android Open Source Project
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

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;

class BluetoothPbapVcardListing {

    private static final String TAG = "BluetoothPbapVcardListing";

    public static final String ATTR_HANDLE = "handle";
    public static final String ATTR_NAME = "name";

    private final ArrayList<HashMap<String, String>> mVcardListing;

    public BluetoothPbapVcardListing(InputStream in) {
        mVcardListing = new ArrayList<HashMap<String, String>>();

        parse(in);
    }

    public void parse(InputStream in) {

        try {
            XmlPullParser xpp = XmlPullParserFactory.newInstance().newPullParser();
            xpp.setInput(in, "utf-8");

            int event = xpp.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                switch (event) {
                    case XmlPullParser.START_TAG:
                        if (xpp.getName().equals("card")) {
                            HashMap<String, String> attrs = new HashMap<String, String>();

                            for (int i = 0; i < xpp.getAttributeCount(); i++) {
                                attrs.put(xpp.getAttributeName(i), xpp.getAttributeValue(i));
                            }

                            mVcardListing.add(attrs);
                        }
                        break;
                }

                event = xpp.next();
            }

        } catch (XmlPullParserException e) {
            Log.e(TAG, "XML parser error when parsing XML", e);
        } catch (IOException e) {
            Log.e(TAG, "I/O error when parsing XML", e);
        }
    }

    public ArrayList<HashMap<String, String>> getList() {
        return mVcardListing;
    }

    public int getCount() {
        return mVcardListing.size();
    }
}
