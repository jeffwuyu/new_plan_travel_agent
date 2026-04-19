package com.travelagent.agent.context;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Full agent state snapshot stored as JSON in {@code tasks.checkpoint_json}.
 *
 * <p>Design invariants:
 * <ul>
 *   <li>All list fields are initialized to empty lists — never null after deserialization.</li>
 *   <li>{@code llmConversationHistory} stores raw {@code Map<String, Object>} entries
 *       (not Spring AI {@code Message} objects) to avoid framework version coupling.
 *       Phase 3 converts to {@code Message} instances at runtime.</li>
 *   <li>{@code schemaVersion = "1.0"} — bump when adding breaking fields.</li>
 *   <li>{@code @JsonIgnoreProperties(ignoreUnknown = true)} allows forward-compatibility
 *       when a newer schema version adds fields that an older JVM reads.</li>
 * </ul>
 */

/**
 * 中文注释：Agent 上下文类，用于承载 Task Checkpoint 相关的运行时状态数据。
 */

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TaskCheckpoint {

    /** Schema version for forward-compatible deserialization. */
    private String schemaVersion = "1.0";

    /** Internal DB primary key — populated after insert. */
    private Long taskId;

    /** Externally-visible UUID (matches {@code tasks.task_uuid}). */
    private String taskUuid;

    /**
     * Current agent state code (matches {@link com.travelagent.model.enums.TaskStatus#getCode()}).
     * E.g. "pending", "planning", "paused".
     */
    private String currentState;

    /** Target region, e.g. "西安市". */
    private String region;

    /** Raw user intent string, e.g. "3天西安历史文化游". */
    private String userIntent;

    /** Planning parameters captured at creation — never changes after first write. */
    private PlanningConfig planningConfig;

    /** All steps that have been fully resolved (geocode + weather + traffic complete). */
    private List<CompletedStep> completedSteps = new ArrayList<>();

    /** 0-based index of the step currently being worked on. */
    private int currentStepIndex = 0;

    /**
     * If non-null, the task was interrupted mid-tool-call.
     * On resume, retry this call before returning to the LLM loop.
     */
    private PendingToolCall pendingToolCall;

    /**
     * LLM conversation history as raw message maps:
     * {@code [{"role": "system", "content": "..."}, {"role": "user", "content": "..."}]}
     *
     * Phase 3's HistoryManager applies sliding-window trimming before each LLM call.
     */
    private List<Map<String, Object>> llmConversationHistory = new ArrayList<>();

    /**
     * Conversation index at which the history was last trimmed/summarized.
     * Null means no trimming has occurred yet.
     */
    private Integer historyTrimmedAt;

    /** Token budget state at the moment the task was paused. Null if never paused. */
    private TokenBudgetSnapshot tokenBudgetSnapshot;

    /** Per-step retry tracking. */
    private RetryState retryState;

    /**
     * When the daily quota is expected to reset (midnight Asia/Shanghai).
     * Populated when the task transitions to PAUSED due to quota exhaustion.
     */
    private LocalDateTime resumableAt;

    // -----------------------------------------------------------------------
    // Convenience helpers
    // -----------------------------------------------------------------------

    /** Returns the number of steps that have been completed. */
    public int completedStepCount() {
        return completedSteps == null ? 0 : completedSteps.size();
    }

    /** Returns total planned steps (0 if planningConfig is null). */
    public int totalPlannedSteps() {
        return planningConfig == null ? 0 : planningConfig.totalSteps();
    }

    /** Returns true if all planned steps are done. */
    public boolean isAllStepsDone() {
        return planningConfig != null && completedStepCount() >= planningConfig.totalSteps();
    }
}
