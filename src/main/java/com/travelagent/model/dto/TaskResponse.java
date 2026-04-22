package com.travelagent.model.dto;

import com.travelagent.agent.context.DailyTimeWindow;
import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.model.entity.Task;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
public class TaskResponse {

    private String taskUuid;
    private String status;
    private String region;
    private String userIntent;
    private String startLocationQuery;
    private String endLocationQuery;
    private LocalDateTime tripStartTime;
    private LocalDateTime tripEndTime;
    private LocalTime fullDayStartTime;
    private LocalTime fullDayEndTime;
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
    private String pauseReason;
    private Integer usedTimeBudgetMin;
    private Integer remainingTimeBudgetMin;
    private Integer projectedReturnToDestinationMin;
    private List<DailyTimeWindow> dailyTimeWindows = new ArrayList<>();
    private List<LocationCandidateItem> locationCandidates = new ArrayList<>();
    private List<LocationCandidateItem> recommendationCandidates = new ArrayList<>();
    private Map<String, Object> currentContext = new LinkedHashMap<>();
    private ResolvedLocation selectedOrigin;
    private ResolvedLocation selectedDestination;

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
            r.startLocationQuery = checkpoint.getStartLocationQuery();
            r.endLocationQuery = checkpoint.getEndLocationQuery();
            r.tripStartTime = checkpoint.getTripStartTime();
            r.tripEndTime = checkpoint.getTripEndTime();
            if (checkpoint.getPlanningConfig() != null) {
                r.fullDayStartTime = checkpoint.getPlanningConfig().resolveFullDayStartTime();
                r.fullDayEndTime = checkpoint.getPlanningConfig().resolveFullDayEndTime();
            }
            r.currentStepIndex = checkpoint.getCurrentStepIndex();
            r.totalSteps = checkpoint.totalPlannedSteps();
            r.pendingInputType = checkpoint.getPendingInputType();
            r.awaitingUserInput = checkpoint.getPendingInputType() != null && !checkpoint.getPendingInputType().isBlank();
            r.pauseReason = checkpoint.getPauseReason();
            r.usedTimeBudgetMin = checkpoint.getUsedTimeBudgetMin();
            r.remainingTimeBudgetMin = checkpoint.getRemainingTimeBudgetMin();
            r.projectedReturnToDestinationMin = checkpoint.getProjectedReturnToDestinationMin();
            r.dailyTimeWindows = checkpoint.getDailyTimeWindows() == null
                    ? new ArrayList<>()
                    : checkpoint.getDailyTimeWindows();
            r.locationCandidates = checkpoint.getLocationCandidates() == null
                    ? new ArrayList<>()
                    : checkpoint.getLocationCandidates();
            r.recommendationCandidates = checkpoint.getRecommendationCandidates() == null
                    ? new ArrayList<>()
                    : checkpoint.getRecommendationCandidates();
            r.currentContext = checkpoint.getCurrentContext() == null
                    ? new LinkedHashMap<>()
                    : checkpoint.getCurrentContext();
            r.selectedOrigin = checkpoint.getSelectedOrigin();
            r.selectedDestination = checkpoint.getSelectedDestination();
        }
        return r;
    }
}
