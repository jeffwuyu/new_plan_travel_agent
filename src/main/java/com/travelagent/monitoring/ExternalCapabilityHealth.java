package com.travelagent.monitoring;

public record ExternalCapabilityHealth(
        String name,
        boolean configured,
        boolean reachable,
        boolean degraded,
        String lastError,
        String checkedAt,
        String checkType,
        String errorCode
) {
    public ExternalCapabilityHealth(String name,
                                    boolean configured,
                                    boolean reachable,
                                    boolean degraded,
                                    String lastError,
                                    String checkedAt,
                                    String checkType) {
        this(name, configured, reachable, degraded, lastError, checkedAt, checkType,
                ExternalCapabilityErrorCode.classify(configured, reachable, degraded, lastError, checkType));
    }

    public ExternalCapabilityHealth(String name,
                                    boolean configured,
                                    boolean reachable,
                                    boolean degraded,
                                    String lastError,
                                    String checkedAt) {
        this(name, configured, reachable, degraded, lastError, checkedAt, "unknown");
    }
}
