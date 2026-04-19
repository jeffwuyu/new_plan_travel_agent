package com.travelagent.model.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Audit log for every LLM and embedding API call.
 * Used for quota reconstruction after Redis restart and billing analysis.
 */

/**
 * 中文注释：实体类，用于定义 Llm Call Log 的持久化数据结构。
 */

@Data
@NoArgsConstructor
public class LlmCallLog {

    private Long id;

    private Long taskId;

    private Long userId;

    /** planning | tool_call | embedding | history_compress */
    private String callType;

    private String model;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer totalTokens;

    private Integer latencyMs;

    /** success | error | timeout */
    private String status;

    /** Idempotency key used for this call, if applicable */
    private String idempotencyKey;

    private LocalDateTime createdAt;
}
