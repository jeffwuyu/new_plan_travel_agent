package com.travelagent.agent.tools;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public class ToolCallRequest {

    private String toolName;
    private Map<String, Object> arguments = new LinkedHashMap<>();
    private String idempotencyKey;
    private Instant requestedAt = Instant.now();
    private int maxRetries = 1;
    private boolean degradeOnFailure = true;

    public ToolCallRequest() {
    }

    public ToolCallRequest(String toolName, Map<String, Object> arguments, String idempotencyKey) {
        this.toolName = toolName;
        this.arguments = copy(arguments);
        this.idempotencyKey = idempotencyKey;
    }

    public static ToolCallRequest of(String toolName, Map<String, Object> arguments, String idempotencyKey) {
        return new ToolCallRequest(toolName, arguments, idempotencyKey);
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public Map<String, Object> getArguments() {
        return arguments;
    }

    public void setArguments(Map<String, Object> arguments) {
        this.arguments = copy(arguments);
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public void setRequestedAt(Instant requestedAt) {
        this.requestedAt = requestedAt;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = Math.max(0, maxRetries);
    }

    public boolean isDegradeOnFailure() {
        return degradeOnFailure;
    }

    public void setDegradeOnFailure(boolean degradeOnFailure) {
        this.degradeOnFailure = degradeOnFailure;
    }

    private static Map<String, Object> copy(Map<String, Object> source) {
        return source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
    }
}
