/*
 * Copyright (c) 2026 AVI-SPL Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.communicator.middleatlantic.select;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Adapter metadata (SYM page 4185161742 "Adapter Metadata"). Filled from the adapter's own state, never from the device.
 * Device adapters report it in the AdapterMetadata group.
 */
public final class AdapterMetadata {
    public static final String GROUP = "AdapterMetadata#";
    public static final String ADAPTER_VERSION = "AdapterVersion";
    public static final String ADAPTER_BUILD_DATE = "AdapterBuildDate";
    public static final String ADAPTER_UPTIME = "AdapterUptime";
    public static final String ADAPTER_UPTIME_MIN = "AdapterUptime(min)";
    private static final String N_A = "N/A";

    private final long startedAt = System.currentTimeMillis();
    private final String version;
    private final String buildDate;

    public AdapterMetadata() {
        Properties properties = new Properties();
        try (InputStream in = AdapterMetadata.class.getResourceAsStream("/version.properties")) {
            if (in != null) {
                properties.load(in);
            }
        } catch (Exception ignored) {
            // metadata falls back to N/A
        }
        version = nonEmptyOrNA(properties.getProperty("adapter.version"));
        buildDate = nonEmptyOrNA(properties.getProperty("adapter.build.date"));
    }

    /**
     * Base adapter metadata, with the AdapterMetadata group prefix
     * @return metadata statistics
     */
    public Map<String, String> statistics() {
        Map<String, String> stats = new HashMap<>();
        long uptimeMs = System.currentTimeMillis() - startedAt;
        stats.put(GROUP + ADAPTER_VERSION, version);
        stats.put(GROUP + ADAPTER_BUILD_DATE, buildDate);
        stats.put(GROUP + ADAPTER_UPTIME, formatUptime(uptimeMs));
        stats.put(GROUP + ADAPTER_UPTIME_MIN, String.valueOf(uptimeMs / 60_000));
        return stats;
    }

    /**
     * Uptime text format from SYM page 4185161742 (Adapter Metadata): "48 d 11 hr 5 min 38 sec",
     * zero leading units left out
     * @param millis uptime in milliseconds
     * @return formatted uptime
     */
    public static String formatUptime(long millis) {
        long seconds = millis / 1000;
        long days = seconds / 86_400;
        long hours = seconds % 86_400 / 3_600;
        long minutes = seconds % 3_600 / 60;
        long secs = seconds % 60;
        StringBuilder text = new StringBuilder();
        if (days > 0) {
            text.append(days).append(" d ");
        }
        if (hours > 0) {
            text.append(hours).append(" hr ");
        }
        if (minutes > 0) {
            text.append(minutes).append(" min ");
        }
        text.append(secs).append(" sec");
        return text.toString();
    }

    /**
     * An unfiltered placeholder (e.g. "${project.version}" when resources were not filtered) or empty value is N/A
     */
    private static String nonEmptyOrNA(String value) {
        return value == null || value.trim().isEmpty() || value.contains("${") ? N_A : value.trim();
    }
}
