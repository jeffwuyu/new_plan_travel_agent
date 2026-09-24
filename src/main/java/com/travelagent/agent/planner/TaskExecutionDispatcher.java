package com.travelagent.agent.planner;

import com.travelagent.mapper.TaskMapper;
import com.travelagent.model.entity.Task;
import com.travelagent.service.agent.AgentService;
import com.travelagent.service.task.RedisTaskLockService;
import com.travelagent.service.task.TaskLifecycleGovernanceService;
import com.travelagent.service.task.TaskDispatchQueueService;
import com.travelagent.service.task.TaskProgressService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Duration;

@Component
public class TaskExecutionDispatcher {

    private static final Logger log = LoggerFactory.getLogger(TaskExecutionDispatcher.class);

    @Autowired private TaskMapper taskMapper;
    @Autowired private AgentService agentService;
    @Autowired(required = false) private RedisTaskLockService redisTaskLockService;
    @Autowired(required = false) private TaskLifecycleGovernanceService lifecycleGovernanceService;
    @Autowired(required = false) private TaskProgressService taskProgressService;
    @Autowired(required = false) private TaskDispatchQueueService taskDispatchQueueService;

    @Autowired
    @Qualifier("agentTaskExecutor")
    private ThreadPoolTaskExecutor executor;

    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    /**
     * 判断dispatchTask。
     * @param taskUuid 任务唯一标识
     * @param trigger t ri gg er 参数
     * @return 是否满足当前条件。
     */
    public boolean dispatchTask(String taskUuid, String trigger) {
        if (taskDispatchQueueService != null && taskDispatchQueueService.isEnabled()) {
            String streamId = taskDispatchQueueService.enqueue(taskUuid, trigger);
            if (streamId == null || streamId.isBlank()) {
                throw new IllegalStateException("Task dispatch queue enqueue returned empty stream id");
            }
            recordQueueEvent(taskUuid, "queued", streamId);
            drainQueuedTasks(1);
            return true;
        }
        return dispatchTaskDirect(taskUuid, trigger);
    }

    private boolean dispatchTaskDirect(String taskUuid, String trigger) {
        if (!inFlight.add(taskUuid)) {
            log.debug("Task {} already in-flight, skipping trigger={}", taskUuid, trigger);
            return false;
        }
        if (redisTaskLockService != null) {
            RedisTaskLockService.AcquireResult acquireResult = redisTaskLockService.acquireForDispatch(taskUuid, trigger);
            if (!acquireResult.shouldDispatch()) {
                inFlight.remove(taskUuid);
                if (acquireResult.redisUnavailable()) {
                    recordDispatchBlocked(taskUuid, trigger, acquireResult);
                } else {
                    log.debug("Task {} already locked by another instance, skipping trigger={}", taskUuid, trigger);
                }
                return false;
            }
            if (acquireResult.redisUnavailable()) {
                recordDispatchDegraded(taskUuid, trigger, acquireResult);
            }
        }

        String databaseLeaseToken = claimDatabaseLease(taskUuid, trigger);
        if (lifecycleGovernanceService != null && databaseLeaseToken == null
                && taskMapper.findByUuid(taskUuid) != null) {
            if (redisTaskLockService != null) {
                redisTaskLockService.release(taskUuid);
            }
            inFlight.remove(taskUuid);
            recordDatabaseLeaseBlocked(taskUuid, trigger);
            return false;
        }

        log.info("Dispatching task uuid={} trigger={}", taskUuid, trigger);
        try {
            executor.execute(() -> {
                try {
                    if (databaseLeaseToken == null) {
                        agentService.executeTask(taskUuid);
                    } else {
                        agentService.executeTask(taskUuid, databaseLeaseToken);
                    }
                } catch (Exception e) {
                    log.error("Uncaught exception executing task {} trigger={}", taskUuid, trigger, e);
                } finally {
                    if (redisTaskLockService != null) {
                        redisTaskLockService.release(taskUuid);
                    }
                    inFlight.remove(taskUuid);
                }
            });
        } catch (RuntimeException e) {
            if (redisTaskLockService != null) {
                redisTaskLockService.release(taskUuid);
            }
            inFlight.remove(taskUuid);
            throw e;
        }
        return true;
    }

    private String claimDatabaseLease(String taskUuid, String trigger) {
        if (lifecycleGovernanceService == null) {
            return null;
        }
        Task task = taskMapper.findByUuid(taskUuid);
        if (task == null || task.getId() == null || task.getStatus() == null) {
            return null;
        }
        try {
            return lifecycleGovernanceService.claimExecutionLease(
                    task.getId(), task.getStatus(),
                    "dispatcher:" + Thread.currentThread().getName() + ":" + trigger,
                    Duration.ofMinutes(10));
        } catch (RuntimeException e) {
            log.warn("Database execution lease claim failed for task={}: {}", taskUuid, e.getMessage());
            return null;
        }
    }

    private void recordDatabaseLeaseBlocked(String taskUuid, String trigger) {
        if (taskProgressService != null) {
            taskProgressService.recordEvent(taskUuid, "DISPATCH_BLOCKED", null, null, null,
                    "Database execution lease unavailable; dispatch was fenced.",
                    Map.of("trigger", trigger, "reason", "DATABASE_LEASE_LOST"));
        }
    }

    /**
     * 处理dispatchByStatus。
     * @param statusCode s ta tu sC od e 参数
     * @param limit 返回数量上限
     */
    public void dispatchByStatus(String statusCode, int limit) {
        List<Task> tasks = taskMapper.findByStatus(statusCode, limit);
        if (taskDispatchQueueService == null || !taskDispatchQueueService.isEnabled()) {
            for (Task task : tasks) {
                dispatchTaskDirect(task.getTaskUuid(), "poll:" + statusCode);
            }
            return;
        }

        for (Task task : tasks) {
            String streamId = taskDispatchQueueService.enqueue(task.getTaskUuid(), "poll:" + statusCode);
            recordQueueEvent(task.getTaskUuid(), statusCode, streamId);
        }
        drainQueuedTasks(limit);
    }

    public int drainQueuedTasks(int limit) {
        if (taskDispatchQueueService == null || !taskDispatchQueueService.isEnabled()) {
            return 0;
        }
        int dispatched = 0;
        for (MapRecord<String, Object, Object> record : taskDispatchQueueService.readOldest(limit)) {
            String taskUuid = value(record, "taskUuid");
            if (taskUuid == null || taskUuid.isBlank()) {
                taskDispatchQueueService.deadLetter(record, "missing taskUuid");
                continue;
            }
            String trigger = "queue:" + value(record, "trigger") + ":" + record.getId().getValue();
            try {
                boolean accepted = dispatchTaskDirect(taskUuid, trigger);
                if (accepted) {
                    taskDispatchQueueService.ack(record.getId().getValue());
                    dispatched += 1;
                } else {
                    taskDispatchQueueService.retryLaterOrDeadLetter(record, "dispatch not accepted");
                }
            } catch (RuntimeException e) {
                taskDispatchQueueService.deadLetter(record, e.getMessage());
            }
        }
        return dispatched;
    }

    private String value(MapRecord<String, Object, Object> record, String key) {
        Object value = record.getValue().get(key);
        return value == null ? null : String.valueOf(value);
    }

    private void recordQueueEvent(String taskUuid, String statusCode, String streamId) {
        if (taskProgressService != null && streamId != null) {
            taskProgressService.recordEvent(taskUuid, "DISPATCH_QUEUED", statusCode, null, null,
                    "Task dispatch request enqueued in Redis Stream.",
                    Map.of("streamId", streamId, "status", statusCode));
        }
    }

    private void recordDispatchBlocked(String taskUuid, String trigger, RedisTaskLockService.AcquireResult result) {
        log.error("Task {} dispatch blocked because Redis lock is unavailable, trigger={}, result={}",
                taskUuid, trigger, result);
        if (taskProgressService != null) {
            taskProgressService.recordEvent(taskUuid, "DISPATCH_BLOCKED", null, null, null,
                    "Redis task lock unavailable; dispatch blocked to avoid multi-instance duplicate execution.",
                    Map.of("trigger", trigger, "result", result.name()));
        }
    }

    private void recordDispatchDegraded(String taskUuid, String trigger, RedisTaskLockService.AcquireResult result) {
        log.error("Task {} dispatch falling back to local in-flight only, trigger={}, result={}",
                taskUuid, trigger, result);
        if (taskProgressService != null) {
            taskProgressService.recordEvent(taskUuid, "DISPATCH_DEGRADED", null, null, null,
                    "Redis task lock unavailable; using explicitly configured local fallback.",
                    Map.of("trigger", trigger, "result", result.name()));
        }
    }
}
