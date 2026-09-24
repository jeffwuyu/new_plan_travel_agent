package com.travelagent.agent.context;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.travelagent.agent.planner.TravelPlan;
import com.travelagent.agent.requirements.TravelConstraints;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentSessionState {

    private String schemaVersion = "1.0";
    private Long taskId;
    private Long userId;
    private String taskUuid;
    private String currentState;
    private String userIntent;
    private TravelConstraints structuredConstraints;
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

    public static AgentSessionState fromCheckpoint(TaskCheckpoint checkpoint) {
        AgentSessionState state = new AgentSessionState();
        if (checkpoint == null) {
            return state;
        }
        state.setSchemaVersion(checkpoint.getSchemaVersion());
        state.setTaskId(checkpoint.getTaskId());
        state.setUserId(checkpoint.getUserId());
        state.setTaskUuid(checkpoint.getTaskUuid());
        state.setCurrentState(checkpoint.getCurrentState());
        state.setUserIntent(checkpoint.getUserIntent());
        state.setStructuredConstraints(checkpoint.getStructuredConstraints());
        state.setCurrentPlan(checkpoint.getCurrentPlan());
        state.setSubtaskStates(copySubtasks(checkpoint.getSubtaskStates()));
        state.setToolResults(copyMap(checkpoint.getToolResults()));
        state.setRagResults(copyListOfMaps(checkpoint.getRagResults()));
        state.setIntermediateSummaries(copyListOfMaps(checkpoint.getIntermediateSummaries()));
        state.setFailureReasons(copyStrings(checkpoint.getFailureReasons()));
        state.setRetryCounts(copyRetryCounts(checkpoint.getRetryCounts()));
        state.setValidatorResults(copyListOfMaps(checkpoint.getValidatorResults()));
        state.setFinalItinerary(checkpoint.getFinalItinerary());
        state.setUserFeedback(copyListOfMaps(checkpoint.getUserFeedback()));
        return state;
    }

    public void applyTo(TaskCheckpoint checkpoint) {
        if (checkpoint == null) {
            return;
        }
        checkpoint.setSchemaVersion(defaultIfBlank(schemaVersion, checkpoint.getSchemaVersion()));
        checkpoint.setTaskId(taskId);
        checkpoint.setUserId(userId);
        checkpoint.setTaskUuid(taskUuid);
        checkpoint.setCurrentState(currentState);
        checkpoint.setUserIntent(userIntent);
        checkpoint.setStructuredConstraints(structuredConstraints);
        checkpoint.setCurrentPlan(currentPlan);
        checkpoint.setSubtaskStates(copySubtasks(subtaskStates));
        checkpoint.setToolResults(copyMap(toolResults));
        checkpoint.setRagResults(copyListOfMaps(ragResults));
        checkpoint.setIntermediateSummaries(copyListOfMaps(intermediateSummaries));
        checkpoint.setFailureReasons(copyStrings(failureReasons));
        checkpoint.setRetryCounts(copyRetryCounts(retryCounts));
        checkpoint.setValidatorResults(copyListOfMaps(validatorResults));
        checkpoint.setFinalItinerary(finalItinerary);
        checkpoint.setUserFeedback(copyListOfMaps(userFeedback));
        checkpoint.refreshSubtaskStatesFromPlan();
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    static Map<String, Object> copyMap(Map<String, Object> source) {
        return source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
    }

    static List<Map<String, Object>> copyListOfMaps(List<Map<String, Object>> source) {
        if (source == null) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> copy = new ArrayList<>();
        for (Map<String, Object> item : source) {
            copy.add(copyMap(item));
        }
        return copy;
    }

    static List<String> copyStrings(List<String> source) {
        return source == null ? new ArrayList<>() : new ArrayList<>(source);
    }

    static Map<String, Integer> copyRetryCounts(Map<String, Integer> source) {
        return source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
    }

    static List<SessionSubtaskState> copySubtasks(List<SessionSubtaskState> source) {
        if (source == null) {
            return new ArrayList<>();
        }
        List<SessionSubtaskState> copy = new ArrayList<>();
        for (SessionSubtaskState item : source) {
            if (item == null) {
                continue;
            }
            copy.add(new SessionSubtaskState(
                    item.getTaskId(),
                    item.getTaskType(),
                    item.getStatus(),
                    copyMap(item.getInput()),
                    copyMap(item.getOutput()),
                    item.getRetryCount(),
                    item.getError()));
        }
        return copy;
    }
}
