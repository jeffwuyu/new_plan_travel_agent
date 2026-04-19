package com.travelagent.exception;

/**
 * Thrown when a user's token quota (daily or monthly) is exhausted.
 * Caught by AgentService to transition the task to PAUSED state
 * and save the checkpoint for later resumption.
 */

/**
 * 中文注释：异常类，用于表达 Quota Exhausted Exception 场景下的错误语义。
 */

public class QuotaExhaustedException extends BusinessException {

    private final String periodType;  // daily | monthly

    public QuotaExhaustedException(String periodType) {
        super(429, "Token配额已耗尽(" + periodType + ")，任务已暂停，配额补充后可继续");
        this.periodType = periodType;
    }

    public String getPeriodType() {
        return periodType;
    }
}
