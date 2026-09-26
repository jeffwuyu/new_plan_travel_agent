package com.travelagent.service.agent.impl;

import com.travelagent.agent.context.CompletedStep;
import com.travelagent.agent.context.RetryState;
import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.agent.planner.MarkovPlanner;
import com.travelagent.agent.planner.PlanNextAttractionRequest;
import com.travelagent.agent.planner.PlanningResult;
import com.travelagent.agent.statemachine.AgentEvent;
import com.travelagent.agent.statemachine.AgentStateMachine;
import com.travelagent.config.DatabaseSchemaGuard;
import com.travelagent.exception.AgentErrorCode;
import com.travelagent.exception.AgentException;
import com.travelagent.exception.CheckpointCorruptedException;
import com.travelagent.exception.QuotaExhaustedException;
import com.travelagent.exception.RateLimitExceededException;
import com.travelagent.mapper.TaskMapper;
import com.travelagent.model.dto.LocationCandidateItem;
import com.travelagent.model.entity.Task;
import com.travelagent.model.enums.TaskStatus;
import com.travelagent.monitoring.TaskMetricsService;
import com.travelagent.service.agent.AgentService;
import com.travelagent.service.llm.LlmUsageAccountingService;
import com.travelagent.service.notification.SseEvent;
import com.travelagent.service.notification.SseNotificationService;
import com.travelagent.service.task.TaskProgressService;
import com.travelagent.service.task.RedisTaskLockService;
import com.travelagent.service.task.TaskLifecycleGovernanceService;
import com.travelagent.service.user.QuotaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 主控服务：状态机转换、规划循环、Quota 处理、SSE 通知、计划持久化。
 * 工具执行委托给 {@link AgentToolExecutor}，Checkpoint 管理委托给 {@link AgentCheckpointHelper}。
 */
@Service
public class AgentServiceImpl implements AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentServiceImpl.class);
    private static final String PAUSE_REASON_DAILY_QUOTA = "daily_quota_exhausted";
    private static final String PAUSE_REASON_AMAP_RATE_LIMITED = "amap_rate_limited";
    private static final String PAUSE_REASON_USER_CANCELLED = "user_cancelled";
    private static final int[] AMAP_RATE_LIMIT_BACKOFF_SECONDS = {2, 5, 10};

    private static final String EVT_STATE_CHANGE = "STATE_CHANGE";
    private static final String EVT_STEP_DONE = "STEP_DONE";
    private static final String EVT_ERROR = "ERROR";
    private static final String EVT_RETRY = "RETRY";
    private static final String EVT_PAUSED = "PAUSED";
    private static final String EVT_COMPLETED = "COMPLETED";
    private static final String EVT_CANCELLED = "CANCELLED";
    private static final String EVT_USER_SELECTION_CONFIRMED = "USER_SELECTION_CONFIRMED";

    @Autowired private TaskMapper taskMapper;
    @Autowired private AgentStateMachine stateMachine;
    @Autowired private SseNotificationService sseNotificationService;
    @Autowired private MarkovPlanner markovPlanner;
    @Autowired private QuotaService quotaService;
    @Autowired private TaskProgressService taskProgressService;
    @Autowired(required = false) private RedisTaskLockService redisTaskLockService;
    @Autowired(required = false) private TaskLifecycleGovernanceService lifecycleGovernanceService;
    @Autowired private TaskMetricsService taskMetricsService;
    @Autowired private DatabaseSchemaGuard schemaGuard;
    @Autowired private LlmUsageAccountingService llmUsageAccountingService;
    @Autowired private AgentCheckpointHelper checkpointHelper;
    @Autowired private AgentToolExecutor toolExecutor;
    @Autowired private AgentSelectionCoordinator selectionCoordinator;
    @Autowired private AgentPlanFinalizationService planFinalizationService;
    @Autowired private AgentToolStepService toolStepService;
    @Value("${agent.task.max-recovery-attempts:3}")
    private int maxRecoveryAttempts;
    @Value("${agent.task.recovery-scan-batch-size:100}")
    private int recoveryScanBatchSize;
    private final Map<String, String> executionLeaseTokens = new ConcurrentHashMap<>();

    @Override
    public void executeTask(String taskUuid, String leaseToken) {
        if (leaseToken != null && !leaseToken.isBlank()) {
            executionLeaseTokens.put(taskUuid, leaseToken);
        }
        executeTask(taskUuid);
    }

    @Override
    public PlanningResult refreshNodeCandidates(Task task, TaskCheckpoint checkpoint, String taskUuid) {
        PlanNextAttractionRequest req = markovPlanner.buildPlanRequest(checkpoint);
        PlanningResult result = markovPlanner.planNextAttraction(task, checkpoint, req, taskUuid);
        if (result.totalTokens() > 0) {
            try {
                int updated = llmUsageAccountingService.recordUsage(task.getId(), task.getUserId(), result.totalTokens());
                task.setTotalTokensUsed(updated);
            } catch (Exception e) {
                log.warn("[AgentService] Failed to record token usage for node-chat task={}: {}", taskUuid, e.getMessage());
            }
        }
        return result;
    }

    /**
     * 应用启动后执行一次卡住任务恢复扫描。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverStuckTasksOnStartup() {
        if (!schemaGuard.isCoreSchemaReady()) {
            return;
        }
        try {
            recoverStuckTasks();
        } catch (Exception e) {
            log.warn("[AgentService] Startup recovery skipped: {}", e.getMessage());
        }
    }

    /**
     * 扫描规划中或工具调用中的陈旧任务，并把可恢复任务转为 resuming。
     */
    public void recoverStuckTasks() {
        boolean durableRecoveryScan = lifecycleGovernanceService != null;
        if (durableRecoveryScan) {
            // Start each pass at the beginning. A task that was healthy during a
            // previous pass may become expired later; retaining an in-memory ID
            // cursor would permanently skip that task until process restart.
            lifecycleGovernanceService.scanExpiredExecutionLeases("recovery", 0L, recoveryScanBatchSize);
        }
        List<String> stuckStatuses = durableRecoveryScan
                ? List.of(TaskStatus.RESUMING.getCode())
                : List.of(TaskStatus.PLANNING.getCode(), TaskStatus.TOOL_CALLING.getCode());
        List<Task> stuckTasks = taskMapper.findByStatusIn(stuckStatuses,
                durableRecoveryScan ? Math.max(1, recoveryScanBatchSize) : 100);
        for (Task task : stuckTasks) {
            try {
                if (redisTaskLockService != null && redisTaskLockService.hasValidLease(task.getTaskUuid())) {
                    continue;
                }
                int attempts = durableRecoveryScan
                        ? (task.getRecoveryAttempts() == null ? 0 : task.getRecoveryAttempts())
                        : incrementRecoveryAttempts(task.getTaskUuid());
                if (attempts > maxRecoveryAttempts) {
                    task.setStatus(TaskStatus.FAILED.getCode());
                    task.setErrorMessage("Task exceeded max recovery attempts: " + maxRecoveryAttempts);
                    taskMapper.update(task);
                    taskProgressService.recordEvent(task.getTaskUuid(), "RECOVERY_EXHAUSTED",
                            TaskStatus.FAILED.getCode(), null, null,
                            task.getErrorMessage(),
                            Map.of("attempts", attempts, "maxRecoveryAttempts", maxRecoveryAttempts));
                    continue;
                }
                taskProgressService.recordEvent(task.getTaskUuid(), "RECOVERY_CLAIMED",
                        TaskStatus.RESUMING.getCode(), null, null,
                        "Stale task claimed for recovery.",
                        Map.of("attempts", attempts, "maxRecoveryAttempts", maxRecoveryAttempts));
                taskMapper.updateStatus(task.getId(), TaskStatus.RESUMING.getCode());
            } catch (Exception e) {
                log.error("[AgentService] Failed to recover task uuid={}: {}", task.getTaskUuid(), e.getMessage());
            }
        }
    }

    private int incrementRecoveryAttempts(String taskUuid) {
        try {
            if (redisTaskLockService == null) {
                return 1;
            }
            return redisTaskLockService.incrementRecoveryAttempts(taskUuid);
        } catch (Exception e) {
            log.warn("[AgentService] Recovery counter unavailable for task={}: {}", taskUuid, e.getMessage());
            return 1;
        }
    }

    /**
     * 执行或恢复指定任务的 Agent 规划流程。
     *
     * @param taskUuid 任务唯一标识
     */
    @Override
    public void executeTask(String taskUuid) {
        Task task = taskMapper.findByUuid(taskUuid);
        if (task == null) {
            return;
        }

        TaskStatus current = TaskStatus.fromCode(task.getStatus());
        if (current != TaskStatus.PENDING && current != TaskStatus.RESUMING) {
            return;
        }

        String leaseToken = executionLeaseTokens.remove(taskUuid);
        if (leaseToken != null && !leaseToken.isBlank()) {
            task.setLeaseToken(leaseToken);
        }

        MDC.put("taskUuid", taskUuid);
        try {
            renewTaskLease(task);
            taskMetricsService.recordTaskStarted();
            runPlanningLoop(task, taskUuid, current);
        } catch (Exception e) {
            Map<String, Object> payload = buildErrorPayload(e, false);
            markFailed(task, taskUuid, e.getMessage(), payload);
        } finally {
            MDC.remove("taskUuid");
        }
    }

    /**
     * 推进任务规划主循环，负责状态切换、候选选择、工具执行和最终落库编排。
     *
     * @param task 任务实体
     * @param taskUuid 任务唯一标识
     * @param current 当前状态
     */
    private void runPlanningLoop(Task task, String taskUuid, TaskStatus current) {
        TaskCheckpoint checkpoint = checkpointHelper.loadCheckpoint(task);
        int userLevel = checkpointHelper.resolveUserLevel(task.getUserId());

        TaskStatus planning = stateMachine.transition(current, AgentEvent.START_PLANNING);
        task.setStatus(planning.getCode());
        checkpoint.setCurrentState(planning.getCode());
        taskMapper.updateStatus(task.getId(), planning.getCode());
        sendStateChange(taskUuid, checkpoint, planning.getCode(), task);
        taskProgressService.recordEvent(taskUuid, EVT_STATE_CHANGE, planning.getCode(),
                checkpoint.getCurrentStepIndex(), checkpoint.totalPlannedSteps(),
                "Task execution started", null);

        if (!checkpoint.isOriginConfirmed()) {
            if (!selectionCoordinator.hasOriginCandidates(checkpoint)) {
                markFailed(task, taskUuid, "No origin candidates were generated",
                        Map.of("code", "NO_ORIGIN_CANDIDATES",
                                "message", "No origin candidates were generated",
                                "retryable", false));
                return;
            }
            selectionCoordinator.awaitOriginSelection(task, checkpoint, taskUuid);
            return;
        }

        if (current == TaskStatus.RESUMING
                && checkpoint.getSelectedOrigin() != null
                && checkpoint.getSelectedAttractionCandidate() == null) {
            taskProgressService.recordEvent(taskUuid, EVT_USER_SELECTION_CONFIRMED, TaskStatus.RESUMING.getCode(),
                    checkpoint.getCurrentStepIndex(), checkpoint.totalPlannedSteps(),
                    "Origin selected: " + checkpoint.getSelectedOrigin().getName(),
                    Map.of("selectedOrigin", checkpoint.getSelectedOrigin()));
        }

        if (checkpoint.getPendingToolCall() != null) {
            PendingToolReplayResult replayResult = toolExecutor.replayPendingToolCall(task, checkpoint, taskUuid);
            if (replayResult == PendingToolReplayResult.PAUSED_FOR_CONFIRMATION) {
                taskMapper.updateStatus(task.getId(), TaskStatus.PAUSED.getCode());
                taskMetricsService.recordTaskPaused();
                return;
            }
        }

        checkpointHelper.refreshRemainingBudget(checkpoint);

        while (!checkpoint.isAllStepsDone() && checkpointHelper.canPlanAnotherStep(checkpoint)) {
            if (stopIfExecutionNoLongerActive(task, taskUuid, checkpoint)) {
                return;
            }
            renewTaskLease(task);
            int stepIndex = checkpoint.getCurrentStepIndex();
            int dayNumber = checkpointHelper.resolveDayNumberForOffset(checkpoint, checkpoint.getUsedTimeBudgetMin());

            taskProgressService.recordEvent(taskUuid, EVT_STATE_CHANGE, planning.getCode(),
                    stepIndex, checkpoint.totalPlannedSteps(),
                    "Starting step " + stepIndex, Map.of(
                            "remainingTimeBudgetMin", checkpoint.getRemainingTimeBudgetMin(),
                            "dayNumber", dayNumber
                    ));

            try {
                quotaService.checkDailyQuota(task.getUserId(), userLevel);
            } catch (QuotaExhaustedException e) {
                handleQuotaExhaustion(task, checkpoint, taskUuid);
                return;
            }

            String attractionName;
            LocationCandidateItem selectedCandidate = checkpoint.getSelectedAttractionCandidate();
            if (selectedCandidate != null) {
                attractionName = selectedCandidate.getTargetAttractionName() != null
                        && !selectedCandidate.getTargetAttractionName().isBlank()
                        ? selectedCandidate.getTargetAttractionName()
                        : selectedCandidate.getName();
            } else {
                try {
                    PlanNextAttractionRequest planningRequest = markovPlanner.buildPlanRequest(checkpoint);
                    PlanningResult planResult = markovPlanner.planNextAttraction(task, checkpoint, planningRequest, taskUuid);
                    int tokensUsed = planResult.totalTokens();
                    if (tokensUsed > 0) {
                        try {
                            int updatedTotal = llmUsageAccountingService.recordUsage(task.getId(), task.getUserId(), tokensUsed);
                            task.setTotalTokensUsed(updatedTotal);
                        } catch (QuotaExhaustedException qe) {
                            Task refreshedTask = taskMapper.findById(task.getId());
                            if (refreshedTask != null) {
                                task.setTotalTokensUsed(refreshedTask.getTotalTokensUsed());
                            }
                            handleQuotaExhaustion(task, checkpoint, taskUuid);
                            return;
                        }
                    }
                    if (planResult.requiresUserSelection()) {
                        LocationCandidateItem autoSelected = selectionCoordinator.chooseAutoCandidate(planResult).orElse(null);
                        if (autoSelected != null) {
                            selectionCoordinator.applyAutoSelection(task, checkpoint, taskUuid,
                                    stepIndex, dayNumber, autoSelected, planResult);
                            selectedCandidate = autoSelected;
                            attractionName = selectionCoordinator.candidateAttractionName(autoSelected);
                        } else {
                            if (!selectionCoordinator.hasSelectionData(planResult)) {
                                markFailed(task, taskUuid, "No selection data was generated",
                                        Map.of("code", "NO_SELECTION_DATA",
                                                "message", "No selection data was generated",
                                                "retryable", false));
                                return;
                            }
                            selectionCoordinator.awaitAttractionSelection(task, checkpoint, taskUuid,
                                    stepIndex, dayNumber, planResult);
                            return;
                        }
                    } else {
                        attractionName = planResult.attractionName();
                    }
                } catch (QuotaExhaustedException e) {
                    handleQuotaExhaustion(task, checkpoint, taskUuid);
                    return;
                } catch (Exception e) {
                    handleRetryOrFail(task, checkpoint, taskUuid, e);
                    return;
                }
            }

            AgentToolStepService.AgentToolStepResult toolResult;
            try {
                toolResult = toolStepService.startAndRunTools(task, checkpoint, taskUuid, stepIndex, attractionName);
            } catch (QuotaExhaustedException e) {
                handleQuotaExhaustion(task, checkpoint, taskUuid);
                return;
            } catch (Exception e) {
                handleRetryOrFail(task, checkpoint, taskUuid, e);
                return;
            }

            if (stopIfExecutionNoLongerActive(task, taskUuid, checkpoint)) {
                return;
            }

            toolStepService.finishToolStage(task, checkpoint, taskUuid, toolResult);

            Map<String, Object> geocodeResult = toolResult.geocodeResult();
            Map<String, Object> weatherResult = toolResult.weatherResult();
            Map<String, Object> trafficResult = toolResult.trafficResult();
            int displayTrafficMin = trafficResult != null
                    ? ((Number) trafficResult.getOrDefault("durationMin", 0)).intValue() : 0;
            int budgetTrafficMin = checkpointHelper.shouldExcludeOriginTravelFromBudget(stepIndex, checkpoint)
                    ? 0
                    : displayTrafficMin;
            int visitDurationMin = checkpoint.getPlanningConfig().getDefaultVisitDurationMin();
            int usedBeforeStep = checkpointHelper.valueOrZero(checkpoint.getUsedTimeBudgetMin());
            int plannedStartOffset = usedBeforeStep + budgetTrafficMin;
            int plannedEndOffset = plannedStartOffset + visitDurationMin;
            LocalDateTime plannedStart = checkpointHelper.resolveDateTimeForOffset(checkpoint, plannedStartOffset);
            LocalDateTime plannedEnd = checkpointHelper.resolveDateTimeForOffset(checkpoint, plannedEndOffset);

            double lat = ((Number) geocodeResult.get("lat")).doubleValue();
            double lng = ((Number) geocodeResult.get("lng")).doubleValue();
            int returnToDestinationMin = checkpointHelper.estimateTravelTimeToDestination(checkpoint, lat, lng);
            CompletedStep step = checkpointHelper.buildCompletedStep(stepIndex, dayNumber, attractionName, lat, lng,
                    geocodeResult, weatherResult, trafficResult, displayTrafficMin, visitDurationMin,
                    plannedStart, plannedEnd, returnToDestinationMin);
            checkpoint.getCompletedSteps().add(step);
            checkpoint.setCurrentStepIndex(stepIndex + 1);
            checkpoint.setUsedTimeBudgetMin(Math.min(checkpoint.totalAvailableMinutes(), plannedEndOffset));
            checkpoint.setProjectedReturnToDestinationMin(returnToDestinationMin);
            checkpoint.setPendingToolCall(null);
            checkpoint.setSelectionStage(null);
            checkpoint.setSelectedBranchType(null);
            checkpoint.setSelectionOptions(List.of());
            checkpoint.setSelectedAttractionCandidate(null);
            checkpoint.setRecommendationCandidates(List.of());
            checkpoint.setCurrentContext(Map.of());
            checkpoint.setWeatherContext(Map.of());
            checkpointHelper.refreshRemainingBudget(checkpoint);
            if (checkpoint.getRetryState() != null) {
                checkpoint.getRetryState().reset();
            }
            checkpointHelper.saveCheckpoint(task, checkpoint);

            Map<String, Object> stepPayload = new HashMap<>();
            stepPayload.put("stepIndex", stepIndex);
            stepPayload.put("attractionName", attractionName);
            stepPayload.put("trafficTimeMin", displayTrafficMin);
            stepPayload.put("dayNumber", dayNumber);
            stepPayload.put("plannedStartTime", plannedStart);
            stepPayload.put("plannedEndTime", plannedEnd);
            stepPayload.put("travelTimeToDestinationMin", returnToDestinationMin);
            if (stepIndex == 0 && checkpoint.getSelectedOrigin() != null) {
                stepPayload.put("originName", checkpoint.getSelectedOrigin().getName());
            }
            sseNotificationService.sendEvent(taskUuid, SseEvent.STEP_DONE, stepPayload);
            taskProgressService.recordEvent(taskUuid, EVT_STEP_DONE, planning.getCode(),
                    stepIndex, checkpoint.totalPlannedSteps(),
                    "Step " + stepIndex + " completed: " + attractionName, stepPayload);
        }

        if (stopIfExecutionNoLongerActive(task, taskUuid, checkpoint)) {
            return;
        }

        Long planId = planFinalizationService.finalizePlan(task, checkpoint).planId();
        TaskStatus completed = stateMachine.transition(TaskStatus.PLANNING, AgentEvent.COMPLETE);
        task.setStatus(completed.getCode());
        checkpoint.setCurrentState(completed.getCode());
        if (task.getTotalTokensUsed() == null) {
            task.setTotalTokensUsed(0);
        }
        checkpointHelper.saveCheckpoint(task, checkpoint);
        taskMapper.updateStatus(task.getId(), completed.getCode());
        taskMetricsService.recordTaskCompleted();
        taskProgressService.recordEvent(taskUuid, EVT_COMPLETED, completed.getCode(),
                checkpoint.getCurrentStepIndex(), checkpoint.totalPlannedSteps(),
                "Task completed successfully", Map.of(
                        "planId", planId,
                        "totalTokensUsed", task.getTotalTokensUsed() == null ? 0 : task.getTotalTokensUsed()
                ));
        sseNotificationService.sendEvent(taskUuid, SseEvent.COMPLETED, Map.of(
                "planId", planId,
                "totalTokensUsed", task.getTotalTokensUsed() == null ? 0 : task.getTotalTokensUsed()
        ));
        sseNotificationService.completeEmitter(taskUuid);
    }

    private boolean stopIfExecutionNoLongerActive(Task task, String taskUuid, TaskCheckpoint checkpoint) {
        Task latest = taskMapper.findByUuid(taskUuid);
        if (latest == null) {
            log.warn("[AgentService] Stop execution because task disappeared: {}", taskUuid);
            return true;
        }

        TaskStatus latestStatus = TaskStatus.fromCode(latest.getStatus());
        if (latestStatus == TaskStatus.CANCELLED) {
            checkpoint.setCurrentState(TaskStatus.CANCELLED.getCode());
            checkpoint.setPauseReason(PAUSE_REASON_USER_CANCELLED);
            latest.setStatus(TaskStatus.CANCELLED.getCode());
            checkpointHelper.saveCheckpoint(latest, checkpoint);

            Map<String, Object> payload = Map.of(
                    "taskUuid", taskUuid,
                    "status", TaskStatus.CANCELLED.getCode(),
                    "reason", PAUSE_REASON_USER_CANCELLED
            );
            taskProgressService.recordEvent(taskUuid, EVT_CANCELLED, TaskStatus.CANCELLED.getCode(),
                    checkpoint.getCurrentStepIndex(), checkpoint.totalPlannedSteps(),
                    "Task execution stopped because it was cancelled", payload);
            sseNotificationService.sendEvent(taskUuid, SseEvent.STATE_CHANGE, payload);
            sseNotificationService.completeEmitter(taskUuid);
            task.setStatus(TaskStatus.CANCELLED.getCode());
            return true;
        }

        if (latestStatus.isTerminal()) {
            log.info("[AgentService] Stop execution because task={} is already terminal: {}", taskUuid, latestStatus.getCode());
            task.setStatus(latestStatus.getCode());
            return true;
        }
        if (!latestStatus.isActive() && latestStatus != TaskStatus.PENDING) {
            log.info("[AgentService] Stop execution because task={} moved to non-active state: {}", taskUuid, latestStatus.getCode());
            task.setStatus(latestStatus.getCode());
            return true;
        }
        return false;
    }

    /**
     * 处理用户日配额耗尽，将任务暂停到次日零点后可恢复。
     *
     * @param task 任务实体
     * @param checkpoint 任务检查点数据
     * @param taskUuid 任务唯一标识
     */
    private void handleQuotaExhaustion(Task task, TaskCheckpoint checkpoint, String taskUuid) {
        LocalDateTime tomorrow = LocalDateTime.now().plusDays(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
        TaskStatus currentStatus = TaskStatus.fromCode(checkpoint.getCurrentState());
        checkpoint.setCurrentState(TaskStatus.PAUSED.getCode());
        checkpoint.setResumableAt(tomorrow);
        checkpoint.setPauseReason(PAUSE_REASON_DAILY_QUOTA);
        checkpointHelper.saveCheckpoint(task, checkpoint);

        TaskStatus paused = stateMachine.transition(currentStatus, AgentEvent.QUOTA_EXHAUSTED);
        taskMapper.updateStatus(task.getId(), paused.getCode());
        taskMetricsService.recordTaskPaused();
        taskProgressService.recordEvent(taskUuid, EVT_PAUSED, paused.getCode(), null, null,
                "Task paused: daily quota exhausted",
                Map.of("reason", PAUSE_REASON_DAILY_QUOTA, "resumableAt", tomorrow.toString()));
        sseNotificationService.sendEvent(taskUuid, SseEvent.PAUSED,
                Map.of("reason", PAUSE_REASON_DAILY_QUOTA, "resumableAt", tomorrow.toString()));
    }

    /**
     * 根据异常可重试性决定继续重试或标记任务失败。
     *
     * @param task 任务实体
     * @param checkpoint 任务检查点数据
     * @param taskUuid 任务唯一标识
     * @param exception 本轮规划异常
     */
    private void handleRetryOrFail(Task task, TaskCheckpoint checkpoint, String taskUuid, Exception exception) {
        if (exception instanceof AgentException agentException
                && agentException.getErrorCode() == AgentErrorCode.TOOL_AMAP_RATE_LIMIT) {
            handleAmapRateLimit(task, checkpoint, taskUuid, agentException);
            return;
        }
        if (exception instanceof RateLimitExceededException) {
            markFailed(task, taskUuid, exception.getMessage(), buildErrorPayload(exception, false));
            return;
        }

        boolean retryable = !(exception instanceof AgentException ae) || ae.isRetryable();
        Map<String, Object> errorPayload = buildErrorPayload(exception, true);

        if (!retryable) {
            markFailed(task, taskUuid, exception.getMessage(), errorPayload);
            return;
        }

        if (checkpoint.getRetryState() == null) {
            checkpoint.setRetryState(new RetryState());
        }
        checkpoint.getRetryState().increment();
        if (checkpoint.getRetryState().isExhausted()) {
            markFailed(task, taskUuid, "Retry budget exhausted: " + exception.getMessage(), errorPayload);
        } else {
            int attempt = checkpoint.getRetryState().getCurrentStepRetryCount();
            int max = checkpoint.getRetryState().getMaxRetries();
            taskProgressService.recordEvent(taskUuid, EVT_RETRY, null, null, null,
                    "Retrying: " + exception.getMessage(), Map.of("attempt", attempt, "maxAttempts", max));
            checkpointHelper.saveCheckpoint(task, checkpoint);
        }
    }

    /**
     * 处理高德限流，优先自动退避重试，重试耗尽后暂停任务。
     *
     * @param task 任务实体
     * @param checkpoint 任务检查点数据
     * @param taskUuid 任务唯一标识
     * @param exception 高德限流异常
     */
    private void handleAmapRateLimit(Task task, TaskCheckpoint checkpoint, String taskUuid, AgentException exception) {
        if (checkpoint.getRetryState() == null) {
            checkpoint.setRetryState(new RetryState());
        }

        int attempt = checkpoint.getRetryState().increment();
        int max = checkpoint.getRetryState().getMaxRetries();
        Map<String, Object> retryPayload = Map.of(
                "attempt", attempt,
                "maxAttempts", max,
                "message", "地图服务调用过于频繁，系统正在自动重试",
                "code", exception.getErrorCode().name()
        );

        if (attempt >= max) {
            pauseForAmapRateLimit(task, checkpoint, taskUuid, exception, retryPayload);
            return;
        }

        checkpointHelper.saveCheckpoint(task, checkpoint);
        taskProgressService.recordEvent(taskUuid, EVT_RETRY, null, null, null,
                "地图服务调用过于频繁，系统正在自动重试", retryPayload);
        sseNotificationService.sendEvent(taskUuid, SseEvent.RETRY, retryPayload);

        int backoffSeconds = AMAP_RATE_LIMIT_BACKOFF_SECONDS[Math.min(attempt - 1, AMAP_RATE_LIMIT_BACKOFF_SECONDS.length - 1)];
        sleepForRateLimitBackoff(backoffSeconds, taskUuid);
        checkpoint.setCurrentState(TaskStatus.RESUMING.getCode());
        checkpoint.setPauseReason(null);
        checkpoint.setResumableAt(null);
        checkpointHelper.saveCheckpoint(task, checkpoint);
        taskMapper.updateStatus(task.getId(), TaskStatus.RESUMING.getCode());
        try {
            executeTask(taskUuid);
        } catch (Exception recursiveException) {
            log.warn("[AgentService] Recursive retry execution failed for task={}: {}", taskUuid, recursiveException.getMessage());
        }
    }

    /**
     * 因高德限流暂停任务，并记录可恢复时间和前端提示载荷。
     *
     * @param task 任务实体
     * @param checkpoint 任务检查点数据
     * @param taskUuid 任务唯一标识
     * @param exception 高德限流异常
     * @param retryPayload 已累计的重试上下文
     */
    private void pauseForAmapRateLimit(Task task, TaskCheckpoint checkpoint, String taskUuid,
                                       AgentException exception, Map<String, Object> retryPayload) {
        LocalDateTime resumableAt = LocalDateTime.now().plusMinutes(2);
        TaskStatus currentStatus = TaskStatus.fromCode(checkpoint.getCurrentState());
        checkpoint.setCurrentState(TaskStatus.PAUSED.getCode());
        checkpoint.setPauseReason(PAUSE_REASON_AMAP_RATE_LIMITED);
        checkpoint.setResumableAt(resumableAt);
        checkpointHelper.saveCheckpoint(task, checkpoint);

        TaskStatus paused = stateMachine.transition(currentStatus, AgentEvent.QUOTA_EXHAUSTED);
        taskMapper.updateStatus(task.getId(), paused.getCode());
        taskMetricsService.recordTaskPaused();

        Map<String, Object> pausePayload = new HashMap<>(retryPayload);
        pausePayload.put("reason", PAUSE_REASON_AMAP_RATE_LIMITED);
        pausePayload.put("resumableAt", resumableAt.toString());
        pausePayload.put("retryable", true);
        pausePayload.put("message", "高德接口限流，任务已暂时暂停，可稍后恢复");
        pausePayload.put("rawMessage", exception.getMessage());

        taskProgressService.recordEvent(taskUuid, EVT_PAUSED, paused.getCode(), null, null,
                "高德接口限流，任务已暂时暂停，可稍后恢复", pausePayload);
        sseNotificationService.sendEvent(taskUuid, SseEvent.PAUSED, pausePayload);
    }

    /**
     * 执行高德限流退避等待。
     *
     * @param seconds 等待秒数
     * @param taskUuid 任务唯一标识
     */
    private void sleepForRateLimitBackoff(int seconds, String taskUuid) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            log.warn("[AgentService] Retry backoff interrupted for task={}", taskUuid);
        }
    }

    /**
     * 将任务标记为失败，并同步 checkpoint、进度事件和 SSE。
     *
     * @param task 任务实体
     * @param taskUuid 任务唯一标识
     * @param errorMsg 错误摘要
     * @param errorPayload 错误事件载荷
     */
    private void markFailed(Task task, String taskUuid, String errorMsg, Map<String, Object> errorPayload) {
        try {
            Task fresh = taskMapper.findByUuid(taskUuid);
            if (fresh != null && !TaskStatus.fromCode(fresh.getStatus()).isTerminal()) {
                fresh.setErrorMessage(errorMsg);
                TaskCheckpoint checkpoint;
                try {
                    checkpoint = checkpointHelper.loadCheckpoint(fresh);
                } catch (CheckpointCorruptedException corrupted) {
                    // Never resume from an untrusted payload. Replace it with a
                    // minimal failure checkpoint so the task is queryable and
                    // no provider call can be made from an unknown step.
                    checkpoint = new TaskCheckpoint();
                    checkpoint.setTaskId(fresh.getId());
                    checkpoint.setUserId(fresh.getUserId());
                    checkpoint.setTaskUuid(fresh.getTaskUuid());
                    errorPayload = new HashMap<>(errorPayload == null ? Map.of() : errorPayload);
                    errorPayload.put("code", corrupted.getCode());
                    errorPayload.put("retryable", false);
                    errorPayload.put("message", corrupted.getMessage());
                    errorMsg = corrupted.getMessage();
                }
                checkpoint.setCurrentState(TaskStatus.FAILED.getCode());
                checkpoint.recordFailure(errorMsg);
                checkpoint.recordIntermediateSummary(errorPayload);
                checkpointHelper.saveCheckpoint(fresh, checkpoint);
                taskMapper.updateStatus(fresh.getId(), TaskStatus.FAILED.getCode());
                fresh.setStatus(TaskStatus.FAILED.getCode());
                taskMapper.update(fresh);
                taskMetricsService.recordTaskFailed();
                taskProgressService.recordEvent(taskUuid, EVT_ERROR, TaskStatus.FAILED.getCode(), null, null, errorMsg, errorPayload);
                sseNotificationService.sendEvent(taskUuid, SseEvent.ERROR, errorPayload);
                sseNotificationService.completeEmitter(taskUuid);
            }
        } catch (Exception ex) {
            log.error("[AgentService] Failed to mark task={} as FAILED: {}", taskUuid, ex.getMessage());
        }
    }

    private Map<String, Object> buildErrorPayload(Exception exception, boolean defaultRetryable) {
        if (exception instanceof AgentException ae) {
            return ae.toEventPayload();
        }
        if (exception instanceof RateLimitExceededException) {
            return Map.of(
                    "code", "RATE_LIMIT_EXCEEDED",
                    "message", String.valueOf(exception.getMessage()),
                    "retryable", false
            );
        }
        return Map.of(
                "code", "UNKNOWN",
                "message", String.valueOf(exception.getMessage()),
                "retryable", defaultRetryable
        );
    }

    /**
     * 推送任务状态变化 SSE。
     *
     * @param taskUuid 任务唯一标识
     * @param checkpoint 任务检查点数据
     * @param status 状态值
     * @param task 任务实体
     */
    private void sendStateChange(String taskUuid, TaskCheckpoint checkpoint, String status, Task task) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("status", status);
        payload.put("step", checkpoint.getCurrentStepIndex());
        payload.put("totalSteps", checkpoint.totalPlannedSteps());
        payload.put("pendingInputType", checkpoint.getPendingInputType());
        payload.put("selectionStage", checkpoint.getSelectionStage());
        payload.put("selectedBranchType", checkpoint.getSelectedBranchType());
        payload.put("remainingTimeBudgetMin", checkpoint.getRemainingTimeBudgetMin());
        payload.put("totalTokensUsed", task.getTotalTokensUsed() == null ? 0 : task.getTotalTokensUsed());
        sseNotificationService.sendEvent(taskUuid, SseEvent.STATE_CHANGE, payload);
    }

    private void renewTaskLease(Task task) {
        if (task != null && lifecycleGovernanceService != null
                && task.getId() != null && task.getLeaseToken() != null
                && !task.getLeaseToken().isBlank()) {
            lifecycleGovernanceService.renewExecutionLease(task.getId(), task.getLeaseToken(), Duration.ofMinutes(10));
            return;
        }
        if (redisTaskLockService != null && task != null) {
            redisTaskLockService.renewLease(task.getTaskUuid());
        }
    }

}
