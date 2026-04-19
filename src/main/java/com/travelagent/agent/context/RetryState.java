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

    /** Number of times the current step has been retried after a transient error. */
    private int currentStepRetryCount = 0;

    /** Maximum allowed retries before the step (and the task) is marked FAILED. */
    private int maxRetries = 3;

    /** Returns true if no more retries are allowed for the current step. */
    public boolean isExhausted() {
        return currentStepRetryCount >= maxRetries;
    }

    /** Increment the retry counter and return the new count. */
    public int increment() {
        return ++currentStepRetryCount;
    }

    /** Reset to 0 when moving to the next step. */
    public void reset() {
        currentStepRetryCount = 0;
    }
}
