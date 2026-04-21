package com.travelagent.service.agent.impl;

import com.travelagent.agent.context.CompletedStep;
import com.travelagent.agent.context.PendingToolCall;
import com.travelagent.agent.context.RetryState;
import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.agent.planner.FinalSummaryResult;
import com.travelagent.agent.planner.MarkovPlanner;
import com.travelagent.agent.planner.PlanningResult;
import com.travelagent.agent.statemachine.AgentEvent;
import com.travelagent.agent.statemachine.AgentStateMachine;
import com.travelagent.agent.tools.GeocodeTool;
import com.travelagent.agent.tools.ToolRegistry;
import com.travelagent.agent.tools.TrafficTimeTool;
import com.travelagent.agent.tools.WeatherTool;
import com.travelagent.config.DatabaseSchemaGuard;
import com.travelagent.exception.AgentException;
import com.travelagent.exception.QuotaExhaustedException;
import com.travelagent.mapper.PlanMapper;
import com.travelagent.mapper.TaskMapper;
import com.travelagent.mapper.UserMapper;
import com.travelagent.model.dto.LocationCandidateItem;
import com.travelagent.model.dto.SelectedOrigin;
import com.travelagent.model.entity.Plan;
import com.travelagent.model.entity.PlanStep;
import com.travelagent.model.entity.Task;
import com.travelagent.model.enums.TaskStatus;
import com.travelagent.monitoring.TaskMetricsService;
import com.travelagent.service.agent.AgentService;
import com.travelagent.service.notification.SseEvent;
import com.travelagent.service.notification.SseNotificationService;
import com.travelagent.service.task.TaskProgressService;
import com.travelagent.service.user.QuotaService;
import com.travelagent.util.JsonUtil;
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

@Service
public class AgentServiceImpl implements AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentServiceImpl.class);

    private static final String EVT_STATE_CHANGE = "STATE_CHANGE";
    private static final String EVT_TOOL_START = "TOOL_START";
    private static final String EVT_TOOL_DONE = "TOOL_DONE";
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
    @Autowired private ToolRegistry toolRegistry;
    @Autowired private QuotaService quotaService;
    @Autowired private JsonUtil jsonUtil;
    @Autowired private PlanMapper planMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private TaskProgressService taskProgressService;
    @Autowired private TaskMetricsService taskMetricsService;
    @Autowired private DatabaseSchemaGuard schemaGuard;

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
        TaskCheckpoint checkpoint = loadCheckpoint(task);
        int userLevel = resolveUserLevel(task.getUserId());

        TaskStatus planning = stateMachine.transition(current, AgentEvent.START_PLANNING);
        task.setStatus(planning.getCode());
        checkpoint.setCurrentState(planning.getCode());
        taskMapper.updateStatus(task.getId(), planning.getCode());
        sendStateChange(taskUuid, checkpoint, planning.getCode());
        taskProgressService.recordEvent(taskUuid, EVT_STATE_CHANGE, planning.getCode(),
                checkpoint.getCurrentStepIndex(), checkpoint.totalPlannedSteps(),
                "Task execution started", null);

        if (!checkpoint.isOriginConfirmed()) {
            handleAwaitingOriginSelection(task, checkpoint, taskUuid);
            return;
        }

        if (current == TaskStatus.RESUMING && checkpoint.getSelectedOrigin() != null) {
            taskProgressService.recordEvent(taskUuid, EVT_USER_SELECTION_CONFIRMED, TaskStatus.RESUMING.getCode(),
                    checkpoint.getCurrentStepIndex(), checkpoint.totalPlannedSteps(),
                    "Origin selected: " + checkpoint.getSelectedOrigin().getName(),
                    Map.of("selectedOrigin", checkpoint.getSelectedOrigin()));
        }

        if (checkpoint.getPendingToolCall() != null) {
            replayPendingToolCall(task, checkpoint, taskUuid);
        }

        while (!checkpoint.isAllStepsDone()) {
            int stepIndex = checkpoint.getCurrentStepIndex();
            int dayNumber = (stepIndex / checkpoint.getPlanningConfig().getAttractionsPerDay()) + 1;

            taskProgressService.recordEvent(taskUuid, EVT_STATE_CHANGE, planning.getCode(),
                    stepIndex, checkpoint.totalPlannedSteps(),
                    "Starting step " + stepIndex, null);

            try {
                quotaService.checkDailyQuota(task.getUserId(), userLevel);
            } catch (QuotaExhaustedException e) {
                handleQuotaExhaustion(task, checkpoint, taskUuid);
                return;
            }

            String attractionName;
            try {
                PlanningResult planResult = markovPlanner.planNextAttraction(task, checkpoint, taskUuid);
                attractionName = planResult.attractionName();
                int tokensUsed = planResult.totalTokens();
                try {
                    quotaService.debitTokens(task.getUserId(), userLevel, tokensUsed);
                } catch (QuotaExhaustedException qe) {
                    handleQuotaExhaustion(task, checkpoint, taskUuid);
                    return;
                }
                task.setTotalTokensUsed((task.getTotalTokensUsed() == null ? 0 : task.getTotalTokensUsed()) + tokensUsed);
            } catch (QuotaExhaustedException e) {
                handleQuotaExhaustion(task, checkpoint, taskUuid);
                return;
            } catch (Exception e) {
                handleRetryOrFail(task, checkpoint, taskUuid, e);
                return;
            }

            TaskStatus toolCalling = stateMachine.transition(TaskStatus.PLANNING, AgentEvent.START_TOOL_CALL);
            task.setStatus(toolCalling.getCode());
            checkpoint.setCurrentState(toolCalling.getCode());
            taskMapper.updateStatus(task.getId(), toolCalling.getCode());
            sendStateChange(taskUuid, checkpoint, toolCalling.getCode());

            Map<String, Object> geocodeResult;
            Map<String, Object> weatherResult;
            Map<String, Object> trafficResult = null;

            try {
                Map<String, Object> geocodeArgs = Map.of("name", attractionName, "region", checkpoint.getRegion());
                geocodeResult = runToolWithCheckpoint(task, checkpoint, GeocodeTool.NAME, geocodeArgs, taskUuid, stepIndex);

                String adcode = (String) geocodeResult.getOrDefault("adcode", "");
                if (adcode.isBlank()) {
                    weatherResult = Map.of("weather", "Unknown", "temperature", "", "windDirection", "", "windPower", "", "humidity", "");
                } else {
                    weatherResult = runToolWithCheckpoint(task, checkpoint, WeatherTool.NAME, Map.of("adcode", adcode), taskUuid, stepIndex);
                }

                if (stepIndex > 0) {
                    CompletedStep prevStep = checkpoint.getCompletedSteps().get(stepIndex - 1);
                    trafficResult = runToolWithCheckpoint(task, checkpoint, TrafficTimeTool.NAME, Map.of(
                            "originLng", prevStep.getLng(),
                            "originLat", prevStep.getLat(),
                            "destLng", geocodeResult.get("lng"),
                            "destLat", geocodeResult.get("lat")
                    ), taskUuid, stepIndex);
                } else if (checkpoint.getSelectedOrigin() != null
                        && checkpoint.getSelectedOrigin().getLatitude() != null
                        && checkpoint.getSelectedOrigin().getLongitude() != null) {
                    trafficResult = runToolWithCheckpoint(task, checkpoint, TrafficTimeTool.NAME, Map.of(
                            "originLng", checkpoint.getSelectedOrigin().getLongitude(),
                            "originLat", checkpoint.getSelectedOrigin().getLatitude(),
                            "destLng", geocodeResult.get("lng"),
                            "destLat", geocodeResult.get("lat")
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
            sendStateChange(taskUuid, checkpoint, backToPlanning.getCode());

            double lat = ((Number) geocodeResult.get("lat")).doubleValue();
            double lng = ((Number) geocodeResult.get("lng")).doubleValue();
            CompletedStep step = buildCompletedStep(stepIndex, dayNumber, attractionName, lat, lng, geocodeResult, weatherResult, trafficResult);
            checkpoint.getCompletedSteps().add(step);
            checkpoint.setCurrentStepIndex(stepIndex + 1);
            checkpoint.setPendingToolCall(null);
            if (checkpoint.getRetryState() != null) {
                checkpoint.getRetryState().reset();
            }
            saveCheckpoint(task, checkpoint);

            int trafficMin = trafficResult != null
                    ? ((Number) trafficResult.getOrDefault("durationMin", 0)).intValue() : 0;
            Map<String, Object> stepPayload = new HashMap<>();
            stepPayload.put("stepIndex", stepIndex);
            stepPayload.put("attractionName", attractionName);
            stepPayload.put("trafficTimeMin", trafficMin);
            stepPayload.put("dayNumber", dayNumber);
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
                "Task completed successfully", Map.of("planId", planId));
        sseNotificationService.sendEvent(taskUuid, SseEvent.COMPLETED, Map.of("planId", planId));
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
        checkpoint.setCurrentState(awaiting.getCode());
        task.setStatus(awaiting.getCode());
        saveCheckpoint(task, checkpoint);
        taskMapper.updateStatus(task.getId(), awaiting.getCode());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskUuid", taskUuid);
        payload.put("currentLocationQuery", checkpoint.getCurrentLocationQuery());
        payload.put("pendingInputType", "origin_selection");
        payload.put("locationCandidates", candidates);

        sseNotificationService.sendEvent(taskUuid, SseEvent.USER_SELECTION_REQUIRED, payload);
        taskProgressService.recordEvent(taskUuid, EVT_USER_SELECTION_REQUIRED, awaiting.getCode(),
                checkpoint.getCurrentStepIndex(), checkpoint.totalPlannedSteps(),
                "Waiting for origin selection",
                payload);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> runToolWithCheckpoint(Task task, TaskCheckpoint checkpoint,
                                                      String toolName, Map<String, Object> arguments,
                                                      String taskUuid, int stepIndex) {
        String idempotencyKey = taskUuid + "-step" + stepIndex + "-" + toolName;
        checkpoint.setPendingToolCall(new PendingToolCall(toolName, arguments, idempotencyKey));
        saveCheckpoint(task, checkpoint);
        taskProgressService.recordEvent(taskUuid, EVT_TOOL_START, null, stepIndex, null, "Calling tool: " + toolName, arguments);

        boolean toolSuccess = true;
        try {
            Map<String, Object> result = (Map<String, Object>) toolRegistry.getTool(toolName).execute(arguments, idempotencyKey);
            checkpoint.setPendingToolCall(null);
            saveCheckpoint(task, checkpoint);
            taskProgressService.recordEvent(taskUuid, EVT_TOOL_DONE, null, stepIndex, null, "Tool completed: " + toolName, result);
            return result;
        } catch (Exception e) {
            toolSuccess = false;
            throw e;
        } finally {
            taskMetricsService.recordToolCall(toolSuccess);
        }
    }

    private void replayPendingToolCall(Task task, TaskCheckpoint checkpoint, String taskUuid) {
        PendingToolCall pending = checkpoint.getPendingToolCall();
        try {
            toolRegistry.getTool(pending.getToolName()).execute(pending.getArguments(), pending.getIdempotencyKey());
            checkpoint.setPendingToolCall(null);
            saveCheckpoint(task, checkpoint);
        } catch (Exception e) {
            log.warn("[AgentService] Replay of pending tool {} failed for task={}: {}", pending.getToolName(), taskUuid, e.getMessage());
        }
    }

    private void handleQuotaExhaustion(Task task, TaskCheckpoint checkpoint, String taskUuid) {
        LocalDateTime tomorrow = LocalDateTime.now().plusDays(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
        TaskStatus currentStatus = TaskStatus.fromCode(checkpoint.getCurrentState());
        checkpoint.setCurrentState(TaskStatus.PAUSED.getCode());
        checkpoint.setResumableAt(tomorrow);
        saveCheckpoint(task, checkpoint);

        TaskStatus paused = stateMachine.transition(currentStatus, AgentEvent.QUOTA_EXHAUSTED);
        taskMapper.updateStatus(task.getId(), paused.getCode());
        taskMetricsService.recordTaskPaused();
        taskProgressService.recordEvent(taskUuid, EVT_PAUSED, paused.getCode(), null, null,
                "Task paused: daily quota exhausted", Map.of("resumableAt", tomorrow.toString()));
        sseNotificationService.sendEvent(taskUuid, SseEvent.PAUSED, Map.of("reason", "daily_quota_exhausted", "resumableAt", tomorrow.toString()));
    }

    private void handleRetryOrFail(Task task, TaskCheckpoint checkpoint, String taskUuid, Exception exception) {
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
            saveCheckpoint(task, checkpoint);
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

    private TaskCheckpoint loadCheckpoint(Task task) {
        if (task.getCheckpointJson() == null || task.getCheckpointJson().isBlank()) {
            return new TaskCheckpoint();
        }
        try {
            return jsonUtil.fromJson(task.getCheckpointJson(), TaskCheckpoint.class);
        } catch (Exception e) {
            throw new RuntimeException("Checkpoint deserialization failed for task=" + task.getTaskUuid(), e);
        }
    }

    private void saveCheckpoint(Task task, TaskCheckpoint checkpoint) {
        task.setCheckpointJson(jsonUtil.toJson(checkpoint));
        task.setStatus(checkpoint.getCurrentState());
        taskMapper.updateCheckpoint(task);
    }

    private void sendStateChange(String taskUuid, TaskCheckpoint checkpoint, String status) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("status", status);
        payload.put("step", checkpoint.getCurrentStepIndex());
        payload.put("totalSteps", checkpoint.totalPlannedSteps());
        payload.put("pendingInputType", checkpoint.getPendingInputType());
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
            ps.setEstimatedDurationMin(ss != null ? ss.estimatedDurationMin() : 90);
            ps.setLlmDescription(ss != null ? ss.llmDescription() : null);

            Map<String, Object> toolResults = cs.getToolCallResults();
            if (toolResults != null) {
                Map<?, ?> traffic = (Map<?, ?>) toolResults.get(TrafficTimeTool.NAME);
                if (traffic != null && traffic.get("durationMin") != null) {
                    ps.setTrafficTimeFromPrev(((Number) traffic.get("durationMin")).intValue());
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

    private CompletedStep buildCompletedStep(int stepIndex, int dayNumber, String attractionName,
                                             double lat, double lng,
                                             Map<String, Object> geocodeResult,
                                             Map<String, Object> weatherResult,
                                             Map<String, Object> trafficResult) {
        CompletedStep step = new CompletedStep();
        step.setStepIndex(stepIndex);
        step.setDayNumber(dayNumber);
        step.setAttractionName(attractionName);
        step.setLat(lat);
        step.setLng(lng);

        Map<String, Object> toolResults = new HashMap<>();
        if (geocodeResult != null) {
            toolResults.put(GeocodeTool.NAME, geocodeResult);
        }
        if (weatherResult != null) {
            toolResults.put(WeatherTool.NAME, weatherResult);
        }
        if (trafficResult != null) {
            toolResults.put(TrafficTimeTool.NAME, trafficResult);
        }
        step.setToolCallResults(toolResults);
        return step;
    }

    private int resolveUserLevel(Long userId) {
        try {
            var user = userMapper.findById(userId);
            return user != null ? user.getUserLevel() : 1;
        } catch (Exception e) {
            return 1;
        }
    }
}
