package com.travelagent.agent.planner;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
public class TravelPlanTask {

    private String taskId;
    private PlanTaskType taskType;
    private PlannerToolType toolType;
    private PlanTaskStatus status = PlanTaskStatus.PENDING;
    private Map<String, Object> input = new LinkedHashMap<>();
    private Map<String, Object> output = new LinkedHashMap<>();
    private List<String> dependencies = new ArrayList<>();
    private List<String> successCriteria = new ArrayList<>();
    private int retryCount;
    private String error;

    public TravelPlanTask(String taskId,
                          PlanTaskType taskType,
                          PlannerToolType toolType,
                          Map<String, Object> input,
                          List<String> dependencies,
                          List<String> successCriteria) {
        this.taskId = taskId;
        this.taskType = taskType;
        this.toolType = toolType;
        this.input = input == null ? new LinkedHashMap<>() : new LinkedHashMap<>(input);
        this.dependencies = dependencies == null ? new ArrayList<>() : new ArrayList<>(dependencies);
        this.successCriteria = successCriteria == null ? new ArrayList<>() : new ArrayList<>(successCriteria);
    }

    public void markRunning() {
        this.status = PlanTaskStatus.RUNNING;
        this.error = null;
    }

    public void markSuccess(Map<String, Object> output) {
        this.status = PlanTaskStatus.SUCCESS;
        this.output = output == null ? new LinkedHashMap<>() : new LinkedHashMap<>(output);
        this.error = null;
    }

    public void markFailed(String error) {
        this.status = PlanTaskStatus.FAILED;
        this.retryCount++;
        this.error = error;
    }

    public void markSkipped(String reason) {
        this.status = PlanTaskStatus.SKIPPED;
        this.error = reason;
    }
}
