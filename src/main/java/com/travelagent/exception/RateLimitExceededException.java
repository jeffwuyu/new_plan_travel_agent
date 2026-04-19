package com.travelagent.exception;

/**
 * Thrown when an anonymous user exceeds the per-IP hourly rate limit.
 */

/**
 * 中文注释：异常类，用于表达 Rate Limit Exceeded Exception 场景下的错误语义。
 */

public class RateLimitExceededException extends BusinessException {

    public RateLimitExceededException() {
        super(429, "请求过于频繁，请稍后再试");
    }
}
