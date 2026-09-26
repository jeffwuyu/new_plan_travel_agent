package com.travelagent.service.task.impl;

import com.travelagent.agent.context.CompletedStep;
import com.travelagent.agent.context.PendingToolCall;
import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.agent.memory.UserPreferenceMemoryService;
import com.travelagent.agent.planner.PlanningResult;
import com.travelagent.agent.planner.TaskExecutionDispatcher;
import com.travelagent.agent.requirements.TravelConstraints;
import com.travelagent.agent.statemachine.AgentEvent;
import com.travelagent.agent.statemachine.AgentStateMachine;
import com.travelagent.exception.BusinessException;
import com.travelagent.exception.TaskNotFoundException;
import com.travelagent.mapper.TaskMapper;
import com.travelagent.model.dto.ConfirmOriginSelectionRequest;
import com.travelagent.model.dto.CreateTaskRequest;
import com.travelagent.model.dto.LocationCandidateItem;
import com.travelagent.model.dto.NodeChatRequest;
import com.travelagent.model.dto.RewindTaskRequest;
import com.travelagent.model.dto.TaskResponse;
import com.travelagent.model.entity.Task;
import com.travelagent.model.entity.UserQuotaConfig;
import com.travelagent.model.enums.TaskStatus;
import com.travelagent.service.agent.AgentService;
import com.travelagent.service.notification.SseEvent;
import com.travelagent.service.notification.SseNotificationService;
import com.travelagent.service.task.TaskProgressService;
import com.travelagent.service.task.TaskService;
import com.travelagent.service.task.TaskLifecycleGovernanceService;
import com.travelagent.service.user.QuotaService;
import com.travelagent.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TaskServiceImpl implements TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskServiceImpl.class);
    private static final String PAUSE_REASON_RESUME_DISPATCH_FAILED = "resume_dispatch_failed";
    private static final String PAUSE_REASON_USER_CANCELLED = "user_cancelled";
    private static final String PAUSE_REASON_PENDING_TOOL_REPLAY_CONFIRMATION = "pending_tool_replay_requires_confirmation";
    private static final String PAUSE_REASON_PENDING_TOOL_SKIPPED = "pending_tool_skipped";

    @Autowired private TaskMapper taskMapper;
    @Autowired private QuotaService quotaService;
    @Autowired private AgentStateMachine stateMachine;
    @Autowired private SseNotificationService sseNotificationService;
    @Autowired private JsonUtil jsonUtil;
    @Autowired private AgentService agentService;
    @Autowired private TaskProgressService taskProgressService;
    @Autowired private TaskRewindHandler rewindHandler;
    @Autowired private TaskExecutionDispatcher taskExecutionDispatcher;
    @Autowired private TaskCheckpointCodec checkpointCodec;
    @Autowired private TaskInitialCheckpointBuilder initialCheckpointBuilder;
    @Autowired private TaskSelectionConfirmationHandler selectionConfirmationHandler;
    @Autowired(required = false) private TaskLifecycleGovernanceService lifecycleGovernanceService;
    @Autowired(required = false) private UserPreferenceMemoryService userPreferenceMemoryService;

    /**
     * 创建旅行规划任务，完成配额校验、任务落库和初始 checkpoint 持久化。
     *
     * @param userId 用户 ID
     * @param userLevel 用户等级
     * @param request 创建任务请求
     * @return 新任务响应
     */
    @Override
    @Transactional
    public TaskResponse createTask(Long userId, int userLevel, CreateTaskRequest request, String requestIp) {
        quotaService.checkDailyQuota(userId, userLevel);
        initialCheckpointBuilder.validateCreateRequest(request);

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
        task.setRequestIp(requestIp);
        task.setSchemaVersion(TaskCheckpoint.CURRENT_SCHEMA_VERSION);
        task.setTotalTokensUsed(0);
        taskMapper.insert(task);

        TaskCheckpoint checkpoint = initialCheckpointBuilder.build(task, request, config.getMaxPlanSteps());
        rememberUserInput(task.getUserId(), checkpoint.getStructuredConstraints(), request.getUserIntent(), "task_create");
        task.setCheckpointJson(jsonUtil.toJson(checkpoint));
        taskMapper.updateCheckpoint(task);

        log.info("Created task uuid={} for userId={}", task.getTaskUuid(), userId);
        return toResponse(task, checkpoint);
    }

    /**
     * 查询当前用户可访问的单个任务。
     *
     * @param taskUuid 任务唯一标识
     * @param requestingUserId 发起请求的用户 ID
     * @return 任务响应
     */
    @Override
    public TaskResponse getTask(String taskUuid, Long requestingUserId) {
        Task task = loadAndVerifyOwnership(taskUuid, requestingUserId);
        return toResponse(task);
    }

    /**
     * 查询用户的任务列表。
     *
     * @param userId 用户 ID
     * @return 任务响应列表
     */
    @Override
    public List<TaskResponse> listTasks(Long userId) {
        return taskMapper.findByUserId(userId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * 取消未终态任务，并同步 checkpoint、进度事件和 SSE。
     *
     * @param taskUuid 任务唯一标识
     * @param requestingUserId 发起请求的用户 ID
     */
    @Override
    @Transactional
    public void cancelTask(String taskUuid, Long requestingUserId) {
        Task task = loadAndVerifyOwnership(taskUuid, requestingUserId);
        TaskStatus current = TaskStatus.fromCode(task.getStatus());
        if (current.isTerminal()) {
            throw new BusinessException(400, "task is already terminal");
        }

        TaskStatus next = stateMachine.transition(current, AgentEvent.CANCEL);
        TaskCheckpoint checkpoint = parseCheckpoint(task);
        if (checkpoint != null) {
            checkpoint.setCurrentState(next.getCode());
            checkpoint.setPauseReason(PAUSE_REASON_USER_CANCELLED);
            checkpoint.setPendingToolCall(null);
            task.setStatus(next.getCode());
            task.setCheckpointJson(jsonUtil.toJson(checkpoint));
            taskMapper.updateCheckpoint(task);
        }
        transitionStatus(task, next.getCode());

        Map<String, Object> payload = Map.of(
                "status", next.getCode(),
                "taskUuid", taskUuid,
                "reason", PAUSE_REASON_USER_CANCELLED
        );
        taskProgressService.recordEvent(taskUuid, "CANCELLED", next.getCode(),
                checkpoint != null ? checkpoint.getCurrentStepIndex() : null,
                checkpoint != null ? checkpoint.totalPlannedSteps() : null,
                "Task cancelled by user", payload);
        sseNotificationService.sendEvent(taskUuid, SseEvent.STATE_CHANGE,
                payload);
        sseNotificationService.completeEmitter(taskUuid);
    }

    /**
     * 恢复暂停任务，并在事务提交后重新派发到 Agent 执行器。
     *
     * @param taskUuid 任务唯一标识
     * @param requestingUserId 发起请求的用户 ID
     * @return 恢复后的任务响应
     */
    @Override
    @Transactional
    public TaskResponse resumeTask(String taskUuid, Long requestingUserId) {
        Task task = loadAndVerifyOwnership(taskUuid, requestingUserId);
        TaskStatus current = TaskStatus.fromCode(task.getStatus());
        if (!current.isResumable()) {
            throw new BusinessException(400, "only paused tasks can be resumed");
        }

        TaskCheckpoint checkpoint = parseCheckpoint(task);
        if (checkpoint != null) {
            checkpoint.setPauseReason(null);
            checkpoint.setResumableAt(null);
            checkpoint.setCurrentState(TaskStatus.RESUMING.getCode());
            task.setCheckpointJson(jsonUtil.toJson(checkpoint));
            taskMapper.updateCheckpoint(task);
        }

        TaskStatus next = stateMachine.transition(current, AgentEvent.RESUME);
        task.setStatus(next.getCode());
        transitionStatus(task, next.getCode());
        scheduleResumeDispatch(taskUuid, "manual_resume");
        Task updated = taskMapper.findByUuid(taskUuid);
        return toResponse(updated);
    }

    @Override
    @Transactional
    public TaskResponse confirmPendingToolReplay(String taskUuid, Long requestingUserId) {
        return decidePendingToolReplay(taskUuid, requestingUserId, true);
    }

    @Override
    @Transactional
    public TaskResponse skipPendingToolReplay(String taskUuid, Long requestingUserId) {
        return decidePendingToolReplay(taskUuid, requestingUserId, false);
    }

    /**
     * 确认当前待处理的起点、分支或景点候选。
     *
     * @param taskUuid 任务唯一标识
     * @param requestingUserId 发起请求的用户 ID
     * @param request 选择确认请求
     * @return 更新后的任务响应
     */
    @Override
    @Transactional
    public TaskResponse confirmOriginSelection(String taskUuid, Long requestingUserId, ConfirmOriginSelectionRequest request) {
        Task task = loadAndVerifyOwnership(taskUuid, requestingUserId);
        TaskStatus current = TaskStatus.fromCode(task.getStatus());
        if (!current.isAwaitingUserInput()) {
            throw new BusinessException(400, "task is not waiting for selection");
        }

        TaskCheckpoint checkpoint = parseCheckpoint(task);
        if (checkpoint == null) {
            throw new BusinessException(400, "task checkpoint is missing");
        }
        TaskSelectionConfirmationHandler.SelectionConfirmationResult selectionResult =
                selectionConfirmationHandler.applySelection(checkpoint, request);
        String pendingInputType = selectionResult.pendingInputType();
        selectionConfirmationHandler.clearSelectionState(checkpoint, pendingInputType, TaskStatus.RESUMING.getCode());
        task.setStatus(TaskStatus.RESUMING.getCode());
        task.setCheckpointJson(jsonUtil.toJson(checkpoint));

        TaskStatus next = stateMachine.transition(current, AgentEvent.USER_INPUT_RECEIVED);
        transitionStatus(task, next.getCode());
        taskMapper.updateCheckpoint(task);

        Map<String, Object> payload = selectionConfirmationHandler.buildSelectionConfirmedPayload(
                taskUuid, checkpoint, selectionResult, request);
        sseNotificationService.sendEvent(taskUuid, SseEvent.USER_SELECTION_CONFIRMED, payload);
        sseNotificationService.sendEvent(taskUuid, SseEvent.STATE_CHANGE, Map.of(
                "status", next.getCode(),
                "taskUuid", taskUuid,
                "pendingInputType", pendingInputType != null ? pendingInputType : ""
        ));
        scheduleResumeDispatch(taskUuid, "user_selection_confirmed");

        Task updated = taskMapper.findByUuid(taskUuid);
        return toResponse(updated, checkpoint);
    }

    /**
     * 将任务回退到指定已完成步骤，并重新派发后续规划。
     *
     * @param taskUuid 任务唯一标识
     * @param requestingUserId 发起请求的用户 ID
     * @param request 回退请求
     * @return 回退后的任务响应
     */
    @Override
    @Transactional
    public TaskResponse rewindTask(String taskUuid, Long requestingUserId, RewindTaskRequest request) {
        Task task = loadAndVerifyOwnership(taskUuid, requestingUserId);
        TaskStatus current = TaskStatus.fromCode(task.getStatus());
        if (current != TaskStatus.PAUSED && current != TaskStatus.AWAITING_USER_INPUT) {
            throw new BusinessException(400, "only paused or awaiting_user_input tasks can be rewound");
        }

        TaskCheckpoint checkpoint = parseCheckpoint(task);
        if (checkpoint == null || checkpoint.getCompletedSteps() == null || checkpoint.getCompletedSteps().isEmpty()) {
            throw new BusinessException(400, "no completed steps available for rewind");
        }

        int targetStepIndex = request.getTargetStepIndex();
        if (targetStepIndex < 0 || targetStepIndex >= checkpoint.getCompletedSteps().size()) {
            throw new BusinessException(400, "target step index is out of range");
        }

        List<CompletedStep> retainedSteps = rewindHandler.applyRewind(checkpoint, targetStepIndex);

        task.setStatus(TaskStatus.RESUMING.getCode());
        task.setCheckpointJson(jsonUtil.toJson(checkpoint));
        taskMapper.updateCheckpoint(task);
        transitionStatus(task, TaskStatus.RESUMING.getCode());

        String targetName = retainedSteps.get(retainedSteps.size() - 1).getAttractionName();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskUuid", taskUuid);
        payload.put("targetStepIndex", targetStepIndex);
        payload.put("targetStepName", targetName);
        payload.put("remainingTimeBudgetMin", checkpoint.getRemainingTimeBudgetMin());
        payload.put("status", TaskStatus.RESUMING.getCode());

        taskProgressService.recordEvent(taskUuid, "REWIND", TaskStatus.RESUMING.getCode(),
                targetStepIndex, checkpoint.totalPlannedSteps(),
                "Rewound task to step " + targetStepIndex, payload);
        sseNotificationService.sendEvent(taskUuid, SseEvent.REWIND, payload);
        sseNotificationService.sendEvent(taskUuid, SseEvent.STATE_CHANGE, Map.of(
                "status", TaskStatus.RESUMING.getCode(),
                "taskUuid", taskUuid,
                "totalTokensUsed", task.getTotalTokensUsed() == null ? 0 : task.getTotalTokensUsed(),
                "remainingTimeBudgetMin", checkpoint.getRemainingTimeBudgetMin()
        ));
        scheduleResumeDispatch(taskUuid, "rewind");

        return toResponse(task, checkpoint);
    }

    /**
     * 在等待用户选择时，根据新的用户偏好刷新当前候选。
     *
     * @param taskUuid 任务唯一标识
     * @param requestingUserId 发起请求的用户 ID
     * @param request 节点偏好输入
     * @return 刷新后的任务响应
     */
    @Override
    @Transactional
    public TaskResponse refreshNodeSelection(String taskUuid, Long requestingUserId, NodeChatRequest request) {
        Task task = loadAndVerifyOwnership(taskUuid, requestingUserId);
        TaskStatus current = TaskStatus.fromCode(task.getStatus());
        if (current != TaskStatus.AWAITING_USER_INPUT) {
            throw new BusinessException(400, "task is not waiting for node input");
        }

        TaskCheckpoint checkpoint = parseCheckpoint(task);
        if (checkpoint == null) {
            throw new BusinessException(400, "task checkpoint is missing");
        }

        String pendingInputType = resolvePendingInputType(checkpoint, request.getPendingInputType());
        if ("origin_selection".equals(pendingInputType)) {
            throw new BusinessException(400, "origin selection does not support node preference refresh");
        }

        Map<String, Object> currentContext = checkpoint.getCurrentContext() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(checkpoint.getCurrentContext());
        currentContext.put("userPreferencePrompt", request.getMessage().trim());
        checkpoint.setCurrentContext(currentContext);
        checkpoint.recordUserFeedback("node_chat", request.getMessage().trim(), Map.of(
                "pendingInputType", pendingInputType
        ));
        rememberUserInput(task.getUserId(), checkpoint.getStructuredConstraints(), request.getMessage().trim(), "node_chat");
        checkpoint.setSelectedAttractionCandidate(null);
        checkpoint.setPendingInputType(null);
        checkpoint.setSelectionStage(null);
        checkpoint.setSelectionOptions(new ArrayList<>());
        checkpoint.setRecommendationCandidates(new ArrayList<>());
        checkpoint.setWeatherContext(new LinkedHashMap<>());

        if ("selection_branch".equals(pendingInputType)) {
            checkpoint.setSelectedBranchType(null);
        } else if ("route_candidate_selection".equals(pendingInputType)) {
            checkpoint.setSelectedBranchType("rag_route");
        } else if ("attraction_selection".equals(pendingInputType)) {
            if (checkpoint.getSelectedBranchType() == null || checkpoint.getSelectedBranchType().isBlank()) {
                checkpoint.setSelectedBranchType("rag_route");
            }
        } else {
            throw new BusinessException(400, "unsupported pending input type for node preference: " + pendingInputType);
        }

        PlanningResult planResult = agentService.refreshNodeCandidates(task, checkpoint, taskUuid);
        if (!planResult.requiresUserSelection()) {
            throw new BusinessException(400, "node preference refresh did not produce selectable candidates");
        }

        checkpoint.setPendingInputType(planResult.pendingInputType());
        checkpoint.setSelectionStage(planResult.selectionStage());
        checkpoint.setSelectedBranchType(planResult.selectedBranchType());
        checkpoint.setSelectionOptions(planResult.selectionOptions() == null
                ? new ArrayList<>()
                : new ArrayList<>(planResult.selectionOptions()));
        checkpoint.setRecommendationCandidates(planResult.recommendationCandidates() == null
                ? new ArrayList<>()
                : new ArrayList<>(planResult.recommendationCandidates()));
        Map<String, Object> refreshedContext = planResult.currentContext() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(planResult.currentContext());
        refreshedContext.put("userPreferencePrompt", request.getMessage().trim());
        checkpoint.setCurrentContext(refreshedContext);
        checkpoint.setWeatherContext(planResult.weatherContext() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(planResult.weatherContext()));
        checkpoint.setCurrentState(TaskStatus.AWAITING_USER_INPUT.getCode());

        task.setStatus(TaskStatus.AWAITING_USER_INPUT.getCode());
        task.setCheckpointJson(jsonUtil.toJson(checkpoint));
        taskMapper.updateCheckpoint(task);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskUuid", taskUuid);
        payload.put("pendingInputType", checkpoint.getPendingInputType());
        payload.put("selectionStage", checkpoint.getSelectionStage());
        payload.put("selectedBranchType", checkpoint.getSelectedBranchType());
        payload.put("selectionOptions", checkpoint.getSelectionOptions());
        payload.put("recommendationCandidates", checkpoint.getRecommendationCandidates());
        payload.put("currentContext", checkpoint.getCurrentContext());
        payload.put("weatherContext", checkpoint.getWeatherContext());
        payload.put("totalTokensUsed", task.getTotalTokensUsed() == null ? 0 : task.getTotalTokensUsed());

        taskProgressService.recordEvent(taskUuid, "NODE_CHAT", TaskStatus.AWAITING_USER_INPUT.getCode(),
                checkpoint.getCurrentStepIndex(), checkpoint.totalPlannedSteps(),
                "Refreshed current node candidates from preference prompt", Map.of(
                        "pendingInputType", pendingInputType,
                        "message", request.getMessage().trim()
                ));
        taskProgressService.recordEvent(taskUuid, "USER_SELECTION_REQUIRED", TaskStatus.AWAITING_USER_INPUT.getCode(),
                checkpoint.getCurrentStepIndex(), checkpoint.totalPlannedSteps(),
                "Waiting for refreshed user selection", payload);
        sseNotificationService.sendEvent(taskUuid, SseEvent.USER_SELECTION_REQUIRED, payload);

        return toResponse(task, checkpoint);
    }

    /**
     * 读取任务实体并校验所有权，供内部协作服务复用。
     *
     * @param taskUuid 任务唯一标识
     * @param requestingUserId 发起请求的用户 ID
     * @return 任务实体
     */
    @Override
    public Task getTaskEntity(String taskUuid, Long requestingUserId) {
        return loadAndVerifyOwnership(taskUuid, requestingUserId);
    }

    private void rememberUserInput(Long userId, TravelConstraints constraints, String message, String source) {
        if (userPreferenceMemoryService == null || userId == null || message == null || message.isBlank()) {
            return;
        }
        try {
            userPreferenceMemoryService.upsertFromConversation(userId, constraints, message, source);
        } catch (Exception e) {
            log.warn("Failed to update user memory userId={} source={}: {}", userId, source, e.getMessage());
        }
    }

    /**
     * 加载任务并校验当前用户是否有权访问。
     *
     * @param taskUuid 任务唯一标识
     * @param requestingUserId 发起请求的用户 ID
     * @return 任务实体
     */
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

    /**
     * 解析任务 checkpoint。
     *
     * @param task 任务实体
     * @return checkpoint，缺失或解析失败时返回 null
     */
    private TaskCheckpoint parseCheckpoint(Task task) {
        if (checkpointCodec == null) {
            checkpointCodec = new TaskCheckpointCodec(jsonUtil);
        }
        return checkpointCodec.parse(task);
    }

    /**
     * 组装任务响应。
     *
     * @param task 任务实体
     * @return 任务响应
     */
    private TaskResponse toResponse(Task task) {
        if (checkpointCodec == null) {
            checkpointCodec = new TaskCheckpointCodec(jsonUtil);
        }
        return checkpointCodec.toResponse(task);
    }

    /**
     * 使用已解析 checkpoint 组装任务响应。
     *
     * @param task 任务实体
     * @param checkpoint 已解析 checkpoint
     * @return 任务响应
     */
    private TaskResponse toResponse(Task task, TaskCheckpoint checkpoint) {
        if (checkpointCodec == null) {
            checkpointCodec = new TaskCheckpointCodec(jsonUtil);
        }
        return checkpointCodec.toResponse(task, checkpoint);
    }

    private TaskResponse decidePendingToolReplay(String taskUuid, Long requestingUserId, boolean confirmReplay) {
        Task task = loadAndVerifyOwnership(taskUuid, requestingUserId);
        TaskStatus current = TaskStatus.fromCode(task.getStatus());
        if (current != TaskStatus.PAUSED && current != TaskStatus.AWAITING_USER_INPUT) {
            throw new BusinessException(400, "only paused or awaiting_user_input tasks can handle pending tool replay");
        }

        TaskCheckpoint checkpoint = parseCheckpoint(task);
        if (checkpoint == null) {
            throw new BusinessException(400, "task checkpoint is missing");
        }
        PendingToolCall pending = checkpoint.getPendingToolCall();
        if (pending == null) {
            throw new BusinessException(400, "no pending tool call to handle");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskUuid", taskUuid);
        payload.put("toolName", pending.getToolName());
        payload.put("idempotencyKey", pending.getIdempotencyKey());
        payload.put("decision", confirmReplay ? "confirm" : "skip");

        if (confirmReplay) {
            pending.setManualReplayApproved(true);
            checkpoint.setPauseReason(null);
        } else {
            checkpoint.setPendingToolCall(null);
            checkpoint.setPauseReason(PAUSE_REASON_PENDING_TOOL_SKIPPED);
        }
        checkpoint.setResumableAt(null);
        checkpoint.setCurrentState(TaskStatus.RESUMING.getCode());

        task.setStatus(TaskStatus.RESUMING.getCode());
        task.setCheckpointJson(jsonUtil.toJson(checkpoint));
        taskMapper.updateCheckpoint(task);
        transitionStatus(task, TaskStatus.RESUMING.getCode());

        String eventType = confirmReplay ? "PENDING_TOOL_REPLAY_CONFIRMED" : "PENDING_TOOL_SKIPPED";
        String message = confirmReplay
                ? "Pending tool replay confirmed: " + pending.getToolName()
                : "Pending tool skipped: " + pending.getToolName();
        taskProgressService.recordEvent(taskUuid, eventType, TaskStatus.RESUMING.getCode(),
                checkpoint.getCurrentStepIndex(), checkpoint.totalPlannedSteps(), message, payload);
        sseNotificationService.sendEvent(taskUuid, SseEvent.STATE_CHANGE, Map.of(
                "status", TaskStatus.RESUMING.getCode(),
                "taskUuid", taskUuid,
                "pendingToolName", pending.getToolName(),
                "pendingToolDecision", confirmReplay ? "confirm" : "skip"
        ));
        scheduleResumeDispatch(taskUuid, confirmReplay ? "pending_tool_confirmed" : "pending_tool_skipped");

        return toResponse(task, checkpoint);
    }

    /**
     * 校验请求中的输入类型与 checkpoint 中的待处理类型是否一致。
     *
     * @param checkpoint 任务检查点数据
     * @param requestType 请求声明的输入类型
     * @return 最终待处理输入类型
     */
    private String resolvePendingInputType(TaskCheckpoint checkpoint, String requestType) {
        String checkpointType = checkpoint.getPendingInputType();
        if (requestType == null || requestType.isBlank()) {
            return checkpointType;
        }
        if (checkpointType != null && !checkpointType.isBlank() && !checkpointType.equals(requestType)) {
            throw new BusinessException(400, "pending input type mismatch");
        }
        return requestType;
    }

    /**
     * 安排恢复派发，事务内调用时延后到提交后执行。
     *
     * @param taskUuid 任务唯一标识
     * @param trigger 恢复触发来源
     */
    private void scheduleResumeDispatch(String taskUuid, String trigger) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                /**
                 * 事务提交后再派发，避免执行线程读取到未提交的 checkpoint。
                 */
                @Override
                public void afterCommit() {
                    dispatchResumeAfterCommit(taskUuid, trigger);
                }
            });
            return;
        }
        dispatchResumeAfterCommit(taskUuid, trigger);
    }

    /**
     * 在事务提交后派发恢复任务。
     *
     * @param taskUuid 任务唯一标识
     * @param trigger 恢复触发来源
     */
    private void dispatchResumeAfterCommit(String taskUuid, String trigger) {
        try {
            taskExecutionDispatcher.dispatchTask(taskUuid, "resume:" + trigger);
        } catch (Exception e) {
            log.error("Failed to dispatch resumed task uuid={} trigger={}: {}", taskUuid, trigger, e.getMessage(), e);
            markResumeDispatchFailed(taskUuid, e, trigger);
        }
    }

    /**
     * 恢复派发失败时把任务回落到可重试暂停状态。
     *
     * @param taskUuid 任务唯一标识
     * @param exception 派发异常
     * @param trigger 恢复触发来源
     */
    private void markResumeDispatchFailed(String taskUuid, Exception exception, String trigger) {
        Task freshTask = taskMapper.findByUuid(taskUuid);
        if (freshTask == null) {
            return;
        }

        if (TaskStatus.fromCode(freshTask.getStatus()) != TaskStatus.RESUMING) {
            return;
        }

        TaskCheckpoint checkpoint = parseCheckpoint(freshTask);
        if (checkpoint != null) {
            checkpoint.setCurrentState(TaskStatus.PAUSED.getCode());
            checkpoint.setPauseReason(PAUSE_REASON_RESUME_DISPATCH_FAILED);
            freshTask.setCheckpointJson(jsonUtil.toJson(checkpoint));
        }

        String message = "Task resume dispatch failed: " + exception.getMessage();
        freshTask.setStatus(TaskStatus.PAUSED.getCode());
        freshTask.setErrorMessage(message);
        taskMapper.update(freshTask);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", "RESUME_DISPATCH_FAILED");
        payload.put("message", message);
        payload.put("retryable", true);
        payload.put("trigger", trigger);
        payload.put("reason", PAUSE_REASON_RESUME_DISPATCH_FAILED);

        taskProgressService.recordEvent(taskUuid, "ERROR", TaskStatus.PAUSED.getCode(), null, null, message, payload);
        sseNotificationService.sendEvent(taskUuid, SseEvent.ERROR, payload);
        sseNotificationService.sendEvent(taskUuid, SseEvent.PAUSED, payload);
    }

    private void transitionStatus(Task task, String nextStatus) {
        if (lifecycleGovernanceService != null) {
            lifecycleGovernanceService.transitionStatus(task, nextStatus);
            return;
        }
        int updated = taskMapper.updateStatus(task, nextStatus);
        if (updated != 1) {
            throw new IllegalStateException("LEASE_LOST");
        }
    }

}
