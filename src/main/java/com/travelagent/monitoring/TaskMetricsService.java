package com.travelagent.monitoring;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class TaskMetricsService {

    private final AtomicLong llmCallsTotal     = new AtomicLong();
    private final AtomicLong llmLatencyMsTotal = new AtomicLong();
    private final AtomicLong llmErrorsTotal    = new AtomicLong();

    private final AtomicLong toolCallsTotal  = new AtomicLong();
    private final AtomicLong toolErrorsTotal = new AtomicLong();

    private final AtomicLong tasksStarted   = new AtomicLong();
    private final AtomicLong tasksCompleted = new AtomicLong();
    private final AtomicLong tasksFailed    = new AtomicLong();
    private final AtomicLong tasksPaused    = new AtomicLong();

    public void recordLlmCall(long latencyMs, boolean success) {
        llmCallsTotal.incrementAndGet();
        llmLatencyMsTotal.addAndGet(latencyMs);
        if (!success) llmErrorsTotal.incrementAndGet();
    }

    public void recordToolCall(boolean success) {
        toolCallsTotal.incrementAndGet();
        if (!success) toolErrorsTotal.incrementAndGet();
    }

    public void recordTaskStarted()   { tasksStarted.incrementAndGet(); }
    public void recordTaskCompleted() { tasksCompleted.incrementAndGet(); }
    public void recordTaskFailed()    { tasksFailed.incrementAndGet(); }
    public void recordTaskPaused()    { tasksPaused.incrementAndGet(); }

    public Map<String, Object> getSnapshot() {
        long calls = llmCallsTotal.get();
        long latencyTotal = llmLatencyMsTotal.get();
        long avgLatency = calls > 0 ? latencyTotal / calls : 0;

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("llm_calls_total",       calls);
        snapshot.put("llm_latency_ms_avg",    avgLatency);
        snapshot.put("llm_latency_ms_total",  latencyTotal);
        snapshot.put("llm_errors_total",      llmErrorsTotal.get());
        snapshot.put("tool_calls_total",      toolCallsTotal.get());
        snapshot.put("tool_errors_total",     toolErrorsTotal.get());
        snapshot.put("tasks_started",         tasksStarted.get());
        snapshot.put("tasks_completed",       tasksCompleted.get());
        snapshot.put("tasks_failed",          tasksFailed.get());
        snapshot.put("tasks_paused",          tasksPaused.get());
        snapshot.put("snapshot_timestamp",    Instant.now().toString());
        return snapshot;
    }
}
