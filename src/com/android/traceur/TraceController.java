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

import static com.android.traceur.MessageConstants.INTENT_EXTRA_TRACE_TYPE;
import static com.android.traceur.MessageConstants.SYSTEM_UI_PACKAGE_NAME;
import static com.android.traceur.TraceService.AUTHORITY;

import android.annotation.Nullable;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import androidx.core.content.FileProvider;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class TraceController extends Handler {
    private static final String TAG = "TraceController";
    private static final String PERFETTO_SUFFIX = ".perfetto-trace";
    private static final String WINSCOPE_SUFFIX = "_winscope_traces.zip";
    private static final int GRANT_ACCESS_FLAGS =
        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION |
            Intent.FLAG_GRANT_READ_URI_PERMISSION |
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION;

    private final Context mContext;

    public TraceController(Context context) {
        mContext = context;
    }

    @Override
    public void handleMessage(Message msg) {
        Log.d(TAG, "handling message " + msg.what + " in TraceController");
        switch (msg.what) {
            case MessageConstants.START_WHAT:
                startTracingSafely(mContext, msg.getData());
                break;
            case MessageConstants.STOP_WHAT:
                TraceUtils.traceStop(mContext);
                break;
            case MessageConstants.SHARE_WHAT:
                shareFiles(mContext, msg.replyTo);
                break;
            case MessageConstants.TAGS_WHAT:
                provideTags(msg.replyTo);
                break;
            default:
                throw new IllegalArgumentException("received unknown msg.what: " + msg.what);
        }
    }

    private static void startTracingSafely(Context context, @Nullable Bundle data) {
        TraceConfig config;
        if (data == null) {
            Log.w(TAG, "bundle containing Input trace config is not present, using default "
                + "trace configuration.");
            config = PresetTraceConfigs.getDefaultConfig();
        } else {
            data.setClassLoader(TraceConfig.class.getClassLoader());
            config = data.getParcelable(INTENT_EXTRA_TRACE_TYPE, TraceConfig.class);
            if (config == null) {
                Log.w(TAG, "Input trace config could not be read, using default trace "
                    + "configuration.");
                config = PresetTraceConfigs.getDefaultConfig();
            }
        }
        TraceUtils.traceStart(context, config);
    }

    private static void replyToClient(Messenger replyTo, int what, Bundle data) {
        Message msg = Message.obtain();
        msg.what = what;
        msg.setData(data);

        try {
            replyTo.send(msg);
        } catch (RemoteException e) {
            Log.e(TAG, "failed to send msg back to client", e);
            throw new RuntimeException(e);
        }
    }

    // Files are kept on private storage, so turn into Uris that we can
    // grant temporary permissions for. We then share them, usually with BetterBug, via Intents
    private static void shareFiles(Context context, Messenger replyTo) {
        Bundle data = new Bundle();
        String perfettoFileName = TraceUtils.getOutputFilename(TraceUtils.RecordingType.TRACE);
        TraceUtils.traceDump(context, perfettoFileName).ifPresent(files -> {
            // Perfetto traces have their own viewer so it makes sense to move them out of the zip.
            files.stream().filter(it ->
                it.getName().endsWith(PERFETTO_SUFFIX)
            ).findFirst().ifPresent(it -> {
                Uri perfettoUri = FileProvider.getUriForFile(context, AUTHORITY, it);
                files.remove(it);
                context.grantUriPermission(SYSTEM_UI_PACKAGE_NAME, perfettoUri, GRANT_ACCESS_FLAGS);
                data.putParcelable(MessageConstants.EXTRA_PERFETTO, perfettoUri);
            });

            String winscopeFileName = perfettoFileName.replace(PERFETTO_SUFFIX, WINSCOPE_SUFFIX);
            Uri winscopeUri = zipFileListIntoOneUri(context, files, winscopeFileName);
            if (winscopeUri != null) {
                context.grantUriPermission(SYSTEM_UI_PACKAGE_NAME, winscopeUri, GRANT_ACCESS_FLAGS);
                data.putParcelable(MessageConstants.EXTRA_WINSCOPE, winscopeUri);
            }
        });
        replyToClient(replyTo, MessageConstants.SHARE_WHAT, data);
    }

    @Nullable
    private static Uri zipFileListIntoOneUri(Context context, List<File> files, String fileName) {
        if (files.isEmpty()) {
            Log.w(TAG, "files are empty");
            return null;
        }

        File outZip = new File(TraceUtils.TRACE_DIRECTORY, fileName);
        try {
            outZip.createNewFile();
        } catch (IOException e) {
            Log.e(TAG, "Failed to create zip file for files.", e);
            return null;
        }
        try (ZipOutputStream os = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(outZip)))) {
            files.forEach(file -> {
                try {
                    os.putNextEntry(new ZipEntry(file.getName()));
                    Files.copy(file.toPath(), os);
                    os.closeEntry();
                } catch (IOException e) {
                    Log.e(TAG, "Failed to zip file: " + file.getName());
                }
            });
            return FileProvider.getUriForFile(context, AUTHORITY, outZip);
        } catch (IOException e) {
            Log.e(TAG, "Failed to zip and package files. Cannot share.", e);
            return null;
        }
    }

    private static void provideTags(Messenger replyTo) {
        Map<String, String> categoryMap = TraceUtils.listCategories();
        Bundle data = new Bundle();
        data.putStringArrayList(MessageConstants.BUNDLE_KEY_TAGS,
            new ArrayList<>(categoryMap.keySet()));
        data.putStringArrayList(MessageConstants.BUNDLE_KEY_TAG_DESCRIPTIONS,
            new ArrayList<>(categoryMap.values()));
        replyToClient(replyTo, MessageConstants.TAGS_WHAT, data);
    }
}
