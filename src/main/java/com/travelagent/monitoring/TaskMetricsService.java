package com.travelagent.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class TaskMetricsService {

    private final Counter llmCallsCounter;
    private final Counter llmErrorsCounter;
    private final Counter toolCallsCounter;
    private final Counter toolErrorsCounter;
    private final Counter tasksStartedCounter;
    private final Counter tasksCompletedCounter;
    private final Counter tasksFailedCounter;
    private final Counter tasksPausedCounter;
    private final Timer llmLatencyTimer;

    private final AtomicLong llmCallsTotal     = new AtomicLong();
    private final AtomicLong llmLatencyMsTotal = new AtomicLong();
    private final AtomicLong llmErrorsTotal    = new AtomicLong();

    private final AtomicLong toolCallsTotal  = new AtomicLong();
    private final AtomicLong toolErrorsTotal = new AtomicLong();

    private final AtomicLong tasksStarted   = new AtomicLong();
    private final AtomicLong tasksCompleted = new AtomicLong();
    private final AtomicLong tasksFailed    = new AtomicLong();
    private final AtomicLong tasksPaused    = new AtomicLong();

    public TaskMetricsService(MeterRegistry registry) {
        llmCallsCounter = registry.counter("travel_agent_llm_calls_total");
        llmErrorsCounter = registry.counter("travel_agent_llm_errors_total");
        toolCallsCounter = registry.counter("travel_agent_tool_calls_total");
        toolErrorsCounter = registry.counter("travel_agent_tool_errors_total");
        tasksStartedCounter = registry.counter("travel_agent_tasks_started_total");
        tasksCompletedCounter = registry.counter("travel_agent_tasks_completed_total");
        tasksFailedCounter = registry.counter("travel_agent_tasks_failed_total");
        tasksPausedCounter = registry.counter("travel_agent_tasks_paused_total");
        llmLatencyTimer = registry.timer("travel_agent_llm_latency");
    }

    /**
     * 处理recordLlmCall。
     * @param latencyMs l at en cy Ms 参数
     * @param success s uc ce ss 参数
     */
    public void recordLlmCall(long latencyMs, boolean success) {
        llmCallsTotal.incrementAndGet();
        llmLatencyMsTotal.addAndGet(latencyMs);
        if (!success) llmErrorsTotal.incrementAndGet();
        llmCallsCounter.increment();
        llmLatencyTimer.record(java.time.Duration.ofMillis(Math.max(0, latencyMs)));
        if (!success) llmErrorsCounter.increment();
    }

    /**
     * 处理recordToolCall。
     * @param success success 参数
     */
    public void recordToolCall(boolean success) {
        toolCallsTotal.incrementAndGet();
        if (!success) toolErrorsTotal.incrementAndGet();
        toolCallsCounter.increment();
        if (!success) toolErrorsCounter.increment();
    }

    /**
     * 处理recordTaskStarted。
     */
    public void recordTaskStarted()   { tasksStarted.incrementAndGet(); tasksStartedCounter.increment(); }
    /**
     * 处理recordTaskCompleted。
     */
    public void recordTaskCompleted() { tasksCompleted.incrementAndGet(); tasksCompletedCounter.increment(); }
    /**
     * 处理recordTaskFailed。
     */
    public void recordTaskFailed()    { tasksFailed.incrementAndGet(); tasksFailedCounter.increment(); }
    /**
     * 处理recordTaskPaused。
     */
    public void recordTaskPaused()    { tasksPaused.incrementAndGet(); tasksPausedCounter.increment(); }

    /**
     * 获取snapshot。
     * @return 返回处理后的映射结果。
     */
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
