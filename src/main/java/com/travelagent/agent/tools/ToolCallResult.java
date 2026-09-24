package com.travelagent.agent.tools;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public class ToolCallResult {

    private String toolName;
    private ToolCallStatus status = ToolCallStatus.SUCCESS;
    private Map<String, Object> input = new LinkedHashMap<>();
    private Map<String, Object> output = new LinkedHashMap<>();
    private String source;
    private Instant queryTime;
    private Instant startedAt;
    private Instant completedAt;
    private long durationMs;
    private String errorMessage;
    private int retryCount;
    private boolean realtime;
    private boolean degraded;
    private String degradationReason;

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("toolName", toolName);
        map.put("status", status.getCode());
        map.put("input", new LinkedHashMap<>(input));
        map.put("output", new LinkedHashMap<>(output));
        map.put("source", source);
        map.put("queryTime", queryTime == null ? null : queryTime.toString());
        map.put("startedAt", startedAt == null ? null : startedAt.toString());
        map.put("completedAt", completedAt == null ? null : completedAt.toString());
        map.put("durationMs", durationMs);
        map.put("errorMessage", errorMessage);
        map.put("retryCount", retryCount);
        map.put("realtime", realtime);
        map.put("degraded", degraded);
        map.put("degradationReason", degradationReason);
        return map;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public ToolCallStatus getStatus() {
        return status;
    }

    public void setStatus(ToolCallStatus status) {
        this.status = status;
    }

    public Map<String, Object> getInput() {
        return input;
    }

    public void setInput(Map<String, Object> input) {
        this.input = copy(input);
    }

    public Map<String, Object> getOutput() {
        return output;
    }

    public void setOutput(Map<String, Object> output) {
        this.output = copy(output);
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Instant getQueryTime() {
        return queryTime;
    }

    public void setQueryTime(Instant queryTime) {
        this.queryTime = queryTime;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
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

    public boolean isRealtime() {
        return realtime;
    }

    public void setRealtime(boolean realtime) {
        this.realtime = realtime;
    }

    public boolean isDegraded() {
        return degraded;
    }

    public void setDegraded(boolean degraded) {
        this.degraded = degraded;
    }

    public String getDegradationReason() {
        return degradationReason;
    }

    public void setDegradationReason(String degradationReason) {
        this.degradationReason = degradationReason;
    }

    private static Map<String, Object> copy(Map<String, Object> source) {
        return source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
    }
}
