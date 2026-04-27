package com.travelagent.exception;

import java.util.Map;

public class AgentException extends RuntimeException {

    private final AgentErrorCode errorCode;

    /**
     * 初始化AgentException 实例。
     * @param errorCode e rr or Co de 参数
     * @param message 提示信息
     */
    public AgentException(AgentErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * 初始化AgentException 实例。
     * @param errorCode e rr or Co de 参数
     * @param message 提示信息
     * @param cause c au se 参数
     */
    public AgentException(AgentErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /**
     * 获取errorcode。
     * @return 返回处理结果。
     */
    public AgentErrorCode getErrorCode() {
        return errorCode;
    }

    /**
     * 判断retryable。
     * @return 是否满足当前条件。
     */
    public boolean isRetryable() {
        return errorCode.isRetryable();
    }

    /**
     * 将数据转换为eventpayload。
     * @return 返回处理后的映射结果。
     */
    public Map<String, Object> toEventPayload() {
        return Map.of(
                "code", errorCode.name(),
                "message", getMessage(),
                "retryable", isRetryable()
        );
    }
}
