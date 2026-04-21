package com.travelagent.service.task.impl;

import com.travelagent.agent.context.PlanningConfig;
import com.travelagent.agent.context.RetryState;
import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.agent.statemachine.AgentEvent;
import com.travelagent.agent.statemachine.AgentStateMachine;
import com.travelagent.exception.BusinessException;
import com.travelagent.exception.TaskNotFoundException;
import com.travelagent.mapper.TaskMapper;
import com.travelagent.model.dto.ConfirmOriginSelectionRequest;
import com.travelagent.model.dto.CreateTaskRequest;
import com.travelagent.model.dto.LocationCandidateItem;
import com.travelagent.model.dto.SelectedOrigin;
import com.travelagent.model.dto.TaskResponse;
import com.travelagent.model.entity.Task;
import com.travelagent.model.entity.UserQuotaConfig;
import com.travelagent.model.enums.TaskStatus;
import com.travelagent.service.notification.SseEvent;
import com.travelagent.service.notification.SseNotificationService;
import com.travelagent.service.task.OriginCandidateService;
import com.travelagent.service.task.TaskService;
import com.travelagent.service.user.QuotaService;
import com.travelagent.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TaskServiceImpl implements TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskServiceImpl.class);
    private static final int DEFAULT_TOTAL_DAYS = 1;

    @Autowired private TaskMapper taskMapper;
    @Autowired private QuotaService quotaService;
    @Autowired private AgentStateMachine stateMachine;
    @Autowired private SseNotificationService sseNotificationService;
    @Autowired private JsonUtil jsonUtil;
    @Autowired private OriginCandidateService originCandidateService;

    @Override
    @Transactional
    public TaskResponse createTask(Long userId, int userLevel, CreateTaskRequest request) {
        quotaService.checkDailyQuota(userId, userLevel);

        UserQuotaConfig config = quotaService.getQuotaConfig(userLevel);
        int activeCount = taskMapper.countActiveByUserId(userId);
        if (activeCount >= config.getMaxConcurrentTasks()) {
            throw new BusinessException(429,
                    String.format("active task limit reached (%d)", config.getMaxConcurrentTasks()));
        }

        Task task = new Task();
        task.setTaskUuid(UUID.randomUUID().toString());
        task.setUserId(userId);
        task.setStatus(TaskStatus.PENDING.getCode());
        task.setRegion(request.getRegion());
        task.setSchemaVersion("1.0");
        task.setTotalTokensUsed(0);
        taskMapper.insert(task);

        TaskCheckpoint checkpoint = buildInitialCheckpoint(task, request);
        task.setCheckpointJson(jsonUtil.toJson(checkpoint));
        taskMapper.updateCheckpoint(task);

        log.info("Created task uuid={} for userId={}", task.getTaskUuid(), userId);
        return TaskResponse.from(task, checkpoint);
    }

    @Override
    public TaskResponse getTask(String taskUuid, Long requestingUserId) {
        Task task = loadAndVerifyOwnership(taskUuid, requestingUserId);
        return TaskResponse.from(task, parseCheckpoint(task));
    }

    @Override
    public List<TaskResponse> listTasks(Long userId) {
        return taskMapper.findByUserId(userId).stream()
                .map(task -> TaskResponse.from(task, parseCheckpoint(task)))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void cancelTask(String taskUuid, Long requestingUserId) {
        Task task = loadAndVerifyOwnership(taskUuid, requestingUserId);
        TaskStatus current = TaskStatus.fromCode(task.getStatus());
        if (current.isTerminal()) {
            throw new BusinessException(400, "task is already terminal");
        }

        TaskStatus next = stateMachine.transition(current, AgentEvent.CANCEL);
        taskMapper.updateStatus(task.getId(), next.getCode());
        sseNotificationService.sendEvent(taskUuid, SseEvent.STATE_CHANGE,
                Map.of("status", next.getCode(), "taskUuid", taskUuid));
        sseNotificationService.completeEmitter(taskUuid);
    }

    @Override
    @Transactional
    public TaskResponse resumeTask(String taskUuid, Long requestingUserId) {
        Task task = loadAndVerifyOwnership(taskUuid, requestingUserId);
        TaskStatus current = TaskStatus.fromCode(task.getStatus());
        if (!current.isResumable()) {
            throw new BusinessException(400, "only paused tasks can be resumed");
        }

        TaskStatus next = stateMachine.transition(current, AgentEvent.RESUME);
        taskMapper.updateStatus(task.getId(), next.getCode());
        Task updated = taskMapper.findByUuid(taskUuid);
        return TaskResponse.from(updated, parseCheckpoint(updated));
    }

    @Override
    @Transactional
    public TaskResponse confirmOriginSelection(String taskUuid, Long requestingUserId, ConfirmOriginSelectionRequest request) {
        Task task = loadAndVerifyOwnership(taskUuid, requestingUserId);
        TaskStatus current = TaskStatus.fromCode(task.getStatus());
        if (!current.isAwaitingUserInput()) {
            throw new BusinessException(400, "task is not waiting for origin selection");
        }

        TaskCheckpoint checkpoint = parseCheckpoint(task);
        if (checkpoint == null || checkpoint.getLocationCandidates() == null || checkpoint.getLocationCandidates().isEmpty()) {
            throw new BusinessException(400, "no origin candidates available");
        }
        if (checkpoint.isOriginConfirmed()) {
            throw new BusinessException(400, "origin has already been selected");
        }

        LocationCandidateItem candidate = checkpoint.getLocationCandidates().stream()
                .filter(item -> item.getCandidateId().equals(request.getSelectedCandidateId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(400, "selected candidate does not belong to this task"));

        SelectedOrigin selectedOrigin = new SelectedOrigin();
        selectedOrigin.setCandidateId(candidate.getCandidateId());
        selectedOrigin.setName(request.getSelectedCandidateName());
        selectedOrigin.setRegion(candidate.getRegion());
        selectedOrigin.setDistrict(candidate.getDistrict());
        selectedOrigin.setAddress(candidate.getAddress());
        selectedOrigin.setLatitude(request.getSelectedLat() != null ? request.getSelectedLat() : candidate.getLatitude());
        selectedOrigin.setLongitude(request.getSelectedLng() != null ? request.getSelectedLng() : candidate.getLongitude());
        selectedOrigin.setAdcode(candidate.getAdcode());
        selectedOrigin.setSource(candidate.getSource());

        checkpoint.setSelectedOrigin(selectedOrigin);
        checkpoint.setOriginConfirmed(true);
        checkpoint.setPendingInputType(null);
        checkpoint.setLocationCandidates(List.of());
        checkpoint.setCurrentState(TaskStatus.RESUMING.getCode());
        task.setStatus(TaskStatus.RESUMING.getCode());
        task.setCheckpointJson(jsonUtil.toJson(checkpoint));

        TaskStatus next = stateMachine.transition(current, AgentEvent.USER_INPUT_RECEIVED);
        taskMapper.updateStatus(task.getId(), next.getCode());
        taskMapper.updateCheckpoint(task);

        Map<String, Object> payload = Map.of(
                "taskUuid", taskUuid,
                "selectedOrigin", selectedOrigin
        );
        sseNotificationService.sendEvent(taskUuid, SseEvent.USER_SELECTION_CONFIRMED, payload);
        sseNotificationService.sendEvent(taskUuid, SseEvent.STATE_CHANGE, Map.of(
                "status", next.getCode(),
                "taskUuid", taskUuid
        ));

        Task updated = taskMapper.findByUuid(taskUuid);
        return TaskResponse.from(updated, checkpoint);
    }

    @Override
    public Task getTaskEntity(String taskUuid, Long requestingUserId) {
        return loadAndVerifyOwnership(taskUuid, requestingUserId);
    }

    private TaskCheckpoint buildInitialCheckpoint(Task task, CreateTaskRequest req) {
        PlanningConfig config = new PlanningConfig(
                DEFAULT_TOTAL_DAYS,
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
        cp.setCurrentLocationQuery(req.getCurrentLocationQuery());
        cp.setPlanningConfig(config);
        cp.setCurrentStepIndex(0);
        cp.setRetryState(new RetryState(0, 3));
        cp.setLocationCandidates(originCandidateService.generateCandidates(req.getRegion(), req.getCurrentLocationQuery()));
        cp.setOriginConfirmed(false);
        return cp;
    }

    private Task loadAndVerifyOwnership(String taskUuid, Long requestingUserId) {
        Task task = taskMapper.findByUuid(taskUuid);
        if (task == null) {
            throw new TaskNotFoundException(taskUuid);
        }
        if (!task.getUserId().equals(requestingUserId)) {
            throw new BusinessException(403, "forbidden");
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
}
