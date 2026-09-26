package com.travelagent.service.task;

import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.mapper.TaskMapper;
import com.travelagent.model.entity.Task;
import com.travelagent.model.enums.TaskStatus;
import com.travelagent.service.notification.SseEvent;
import com.travelagent.service.notification.SseNotificationService;
import com.travelagent.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

@Service
public class TaskLifecycleGovernanceService {

    private static final Logger log = LoggerFactory.getLogger(TaskLifecycleGovernanceService.class);

    private static final String REASON_AWAITING_TIMEOUT = "awaiting_user_input_timeout";
    private static final String REASON_RESUMING_TIMEOUT = "resuming_timeout";
    private static final String REASON_PAUSED_ARCHIVE_TIMEOUT = "paused_archive_timeout";

    private final TaskMapper taskMapper;
    private final TaskProgressService taskProgressService;
    private final SseNotificationService sseNotificationService;
    private final JsonUtil jsonUtil;

    @Value("${agent.task.lifecycle.enabled:true}")
    private boolean enabled;

    @Value("${agent.task.lifecycle.awaiting-timeout-hours:24}")
    private long awaitingTimeoutHours;

    @Value("${agent.task.lifecycle.resuming-timeout-minutes:30}")
    private long resumingTimeoutMinutes;

    @Value("${agent.task.lifecycle.paused-archive-days:14}")
    private long pausedArchiveDays;

    @Value("${agent.task.lifecycle.scan-limit:100}")
    private int scanLimit;

    public TaskLifecycleGovernanceService(TaskMapper taskMapper,
                                          TaskProgressService taskProgressService,
                                          SseNotificationService sseNotificationService,
                                          JsonUtil jsonUtil) {
        this.taskMapper = taskMapper;
        this.taskProgressService = taskProgressService;
        this.sseNotificationService = sseNotificationService;
        this.jsonUtil = jsonUtil;
    }

    @Scheduled(
            fixedDelayString = "${agent.task.lifecycle.scan-interval-ms:600000}",
            initialDelayString = "${agent.task.lifecycle.scan-initial-delay-ms:600000}"
    )
    public void scheduledScan() {
        if (!enabled) {
            return;
        }
        Map<String, Object> result = scanAndApplyTimeouts("scheduled");
        log.debug("[TaskLifecycleGovernance] scan result={}", result);
    }

    public Map<String, Object> scanAndApplyTimeouts(String trigger) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("enabled", enabled);
        summary.put("trigger", normalizeTrigger(trigger));
        if (!enabled) {
            summary.put("pausedAwaiting", 0);
            summary.put("pausedResuming", 0);
            summary.put("archivedPaused", 0);
            return summary;
        }

        int limit = Math.max(1, scanLimit);
        int pausedAwaiting = pauseStaleTasks(
                TaskStatus.AWAITING_USER_INPUT,
                Duration.ofHours(Math.max(1, awaitingTimeoutHours)),
                REASON_AWAITING_TIMEOUT,
                "TASK_WAIT_TIMEOUT",
                "Task paused after waiting too long for user input",
                normalizeTrigger(trigger),
                limit
        );
        int pausedResuming = pauseStaleTasks(
                TaskStatus.RESUMING,
                Duration.ofMinutes(Math.max(1, resumingTimeoutMinutes)),
                REASON_RESUMING_TIMEOUT,
                "TASK_RESUME_TIMEOUT",
                "Task paused because resume dispatch stayed pending too long",
                normalizeTrigger(trigger),
                limit
        );
        int archivedPaused = archiveStalePausedTasks(
                Duration.ofDays(Math.max(1, pausedArchiveDays)),
                normalizeTrigger(trigger),
                limit
        );

        summary.put("pausedAwaiting", pausedAwaiting);
        summary.put("pausedResuming", pausedResuming);
        summary.put("archivedPaused", archivedPaused);
        summary.put("scanLimit", limit);
        return summary;
    }

    /**
     * Claims expired executable tasks using a database cursor. The cursor makes
     * repeated scans progress through large backlogs without relying on a
     * process-start snapshot.
     */
    public RecoveryScanResult scanExpiredExecutionLeases(String trigger, Long afterId, int batchSize) {
        List<String> statuses = List.of(TaskStatus.PLANNING.getCode(), TaskStatus.TOOL_CALLING.getCode(),
                TaskStatus.RESUMING.getCode());
        long cursor = afterId == null ? 0L : Math.max(0L, afterId);
        int claimed = 0;
        int skipped = 0;
        List<String> claimedTaskUuids = new ArrayList<>();
        int limit = Math.max(1, Math.min(batchSize, 500));
        while (true) {
            List<Task> tasks = taskMapper.findRecoverableExpiredLeases(statuses,
                    LocalDateTime.now(), cursor, limit);
            if (tasks == null || tasks.isEmpty()) {
                break;
            }
            for (Task task : tasks) {
                cursor = Math.max(cursor, task.getId() == null ? cursor : task.getId());
                String token = UUID.randomUUID().toString();
                long revision = task.getRevision() == null ? 1L : task.getRevision();
                int updated = taskMapper.claimRecoveryIfExpired(task.getId(), revision,
                        normalizeTrigger(trigger), token, LocalDateTime.now().plusMinutes(10));
                if (updated == 1) {
                    claimed++;
                    claimedTaskUuids.add(task.getTaskUuid());
                } else {
                    skipped++;
                }
            }
            if (tasks.size() < limit) {
                break;
            }
        }
        return new RecoveryScanResult(cursor, claimed, skipped, claimedTaskUuids);
    }

    public record RecoveryScanResult(long nextCursor, int claimed, int skipped, List<String> taskUuids) {
    }

    public String claimExecutionLease(Long taskId, String expectedStatus, String owner, Duration ttl) {
        Task task = taskMapper.findById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("TASK_NOT_FOUND");
        }
        String token = UUID.randomUUID().toString();
        long revision = task.getRevision() == null ? 1L : task.getRevision();
        int claimed = taskMapper.claimExecution(taskId, expectedStatus, owner, token,
                LocalDateTime.now().plus(ttl), revision);
        if (claimed != 1) {
            throw new IllegalStateException("LEASE_LOST");
        }
        return token;
    }

    public void renewExecutionLease(Long taskId, String leaseToken, Duration ttl) {
        int renewed = taskMapper.renewExecutionLease(taskId, leaseToken, LocalDateTime.now().plus(ttl));
        if (renewed != 1) {
            throw new IllegalStateException("LEASE_LOST");
        }
    }

    public void saveCheckpointWithLease(Task task, String leaseToken) {
        long revision = task.getRevision() == null ? 1L : task.getRevision();
        int updated = taskMapper.updateCheckpointIfOwned(task, revision, leaseToken);
        if (updated != 1) {
            throw new IllegalStateException("LEASE_LOST");
        }
        task.setRevision(revision + 1);
    }

    /** Apply a lifecycle transition using the current revision and lease token. */
    public void transitionStatus(Task task, String nextStatus) {
        if (task == null || task.getId() == null) {
            throw new IllegalArgumentException("TASK_NOT_FOUND");
        }
        long revision = task.getRevision() == null ? 1L : task.getRevision();
        int updated = taskMapper.updateStatusIfRevision(task.getId(), nextStatus,
                revision, task.getLeaseToken());
        if (updated != 1) {
            throw new IllegalStateException("LEASE_LOST");
        }
        task.setRevision(revision + 1);
        task.setStatus(nextStatus);
    }

    public boolean reserveOperation(Long userId, String taskUuid, String operationId, String operationType) {
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("operationId is required");
        }
        return taskMapper.insertOperationIfAbsent(userId, taskUuid, operationId, operationType) == 1;
    }

    public Map<String, Object> getOperation(Long userId, String taskUuid, String operationId) {
        return taskMapper.findOperation(userId, taskUuid, operationId);
    }

    public void completeOperation(Long userId, String taskUuid, String operationId,
                                  String resultStatus, String resultJson) {
        if (taskMapper.completeOperation(userId, taskUuid, operationId, resultStatus, resultJson) != 1) {
            throw new IllegalStateException("OPERATION_NOT_RUNNING");
        }
    }

    private int pauseStaleTasks(TaskStatus staleStatus,
                                Duration timeout,
                                String reason,
                                String eventType,
                                String message,
                                String trigger,
                                int limit) {
        LocalDateTime cutoff = LocalDateTime.now().minus(timeout);
        List<Task> tasks = taskMapper.findStaleByStatus(staleStatus.getCode(), cutoff, limit);
        int changed = 0;
        for (Task task : tasks) {
            TaskCheckpoint checkpoint = parseCheckpoint(task);
            String checkpointJson = task.getCheckpointJson();
            if (checkpoint != null) {
                checkpoint.setCurrentState(TaskStatus.PAUSED.getCode());
                checkpoint.setPauseReason(reason);
                checkpoint.setResumableAt(null);
                checkpointJson = jsonUtil.toJson(checkpoint);
            }
            String errorMessage = message + " (" + reason + ")";
            int updated = taskMapper.transitionStatusIfCurrent(
                    task.getId(),
                    staleStatus.getCode(),
                    TaskStatus.PAUSED.getCode(),
                    checkpointJson,
                    errorMessage,
                    task.getRevision() == null ? 1L : task.getRevision()
            );
            if (updated > 0) {
                changed++;
                Map<String, Object> payload = buildPayload(task, TaskStatus.PAUSED, staleStatus, reason, trigger);
                taskProgressService.recordEvent(task.getTaskUuid(), eventType, TaskStatus.PAUSED.getCode(),
                        checkpoint != null ? checkpoint.getCurrentStepIndex() : null,
                        checkpoint != null ? checkpoint.totalPlannedSteps() : null,
                        message,
                        payload);
                sseNotificationService.sendEvent(task.getTaskUuid(), SseEvent.PAUSED, payload);
                sseNotificationService.sendEvent(task.getTaskUuid(), SseEvent.STATE_CHANGE, payload);
            }
        }
        return changed;
    }

    private int archiveStalePausedTasks(Duration timeout, String trigger, int limit) {
        LocalDateTime cutoff = LocalDateTime.now().minus(timeout);
        List<Task> tasks = taskMapper.findStaleByStatus(TaskStatus.PAUSED.getCode(), cutoff, limit);
        int changed = 0;
        for (Task task : tasks) {
            TaskCheckpoint checkpoint = parseCheckpoint(task);
            String checkpointJson = task.getCheckpointJson();
            if (checkpoint != null) {
                checkpoint.setCurrentState(TaskStatus.CANCELLED.getCode());
                checkpoint.setPauseReason(REASON_PAUSED_ARCHIVE_TIMEOUT);
                checkpoint.setPendingToolCall(null);
                checkpointJson = jsonUtil.toJson(checkpoint);
            }
            String message = "Task auto-closed after staying paused too long";
            int updated = taskMapper.transitionStatusIfCurrent(
                    task.getId(),
                    TaskStatus.PAUSED.getCode(),
                    TaskStatus.CANCELLED.getCode(),
                    checkpointJson,
                    message + " (" + REASON_PAUSED_ARCHIVE_TIMEOUT + ")",
                    task.getRevision() == null ? 1L : task.getRevision()
            );
            if (updated > 0) {
                changed++;
                Map<String, Object> payload = buildPayload(task, TaskStatus.CANCELLED, TaskStatus.PAUSED,
                        REASON_PAUSED_ARCHIVE_TIMEOUT, trigger);
                taskProgressService.recordEvent(task.getTaskUuid(), "TASK_AUTO_ARCHIVED", TaskStatus.CANCELLED.getCode(),
                        checkpoint != null ? checkpoint.getCurrentStepIndex() : null,
                        checkpoint != null ? checkpoint.totalPlannedSteps() : null,
                        message,
                        payload);
                sseNotificationService.sendEvent(task.getTaskUuid(), SseEvent.STATE_CHANGE, payload);
                sseNotificationService.completeEmitter(task.getTaskUuid());
            }
        }
        return changed;
    }

    private Map<String, Object> buildPayload(Task task,
                                             TaskStatus status,
                                             TaskStatus previousStatus,
                                             String reason,
                                             String trigger) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskUuid", task.getTaskUuid());
        payload.put("status", status.getCode());
        payload.put("previousStatus", previousStatus.getCode());
        payload.put("reason", reason);
        payload.put("trigger", trigger);
        payload.put("userId", task.getUserId());
        return payload;
    }

    private TaskCheckpoint parseCheckpoint(Task task) {
        if (task == null || task.getCheckpointJson() == null || task.getCheckpointJson().isBlank()) {
            return null;
        }
        try {
            TaskCheckpoint checkpoint = jsonUtil.fromJson(task.getCheckpointJson(), TaskCheckpoint.class);
            checkpoint.migrateToCurrentSchema();
            return checkpoint;
        } catch (IllegalArgumentException e) {
            String code = e.getMessage() != null && e.getMessage().contains("schema")
                    ? "CHECKPOINT_VERSION_UNSUPPORTED" : "CHECKPOINT_CORRUPTED";
            String message = "Checkpoint cannot be resumed for task=" + task.getTaskUuid() + ": " + e.getMessage();
            markCorruptCheckpoint(task, code, message);
            return null;
        } catch (Exception e) {
            log.warn("[TaskLifecycleGovernance] Failed to parse checkpoint for task={}: {}",
                    task.getTaskUuid(), e.getMessage());
            String message = "Checkpoint cannot be parsed for task=" + task.getTaskUuid();
            markCorruptCheckpoint(task, "CHECKPOINT_CORRUPTED", message);
            return null;
        }
    }

    private void markCorruptCheckpoint(Task task, String code, String message) {
        int updated = taskMapper.transitionStatusIfCurrent(
                task.getId(), task.getStatus(), TaskStatus.FAILED.getCode(), null,
                code + ": " + message, task.getRevision() == null ? 1L : task.getRevision());
        if (updated > 0) {
            taskProgressService.recordEvent(task.getTaskUuid(), "CHECKPOINT_CORRUPTED",
                    TaskStatus.FAILED.getCode(), null, null, message,
                    Map.of("code", code, "retryable", false));
            sseNotificationService.sendEvent(task.getTaskUuid(), SseEvent.ERROR,
                    Map.of("code", code, "message", message, "retryable", false));
        }
    }

    private String normalizeTrigger(String trigger) {
        return trigger == null || trigger.isBlank() ? "manual" : trigger.trim();
    }
}
