package com.travelagent.agent.planner;

import com.travelagent.config.DatabaseSchemaGuard;
import com.travelagent.mapper.TaskMapper;
import com.travelagent.model.entity.Task;
import com.travelagent.model.enums.TaskStatus;
import com.travelagent.service.agent.AgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Background poller that dispatches runnable tasks to the agent thread pool.
 *
 * <h3>How it works</h3>
 * <ol>
 *   <li>Every {@code agent.task.poll-interval-ms} milliseconds (default 2 s),
 *       query the DB for tasks in PENDING and RESUMING states.</li>
 *   <li>For each task not already in-flight, submit an
 *       {@link AgentService#executeTask} call to {@code agentTaskExecutor}.</li>
 *   <li>Track dispatched UUIDs in {@code inFlight} to prevent double-dispatch
 *       within a single JVM instance.</li>
 * </ol>
 *
 * <h3>In-flight guard</h3>
 * {@code inFlight} is an in-memory set — it resets on restart. The database
 * acts as the durable guard: once AgentService transitions a task from PENDING
 * to PLANNING, the DB query no longer returns it as PENDING.
 *
 * <h3>Stale tasks after restart</h3>
 * Tasks left in PLANNING or TOOL_CALLING state after a JVM crash are not
 * recovered by this dispatcher (it only polls PENDING and RESUMING). Phase 3
 * will add a startup recovery pass in {@code AgentServiceImpl@PostConstruct}.
 *
 * <h3>fixedDelay vs fixedRate</h3>
 * {@code fixedDelay} is intentional: the next poll starts only after the
 * previous one finishes. This prevents overlapping DB queries if a poll is
 * slow (e.g. DB under load).
 */

/**
 * 中文注释：调度或规划类，负责 Task Dispatcher 相关的任务编排能力。
 */

@Component
public class TaskDispatcher {

    private static final Logger log = LoggerFactory.getLogger(TaskDispatcher.class);

    @Autowired private TaskMapper    taskMapper;
    @Autowired private AgentService  agentService;
    @Autowired private DatabaseSchemaGuard schemaGuard;

    @Autowired
    @Qualifier("agentTaskExecutor")
    private ThreadPoolTaskExecutor executor;

    /** UUIDs currently dispatched to the thread pool (clears on restart). */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    /**
     * Maximum number of tasks to fetch per status per poll cycle.
     * Should match the thread pool core size to avoid over-fetching.
     */
    @Value("${agent.task.max-per-poll:4}")
    private int maxPerPoll;

    // -----------------------------------------------------------------------
    // Scheduled poll
    // -----------------------------------------------------------------------

    /**
     * Poll for PENDING and RESUMING tasks and dispatch them to the executor.
     * Uses {@code fixedDelay} so back-pressure from a slow DB is respected.
     */
    @Scheduled(fixedDelayString = "${agent.task.poll-interval-ms:2000}")
    public void pollAndDispatch() {
        if (!schemaGuard.isCoreSchemaReady()) {
            log.debug("TaskDispatcher skipped: database schema not ready yet");
            return;
        }
        try {
            dispatchByStatus(TaskStatus.PENDING.getCode());
            dispatchByStatus(TaskStatus.RESUMING.getCode());
        } catch (Exception e) {
            log.error("TaskDispatcher poll error", e);
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private void dispatchByStatus(String statusCode) {
        List<Task> tasks = taskMapper.findByStatus(statusCode, maxPerPoll);
        if (tasks.isEmpty()) return;

        for (Task task : tasks) {
            String uuid = task.getTaskUuid();

            // Skip if already in-flight (ConcurrentHashMap.newKeySet add is atomic)
            if (!inFlight.add(uuid)) {
                log.debug("Task {} already in-flight, skipping", uuid);
                continue;
            }

            log.info("Dispatching task uuid={} status={}", uuid, statusCode);
            executor.execute(() -> {
                try {
                    agentService.executeTask(uuid);
                } catch (Exception e) {
                    log.error("Uncaught exception executing task {}", uuid, e);
                } finally {
                    inFlight.remove(uuid);
                }
            });
        }
    }
}
