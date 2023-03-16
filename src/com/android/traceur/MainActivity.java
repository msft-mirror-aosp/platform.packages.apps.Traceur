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
 */

package com.android.traceur;

import android.app.Activity;
import android.os.Bundle;
import android.os.UserManager;
import android.provider.Settings;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;

public class MainActivity extends CollapsingToolbarBaseActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity);
    }

    @Override
    protected void onStart() {
        super.onStart();
        boolean developerOptionsIsEnabled =
            Settings.Global.getInt(getApplicationContext().getContentResolver(),
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0;
        boolean isAdminUser = getApplicationContext()
                .getSystemService(UserManager.class).isAdminUser();

        if (!developerOptionsIsEnabled || !isAdminUser) {
            finish();
        }
    }
}
