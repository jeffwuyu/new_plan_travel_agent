package com.travelagent.agent.orchestration;

import com.travelagent.agent.planner.PlanTaskType;
import com.travelagent.agent.planner.TravelPlan;
import com.travelagent.agent.requirements.TravelConstraints;
import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.model.entity.Task;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
public class AgentWorkflowContext {

    private String requestText;
    private TravelConstraints constraints;
    private TravelPlan plan;
    private AgentWorkflowNode currentNode = AgentWorkflowNode.INTENT_CONSTRAINT_PARSER;
    private List<AgentNodeTrace> traces = new ArrayList<>();
    private Map<AgentWorkflowNode, List<Map<String, Object>>> nodeOutputs = new LinkedHashMap<>();
    private Map<PlanTaskType, Integer> simulatedFailuresRemaining = new EnumMap<>(PlanTaskType.class);
    private int validatorFailuresRemaining;
    private int maxTaskRetries = 1;
    private boolean realToolExecutionEnabled;
    private Task task;
    private TaskCheckpoint checkpoint;
    private String draftItinerary;
    private String finalItinerary;

    public AgentWorkflowContext(String requestText) {
        this.requestText = requestText;
    }

    public void record(AgentWorkflowNode node,
                       AgentWorkflowRoute route,
                       Map<String, Object> input,
                       Map<String, Object> output,
                       String message) {
        traces.add(new AgentNodeTrace(node, route, input, output, message));
        nodeOutputs.computeIfAbsent(node, ignored -> new ArrayList<>())
                .add(output == null ? new LinkedHashMap<>() : new LinkedHashMap<>(output));
    }

    public boolean isComplete() {
        return currentNode == AgentWorkflowNode.COMPLETE;
    }

    public boolean isFailed() {
        return currentNode == AgentWorkflowNode.FAILED;
    }
}
