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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class TraceController extends Handler {
    private static final String TAG = "TraceController";
    private static final String PERFETTO_SUFFIX = ".perfetto-trace";
    private static final String WINSCOPE_SUFFIX = "_winscope_traces.zip";

    private final Context mContext;

    public TraceController(Context context) {
        mContext = context;
    }

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case MessageConstants.START_WHAT:
                TraceUtils.presetTraceStart(mContext, msg.getData().getSerializable(
                    INTENT_EXTRA_TRACE_TYPE, TraceUtils.PresetTraceType.class));
                break;
            case MessageConstants.STOP_WHAT:
                TraceUtils.traceStop(mContext);
                break;
            case MessageConstants.SHARE_WHAT:
                shareFiles(mContext, msg.replyTo);
                break;
            default:
                throw new IllegalArgumentException("received unknown msg.what: " + msg.what);
        }
    }

    // Files are kept on private storage, so turn into Uris that we can
    // grant temporary permissions for. We then share them, usually with BetterBug, via Intents
    private static void shareFiles(Context context, Messenger replyTo) {
        String perfettoFileName = TraceUtils.getOutputFilename(TraceUtils.RecordingType.TRACE);
        TraceUtils.traceDump(context, perfettoFileName).ifPresent(files -> {
            int grantAccessFlags =
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION |
                    Intent.FLAG_GRANT_READ_URI_PERMISSION |
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            Bundle data = new Bundle();

            // Perfetto traces have their own viewer so it makes sense to move them out of the zip.
            files.stream().filter(it ->
                it.getName().endsWith(PERFETTO_SUFFIX)
            ).findFirst().ifPresent(it -> {
                Uri perfettoUri = FileProvider.getUriForFile(context, AUTHORITY, it);
                files.remove(it);
                context.grantUriPermission(SYSTEM_UI_PACKAGE_NAME, perfettoUri, grantAccessFlags);
                data.putParcelable(MessageConstants.EXTRA_PERFETTO, perfettoUri);
            });

            String winscopeFileName = perfettoFileName.replace(PERFETTO_SUFFIX, WINSCOPE_SUFFIX);
            Uri winscopeUri = zipFileListIntoOneUri(context, files, winscopeFileName);
            if (winscopeUri != null) {
                context.grantUriPermission(SYSTEM_UI_PACKAGE_NAME, winscopeUri, grantAccessFlags);
                data.putParcelable(MessageConstants.EXTRA_WINSCOPE, winscopeUri);
            }

            Message msg = Message.obtain();
            msg.what = MessageConstants.SHARE_WHAT;
            msg.setData(data);

            try {
                replyTo.send(msg);
            } catch (RemoteException e) {
                Log.e(TAG, "failed to send msg back to client", e);
                throw new RuntimeException(e);
            }
        });
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
        try (ZipOutputStream os = new ZipOutputStream(new FileOutputStream(outZip))) {
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
}