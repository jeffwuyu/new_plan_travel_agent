package com.travelagent.agent.context;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Describes a tool call that was in-flight when the task was interrupted.
 * On resume, the agent retries this call using the idempotency key to avoid
 * re-charging the user for an already-executed external API call.
 */

/**
 * 中文注释：Agent 上下文类，用于承载 Pending Tool Call 相关的运行时状态数据。
 */

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PendingToolCall {

    /** Fully-qualified tool class name, e.g. "GeocodeTool". */
    private String toolName;

    /** Input arguments as raw map (matches the tool's ToolRequest schema). */
    private Map<String, Object> arguments;

    /**
     * Deterministic idempotency key for deduplication.
     * Format: {@code <taskUuid>-step<N>-<toolName>}
     * Stored in Redis with 24h TTL; if present, result is returned from cache.
     */
    private String idempotencyKey;
}
