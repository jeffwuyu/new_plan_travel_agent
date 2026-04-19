package com.travelagent.model.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Agent task entity. Maps to the `tasks` table.
 * The checkpoint_json column stores the full TaskCheckpoint as a JSON string,
 * enabling seamless resume after token quota replenishment.
 */

/**
 * 中文注释：实体类，用于定义 Task 的持久化数据结构。
 */

@Data
@NoArgsConstructor
public class Task {

    private Long id;

    /** UUID for external references (never expose internal id) */
    private String taskUuid;

    private Long userId;

    /**
     * Current lifecycle state.
     * @see com.travelagent.model.enums.TaskStatus
     */
    private String status;

    /** Target region, e.g. "西安市" */
    private String region;

    /**
     * Full JSON snapshot of AgentContext at the last checkpoint.
     * Stored as MEDIUMTEXT to accommodate large conversation histories.
     */
    private String checkpointJson;

    /** Schema version of the checkpoint JSON, for migration handling. */
    private String schemaVersion;

    /** Total LLM tokens consumed by this task across all calls. */
    private Integer totalTokensUsed;

    /** Error message if status=FAILED */
    private String errorMessage;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime completedAt;
}
