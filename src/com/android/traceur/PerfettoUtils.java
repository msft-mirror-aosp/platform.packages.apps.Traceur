/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.system.Os;
import android.util.Log;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import perfetto.protos.DataSourceDescriptorOuterClass.DataSourceDescriptor;
import perfetto.protos.FtraceDescriptorOuterClass.FtraceDescriptor.AtraceCategory;
import perfetto.protos.TracingServiceStateOuterClass.TracingServiceState;
import perfetto.protos.TracingServiceStateOuterClass.TracingServiceState.DataSource;

/**
 * Utility functions for calling Perfetto
 */
public class PerfettoUtils implements TraceUtils.TraceEngine {

    static final String TAG = "Traceur";
    public static final String NAME = "PERFETTO";

    private static final String OUTPUT_EXTENSION = "perfetto-trace";
    private static final String TEMP_DIR= "/data/local/traces/";
    private static final String TEMP_TRACE_LOCATION = "/data/local/traces/.trace-in-progress.trace";

    private static final String PERFETTO_TAG = "traceur";
    private static final String MARKER = "PERFETTO_ARGUMENTS";
    private static final int LIST_TIMEOUT_MS = 10000;
    private static final int STARTUP_TIMEOUT_MS = 10000;
    private static final int STOP_TIMEOUT_MS = 30000;
    private static final long MEGABYTES_TO_BYTES = 1024L * 1024L;
    private static final long MINUTES_TO_MILLISECONDS = 60L * 1000L;

    // The total amount of memory allocated to the two target buffers will be divided according to a
    // ratio of (BUFFER_SIZE_RATIO - 1) to 1.
    private static final int BUFFER_SIZE_RATIO = 64;

    private static final String CAMERA_TAG = "camera";
    private static final String GFX_TAG = "gfx";
    private static final String MEMORY_TAG = "memory";
    private static final String POWER_TAG = "power";
    private static final String SCHED_TAG = "sched";
    private static final String WEBVIEW_TAG = "webview";

    public String getName() {
        return NAME;
    }

    public String getOutputExtension() {
        return OUTPUT_EXTENSION;
    }

    public boolean traceStart(Collection<String> tags, int bufferSizeKb, boolean apps,
            boolean attachToBugreport, boolean longTrace, int maxLongTraceSizeMb,
            int maxLongTraceDurationMinutes) {
        if (isTracingOn()) {
            Log.e(TAG, "Attempting to start perfetto trace but trace is already in progress");
            return false;
        } else {
            // Ensure the temporary trace file is cleared.
            try {
                Files.deleteIfExists(Paths.get(TEMP_TRACE_LOCATION));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        // The user chooses a per-CPU buffer size due to atrace limitations.
        // So we use this to ensure that we reserve the correctly-sized buffer.
        int numCpus = Runtime.getRuntime().availableProcessors();

        // Allots 1 / BUFFER_SIZE_RATIO to the small buffer and the remainder to the large buffer.
        int totalBufferSizeKb = numCpus * bufferSizeKb;
        int targetBuffer1Kb = totalBufferSizeKb / BUFFER_SIZE_RATIO;
        int targetBuffer0Kb = totalBufferSizeKb - targetBuffer1Kb;

        // Build the perfetto config that will be passed on the command line.
        StringBuilder config = new StringBuilder()
            .append("write_into_file: true\n")
            // Ensure that we flush ftrace data every 30s even if cpus are idle.
            .append("flush_period_ms: 30000\n");

            // If the user has flagged that in-progress trace sessions should be grabbed
            // during bugreports, and BetterBug is present.
            if (attachToBugreport) {
                config.append("bugreport_score: 500\n");
            }

            // Indicates that perfetto should notify Traceur if the tracing session's status
            // changes.
            config.append("notify_traceur: true\n");

            if (longTrace) {
                if (maxLongTraceSizeMb != 0) {
                    config.append("max_file_size_bytes: "
                        + (maxLongTraceSizeMb * MEGABYTES_TO_BYTES) + "\n");
                }

                if (maxLongTraceDurationMinutes != 0) {
                    config.append("duration_ms: "
                        + (maxLongTraceDurationMinutes * MINUTES_TO_MILLISECONDS)
                        + "\n");
                }

                // Default value for long traces to write to file.
                config.append("file_write_period_ms: 1000\n");
            } else {
                // For short traces, we don't write to the file.
                // So, always use the maximum value here: 7 days.
                config.append("file_write_period_ms: 604800000\n");
            }

        config.append("incremental_state_config {\n")
            .append("  clear_period_ms: 15000\n")
            .append("} \n")
            // This is target_buffer: 0, which is used for ftrace and the ftrace-derived
            // android.gpu.memory.
            .append("buffers {\n")
            .append("  size_kb: " + targetBuffer0Kb + "\n")
            .append("  fill_policy: RING_BUFFER\n")
            .append("} \n")
            // This is target_buffer: 1, which is used for additional data sources.
            .append("buffers {\n")
            .append("  size_kb: " + targetBuffer1Kb + "\n")
            .append("  fill_policy: RING_BUFFER\n")
            .append("} \n")
            .append("data_sources {\n")
            .append("  config {\n")
            .append("    name: \"linux.ftrace\"\n")
            .append("    target_buffer: 0\n")
            .append("    ftrace_config {\n")
            .append("      symbolize_ksyms: true\n");

        for (String tag : tags) {
            // Tags are expected to be only letters, numbers, and underscores.
            String cleanTag = tag.replaceAll("[^a-zA-Z0-9_]", "");
            if (!cleanTag.equals(tag)) {
                Log.w(TAG, "Attempting to use an invalid tag: " + tag);
            }
            config.append("      atrace_categories: \"" + cleanTag + "\"\n");
        }

        if (apps) {
            config.append("      atrace_apps: \"*\"\n");
        }

        // Request a dense encoding of the common sched events (sched_switch, sched_waking).
        if (tags.contains(SCHED_TAG)) {
            config.append("      compact_sched {\n");
            config.append("        enabled: true\n");
            config.append("      }\n");
        }

        // These parameters affect only the kernel trace buffer size and how
        // frequently it gets moved into the userspace buffer defined above.
        config.append("      buffer_size_kb: 8192\n")
            .append("      drain_period_ms: 1000\n")
            .append("    }\n")
            .append("  }\n")
            .append("}\n")
            .append(" \n");

        // Captures initial counter values, updates are captured in ftrace.
        if (tags.contains(MEMORY_TAG) || tags.contains(GFX_TAG)) {
             config.append("data_sources: {\n")
                .append("  config { \n")
                .append("    name: \"android.gpu.memory\"\n")
                .append("    target_buffer: 0\n")
                .append("  }\n")
                .append("}\n");
        }

        // For process association. If the memory tag is enabled,
        // poll periodically instead of just once at the beginning.
        config.append("data_sources {\n")
            .append("  config {\n")
            .append("    name: \"linux.process_stats\"\n")
            .append("    target_buffer: 1\n");
        if (tags.contains(MEMORY_TAG)) {
            config.append("    process_stats_config {\n")
                .append("      proc_stats_poll_ms: 60000\n")
                .append("    }\n");
        }
        config.append("  }\n")
            .append("} \n");

        if (tags.contains(POWER_TAG)) {
            config.append("data_sources: {\n")
                .append("  config { \n")
                .append("    name: \"android.power\"\n")
                .append("    target_buffer: 1\n")
                .append("    android_power_config {\n");
            if (longTrace) {
                config.append("      battery_poll_ms: 5000\n");
            } else {
                config.append("      battery_poll_ms: 1000\n");
            }
            config.append("      collect_power_rails: true\n")
                .append("      battery_counters: BATTERY_COUNTER_CAPACITY_PERCENT\n")
                .append("      battery_counters: BATTERY_COUNTER_CHARGE\n")
                .append("      battery_counters: BATTERY_COUNTER_CURRENT\n")
                .append("    }\n")
                .append("  }\n")
                .append("}\n");
        }

        if (tags.contains(MEMORY_TAG)) {
            config.append("data_sources: {\n")
                .append("  config { \n")
                .append("    name: \"android.sys_stats\"\n")
                .append("    target_buffer: 1\n")
                .append("    sys_stats_config {\n")
                .append("      vmstat_period_ms: 1000\n")
                .append("    }\n")
                .append("  }\n")
                .append("}\n");
        }

        if (tags.contains(GFX_TAG)) {
          config.append("data_sources: {\n")
              .append("  config { \n")
              .append("    name: \"android.surfaceflinger.frametimeline\"\n")
              .append("  }\n")
              .append("}\n");
        }

        if (tags.contains(CAMERA_TAG)) {
          config.append("data_sources: {\n")
              .append("  config { \n")
              .append("    name: \"android.hardware.camera\"\n")
              .append("    target_buffer: 1\n")
              .append("  }\n")
              .append("}\n");
        }

        // Also enable Chrome events when the WebView tag is enabled.
        if (tags.contains(WEBVIEW_TAG)) {
            String chromeTraceConfig =  "{" +
                "\\\"record_mode\\\":\\\"record-continuously\\\"," +
                "\\\"included_categories\\\":[\\\"*\\\"]" +
                "}";
            config.append("data_sources: {\n")
                .append("  config {\n")
                .append("    name: \"org.chromium.trace_event\"\n")
                .append("    chrome_config {\n")
                .append("      trace_config: \"" + chromeTraceConfig + "\"\n")
                .append("    }\n")
                .append("  }\n")
                .append("}\n")
                .append("data_sources: {\n")
                .append("  config {\n")
                .append("    name: \"org.chromium.trace_metadata\"\n")
                .append("      chrome_config {\n")
                .append("        trace_config: \"" + chromeTraceConfig + "\"\n")
                .append("      }\n")
                .append("  }\n")
                .append("}\n");
        }

        String configString = config.toString();

        // If the here-doc ends early, within the config string, exit immediately.
        // This should never happen.
        if (configString.contains(MARKER)) {
            throw new RuntimeException("The arguments to the Perfetto command are malformed.");
        }

        String cmd = "perfetto --detach=" + PERFETTO_TAG
            + " -o " + TEMP_TRACE_LOCATION
            + " -c - --txt"
            + " <<" + MARKER +"\n" + configString + "\n" + MARKER;

        Log.v(TAG, "Starting perfetto trace.");
        try {
            Process process = TraceUtils.execWithTimeout(cmd, TEMP_DIR, STARTUP_TIMEOUT_MS);
            if (process == null) {
                return false;
            } else if (process.exitValue() != 0) {
                Log.e(TAG, "perfetto traceStart failed with: " + process.exitValue());
                return false;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        Log.v(TAG, "perfetto traceStart succeeded!");
        return true;
    }

    public void traceStop() {
        Log.v(TAG, "Stopping perfetto trace.");

        if (!isTracingOn()) {
            Log.w(TAG, "No trace appears to be in progress. Stopping perfetto trace may not work.");
        }

        String cmd = "perfetto --stop --attach=" + PERFETTO_TAG;
        try {
            Process process = TraceUtils.execWithTimeout(cmd, null, STOP_TIMEOUT_MS);
            if (process != null && process.exitValue() != 0) {
                Log.e(TAG, "perfetto traceStop failed with: " + process.exitValue());
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public boolean traceDump(File outFile) {
        traceStop();

        // Short-circuit if a trace was not stopped.
        if (isTracingOn()) {
            Log.e(TAG, "Trace was not stopped successfully, aborting trace dump.");
            return false;
        }

        // Short-circuit if the file we're trying to dump to doesn't exist.
        if (!Files.exists(Paths.get(TEMP_TRACE_LOCATION))) {
            Log.e(TAG, "In-progress trace file doesn't exist, aborting trace dump.");
            return false;
        }

        Log.v(TAG, "Saving perfetto trace to " + outFile);

        try {
            Os.rename(TEMP_TRACE_LOCATION, outFile.getCanonicalPath());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        outFile.setReadable(true, false); // (readable, ownerOnly)
        outFile.setWritable(true, false); // (readable, ownerOnly)
        return true;
    }

    public boolean isTracingOn() {
        String cmd = "perfetto --is_detached=" + PERFETTO_TAG;

        try {
            Process process = TraceUtils.exec(cmd);

            // 0 represents a detached process exists with this name
            // 2 represents no detached process with this name
            // 1 (or other error code) represents an error
            int result = process.waitFor();
            if (result == 0) {
                return true;
            } else if (result == 2) {
                return false;
            } else {
                throw new RuntimeException("Perfetto error: " + result);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static TreeMap<String,String> perfettoListCategories() {
        String cmd = "perfetto --query-raw";

        Log.v(TAG, "Listing tags: " + cmd);
        try {

            TreeMap<String, String> result = new TreeMap<>();

            // execWithTimeout() cannot be used because stdout must be consumed before the process
            // is terminated.
            Process perfetto = TraceUtils.exec(cmd, null, false);
            TracingServiceState serviceState =
                    TracingServiceState.parseFrom(perfetto.getInputStream());

            // Destroy the perfetto process if it times out.
            if (!perfetto.waitFor(LIST_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.e(TAG, "perfettoListCategories timed out after " + LIST_TIMEOUT_MS + " ms.");
                perfetto.destroyForcibly();
                return result;
            }

            // The perfetto process completed and failed, but does not need to be destroyed.
            if (perfetto.exitValue() != 0) {
                Log.e(TAG, "perfettoListCategories failed with: " + perfetto.exitValue());
            }

            List<AtraceCategory> categories = null;

            for (DataSource dataSource : serviceState.getDataSourcesList()) {
                DataSourceDescriptor dataSrcDescriptor = dataSource.getDsDescriptor();
                if (dataSrcDescriptor.getName().equals("linux.ftrace")){
                    categories = dataSrcDescriptor.getFtraceDescriptor().getAtraceCategoriesList();
                    break;
                }
            }

            if (categories != null) {
                for (AtraceCategory category : categories) {
                    result.put(category.getName(), category.getDescription());
                }
            }
            return result;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
