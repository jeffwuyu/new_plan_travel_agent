package com.travelagent.agent.context;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.travelagent.agent.planner.TravelPlanTask;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SessionSubtaskState {

    @JsonProperty("task_id")
    private String taskId;

    @JsonProperty("task_type")
    private String taskType;

    private String status;
    private Map<String, Object> input = new LinkedHashMap<>();
    private Map<String, Object> output = new LinkedHashMap<>();

    @JsonProperty("retry_count")
    private int retryCount;

    private String error;

    public static SessionSubtaskState fromPlanTask(TravelPlanTask task) {
        if (task == null) {
            return null;
        }
        SessionSubtaskState state = new SessionSubtaskState();
        state.setTaskId(task.getTaskId());
        state.setTaskType(task.getTaskType() == null ? null : task.getTaskType().getCode());
        state.setStatus(task.getStatus() == null ? null : task.getStatus().getCode());
        state.setInput(task.getInput() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(task.getInput()));
        state.setOutput(task.getOutput() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(task.getOutput()));
        state.setRetryCount(task.getRetryCount());
        state.setError(task.getError());
        return state;
    }
}
