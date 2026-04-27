package com.travelagent.agent.context;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tracks per-step retry budget for recoverable failures (e.g. tool timeouts).
 * Stored in the checkpoint so retry counts survive a JVM restart.
 */

/**
 * 中文注释：Agent 上下文类，用于承载 Retry State 相关的运行时状态数据。
 */

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RetryState {

    private int currentStepRetryCount = 0;

    private int maxRetries = 3;

    /**
     * 判断exhausted。
     * @return 是否满足当前条件。
     */
    public boolean isExhausted() {
        return currentStepRetryCount >= maxRetries;
    }

    /**
     * 处理increment。
     * @return 返回处理结果。
     */
    public int increment() {
        return ++currentStepRetryCount;
    }

    /**
     * 处理reset。
     */
    public void reset() {
        currentStepRetryCount = 0;
    }
}
