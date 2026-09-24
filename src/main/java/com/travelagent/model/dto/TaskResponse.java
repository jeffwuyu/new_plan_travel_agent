package com.travelagent.model.dto;

import com.travelagent.agent.context.DailyTimeWindow;
import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.agent.requirements.TravelConstraints;
import com.travelagent.model.entity.Task;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.math.BigDecimal;
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
    private String provinceName;
    private String cityName;
    private String districtName;
    private String adcode;
    private String userIntent;
    private TravelConstraints structuredConstraints;
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
    private String selectionStage;
    private String selectedBranchType;
    private String pauseReason;
    private Boolean pendingToolReplayRequired = false;
    private Boolean pendingToolGuardRequired = false;
    private String pendingToolName;
    private Boolean pendingToolReplayApproved = false;
    private Integer usedTimeBudgetMin;
    private Integer remainingTimeBudgetMin;
    private Integer projectedReturnToDestinationMin;
    private List<DailyTimeWindow> dailyTimeWindows = new ArrayList<>();
    private List<LocationCandidateItem> locationCandidates = new ArrayList<>();
    private List<SelectionOptionItem> selectionOptions = new ArrayList<>();
    private List<LocationCandidateItem> recommendationCandidates = new ArrayList<>();
    private SelectionPromptDto selectionPrompt;
    private Map<String, Object> currentContext = new LinkedHashMap<>();
    private Map<String, Object> weatherContext = new LinkedHashMap<>();
    private ResolvedLocation selectedOrigin;
    private ResolvedLocation selectedDestination;
    private BigDecimal totalBudgetYuan;
    private BigDecimal lodgingBudgetPerNightYuan;
    private List<String> accommodationTypes = new ArrayList<>();
    private Integer adultCount;
    private Integer roomCount;

    /**
     * 处理from。
     * @param task 任务实体
     * @return 返回处理结果。
     */
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

    /**
     * 处理from。
     * @param task 任务实体
     * @param checkpoint 任务检查点数据
     * @return 返回处理结果。
     */
    public static TaskResponse from(Task task, TaskCheckpoint checkpoint) {
        TaskResponse r = from(task);
        if (checkpoint != null) {
            r.userIntent = checkpoint.getUserIntent();
            r.structuredConstraints = checkpoint.getStructuredConstraints();
            r.provinceName = checkpoint.getProvinceName();
            r.cityName = checkpoint.getCityName();
            r.districtName = checkpoint.getDistrictName();
            r.adcode = checkpoint.getAdcode();
            r.startLocationQuery = checkpoint.getStartLocationQuery();
            r.endLocationQuery = checkpoint.getEndLocationQuery();
            r.tripStartTime = checkpoint.getTripStartTime();
            r.tripEndTime = checkpoint.getTripEndTime();
            if (checkpoint.getPlanningConfig() != null) {
                r.fullDayStartTime = checkpoint.getPlanningConfig().resolveFullDayStartTime();
                r.fullDayEndTime = checkpoint.getPlanningConfig().resolveFullDayEndTime();
                r.totalBudgetYuan = checkpoint.getPlanningConfig().getTotalBudgetYuan();
                r.lodgingBudgetPerNightYuan = checkpoint.getPlanningConfig().getLodgingBudgetPerNightYuan();
                r.accommodationTypes = checkpoint.getPlanningConfig().getAccommodationTypes() == null
                        ? new ArrayList<>()
                        : checkpoint.getPlanningConfig().getAccommodationTypes();
                r.adultCount = checkpoint.getPlanningConfig().getAdultCount();
                r.roomCount = checkpoint.getPlanningConfig().getRoomCount();
            }
            r.currentStepIndex = checkpoint.getCurrentStepIndex();
            r.totalSteps = checkpoint.totalPlannedSteps();
            r.pendingInputType = checkpoint.getPendingInputType();
            r.selectionStage = checkpoint.getSelectionStage();
            r.selectedBranchType = checkpoint.getSelectedBranchType();
            r.awaitingUserInput = checkpoint.getPendingInputType() != null && !checkpoint.getPendingInputType().isBlank();
            r.pauseReason = checkpoint.getPauseReason();
            if (checkpoint.getPendingToolCall() != null) {
                r.pendingToolName = checkpoint.getPendingToolCall().getToolName();
                r.pendingToolReplayApproved = checkpoint.getPendingToolCall().isManualReplayApproved();
                r.pendingToolReplayRequired = "pending_tool_replay_requires_confirmation".equals(checkpoint.getPauseReason());
                r.pendingToolGuardRequired = "tool_guard_requires_confirmation".equals(checkpoint.getPauseReason());
            }
            r.usedTimeBudgetMin = checkpoint.getUsedTimeBudgetMin();
            r.remainingTimeBudgetMin = checkpoint.getRemainingTimeBudgetMin();
            r.projectedReturnToDestinationMin = checkpoint.getProjectedReturnToDestinationMin();
            r.dailyTimeWindows = checkpoint.getDailyTimeWindows() == null
                    ? new ArrayList<>()
                    : checkpoint.getDailyTimeWindows();
            r.locationCandidates = checkpoint.getLocationCandidates() == null
                    ? new ArrayList<>()
                    : checkpoint.getLocationCandidates();
            r.selectionOptions = checkpoint.getSelectionOptions() == null
                    ? new ArrayList<>()
                    : checkpoint.getSelectionOptions();
            r.recommendationCandidates = checkpoint.getRecommendationCandidates() == null
                    ? new ArrayList<>()
                    : checkpoint.getRecommendationCandidates();
            r.selectionPrompt = SelectionPromptDto.from(
                    checkpoint.getPendingInputType(),
                    checkpoint.getSelectionStage(),
                    checkpoint.getSelectedBranchType(),
                    checkpoint.getStartLocationQuery(),
                    checkpoint.getCurrentStepIndex(),
                    null,
                    checkpoint.getCurrentContext(),
                    checkpoint.getWeatherContext());
            r.currentContext = checkpoint.getCurrentContext() == null
                    ? new LinkedHashMap<>()
                    : checkpoint.getCurrentContext();
            r.weatherContext = checkpoint.getWeatherContext() == null
                    ? new LinkedHashMap<>()
                    : checkpoint.getWeatherContext();
            r.selectedOrigin = checkpoint.getSelectedOrigin();
            r.selectedDestination = checkpoint.getSelectedDestination();
        }
        return r;
    }
}
