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
 * limitations under the License
 */

package com.android.traceur;

import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import android.preference.PreferenceManager;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;

public class StackSamplingQsService extends TileService {

    private static final String TAG = "Traceur";
    private static StackSamplingQsService sListeningInstance;

    public static void updateTile() {
        if (sListeningInstance != null) {
            sListeningInstance.update();
        }
    }

    @Override
    public void onStartListening() {
        sListeningInstance = this;
        update();
    }

    @Override
    public void onStopListening() {
        if (sListeningInstance == this) {
            sListeningInstance = null;
        }
    }

    private void update() {
        Receiver.updateDeveloperOptionsWatcher(this, /* fromBootIntent */ false);
        if (getQsTile() == null) {
            Log.w(TAG, "StackSamplingQsService.getQsTile() returned null");
            return;
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean tracingOn = prefs.getBoolean(getString(R.string.pref_key_tracing_on), false);
        boolean stackSamplingOn = prefs.getBoolean(
                getString(R.string.pref_key_stack_sampling_on), false);
        boolean heapDumpOn = prefs.getBoolean(
                getString(R.string.pref_key_heap_dump_on), false);

        String titleString = getString(stackSamplingOn ?
                R.string.stop_stack_sampling: R.string.record_stack_samples);

        getQsTile().setIcon(Icon.createWithResource(this, R.drawable.bugfood_icon));
        // If a trace or heap dumps are being recorded, this tile is made uninteractable.
        // Otherwise, it reflects the current stack sampling state (active vs. inactive).
        getQsTile().setState((tracingOn || heapDumpOn) ? Tile.STATE_UNAVAILABLE :
                (stackSamplingOn ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE));
        getQsTile().setLabel(titleString);
        getQsTile().updateTile();
    }

    /** When we click the tile, toggle stack sampling state.
     *  If stack sampling is being turned off, dump and offer to share. */
    @Override
    public void onClick() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean newState = !prefs.getBoolean(getString(R.string.pref_key_stack_sampling_on), false);
        prefs.edit().putBoolean(getString(R.string.pref_key_stack_sampling_on), newState).commit();

        Receiver.updateTracing(this);
    }
}