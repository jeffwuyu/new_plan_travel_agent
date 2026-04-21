package com.travelagent.exception;

import java.util.Map;

public class AgentException extends RuntimeException {

    private final AgentErrorCode errorCode;

    public AgentException(AgentErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public AgentException(AgentErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public AgentErrorCode getErrorCode() {
        return errorCode;
    }

    public boolean isRetryable() {
        return errorCode.isRetryable();
    }

    public Map<String, Object> toEventPayload() {
        return Map.of(
                "code", errorCode.name(),
                "message", getMessage(),
                "retryable", isRetryable()
        );
    }
}
