package com.travelagent.exception;

/**
 * Indicates that a persisted task checkpoint cannot be trusted.
 *
 * <p>A corrupt or unsupported checkpoint is different from a missing checkpoint:
 * execution must stop and the task must be moved to an explicit failed state so
 * that a provider is never called from an unknown position.</p>
 */
public class CheckpointCorruptedException extends RuntimeException {

    private final String code;

    public CheckpointCorruptedException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public CheckpointCorruptedException(String code, String message) {
        this(code, message, null);
    }

    public String getCode() {
        return code;
    }
}
