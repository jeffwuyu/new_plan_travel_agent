package com.travelagent.service.admin;

import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.agent.planner.TaskExecutionDispatcher;
import com.travelagent.exception.BusinessException;
import com.travelagent.mapper.TaskMapper;
import com.travelagent.model.entity.Task;
import com.travelagent.model.entity.TaskExecutionEvent;
import com.travelagent.model.entity.User;
import com.travelagent.model.enums.TaskStatus;
import com.travelagent.service.notification.SseEvent;
import com.travelagent.service.notification.SseNotificationService;
import com.travelagent.service.task.TaskDispatchQueueService;
import com.travelagent.service.task.TaskLifecycleGovernanceService;
import com.travelagent.service.task.RedisTaskLockService;
import com.travelagent.service.task.TaskProgressService;
import com.travelagent.util.JsonUtil;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AdminTaskOpsService {

    private static final String PAUSE_REASON_USER_DISABLED = "user_disabled_by_admin";

    private final TaskMapper taskMapper;
    private final TaskProgressService taskProgressService;
    private final SseNotificationService sseNotificationService;
    private final JsonUtil jsonUtil;
    private final RedisTaskLockService redisTaskLockService;
    private final TaskDispatchQueueService taskDispatchQueueService;
    private final TaskExecutionDispatcher taskExecutionDispatcher;
    private final TaskLifecycleGovernanceService taskLifecycleGovernanceService;

    public AdminTaskOpsService(TaskMapper taskMapper,
                               TaskProgressService taskProgressService,
                               SseNotificationService sseNotificationService,
                               JsonUtil jsonUtil,
                               RedisTaskLockService redisTaskLockService,
                               TaskDispatchQueueService taskDispatchQueueService,
                               TaskExecutionDispatcher taskExecutionDispatcher,
                               TaskLifecycleGovernanceService taskLifecycleGovernanceService) {
        this.taskMapper = taskMapper;
        this.taskProgressService = taskProgressService;
        this.sseNotificationService = sseNotificationService;
        this.jsonUtil = jsonUtil;
        this.redisTaskLockService = redisTaskLockService;
        this.taskDispatchQueueService = taskDispatchQueueService;
        this.taskExecutionDispatcher = taskExecutionDispatcher;
        this.taskLifecycleGovernanceService = taskLifecycleGovernanceService;
    }

    public int cancelActiveTasksForDisabledUser(User user, Long adminUserId) {
        if (user == null || user.getId() == null) {
            return 0;
        }
        List<Task> activeTasks = taskMapper.findActiveByUserId(user.getId());
        int cancelled = 0;
        for (Task task : activeTasks) {
            if (cancelTaskForDisabledUser(task, adminUserId)) {
                cancelled++;
            }
        }
        return cancelled;
    }

    public Map<String, Object> getLeaseInfo(String taskUuid) {
        if (redisTaskLockService == null) {
            return Map.of(
                    "taskUuid", taskUuid,
                    "available", false,
                    "error", "RedisTaskLockService is not enabled"
            );
        }
        return redisTaskLockService.getLeaseInfo(taskUuid);
    }

    public List<TaskExecutionEvent> getTaskTimeline(String taskUuid, int limit) {
        return taskProgressService.getProgress(taskUuid, limit).getEvents();
    }

    public Map<String, Object> getDispatchQueueSnapshot() {
        if (taskDispatchQueueService == null) {
            return Map.of(
                    "available", false,
                    "error", "TaskDispatchQueueService is not enabled"
            );
        }
        return taskDispatchQueueService.getQueueSnapshot();
    }

    public Map<String, Object> runLifecycleScan(String trigger) {
        if (taskLifecycleGovernanceService == null) {
            return Map.of(
                    "available", false,
                    "error", "TaskLifecycleGovernanceService is not enabled"
            );
        }
        return taskLifecycleGovernanceService.scanAndApplyTimeouts(trigger);
    }

    public Map<String, Object> redispatchTask(String taskUuid, Long adminUserId, String reason) {
        if (taskUuid == null || taskUuid.isBlank()) {
            throw new BusinessException(400, "taskUuid is required");
        }
        Task task = taskMapper.findByUuid(taskUuid);
        if (task == null) {
            throw new BusinessException(404, "task not found");
        }
        TaskStatus current = TaskStatus.fromCode(task.getStatus());
        if (current != TaskStatus.FAILED && current != TaskStatus.PAUSED) {
            throw new BusinessException(400, "only failed or paused tasks can be redispatched by admin");
        }

        TaskCheckpoint checkpoint = parseCheckpoint(task);
        if (checkpoint != null) {
            checkpoint.setCurrentState(TaskStatus.RESUMING.getCode());
            checkpoint.setPauseReason(null);
            checkpoint.setResumableAt(null);
            task.setCheckpointJson(jsonUtil.toJson(checkpoint));
        }
        task.setStatus(TaskStatus.RESUMING.getCode());
        task.setErrorMessage(null);
        taskMapper.resetForAdminRedispatch(task.getId(), TaskStatus.RESUMING.getCode(), task.getCheckpointJson());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskUuid", taskUuid);
        payload.put("status", TaskStatus.RESUMING.getCode());
        payload.put("previousStatus", current.getCode());
        payload.put("adminUserId", adminUserId);
        payload.put("reason", normalizeReason(reason));

        taskProgressService.recordEvent(taskUuid, "ADMIN_REDISPATCH", TaskStatus.RESUMING.getCode(),
                checkpoint != null ? checkpoint.getCurrentStepIndex() : null,
                checkpoint != null ? checkpoint.totalPlannedSteps() : null,
                "Task redispatched by admin", payload);
        sseNotificationService.sendEvent(taskUuid, SseEvent.STATE_CHANGE, payload);

        boolean dispatched;
        try {
            dispatched = taskExecutionDispatcher != null
                    && taskExecutionDispatcher.dispatchTask(taskUuid, "admin_redispatch");
        } catch (RuntimeException e) {
            payload.put("dispatched", false);
            payload.put("dispatchError", e.getMessage());
            taskProgressService.recordEvent(taskUuid, "ADMIN_REDISPATCH_FAILED", TaskStatus.RESUMING.getCode(),
                    checkpoint != null ? checkpoint.getCurrentStepIndex() : null,
                    checkpoint != null ? checkpoint.totalPlannedSteps() : null,
                    "Task redispatch failed: " + e.getMessage(), payload);
            throw e;
        }
        payload.put("dispatched", dispatched);
        return payload;
    }

    private boolean cancelTaskForDisabledUser(Task task, Long adminUserId) {
        TaskStatus current = TaskStatus.fromCode(task.getStatus());
        if (current.isTerminal()) {
            return false;
        }

        TaskCheckpoint checkpoint = parseCheckpoint(task);
        if (checkpoint != null) {
            checkpoint.setCurrentState(TaskStatus.CANCELLED.getCode());
            checkpoint.setPauseReason(PAUSE_REASON_USER_DISABLED);
            checkpoint.setPendingToolCall(null);
            task.setCheckpointJson(jsonUtil.toJson(checkpoint));
        }
        task.setStatus(TaskStatus.CANCELLED.getCode());
        task.setErrorMessage("Task cancelled because the user account was disabled by an admin");
        taskMapper.update(task);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskUuid", task.getTaskUuid());
        payload.put("status", TaskStatus.CANCELLED.getCode());
        payload.put("reason", PAUSE_REASON_USER_DISABLED);
        payload.put("adminUserId", adminUserId);
        payload.put("userId", task.getUserId());
        payload.put("message", task.getErrorMessage());

        taskProgressService.recordEvent(task.getTaskUuid(), "CANCELLED", TaskStatus.CANCELLED.getCode(),
                checkpoint != null ? checkpoint.getCurrentStepIndex() : null,
                checkpoint != null ? checkpoint.totalPlannedSteps() : null,
                task.getErrorMessage(),
                payload);
        sseNotificationService.sendEvent(task.getTaskUuid(), SseEvent.STATE_CHANGE, payload);
        sseNotificationService.completeEmitter(task.getTaskUuid());
        return true;
    }

    private TaskCheckpoint parseCheckpoint(Task task) {
        if (task.getCheckpointJson() == null || task.getCheckpointJson().isBlank()) {
            return null;
        }
        try {
            return jsonUtil.fromJson(task.getCheckpointJson(), TaskCheckpoint.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String normalizeReason(String reason) {
        return reason == null || reason.isBlank() ? "manual_admin_redispatch" : reason.trim();
    }
}
