package com.travelagent.exception;

/**
 * Base business exception. All domain-specific exceptions extend this.
 * Carries an HTTP status code and a user-facing message.
 */

/**
 * 中文注释：异常类，用于表达 Business Exception 场景下的错误语义。
 */

public class BusinessException extends RuntimeException {

    private final int httpStatus;

    public BusinessException(int httpStatus, String message) {
        super(message);
        this.httpStatus = httpStatus;
    }

    public BusinessException(int httpStatus, String message, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
