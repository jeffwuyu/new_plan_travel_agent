package com.travelagent.agent.orchestration;

import lombok.Getter;

import java.util.LinkedHashMap;
import java.util.Map;

@Getter
public class AgentNodeResult {

    private final AgentWorkflowRoute route;
    private final Map<String, Object> output;
    private final String message;

    public AgentNodeResult(AgentWorkflowRoute route, Map<String, Object> output, String message) {
        this.route = route == null ? AgentWorkflowRoute.NEXT : route;
        this.output = output == null ? new LinkedHashMap<>() : new LinkedHashMap<>(output);
        this.message = message;
    }

    public static AgentNodeResult next(Map<String, Object> output) {
        return new AgentNodeResult(AgentWorkflowRoute.NEXT, output, null);
    }

    public static AgentNodeResult route(AgentWorkflowRoute route, Map<String, Object> output, String message) {
        return new AgentNodeResult(route, output, message);
    }
}
