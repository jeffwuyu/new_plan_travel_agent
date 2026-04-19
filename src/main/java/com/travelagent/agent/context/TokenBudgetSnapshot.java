package com.travelagent.agent.context;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Snapshot of the user's token budget at the moment the task was paused.
 * Used to populate the PAUSED SSE event and the checkpoint for audit purposes.
 */

/**
 * 中文注释：Agent 上下文类，用于承载 Token Budget Snapshot 相关的运行时状态数据。
 */

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TokenBudgetSnapshot {

    /** Tokens consumed by this task across all LLM calls so far. */
    private long tokensUsedThisTask;

    /** Total tokens consumed by this user today (across all tasks). */
    private long tokensUsedToday;

    /** The user's configured daily token limit (from UserQuotaConfig). */
    private long dailyLimit;

    /**
     * Human-readable reason for pausing.
     * e.g. "daily_quota_exhausted", "monthly_quota_exhausted"
     */
    private String pauseReason;
}
