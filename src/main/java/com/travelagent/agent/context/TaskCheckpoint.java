package com.travelagent.agent.context;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.travelagent.agent.planner.TravelPlan;
import com.travelagent.agent.requirements.TravelConstraints;
import com.travelagent.model.dto.LocationCandidateItem;
import com.travelagent.model.dto.ResolvedLocation;
import com.travelagent.model.dto.SelectionOptionItem;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TaskCheckpoint {

    public static final String CURRENT_SCHEMA_VERSION = "2.0";
    public static final int CURRENT_FORMAT_VERSION = 2;
    private static final int MAX_HISTORY_ITEMS = 20;

    private String schemaVersion = CURRENT_SCHEMA_VERSION;
    /** Independent wire/storage format version for forward-compatible decoding. */
    private Integer formatVersion = CURRENT_FORMAT_VERSION;
    /** Lease token that was held when this checkpoint was written. */
    private String leaseToken;
    /** Durable recovery claim number associated with this checkpoint. */
    private Integer recoveryAttempt = 0;
    private Long taskId;
    private Long userId;
    private String taskUuid;
    private String currentState;
    private String region;
    private String provinceName;
    private String cityName;
    private String districtName;
    private String adcode;
    private String userIntent;
    private TravelConstraints structuredConstraints;
    private PlanningConfig planningConfig;
    private List<CompletedStep> completedSteps = new ArrayList<>();
    private int currentStepIndex = 0;
    private PendingToolCall pendingToolCall;
    private List<Map<String, Object>> llmConversationHistory = new ArrayList<>();
    private Integer historyTrimmedAt;
    private List<Map<String, Object>> reactScratchpad = new ArrayList<>();
    private Integer scratchpadTrimmedAt;
    private TokenBudgetSnapshot tokenBudgetSnapshot;
    private RetryState retryState;
    private LocalDateTime resumableAt;
    private String pauseReason;
    private String startLocationQuery;
    private String endLocationQuery;
    private LocalDateTime tripStartTime;
    private LocalDateTime tripEndTime;
    private List<DailyTimeWindow> dailyTimeWindows = new ArrayList<>();
    private Integer usedTimeBudgetMin = 0;
    private Integer remainingTimeBudgetMin = 0;
    private Integer projectedReturnToDestinationMin = 0;
    private String pendingInputType;
    private String selectionStage;
    private String selectedBranchType;
    private List<LocationCandidateItem> locationCandidates = new ArrayList<>();
    private List<SelectionOptionItem> selectionOptions = new ArrayList<>();
    private List<LocationCandidateItem> recommendationCandidates = new ArrayList<>();
    private Map<String, Object> currentContext = new LinkedHashMap<>();
    private Map<String, Object> weatherContext = new LinkedHashMap<>();
    private ResolvedLocation selectedOrigin;
    private ResolvedLocation selectedDestination;
    private LocationCandidateItem selectedAttractionCandidate;
    private boolean originConfirmed;
    private TravelPlan currentPlan;
    private List<SessionSubtaskState> subtaskStates = new ArrayList<>();
    private Map<String, Object> toolResults = new LinkedHashMap<>();
    private List<Map<String, Object>> ragResults = new ArrayList<>();
    private List<Map<String, Object>> intermediateSummaries = new ArrayList<>();
    private List<String> failureReasons = new ArrayList<>();
    private Map<String, Integer> retryCounts = new LinkedHashMap<>();
    private List<Map<String, Object>> validatorResults = new ArrayList<>();
    private String finalItinerary;
    private List<Map<String, Object>> userFeedback = new ArrayList<>();

    public void setCurrentPlan(TravelPlan currentPlan) {
        this.currentPlan = currentPlan;
        refreshSubtaskStatesFromPlan();
    }

    public void migrateToCurrentSchema() {
        initializeCollections();
        if (formatVersion == null) {
            formatVersion = 1;
        }
        if (formatVersion > CURRENT_FORMAT_VERSION || formatVersion < 1) {
            throw new IllegalArgumentException("Unsupported checkpoint format version: " + formatVersion);
        }
        if (schemaVersion == null || schemaVersion.isBlank()) {
            schemaVersion = "1.0";
        }
        if (!"1.0".equals(schemaVersion) && !CURRENT_SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported checkpoint schema version: " + schemaVersion);
        }
        if ("1.0".equals(schemaVersion)) {
            schemaVersion = CURRENT_SCHEMA_VERSION;
        }
        formatVersion = CURRENT_FORMAT_VERSION;
        refreshSubtaskStatesFromPlan();
    }

    public void prepareForStorage() {
        migrateToCurrentSchema();
        llmConversationHistory = tail(llmConversationHistory, MAX_HISTORY_ITEMS);
        ragResults = tail(ragResults, MAX_HISTORY_ITEMS);
        intermediateSummaries = tail(intermediateSummaries, MAX_HISTORY_ITEMS);
        validatorResults = tail(validatorResults, MAX_HISTORY_ITEMS);
        userFeedback = tail(userFeedback, MAX_HISTORY_ITEMS);
        failureReasons = tail(failureReasons, MAX_HISTORY_ITEMS);
    }

    private void initializeCollections() {
        if (completedSteps == null) completedSteps = new ArrayList<>();
        if (llmConversationHistory == null) llmConversationHistory = new ArrayList<>();
        if (reactScratchpad == null) reactScratchpad = new ArrayList<>();
        if (dailyTimeWindows == null) dailyTimeWindows = new ArrayList<>();
        if (locationCandidates == null) locationCandidates = new ArrayList<>();
        if (selectionOptions == null) selectionOptions = new ArrayList<>();
        if (recommendationCandidates == null) recommendationCandidates = new ArrayList<>();
        if (currentContext == null) currentContext = new LinkedHashMap<>();
        if (weatherContext == null) weatherContext = new LinkedHashMap<>();
        if (subtaskStates == null) subtaskStates = new ArrayList<>();
        if (toolResults == null) toolResults = new LinkedHashMap<>();
        if (ragResults == null) ragResults = new ArrayList<>();
        if (intermediateSummaries == null) intermediateSummaries = new ArrayList<>();
        if (failureReasons == null) failureReasons = new ArrayList<>();
        if (retryCounts == null) retryCounts = new LinkedHashMap<>();
        if (validatorResults == null) validatorResults = new ArrayList<>();
        if (userFeedback == null) userFeedback = new ArrayList<>();
        if (usedTimeBudgetMin == null) usedTimeBudgetMin = 0;
        if (remainingTimeBudgetMin == null) remainingTimeBudgetMin = 0;
        if (projectedReturnToDestinationMin == null) projectedReturnToDestinationMin = 0;
    }

    private <T> List<T> tail(List<T> values, int maxItems) {
        if (values == null) {
            return new ArrayList<>();
        }
        if (values.size() <= maxItems) {
            return values;
        }
        return new ArrayList<>(values.subList(values.size() - maxItems, values.size()));
    }

    public void refreshSubtaskStatesFromPlan() {
        if (currentPlan == null || currentPlan.getTasks() == null) {
            if (subtaskStates == null) {
                subtaskStates = new ArrayList<>();
            }
            return;
        }
        List<SessionSubtaskState> states = new ArrayList<>();
        currentPlan.getTasks().stream()
                .map(SessionSubtaskState::fromPlanTask)
                .forEach(state -> {
                    if (state != null) {
                        states.add(state);
                    }
                });
        subtaskStates = states;
    }

    public AgentSessionState toSessionState() {
        refreshSubtaskStatesFromPlan();
        return AgentSessionState.fromCheckpoint(this);
    }

    public void applySessionState(AgentSessionState sessionState) {
        if (sessionState != null) {
            sessionState.applyTo(this);
        }
    }

    public void recordToolResult(String toolName, Map<String, Object> result, int retryCount, String errorMessage) {
        if (toolName == null || toolName.isBlank()) {
            return;
        }
        if (toolResults == null) {
            toolResults = new LinkedHashMap<>();
        }
        toolResults.put(toolName, result == null ? new LinkedHashMap<>() : new LinkedHashMap<>(result));
        if (retryCounts == null) {
            retryCounts = new LinkedHashMap<>();
        }
        retryCounts.put(toolName, Math.max(0, retryCount));
        if (errorMessage != null && !errorMessage.isBlank()) {
            recordFailure(toolName + ": " + errorMessage);
        }
    }

    public void recordRagResult(Map<String, Object> result) {
        if (ragResults == null) {
            ragResults = new ArrayList<>();
        }
        ragResults.add(result == null ? new LinkedHashMap<>() : new LinkedHashMap<>(result));
    }

    public void recordValidatorResult(Map<String, Object> result) {
        if (validatorResults == null) {
            validatorResults = new ArrayList<>();
        }
        validatorResults.add(result == null ? new LinkedHashMap<>() : new LinkedHashMap<>(result));
    }

    public void recordIntermediateSummary(Map<String, Object> summary) {
        if (intermediateSummaries == null) {
            intermediateSummaries = new ArrayList<>();
        }
        intermediateSummaries.add(summary == null ? new LinkedHashMap<>() : new LinkedHashMap<>(summary));
    }

    public void recordFailure(String reason) {
        if (reason == null || reason.isBlank()) {
            return;
        }
        if (failureReasons == null) {
            failureReasons = new ArrayList<>();
        }
        failureReasons.add(reason);
    }

    public void recordUserFeedback(String source, String message, Map<String, Object> details) {
        if (message == null || message.isBlank()) {
            return;
        }
        if (userFeedback == null) {
            userFeedback = new ArrayList<>();
        }
        Map<String, Object> feedback = new LinkedHashMap<>();
        feedback.put("source", source);
        feedback.put("message", message);
        feedback.put("details", details == null ? new LinkedHashMap<>() : new LinkedHashMap<>(details));
        userFeedback.add(feedback);
    }

    /**
     * 处理completedStepCount。
     * @return 返回处理结果。
     */
    public int completedStepCount() {
        return completedSteps == null ? 0 : completedSteps.size();
    }

    /**
     * 处理totalPlannedSteps。
     * @return 返回处理结果。
     */
    public int totalPlannedSteps() {
        return planningConfig == null ? 0 : planningConfig.totalSteps();
    }

    /**
     * 判断allstepsdone。
     * @return 是否满足当前条件。
     */
    public boolean isAllStepsDone() {
        return planningConfig != null && completedStepCount() >= planningConfig.totalSteps();
    }

    /**
     * 获取dailywindow。
     * @param dayNumber d ay Nu mb er 参数
     * @return 返回处理结果。
     */
    public DailyTimeWindow getDailyWindow(int dayNumber) {
        if (dailyTimeWindows == null || dailyTimeWindows.isEmpty()) {
            return null;
        }
        return dailyTimeWindows.stream()
                .filter(window -> window.getDayNumber() == dayNumber)
                .findFirst()
                .orElse(null);
    }

    /**
     * 处理totalAvailableMinutes。
     * @return 返回处理结果。
     */
    public int totalAvailableMinutes() {
        if (dailyTimeWindows == null || dailyTimeWindows.isEmpty()) {
            return 0;
        }
        return dailyTimeWindows.stream().mapToInt(DailyTimeWindow::availableMinutes).sum();
    }
}
