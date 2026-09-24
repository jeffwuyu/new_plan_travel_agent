package com.travelagent.monitoring;

import java.util.Locale;

public enum ExternalCapabilityErrorCode {
    NOT_CONFIGURED,
    INVALID_CONFIG,
    AUTH_FAILED,
    RATE_LIMITED,
    TIMEOUT,
    EMPTY_RESULT,
    SAFETY_BLOCKED,
    PROVIDER_DOWN,
    CIRCUIT_OPEN,
    DISABLED,
    UNKNOWN;

    public static String classify(boolean configured,
                                  boolean reachable,
                                  boolean degraded,
                                  String lastError,
                                  String checkType) {
        if (!degraded) {
            return null;
        }
        if ("disabled".equalsIgnoreCase(checkType)) {
            return DISABLED.name();
        }
        if ("circuit_open".equalsIgnoreCase(checkType)) {
            return CIRCUIT_OPEN.name();
        }
        if (!configured) {
            return NOT_CONFIGURED.name();
        }
        String text = lastError == null ? "" : lastError.toLowerCase(Locale.ROOT);
        if (text.contains("invalid")) {
            return INVALID_CONFIG.name();
        }
        if (text.contains("401") || text.contains("403") || text.contains("unauthorized")
                || text.contains("forbidden") || text.contains("auth")) {
            return AUTH_FAILED.name();
        }
        if (text.contains("429") || text.contains("rate limit") || text.contains("too many requests")) {
            return RATE_LIMITED.name();
        }
        if (text.contains("timeout") || text.contains("timed out")) {
            return TIMEOUT.name();
        }
        if (text.contains("empty") || text.contains("no result")) {
            return EMPTY_RESULT.name();
        }
        if (text.contains("safety") || text.contains("risk") || text.contains("blocked")) {
            return SAFETY_BLOCKED.name();
        }
        if (text.contains("http 5") || text.contains("service unavailable") || text.contains("connection")
                || text.contains("refused") || text.contains("down")) {
            return PROVIDER_DOWN.name();
        }
        return UNKNOWN.name();
    }
}
