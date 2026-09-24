package com.travelagent.agent.orchestration;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@NoArgsConstructor
public class AgentNodeTrace {

    private AgentWorkflowNode node;
    private AgentWorkflowRoute route;
    private Map<String, Object> input = new LinkedHashMap<>();
    private Map<String, Object> output = new LinkedHashMap<>();
    private String message;
    private Instant recordedAt = Instant.now();

    public AgentNodeTrace(AgentWorkflowNode node,
                          AgentWorkflowRoute route,
                          Map<String, Object> input,
                          Map<String, Object> output,
                          String message) {
        this.node = node;
        this.route = route;
        this.input = input == null ? new LinkedHashMap<>() : new LinkedHashMap<>(input);
        this.output = output == null ? new LinkedHashMap<>() : new LinkedHashMap<>(output);
        this.message = message;
        this.recordedAt = Instant.now();
    }
}
