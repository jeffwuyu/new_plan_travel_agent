package com.travelagent.service.agent.impl;

import com.travelagent.agent.context.CompletedStep;
import com.travelagent.agent.context.RetryState;
import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.agent.planner.FinalSummaryResult;
import com.travelagent.agent.planner.MarkovPlanner;
import com.travelagent.agent.planner.PlanNextAttractionRequest;
import com.travelagent.agent.planner.PlanningResult;
import com.travelagent.agent.statemachine.AgentEvent;
import com.travelagent.agent.statemachine.AgentStateMachine;
import com.travelagent.agent.tools.GeocodeTool;
import com.travelagent.agent.tools.TrafficTimeTool;
import com.travelagent.agent.tools.WeatherTool;
import com.travelagent.config.DatabaseSchemaGuard;
import com.travelagent.exception.AgentErrorCode;
import com.travelagent.exception.AgentException;
import com.travelagent.exception.QuotaExhaustedException;
import com.travelagent.mapper.PlanMapper;
import com.travelagent.mapper.TaskMapper;
import com.travelagent.model.dto.LocationCandidateItem;
import com.travelagent.model.entity.Plan;
import com.travelagent.model.entity.PlanStep;
import com.travelagent.model.entity.Task;
import com.travelagent.model.enums.TaskStatus;
import com.travelagent.monitoring.TaskMetricsService;
import com.travelagent.service.agent.AgentService;
import com.travelagent.service.llm.LlmUsageAccountingService;
import com.travelagent.service.notification.SseEvent;
import com.travelagent.service.notification.SseNotificationService;
import com.travelagent.service.task.TaskProgressService;
import com.travelagent.service.user.QuotaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 主控服务：状态机转换、规划循环、Quota 处理、SSE 通知、计划持久化。
 * 工具执行委托给 {@link AgentToolExecutor}，Checkpoint 管理委托给 {@link AgentCheckpointHelper}。
 */
@Service
public class AgentServiceImpl implements AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentServiceImpl.class);
    private static final String PAUSE_REASON_DAILY_QUOTA = "daily_quota_exhausted";
    private static final String PAUSE_REASON_AMAP_RATE_LIMITED = "amap_rate_limited";
    private static final int[] AMAP_RATE_LIMIT_BACKOFF_SECONDS = {2, 5, 10};

    private static final String EVT_STATE_CHANGE = "STATE_CHANGE";
    private static final String EVT_STEP_DONE = "STEP_DONE";
    private static final String EVT_ERROR = "ERROR";
    private static final String EVT_RETRY = "RETRY";
    private static final String EVT_PAUSED = "PAUSED";
    private static final String EVT_COMPLETED = "COMPLETED";
    private static final String EVT_USER_SELECTION_REQUIRED = "USER_SELECTION_REQUIRED";
    private static final String EVT_USER_SELECTION_CONFIRMED = "USER_SELECTION_CONFIRMED";

    @Autowired private TaskMapper taskMapper;
    @Autowired private AgentStateMachine stateMachine;
    @Autowired private SseNotificationService sseNotificationService;
    @Autowired private MarkovPlanner markovPlanner;
    @Autowired private QuotaService quotaService;
    @Autowired private PlanMapper planMapper;
    @Autowired private TaskProgressService taskProgressService;
    @Autowired private TaskMetricsService taskMetricsService;
    @Autowired private DatabaseSchemaGuard schemaGuard;
    @Autowired private LlmUsageAccountingService llmUsageAccountingService;
    @Autowired private AgentCheckpointHelper checkpointHelper;
    @Autowired private AgentToolExecutor toolExecutor;

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

    public void recoverStuckTasks() {
        List<String> stuckStatuses = List.of(
                TaskStatus.PLANNING.getCode(),
                TaskStatus.TOOL_CALLING.getCode()
        );
        List<Task> stuckTasks = taskMapper.findByStatusIn(stuckStatuses, 100);
        for (Task task : stuckTasks) {
            try {
                taskMapper.updateStatus(task.getId(), TaskStatus.RESUMING.getCode());
            } catch (Exception e) {
                log.error("[AgentService] Failed to recover task uuid={}: {}", task.getTaskUuid(), e.getMessage());
            }
        }
    }

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

        MDC.put("taskUuid", taskUuid);
        try {
            taskMetricsService.recordTaskStarted();
            runPlanningLoop(task, taskUuid, current);
        } catch (Exception e) {
            Map<String, Object> payload = (e instanceof AgentException ae)
                    ? ae.toEventPayload()
                    : Map.of("code", "UNKNOWN", "message", String.valueOf(e.getMessage()), "retryable", false);
            markFailed(task, taskUuid, e.getMessage(), payload);
        } finally {
            MDC.remove("taskUuid");
        }
    }

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
            handleAwaitingOriginSelection(task, checkpoint, taskUuid);
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
            toolExecutor.replayPendingToolCall(task, checkpoint, taskUuid);
        }

        checkpointHelper.refreshRemainingBudget(checkpoint);

        while (!checkpoint.isAllStepsDone() && checkpointHelper.canPlanAnotherStep(checkpoint)) {
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
                        handleAwaitingSelection(task, checkpoint, taskUuid, stepIndex, dayNumber, planResult);
                        return;
                    }
                    attractionName = planResult.attractionName();
                } catch (QuotaExhaustedException e) {
                    handleQuotaExhaustion(task, checkpoint, taskUuid);
                    return;
                } catch (Exception e) {
                    handleRetryOrFail(task, checkpoint, taskUuid, e);
                    return;
                }
            }

            TaskStatus toolCalling = stateMachine.transition(TaskStatus.PLANNING, AgentEvent.START_TOOL_CALL);
            task.setStatus(toolCalling.getCode());
            checkpoint.setCurrentState(toolCalling.getCode());
            taskMapper.updateStatus(task.getId(), toolCalling.getCode());
            sendStateChange(taskUuid, checkpoint, toolCalling.getCode(), task);

            Map<String, Object> geocodeResult;
            Map<String, Object> weatherResult;
            Map<String, Object> trafficResult = null;

            try {
                Map<String, Object> geocodeArgs = Map.of("name", attractionName, "region", checkpoint.getRegion());
                geocodeResult = toolExecutor.runToolWithCheckpoint(task, checkpoint, GeocodeTool.NAME, geocodeArgs, taskUuid, stepIndex);

                String adcode = (String) geocodeResult.getOrDefault("adcode", "");
                if (adcode.isBlank()) {
                    weatherResult = Map.of("weather", "Unknown", "temperature", "", "windDirection", "", "windPower", "", "humidity", "");
                } else {
                    weatherResult = toolExecutor.runToolWithCheckpoint(task, checkpoint, WeatherTool.NAME, Map.of("adcode", adcode), taskUuid, stepIndex);
                }

                if (stepIndex > 0) {
                    CompletedStep prevStep = checkpoint.getCompletedSteps().get(stepIndex - 1);
                    trafficResult = toolExecutor.runToolWithCheckpoint(task, checkpoint, TrafficTimeTool.NAME, Map.of(
                            "originLng", prevStep.getLng(),
                            "originLat", prevStep.getLat(),
                            "destLng", geocodeResult.get("lng"),
                            "destLat", geocodeResult.get("lat"),
                            "travelMode", checkpoint.getPlanningConfig().getTravelMode()
                    ), taskUuid, stepIndex);
                } else if (checkpoint.getSelectedOrigin() != null
                        && checkpoint.getSelectedOrigin().getLatitude() != null
                        && checkpoint.getSelectedOrigin().getLongitude() != null) {
                    trafficResult = toolExecutor.runToolWithCheckpoint(task, checkpoint, TrafficTimeTool.NAME, Map.of(
                            "originLng", checkpoint.getSelectedOrigin().getLongitude(),
                            "originLat", checkpoint.getSelectedOrigin().getLatitude(),
                            "destLng", geocodeResult.get("lng"),
                            "destLat", geocodeResult.get("lat"),
                            "travelMode", checkpoint.getPlanningConfig().getTravelMode()
                    ), taskUuid, stepIndex);
                }
            } catch (QuotaExhaustedException e) {
                handleQuotaExhaustion(task, checkpoint, taskUuid);
                return;
            } catch (Exception e) {
                handleRetryOrFail(task, checkpoint, taskUuid, e);
                return;
            }

            Map<String, Object> toolPayload = new HashMap<>();
            toolPayload.put("geocode", geocodeResult);
            toolPayload.put("weather", weatherResult);
            toolPayload.put("traffic_time", trafficResult);
            sseNotificationService.sendEvent(taskUuid, SseEvent.TOOL_RESULT, toolPayload);

            TaskStatus backToPlanning = stateMachine.transition(TaskStatus.TOOL_CALLING, AgentEvent.TOOL_CALL_DONE);
            task.setStatus(backToPlanning.getCode());
            checkpoint.setCurrentState(backToPlanning.getCode());
            taskMapper.updateStatus(task.getId(), backToPlanning.getCode());
            sendStateChange(taskUuid, checkpoint, backToPlanning.getCode(), task);

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

        Long planId = persistPlan(task, checkpoint);
        TaskStatus completed = stateMachine.transition(TaskStatus.PLANNING, AgentEvent.COMPLETE);
        task.setStatus(completed.getCode());
        checkpoint.setCurrentState(completed.getCode());
        if (task.getTotalTokensUsed() == null) {
            task.setTotalTokensUsed(0);
        }
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

    private void handleAwaitingOriginSelection(Task task, TaskCheckpoint checkpoint, String taskUuid) {
        List<LocationCandidateItem> candidates = checkpoint.getLocationCandidates() == null
                ? List.of()
                : checkpoint.getLocationCandidates();
        if (candidates.isEmpty()) {
            markFailed(task, taskUuid, "No origin candidates were generated",
                    Map.of("code", "NO_ORIGIN_CANDIDATES", "message", "No origin candidates were generated", "retryable", false));
            return;
        }

        TaskStatus awaiting = stateMachine.transition(TaskStatus.PLANNING, AgentEvent.USER_INPUT_REQUIRED);
        checkpoint.setPendingInputType("origin_selection");
        checkpoint.setSelectionStage("origin_selection");
        checkpoint.setSelectedBranchType(null);
        checkpoint.setSelectionOptions(List.of());
        checkpoint.setRecommendationCandidates(new ArrayList<>(candidates));
        checkpoint.setCurrentContext(Map.of());
        checkpoint.setWeatherContext(Map.of());
        checkpoint.setCurrentState(awaiting.getCode());
        task.setStatus(awaiting.getCode());
        checkpointHelper.saveCheckpoint(task, checkpoint);
        taskMapper.updateStatus(task.getId(), awaiting.getCode());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskUuid", taskUuid);
        payload.put("startLocationQuery", checkpoint.getStartLocationQuery());
        payload.put("pendingInputType", "origin_selection");
        payload.put("locationCandidates", candidates);
        payload.put("recommendationCandidates", candidates);
        payload.put("currentContext", Map.of());

        sseNotificationService.sendEvent(taskUuid, SseEvent.USER_SELECTION_REQUIRED, payload);
        taskProgressService.recordEvent(taskUuid, EVT_USER_SELECTION_REQUIRED, awaiting.getCode(),
                checkpoint.getCurrentStepIndex(), checkpoint.totalPlannedSteps(),
                "Waiting for origin selection", payload);
    }

    private void handleAwaitingSelection(Task task,
                                         TaskCheckpoint checkpoint,
                                         String taskUuid,
                                         int stepIndex,
                                         int dayNumber,
                                         PlanningResult planResult) {
        boolean needsOptions = planResult.selectionOptions() != null && !planResult.selectionOptions().isEmpty();
        boolean needsCandidates = planResult.recommendationCandidates() != null && !planResult.recommendationCandidates().isEmpty();
        if (!needsOptions && !needsCandidates) {
            markFailed(task, taskUuid, "No selection data was generated",
                    Map.of("code", "NO_SELECTION_DATA", "message", "No selection data was generated", "retryable", false));
            return;
        }

        TaskStatus awaiting = stateMachine.transition(TaskStatus.PLANNING, AgentEvent.USER_INPUT_REQUIRED);
        Map<String, Object> currentContext = new LinkedHashMap<>();
        if (planResult.currentContext() != null && !planResult.currentContext().isEmpty()) {
            currentContext.putAll(planResult.currentContext());
        } else {
            currentContext.putAll(buildAttractionSelectionContext(checkpoint, stepIndex, dayNumber));
        }
        checkpoint.setPendingInputType(planResult.pendingInputType());
        checkpoint.setSelectionStage(planResult.selectionStage());
        checkpoint.setSelectedBranchType(planResult.selectedBranchType());
        checkpoint.setSelectionOptions(planResult.selectionOptions() == null ? List.of() : new ArrayList<>(planResult.selectionOptions()));
        checkpoint.setRecommendationCandidates(planResult.recommendationCandidates() == null
                ? List.of()
                : new ArrayList<>(planResult.recommendationCandidates()));
        checkpoint.setCurrentContext(currentContext);
        checkpoint.setWeatherContext(planResult.weatherContext() == null ? Map.of() : new LinkedHashMap<>(planResult.weatherContext()));
        checkpoint.setCurrentState(awaiting.getCode());
        task.setStatus(awaiting.getCode());
        checkpointHelper.saveCheckpoint(task, checkpoint);
        taskMapper.updateStatus(task.getId(), awaiting.getCode());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskUuid", taskUuid);
        payload.put("pendingInputType", planResult.pendingInputType());
        payload.put("selectionStage", planResult.selectionStage());
        payload.put("selectedBranchType", planResult.selectedBranchType());
        payload.put("stepIndex", stepIndex);
        payload.put("dayNumber", dayNumber);
        payload.put("selectionOptions", checkpoint.getSelectionOptions());
        payload.put("recommendationCandidates", checkpoint.getRecommendationCandidates());
        payload.put("currentContext", currentContext);
        payload.put("weatherContext", checkpoint.getWeatherContext());

        sseNotificationService.sendEvent(taskUuid, SseEvent.USER_SELECTION_REQUIRED, payload);
        taskProgressService.recordEvent(taskUuid, EVT_USER_SELECTION_REQUIRED, awaiting.getCode(),
                stepIndex, checkpoint.totalPlannedSteps(),
                "Waiting for user selection", payload);
    }

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

    private void handleRetryOrFail(Task task, TaskCheckpoint checkpoint, String taskUuid, Exception exception) {
        if (exception instanceof AgentException agentException
                && agentException.getErrorCode() == AgentErrorCode.TOOL_AMAP_RATE_LIMIT) {
            handleAmapRateLimit(task, checkpoint, taskUuid, agentException);
            return;
        }

        boolean retryable = !(exception instanceof AgentException ae) || ae.isRetryable();
        Map<String, Object> errorPayload = (exception instanceof AgentException ae)
                ? ae.toEventPayload()
                : Map.of("code", "UNKNOWN", "message", String.valueOf(exception.getMessage()), "retryable", true);

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

    private void sleepForRateLimitBackoff(int seconds, String taskUuid) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            log.warn("[AgentService] Retry backoff interrupted for task={}", taskUuid);
        }
    }

    private void markFailed(Task task, String taskUuid, String errorMsg, Map<String, Object> errorPayload) {
        try {
            Task fresh = taskMapper.findByUuid(taskUuid);
            if (fresh != null && !TaskStatus.fromCode(fresh.getStatus()).isTerminal()) {
                fresh.setErrorMessage(errorMsg);
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

    private Long persistPlan(Task task, TaskCheckpoint checkpoint) {
        FinalSummaryResult summary;
        try {
            summary = markovPlanner.generateFinalSummary(task, checkpoint, task.getTaskUuid());
        } catch (Exception e) {
            summary = null;
        }

        Plan plan = new Plan();
        plan.setTaskId(task.getId());
        plan.setUserId(task.getUserId());
        plan.setRegion(checkpoint.getRegion());
        plan.setTotalDays(checkpoint.getPlanningConfig().getTotalDays());
        plan.setStartLocationQuery(checkpoint.getStartLocationQuery());
        plan.setEndLocationQuery(checkpoint.getEndLocationQuery());
        plan.setTripStartTime(checkpoint.getTripStartTime());
        plan.setTripEndTime(checkpoint.getTripEndTime());
        plan.setFullDayStartTime(checkpoint.getPlanningConfig().resolveFullDayStartTime());
        plan.setFullDayEndTime(checkpoint.getPlanningConfig().resolveFullDayEndTime());
        plan.setDestinationBufferMin(checkpoint.getPlanningConfig().getDestinationBufferMin());
        if (summary != null) {
            plan.setTitle(summary.title());
            plan.setSummary(summary.summary());
        } else {
            plan.setTitle(checkpoint.getRegion() + " " + checkpoint.getPlanningConfig().getTotalDays() + "-Day Trip");
            plan.setSummary(checkpoint.getUserIntent());
        }
        planMapper.insertPlan(plan);

        List<FinalSummaryResult.StepSummary> stepSummaries = summary != null ? summary.steps() : List.of();
        List<PlanStep> steps = buildPlanSteps(plan.getId(), checkpoint.getCompletedSteps(), stepSummaries);
        if (!steps.isEmpty()) {
            planMapper.insertSteps(steps);
        }
        return plan.getId();
    }

    private List<PlanStep> buildPlanSteps(Long planId, List<CompletedStep> completedSteps,
                                          List<FinalSummaryResult.StepSummary> stepSummaries) {
        Map<Integer, FinalSummaryResult.StepSummary> summaryByOrder = new LinkedHashMap<>();
        for (FinalSummaryResult.StepSummary ss : stepSummaries) {
            summaryByOrder.put(ss.stepOrder(), ss);
        }

        List<PlanStep> steps = new ArrayList<>();
        for (CompletedStep cs : completedSteps) {
            PlanStep ps = new PlanStep();
            ps.setPlanId(planId);
            ps.setStepOrder(cs.getStepIndex());
            ps.setDayNumber(cs.getDayNumber());
            ps.setAttractionName(cs.getAttractionName());
            ps.setLatitude(cs.getLat() != null ? BigDecimal.valueOf(cs.getLat()) : null);
            ps.setLongitude(cs.getLng() != null ? BigDecimal.valueOf(cs.getLng()) : null);

            FinalSummaryResult.StepSummary ss = summaryByOrder.get(cs.getStepIndex());
            ps.setEstimatedDurationMin(ss != null ? ss.estimatedDurationMin() : cs.getEstimatedVisitDurationMin());
            ps.setLlmDescription(ss != null ? ss.llmDescription() : null);
            ps.setPlannedStartTime(cs.getPlannedStartTime());
            ps.setPlannedEndTime(cs.getPlannedEndTime());
            ps.setTravelTimeToDestinationMin(cs.getTravelTimeToDestinationMin());

            Map<String, Object> toolResults = cs.getToolCallResults();
            if (toolResults != null) {
                Map<?, ?> traffic = (Map<?, ?>) toolResults.get(TrafficTimeTool.NAME);
                if (traffic != null && traffic.get("durationMin") != null) {
                    ps.setTrafficTimeFromPrev(((Number) traffic.get("durationMin")).intValue());
                } else {
                    ps.setTrafficTimeFromPrev(cs.getTrafficTimeFromPrevMin());
                }
                Map<?, ?> weather = (Map<?, ?>) toolResults.get(WeatherTool.NAME);
                if (weather != null) {
                    Object weatherValue = weather.get("weather");
                    Object temperatureValue = weather.get("temperature");
                    String weatherText = weatherValue == null ? "" : weatherValue.toString().trim();
                    String temperatureText = temperatureValue == null ? "" : temperatureValue.toString().trim();
                    ps.setWeatherNote((weatherText + " " + temperatureText + " C").trim());
                }
            }
            steps.add(ps);
        }
        return steps;
    }

    private Map<String, Object> buildAttractionSelectionContext(TaskCheckpoint checkpoint, int stepIndex, int dayNumber) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("stepIndex", stepIndex);
        context.put("dayNumber", dayNumber);
        context.put("remainingTimeBudgetMin", checkpoint.getRemainingTimeBudgetMin());
        context.put("projectedReturnToDestinationMin", checkpoint.getProjectedReturnToDestinationMin());
        context.put("currentPositionName", resolveCurrentPositionName(checkpoint));
        context.put("destinationName", checkpoint.getSelectedDestination() != null
                ? checkpoint.getSelectedDestination().getName()
                : checkpoint.getEndLocationQuery());
        context.put("travelMode", checkpoint.getPlanningConfig() != null
                ? checkpoint.getPlanningConfig().getTravelMode()
                : null);
        return context;
    }

    private String resolveCurrentPositionName(TaskCheckpoint checkpoint) {
        if (checkpoint.getCompletedSteps() != null && !checkpoint.getCompletedSteps().isEmpty()) {
            return checkpoint.getCompletedSteps().get(checkpoint.getCompletedSteps().size() - 1).getAttractionName();
        }
        if (checkpoint.getSelectedOrigin() != null) {
            return checkpoint.getSelectedOrigin().getName();
        }
        return checkpoint.getStartLocationQuery();
    }
}
