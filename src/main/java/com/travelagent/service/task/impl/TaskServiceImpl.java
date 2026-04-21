package com.travelagent.service.task.impl;

import com.travelagent.agent.context.PlanningConfig;
import com.travelagent.agent.context.RetryState;
import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.agent.statemachine.AgentEvent;
import com.travelagent.agent.statemachine.AgentStateMachine;
import com.travelagent.exception.BusinessException;
import com.travelagent.exception.TaskNotFoundException;
import com.travelagent.mapper.TaskMapper;
import com.travelagent.model.dto.CreateTaskRequest;
import com.travelagent.model.dto.TaskResponse;
import com.travelagent.model.entity.Task;
import com.travelagent.model.entity.UserQuotaConfig;
import com.travelagent.model.enums.TaskStatus;
import com.travelagent.service.notification.SseEvent;
import com.travelagent.service.notification.SseNotificationService;
import com.travelagent.service.task.TaskService;
import com.travelagent.service.user.QuotaService;
import com.travelagent.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 中文注释：服务实现类，负责承载 Task Service Impl 对应的核心业务逻辑。
 */

@Service
public class TaskServiceImpl implements TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskServiceImpl.class);

    @Autowired private TaskMapper              taskMapper;
    @Autowired private QuotaService            quotaService;
    @Autowired private AgentStateMachine       stateMachine;
    @Autowired private SseNotificationService  sseNotificationService;
    @Autowired private JsonUtil                jsonUtil;

    // -----------------------------------------------------------------------
    // createTask
    // -----------------------------------------------------------------------

    @Override
    @Transactional
    public TaskResponse createTask(Long userId, int userLevel, CreateTaskRequest request) {
        // 1. Daily quota check (throws QuotaExhaustedException → HTTP 429 via GlobalExceptionHandler)
        quotaService.checkDailyQuota(userId, userLevel);

        // 2. Concurrent task limit check
        UserQuotaConfig config = quotaService.getQuotaConfig(userLevel);
        int activeCount = taskMapper.countActiveByUserId(userId);
        if (activeCount >= config.getMaxConcurrentTasks()) {
            throw new BusinessException(429,
                String.format("已达到最大并发任务数(%d)，请等待现有任务完成后再创建",
                    config.getMaxConcurrentTasks()));
        }

        // 3. Build and persist the Task entity (checkpoint will be backfilled below)
        Task task = new Task();
        task.setTaskUuid(UUID.randomUUID().toString());
        task.setUserId(userId);
        task.setStatus(TaskStatus.PENDING.getCode());
        task.setRegion(request.getRegion());
        task.setSchemaVersion("1.0");
        task.setTotalTokensUsed(0);

        taskMapper.insert(task);  // populates task.getId() via useGeneratedKeys

        // 4. Build TaskCheckpoint with known taskId and persist it atomically
        TaskCheckpoint checkpoint = buildInitialCheckpoint(task, request);
        task.setCheckpointJson(jsonUtil.toJson(checkpoint));
        taskMapper.updateCheckpoint(task);

        log.info("Created task uuid={} for userId={}", task.getTaskUuid(), userId);
        return TaskResponse.from(task, checkpoint);
    }

    private TaskCheckpoint buildInitialCheckpoint(Task task, CreateTaskRequest req) {
        PlanningConfig config = new PlanningConfig(
            req.getTotalDays(),
            req.getAttractionsPerDay(),
            req.getPreferenceKeywords(),
            req.getTravelMode()
        );
        TaskCheckpoint cp = new TaskCheckpoint();
        cp.setSchemaVersion("1.0");
        cp.setTaskId(task.getId());
        cp.setTaskUuid(task.getTaskUuid());
        cp.setCurrentState(TaskStatus.PENDING.getCode());
        cp.setRegion(req.getRegion());
        cp.setUserIntent(req.getUserIntent());
        cp.setPlanningConfig(config);
        cp.setCurrentStepIndex(0);
        cp.setRetryState(new RetryState(0, 3));
        return cp;
    }

    // -----------------------------------------------------------------------
    // getTask
    // -----------------------------------------------------------------------

    @Override
    public TaskResponse getTask(String taskUuid, Long requestingUserId) {
        Task task = loadAndVerifyOwnership(taskUuid, requestingUserId);
        TaskCheckpoint checkpoint = parseCheckpoint(task);
        return TaskResponse.from(task, checkpoint);
    }

    // -----------------------------------------------------------------------
    // listTasks
    // -----------------------------------------------------------------------

    @Override
    public List<TaskResponse> listTasks(Long userId) {
        return taskMapper.findByUserId(userId).stream()
            .map(TaskResponse::from)
            .collect(Collectors.toList());
    }

    // -----------------------------------------------------------------------
    // cancelTask
    // -----------------------------------------------------------------------

    @Override
    @Transactional
    public void cancelTask(String taskUuid, Long requestingUserId) {
        Task task = loadAndVerifyOwnership(taskUuid, requestingUserId);
        TaskStatus current = TaskStatus.fromCode(task.getStatus());

        if (current.isTerminal()) {
            throw new BusinessException(400,
                String.format("任务已处于终态[%s]，无法取消", current.getCode()));
        }

        TaskStatus next = stateMachine.transition(current, AgentEvent.CANCEL);
        taskMapper.updateStatus(task.getId(), next.getCode());

        // Notify any connected SSE clients and clean up the emitter
        sseNotificationService.sendEvent(taskUuid, SseEvent.STATE_CHANGE,
            Map.of("status", next.getCode(), "taskUuid", taskUuid));
        sseNotificationService.completeEmitter(taskUuid);

        log.info("Cancelled task uuid={} by userId={}", taskUuid, requestingUserId);
    }

    // -----------------------------------------------------------------------
    // resumeTask
    // -----------------------------------------------------------------------

    @Override
    @Transactional
    public TaskResponse resumeTask(String taskUuid, Long requestingUserId) {
        Task task = loadAndVerifyOwnership(taskUuid, requestingUserId);
        TaskStatus current = TaskStatus.fromCode(task.getStatus());

        if (!current.isResumable()) {
            throw new BusinessException(400,
                String.format("任务当前状态[%s]不可恢复，只有PAUSED状态的任务可以恢复", current.getCode()));
        }

        TaskStatus next = stateMachine.transition(current, AgentEvent.RESUME);
        taskMapper.updateStatus(task.getId(), next.getCode());

        // Refresh entity to return accurate updatedAt
        Task updated = taskMapper.findByUuid(taskUuid);
        TaskCheckpoint checkpoint = parseCheckpoint(updated);
        log.info("Resumed task uuid={} by userId={}, new status={}", taskUuid, requestingUserId, next.getCode());
        return TaskResponse.from(updated, checkpoint);
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private Task loadAndVerifyOwnership(String taskUuid, Long requestingUserId) {
        Task task = taskMapper.findByUuid(taskUuid);
        if (task == null) {
            throw new TaskNotFoundException(taskUuid);
        }
        if (!task.getUserId().equals(requestingUserId)) {
            throw new BusinessException(403, "无权操作该任务");
        }
        return task;
    }

    private TaskCheckpoint parseCheckpoint(Task task) {
        if (task.getCheckpointJson() == null || task.getCheckpointJson().isBlank()) {
            return null;
        }
        try {
            return jsonUtil.fromJson(task.getCheckpointJson(), TaskCheckpoint.class);
        } catch (Exception e) {
            log.warn("Failed to parse checkpoint for task={}: {}", task.getTaskUuid(), e.getMessage());
            return null;
        }
    }

    @Override
    public Task getTaskEntity(String taskUuid, Long requestingUserId) {
        return loadAndVerifyOwnership(taskUuid, requestingUserId);
    }

    /** Midnight Asia/Shanghai of the next day — when daily quota resets. */
    @SuppressWarnings("unused")
    private LocalDateTime nextDailyReset() {
        return LocalDateTime.of(LocalDate.now().plusDays(1), LocalTime.MIDNIGHT);
    }
}
