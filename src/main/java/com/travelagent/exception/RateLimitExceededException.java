package com.travelagent.exception;

/**
 * 中文注释：匿名请求触发 IP 限流时抛出的业务异常。
 */
public class RateLimitExceededException extends BusinessException {

    private static final int HTTP_STATUS = 429;
    private static final String DEFAULT_MESSAGE = "请求过于频繁，请稍后再试";

    public RateLimitExceededException() {
        super(HTTP_STATUS, DEFAULT_MESSAGE);
    }

    public RateLimitExceededException(String message) {
        super(HTTP_STATUS, message);
    }
}
