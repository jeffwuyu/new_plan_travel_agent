package com.travelagent.model.dto;

import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.model.entity.Task;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class TaskResponse {

    private String taskUuid;
    private String status;
    private String region;
    private String userIntent;
    private String currentLocationQuery;
    private Integer totalTokensUsed;
    private String errorMessage;
    private String schemaVersion;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;
    private Integer currentStepIndex;
    private Integer totalSteps;
    private Boolean awaitingUserInput = false;
    private String pendingInputType;
    private List<LocationCandidateItem> locationCandidates = new ArrayList<>();
    private SelectedOrigin selectedOrigin;

    public static TaskResponse from(Task task) {
        TaskResponse r = new TaskResponse();
        r.taskUuid = task.getTaskUuid();
        r.status = task.getStatus();
        r.region = task.getRegion();
        r.totalTokensUsed = task.getTotalTokensUsed();
        r.errorMessage = task.getErrorMessage();
        r.schemaVersion = task.getSchemaVersion();
        r.createdAt = task.getCreatedAt();
        r.updatedAt = task.getUpdatedAt();
        r.completedAt = task.getCompletedAt();
        r.awaitingUserInput = false;
        return r;
    }

    public static TaskResponse from(Task task, TaskCheckpoint checkpoint) {
        TaskResponse r = from(task);
        if (checkpoint != null) {
            r.userIntent = checkpoint.getUserIntent();
            r.currentLocationQuery = checkpoint.getCurrentLocationQuery();
            r.currentStepIndex = checkpoint.getCurrentStepIndex();
            r.totalSteps = checkpoint.totalPlannedSteps();
            r.pendingInputType = checkpoint.getPendingInputType();
            r.awaitingUserInput = "origin_selection".equals(checkpoint.getPendingInputType());
            r.locationCandidates = checkpoint.getLocationCandidates() == null
                    ? new ArrayList<>()
                    : checkpoint.getLocationCandidates();
            r.selectedOrigin = checkpoint.getSelectedOrigin();
        }
        return r;
    }
}
