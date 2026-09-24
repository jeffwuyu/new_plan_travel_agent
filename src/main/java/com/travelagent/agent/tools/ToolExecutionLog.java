package com.travelagent.agent.tools;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public class ToolExecutionLog {

    private String toolName;
    private Map<String, Object> input = new LinkedHashMap<>();
    private Map<String, Object> output = new LinkedHashMap<>();
    private Instant calledAt;
    private long durationMs;
    private String errorMessage;
    private int retryCount;

    public static ToolExecutionLog fromResult(ToolCallResult result) {
        ToolExecutionLog log = new ToolExecutionLog();
        log.setToolName(result.getToolName());
        log.setInput(result.getInput());
        log.setOutput(result.getOutput());
        log.setCalledAt(result.getStartedAt());
        log.setDurationMs(result.getDurationMs());
        log.setErrorMessage(result.getErrorMessage());
        log.setRetryCount(result.getRetryCount());
        return log;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("toolName", toolName);
        map.put("input", new LinkedHashMap<>(input));
        map.put("output", new LinkedHashMap<>(output));
        map.put("calledAt", calledAt == null ? null : calledAt.toString());
        map.put("durationMs", durationMs);
        map.put("errorMessage", errorMessage);
        map.put("retryCount", retryCount);
        return map;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public Map<String, Object> getInput() {
        return input;
    }

    public void setInput(Map<String, Object> input) {
        this.input = input == null ? new LinkedHashMap<>() : new LinkedHashMap<>(input);
    }

    public Map<String, Object> getOutput() {
        return output;
    }

    public void setOutput(Map<String, Object> output) {
        this.output = output == null ? new LinkedHashMap<>() : new LinkedHashMap<>(output);
    }

    public Instant getCalledAt() {
        return calledAt;
    }

    public void setCalledAt(Instant calledAt) {
        this.calledAt = calledAt;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = Math.max(0, retryCount);
    }
}
