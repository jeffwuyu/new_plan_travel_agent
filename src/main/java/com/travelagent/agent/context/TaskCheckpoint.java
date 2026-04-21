package com.travelagent.agent.context;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.travelagent.model.dto.LocationCandidateItem;
import com.travelagent.model.dto.SelectedOrigin;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TaskCheckpoint {

    private String schemaVersion = "1.0";
    private Long taskId;
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

    private String currentLocationQuery;
    private String pendingInputType;
    private List<LocationCandidateItem> locationCandidates = new ArrayList<>();
    private SelectedOrigin selectedOrigin;
    private boolean originConfirmed;

    public int completedStepCount() {
        return completedSteps == null ? 0 : completedSteps.size();
    }

    public int totalPlannedSteps() {
        return planningConfig == null ? 0 : planningConfig.totalSteps();
    }

    public boolean isAllStepsDone() {
        return planningConfig != null && completedStepCount() >= planningConfig.totalSteps();
    }
}
