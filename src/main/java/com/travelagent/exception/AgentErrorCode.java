package com.travelagent.exception;

public enum AgentErrorCode {

    // LLM errors
    LLM_TIMEOUT(true),
    LLM_RATE_LIMIT(true),
    LLM_INVALID_RESPONSE(false),
    LLM_SERVICE_ERROR(true),

    // Tool errors
    TOOL_AMAP_TIMEOUT(true),
    TOOL_AMAP_RATE_LIMIT(true),
    TOOL_AMAP_ERROR(false),
    TOOL_DASHVECTOR_TIMEOUT(true),
    TOOL_DASHVECTOR_ERROR(false),

    // Task-level
    QUOTA_EXHAUSTED(false),
    TASK_FAILED_PERMANENT(false);

    private final boolean retryable;

    AgentErrorCode(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
