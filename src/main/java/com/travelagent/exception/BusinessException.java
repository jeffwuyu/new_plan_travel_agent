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

    /**
     * 初始化BusinessException 实例。
     * @param httpStatus h tt pS ta tu s 参数
     * @param message 提示信息
     */
    public BusinessException(int httpStatus, String message) {
        super(message);
        this.httpStatus = httpStatus;
    }

    /**
     * 初始化BusinessException 实例。
     * @param httpStatus h tt pS ta tu s 参数
     * @param message 提示信息
     * @param cause c au se 参数
     */
    public BusinessException(int httpStatus, String message, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
    }

    /**
     * 获取httpstatus。
     * @return 返回处理结果。
     */
    public int getHttpStatus() {
        return httpStatus;
    }
}
