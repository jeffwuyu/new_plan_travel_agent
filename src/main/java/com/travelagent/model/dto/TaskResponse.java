package com.travelagent.model.dto;

import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.model.entity.Task;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * REST response DTO for a single task. Exposes only external-safe fields —
 * never exposes the internal DB {@code id} or the raw {@code checkpointJson}.
 */

/**
 * 中文注释：DTO 类，用于在接口或服务之间传递 Task Response 数据。
 */

@Data
@NoArgsConstructor
public class TaskResponse {

    private String taskUuid;
    private String status;
    private String region;
    private String userIntent;
    private Integer totalTokensUsed;
    private String errorMessage;
    private String schemaVersion;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;

    /** Current 0-based step index (from checkpoint; null if checkpoint not parsed). */
    private Integer currentStepIndex;

    /** Total planned steps = totalDays * attractionsPerDay (from checkpoint). */
    private Integer totalSteps;

    // -----------------------------------------------------------------------
    // Factory methods
    // -----------------------------------------------------------------------

    /**
     * Basic projection — no checkpoint parsing overhead. Suitable for list endpoints.
     */
    public static TaskResponse from(Task task) {
        TaskResponse r = new TaskResponse();
        r.taskUuid         = task.getTaskUuid();
        r.status           = task.getStatus();
        r.region           = task.getRegion();
        r.totalTokensUsed  = task.getTotalTokensUsed();
        r.errorMessage     = task.getErrorMessage();
        r.schemaVersion    = task.getSchemaVersion();
        r.createdAt        = task.getCreatedAt();
        r.updatedAt        = task.getUpdatedAt();
        r.completedAt      = task.getCompletedAt();
        return r;
    }

    /**
     * Enriched projection that includes checkpoint summary fields.
     * Used by single-task GET and create/resume responses.
     */
    public static TaskResponse from(Task task, TaskCheckpoint checkpoint) {
        TaskResponse r = from(task);
        if (checkpoint != null) {
            r.userIntent        = checkpoint.getUserIntent();
            r.currentStepIndex  = checkpoint.getCurrentStepIndex();
            r.totalSteps        = checkpoint.totalPlannedSteps();
        }
        return r;
    }
}
