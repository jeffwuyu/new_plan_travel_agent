package com.travelagent.agent.context;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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

    private String schemaVersion = "1.0";
    private Long taskId;
    private Long userId;
    private String taskUuid;
    private String currentState;
    private String region;
    private String userIntent;
    private PlanningConfig planningConfig;
    private List<CompletedStep> completedSteps = new ArrayList<>();
    private int currentStepIndex = 0;
    private PendingToolCall pendingToolCall;
    private List<Map<String, Object>> llmConversationHistory = new ArrayList<>();
    private Integer historyTrimmedAt;
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
