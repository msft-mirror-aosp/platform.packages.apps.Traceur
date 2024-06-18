/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.traceur;

public final class MessageConstants {
    // Message bundle `what` values to signal to BindableTraceService what action to take
    public static final int START_WHAT = 0;
    public static final int STOP_WHAT = 1;
    public static final int SHARE_WHAT = 2;

    // Package / Service names so Traceur and SystemUI can interact with each other
    // and grant URI permissions accordingly
    public static final String TRACING_APP_PACKAGE_NAME = "com.android.traceur";
    public static final String TRACING_APP_ACTIVITY = TRACING_APP_PACKAGE_NAME +
            ".BindableTraceService";
    public static final String SYSTEM_UI_PACKAGE_NAME = "com.android.systemui";

    // The winscope and perfetto extras are for uris that Traceur is allowing SystemUI to share.
    public static final String EXTRA_WINSCOPE = TRACING_APP_PACKAGE_NAME + ".WINSCOPE_ZIP";
    public static final String EXTRA_PERFETTO = TRACING_APP_PACKAGE_NAME + ".PERFETTO";

    // Trace type is used during trace start to tell Traceur which type of trace the user has
    // selected (battery, performance, ui, thermal, etc.)
    public static final String INTENT_EXTRA_TRACE_TYPE = TRACING_APP_PACKAGE_NAME + ".trace_type";
}
