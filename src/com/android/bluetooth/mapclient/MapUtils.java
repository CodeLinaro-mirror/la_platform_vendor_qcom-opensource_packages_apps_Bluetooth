/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * Changes from Qualcomm Innovation Center are provided under the
 * following license:
 * Copyright (c) 2023 Qualcomm Innovation Center, Inc. All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */
package com.android.bluetooth.mapclient;

import android.os.SystemProperties;

import com.android.bluetooth.Utils;
import com.android.internal.annotations.VisibleForTesting;

class MapUtils {
    private static MnsService sMnsService = null;
    private static final String FETCH_MESSAGE_TYPE =
            "persist.bluetooth.pts.mapclient.fetchmessagetype";

    @VisibleForTesting
    static void setMnsService(MnsService service) {
        sMnsService = service;
    }

    static MnsService newMnsServiceInstance(MapClientService mapClientService) {
        return (sMnsService == null) ? new MnsService(mapClientService) : sMnsService;
    }

    static byte fetchMessageType() {
        if (Utils.isPtsTestMode()) {
            return (byte) SystemProperties.getInt(FETCH_MESSAGE_TYPE,
                    MessagesFilter.MESSAGE_TYPE_ALL);
        } else {
            return MessagesFilter.MESSAGE_TYPE_ALL;
        }
    }
}
