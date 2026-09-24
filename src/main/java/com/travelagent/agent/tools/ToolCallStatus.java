package com.travelagent.agent.tools;

public enum ToolCallStatus {

    SUCCESS("success"),
    DEGRADED("degraded"),
    FAILED("failed"),
    UNKNOWN("unknown"),
    IDEMPOTENCY_CONFLICT("idempotency_conflict");

    private final String code;

    ToolCallStatus(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
