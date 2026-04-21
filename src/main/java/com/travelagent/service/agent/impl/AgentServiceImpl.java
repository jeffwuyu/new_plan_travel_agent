package com.travelagent.service.agent.impl;

import com.travelagent.agent.context.CompletedStep;
import com.travelagent.agent.context.PendingToolCall;
import com.travelagent.agent.context.RetryState;
import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.config.DatabaseSchemaGuard;
import com.travelagent.agent.planner.FinalSummaryResult;
import com.travelagent.agent.planner.MarkovPlanner;
import com.travelagent.agent.planner.PlanningResult;
import com.travelagent.agent.statemachine.AgentEvent;
import com.travelagent.agent.statemachine.AgentStateMachine;
import com.travelagent.agent.tools.GeocodeTool;
import com.travelagent.agent.tools.ToolRegistry;
import com.travelagent.agent.tools.TrafficTimeTool;
import com.travelagent.agent.tools.WeatherTool;
import com.travelagent.exception.AgentException;
import com.travelagent.exception.QuotaExhaustedException;
import com.travelagent.service.task.TaskProgressService;
import com.travelagent.mapper.PlanMapper;
import com.travelagent.mapper.TaskMapper;
import com.travelagent.mapper.UserMapper;
import com.travelagent.model.entity.Plan;
import com.travelagent.model.entity.PlanStep;
import com.travelagent.model.entity.Task;
import com.travelagent.model.enums.TaskStatus;
import com.travelagent.service.agent.AgentService;
import com.travelagent.service.notification.SseEvent;
import com.travelagent.service.notification.SseNotificationService;
import com.travelagent.service.user.QuotaService;
import com.travelagent.monitoring.TaskMetricsService;
import com.travelagent.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates the full agent task lifecycle: PENDING/RESUMING → planning loop → COMPLETED/PAUSED/FAILED.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Load and persist {@link TaskCheckpoint} (断点续传 — breakpoint-resume).</li>
 *   <li>Drive the {@link AgentStateMachine} through state transitions.</li>
 *   <li>Delegate LLM attraction selection to {@link MarkovPlanner}.</li>
 *   <li>Execute agent tools (geocode / weather / traffic) with checkpoint save around each call.</li>
 *   <li>Handle {@link QuotaExhaustedException}: transition to PAUSED, save checkpoint, notify client.</li>
 *   <li>Persist the final {@link Plan} and {@link PlanStep} records on completion.</li>
 *   <li>Push real-time SSE events at each transition and step.</li>
 * </ul>
 */
@Service
public class AgentServiceImpl implements AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentServiceImpl.class);

    private static final String EVT_STATE_CHANGE = "STATE_CHANGE";
    private static final String EVT_TOOL_START   = "TOOL_START";
    private static final String EVT_TOOL_DONE    = "TOOL_DONE";
    private static final String EVT_STEP_DONE    = "STEP_DONE";
    private static final String EVT_ERROR        = "ERROR";
    private static final String EVT_RETRY        = "RETRY";
    private static final String EVT_PAUSED       = "PAUSED";
    private static final String EVT_COMPLETED    = "COMPLETED";

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

    // -----------------------------------------------------------------------
    // Startup recovery
    // -----------------------------------------------------------------------

    /**
     * Scans for tasks stuck in mid-execution states after a JVM crash and transitions
     * them to RESUMING so TaskDispatcher picks them up on its next poll cycle.
     *
     * <p>Background: TaskDispatcher only polls PENDING and RESUMING. A task in PLANNING
     * or TOOL_CALLING at crash time will never be polled again without this recovery.
     *
     * <p>Uses {@code ApplicationReadyEvent} (rather than {@code @PostConstruct}) to
     * guarantee the database schema is fully initialized before the query runs. By the
     * time {@code ApplicationReadyEvent} is published, all {@code InitializingBean} beans
     * (including {@code DataSourceScriptDatabaseInitializer}) have finished executing.
     * Capped at 100 rows to avoid slow startup on a large backlog.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverStuckTasksOnStartup() {
        if (!schemaGuard.isCoreSchemaReady()) {
            log.warn("[AgentService] Startup recovery skipped - schema not initialized yet");
            return;
        }
        try {
            recoverStuckTasks();
        } catch (Exception e) {
            // Non-fatal: if the DB schema isn't ready yet (e.g. first boot before migration),
            // log a warning and continue. Tasks will need manual resume or next-restart recovery.
            log.warn("[AgentService] Startup recovery skipped — DB not ready: {}", e.getMessage());
        }
    }

    /**
     * Scans for tasks stuck in mid-execution states after a JVM crash and transitions
     * them to RESUMING so TaskDispatcher picks them up on its next poll cycle.
     *
     * <p>Background: TaskDispatcher only polls PENDING and RESUMING. A task in PLANNING
     * or TOOL_CALLING at crash time will never be polled again without this recovery.
     *
     * <p>Called automatically at startup via {@link #recoverStuckTasksOnStartup()}, and
     * can also be invoked directly in tests or admin tooling.
     * Capped at 100 rows to avoid slow startup on a large backlog.
     */
    public void recoverStuckTasks() {
        List<String> stuckStatuses = List.of(
                TaskStatus.PLANNING.getCode(),
                TaskStatus.TOOL_CALLING.getCode()
        );
        List<Task> stuckTasks = taskMapper.findByStatusIn(stuckStatuses, 100);
        if (stuckTasks.isEmpty()) {
            log.info("[AgentService] Startup recovery: no stuck tasks found.");
            return;
        }
        log.warn("[AgentService] Startup recovery: found {} stuck task(s) in states {}. "
                + "Transitioning to RESUMING.", stuckTasks.size(), stuckStatuses);
        for (Task task : stuckTasks) {
            try {
                taskMapper.updateStatus(task.getId(), TaskStatus.RESUMING.getCode());
                log.info("[AgentService] Recovered task uuid={} ({} → resuming)",
                        task.getTaskUuid(), task.getStatus());
            } catch (Exception e) {
                log.error("[AgentService] Failed to recover task uuid={}: {}",
                        task.getTaskUuid(), e.getMessage());
            }
        }
    }

    // -----------------------------------------------------------------------
    // AgentService interface
    // -----------------------------------------------------------------------

    @Override
    public void executeTask(String taskUuid) {
        log.info("[AgentService] Starting task execution: uuid={}", taskUuid);

        Task task = taskMapper.findByUuid(taskUuid);
        if (task == null) {
            log.error("[AgentService] Task not found: uuid={}", taskUuid);
            return;
        }

        TaskStatus current = TaskStatus.fromCode(task.getStatus());
        if (current != TaskStatus.PENDING && current != TaskStatus.RESUMING) {
            log.warn("[AgentService] Skipping task in unexpected status={} uuid={}",
                    current.getCode(), taskUuid);
            return;
        }

        MDC.put("taskUuid", taskUuid);
        try {
            taskMetricsService.recordTaskStarted();
            runPlanningLoop(task, taskUuid, current);
        } catch (Exception e) {
            log.error("[AgentService] Unhandled error in task={}: {}", taskUuid, e.getMessage(), e);
            Map<String, Object> payload = (e instanceof AgentException ae)
                    ? ae.toEventPayload()
                    : Map.of("code", "UNKNOWN", "message", String.valueOf(e.getMessage()), "retryable", false);
            markFailed(task, taskUuid, e.getMessage(), payload);
        } finally {
            MDC.remove("taskUuid");
        }
    }

    // -----------------------------------------------------------------------
    // Core planning loop
    // -----------------------------------------------------------------------

    private void runPlanningLoop(Task task, String taskUuid, TaskStatus current) {
        TaskCheckpoint checkpoint = loadCheckpoint(task);
        int userLevel = resolveUserLevel(task.getUserId());

        // Transition to PLANNING
        TaskStatus planning = stateMachine.transition(current, AgentEvent.START_PLANNING);
        task.setStatus(planning.getCode());
        checkpoint.setCurrentState(planning.getCode());
        taskMapper.updateStatus(task.getId(), planning.getCode());
        sseNotificationService.sendEvent(taskUuid, SseEvent.STATE_CHANGE, Map.of(
                "status", planning.getCode(),
                "step", checkpoint.getCurrentStepIndex(),
                "totalSteps", checkpoint.totalPlannedSteps()
        ));
        taskProgressService.recordEvent(taskUuid, EVT_STATE_CHANGE, planning.getCode(),
                checkpoint.getCurrentStepIndex(), checkpoint.totalPlannedSteps(),
                "Task execution started", null);

        // Resume: replay any tool call that was interrupted mid-execution
        if (checkpoint.getPendingToolCall() != null) {
            replayPendingToolCall(task, checkpoint, taskUuid);
        }

        // Main Markov planning loop
        while (!checkpoint.isAllStepsDone()) {
            int stepIndex = checkpoint.getCurrentStepIndex();
            int dayNumber = (stepIndex / checkpoint.getPlanningConfig().getAttractionsPerDay()) + 1;

            taskProgressService.recordEvent(taskUuid, EVT_STATE_CHANGE, planning.getCode(),
                    stepIndex, checkpoint.totalPlannedSteps(),
                    "Starting step " + stepIndex, null);

            // --- Quota check before each LLM call ---
            try {
                quotaService.checkDailyQuota(task.getUserId(), userLevel);
            } catch (QuotaExhaustedException e) {
                handleQuotaExhaustion(task, checkpoint, taskUuid);
                return;
            }

            // --- LLM planning step (via MarkovPlanner) ---
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
                task.setTotalTokensUsed(
                        (task.getTotalTokensUsed() == null ? 0 : task.getTotalTokensUsed()) + tokensUsed);
            } catch (QuotaExhaustedException e) {
                handleQuotaExhaustion(task, checkpoint, taskUuid);
                return;
            } catch (Exception e) {
                handleRetryOrFail(task, checkpoint, taskUuid, e);
                return;
            }

            // --- Transition to TOOL_CALLING ---
            TaskStatus toolCalling = stateMachine.transition(TaskStatus.PLANNING, AgentEvent.START_TOOL_CALL);
            task.setStatus(toolCalling.getCode());
            checkpoint.setCurrentState(toolCalling.getCode());
            taskMapper.updateStatus(task.getId(), toolCalling.getCode());
            sseNotificationService.sendEvent(taskUuid, SseEvent.STATE_CHANGE,
                    Map.of("status", toolCalling.getCode(), "step", stepIndex));

            // --- Tool calls with checkpoint save around each ---
            Map<String, Object> geocodeResult;
            Map<String, Object> weatherResult;
            Map<String, Object> trafficResult = null;

            try {
                // 1. Geocode: name → coordinates + adcode
                Map<String, Object> geocodeArgs = Map.of(
                        "name", attractionName, "region", checkpoint.getRegion());
                geocodeResult = runToolWithCheckpoint(
                        task, checkpoint, GeocodeTool.NAME, geocodeArgs, taskUuid, stepIndex);

                // 2. Weather: adcode → conditions
                String adcode = (String) geocodeResult.getOrDefault("adcode", "");
                if (adcode.isBlank()) {
                    weatherResult = Map.of("weather", "Unknown", "temperature", "",
                            "windDirection", "", "windPower", "", "humidity", "");
                } else {
                    Map<String, Object> weatherArgs = Map.of("adcode", adcode);
                    weatherResult = runToolWithCheckpoint(
                            task, checkpoint, WeatherTool.NAME, weatherArgs, taskUuid, stepIndex);
                }

                // 3. Traffic: previous attraction → this one (skipped for step 0)
                if (stepIndex > 0) {
                    CompletedStep prevStep = checkpoint.getCompletedSteps().get(stepIndex - 1);
                    Map<String, Object> trafficArgs = new LinkedHashMap<>();
                    trafficArgs.put("originLng", prevStep.getLng());
                    trafficArgs.put("originLat", prevStep.getLat());
                    trafficArgs.put("destLng", geocodeResult.get("lng"));
                    trafficArgs.put("destLat", geocodeResult.get("lat"));
                    trafficResult = runToolWithCheckpoint(
                            task, checkpoint, TrafficTimeTool.NAME, trafficArgs, taskUuid, stepIndex);
                }

            } catch (QuotaExhaustedException e) {
                handleQuotaExhaustion(task, checkpoint, taskUuid);
                return;
            } catch (Exception e) {
                handleRetryOrFail(task, checkpoint, taskUuid, e);
                return;
            }

            // Broadcast tool results to SSE subscribers
            Map<String, Object> toolResults = new HashMap<>();
            toolResults.put("geocode", geocodeResult);
            toolResults.put("weather", weatherResult);
            toolResults.put("traffic_time", trafficResult);
            sseNotificationService.sendEvent(taskUuid, SseEvent.TOOL_RESULT, toolResults);

            // --- Transition back to PLANNING ---
            TaskStatus backToPlanning = stateMachine.transition(
                    TaskStatus.TOOL_CALLING, AgentEvent.TOOL_CALL_DONE);
            task.setStatus(backToPlanning.getCode());
            checkpoint.setCurrentState(backToPlanning.getCode());
            taskMapper.updateStatus(task.getId(), backToPlanning.getCode());
            sseNotificationService.sendEvent(taskUuid, SseEvent.STATE_CHANGE,
                    Map.of("status", backToPlanning.getCode(), "step", stepIndex));

            // --- Commit completed step to checkpoint ---
            double lat = ((Number) geocodeResult.get("lat")).doubleValue();
            double lng = ((Number) geocodeResult.get("lng")).doubleValue();
            CompletedStep step = buildCompletedStep(
                    stepIndex, dayNumber, attractionName, lat, lng,
                    geocodeResult, weatherResult, trafficResult);
            checkpoint.getCompletedSteps().add(step);
            checkpoint.setCurrentStepIndex(stepIndex + 1);
            checkpoint.setPendingToolCall(null);
            if (checkpoint.getRetryState() != null) {
                checkpoint.getRetryState().reset();
            }
            saveCheckpoint(task, checkpoint);

            int trafficMin = trafficResult != null
                    ? ((Number) trafficResult.getOrDefault("durationMin", 0)).intValue() : 0;
            sseNotificationService.sendEvent(taskUuid, SseEvent.STEP_DONE, Map.of(
                    "stepIndex", stepIndex,
                    "attractionName", attractionName,
                    "trafficTimeMin", trafficMin,
                    "dayNumber", dayNumber
            ));
            taskProgressService.recordEvent(taskUuid, EVT_STEP_DONE, planning.getCode(),
                    stepIndex, checkpoint.totalPlannedSteps(),
                    "Step " + stepIndex + " completed: " + attractionName,
                    Map.of("attractionName", attractionName, "dayNumber", dayNumber));
        }

        // --- All steps done: persist plan and transition to COMPLETED ---
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

    // -----------------------------------------------------------------------
    // Tool execution with checkpoint guard
    // -----------------------------------------------------------------------

    /**
     * Executes a named tool, wrapping the call with checkpoint saves:
     * <ol>
     *   <li>Save {@code pendingToolCall} before execution (so a crash can be replayed on resume).</li>
     *   <li>Clear {@code pendingToolCall} after success.</li>
     * </ol>
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> runToolWithCheckpoint(Task task, TaskCheckpoint checkpoint,
                                                      String toolName,
                                                      Map<String, Object> arguments,
                                                      String taskUuid,
                                                      int stepIndex) {
        String idempotencyKey = taskUuid + "-step" + stepIndex + "-" + toolName;
        checkpoint.setPendingToolCall(new PendingToolCall(toolName, arguments, idempotencyKey));
        saveCheckpoint(task, checkpoint);

        taskProgressService.recordEvent(taskUuid, EVT_TOOL_START, null,
                stepIndex, null, "Calling tool: " + toolName, arguments);

        boolean toolSuccess = true;
        try {
            Map<String, Object> result = (Map<String, Object>) toolRegistry
                    .getTool(toolName)
                    .execute(arguments, idempotencyKey);

            checkpoint.setPendingToolCall(null);
            saveCheckpoint(task, checkpoint);

            taskProgressService.recordEvent(taskUuid, EVT_TOOL_DONE, null,
                    stepIndex, null, "Tool completed: " + toolName, result);
            return result;
        } catch (Exception e) {
            toolSuccess = false;
            throw e;
        } finally {
            taskMetricsService.recordToolCall(toolSuccess);
        }
    }

    /**
     * On resume, replays the {@code pendingToolCall} saved in the checkpoint.
     * The tool's idempotency key ensures the external API is not called twice.
     */
    private void replayPendingToolCall(Task task, TaskCheckpoint checkpoint, String taskUuid) {
        PendingToolCall pending = checkpoint.getPendingToolCall();
        try {
            toolRegistry.getTool(pending.getToolName())
                    .execute(pending.getArguments(), pending.getIdempotencyKey());
            checkpoint.setPendingToolCall(null);
            saveCheckpoint(task, checkpoint);
        } catch (Exception e) {
            log.warn("[AgentService] Replay of pending tool {} failed for task={}: {}",
                    pending.getToolName(), taskUuid, e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Error / quota handling
    // -----------------------------------------------------------------------

    /**
     * Handles quota exhaustion: saves checkpoint, transitions task to PAUSED,
     * computes resumable time (next midnight Asia/Shanghai), and notifies via SSE.
     */
    private void handleQuotaExhaustion(Task task, TaskCheckpoint checkpoint, String taskUuid) {
        LocalDateTime tomorrow = LocalDateTime.now()
                .plusDays(1).withHour(0).withMinute(0).withSecond(0).withNano(0);

        checkpoint.setCurrentState(TaskStatus.PAUSED.getCode());
        checkpoint.setResumableAt(tomorrow);
        saveCheckpoint(task, checkpoint);

        TaskStatus paused = stateMachine.transition(TaskStatus.PLANNING, AgentEvent.QUOTA_EXHAUSTED);
        taskMapper.updateStatus(task.getId(), paused.getCode());

        taskMetricsService.recordTaskPaused();
        taskProgressService.recordEvent(taskUuid, EVT_PAUSED, TaskStatus.PAUSED.getCode(),
                null, null, "Task paused: daily quota exhausted",
                Map.of("resumableAt", tomorrow.toString()));
        sseNotificationService.sendEvent(taskUuid, SseEvent.PAUSED, Map.of(
                "reason", "daily_quota_exhausted",
                "resumableAt", tomorrow.toString()
        ));
        log.info("[AgentService] Task {} paused due to quota exhaustion; resumable at {}",
                taskUuid, tomorrow);
    }

    /**
     * Increments retry counter. Non-retryable errors fail immediately; retryable ones consume
     * the retry budget and save checkpoint for re-queue by the dispatcher.
     */
    private void handleRetryOrFail(Task task, TaskCheckpoint checkpoint,
                                   String taskUuid, Exception exception) {
        boolean retryable = !(exception instanceof AgentException ae) || ae.isRetryable();
        Map<String, Object> errorPayload = (exception instanceof AgentException ae)
                ? ae.toEventPayload()
                : Map.of("code", "UNKNOWN", "message", String.valueOf(exception.getMessage()), "retryable", true);

        if (!retryable) {
            log.warn("[AgentService] Non-retryable error for task={}: code={} message={}",
                    taskUuid, ((AgentException) exception).getErrorCode(), exception.getMessage());
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
            int max     = checkpoint.getRetryState().getMaxRetries();
            log.warn("[AgentService] Step failed for task={}, retry {}/{}: {}",
                    taskUuid, attempt, max, exception.getMessage());
            taskProgressService.recordEvent(taskUuid, EVT_RETRY, null, null, null,
                    "Retrying: " + exception.getMessage(),
                    Map.of("attempt", attempt, "maxAttempts", max));
            saveCheckpoint(task, checkpoint);
        }
    }

    private void markFailed(Task task, String taskUuid, String errorMsg,
                            Map<String, Object> errorPayload) {
        try {
            Task fresh = taskMapper.findByUuid(taskUuid);
            if (fresh != null && !TaskStatus.fromCode(fresh.getStatus()).isTerminal()) {
                fresh.setErrorMessage(errorMsg);
                taskMapper.updateStatus(fresh.getId(), TaskStatus.FAILED.getCode());
                fresh.setStatus(TaskStatus.FAILED.getCode());
                taskMapper.update(fresh);
                taskMetricsService.recordTaskFailed();
                taskProgressService.recordEvent(taskUuid, EVT_ERROR, TaskStatus.FAILED.getCode(),
                        null, null, errorMsg, errorPayload);
                sseNotificationService.sendEvent(taskUuid, SseEvent.ERROR, errorPayload);
                sseNotificationService.completeEmitter(taskUuid);
            }
        } catch (Exception ex) {
            log.error("[AgentService] Failed to mark task={} as FAILED: {}",
                    taskUuid, ex.getMessage());
        }
    }

    private void markFailed(Task task, String taskUuid, String errorMsg) {
        markFailed(task, taskUuid, errorMsg,
                Map.of("code", "TASK_FAILED_PERMANENT", "message", String.valueOf(errorMsg), "retryable", false));
    }

    // -----------------------------------------------------------------------
    // Checkpoint helpers
    // -----------------------------------------------------------------------

    private TaskCheckpoint loadCheckpoint(Task task) {
        if (task.getCheckpointJson() == null || task.getCheckpointJson().isBlank()) {
            return new TaskCheckpoint();
        }
        try {
            return jsonUtil.fromJson(task.getCheckpointJson(), TaskCheckpoint.class);
        } catch (Exception e) {
            throw new RuntimeException("Checkpoint deserialization failed for task="
                    + task.getTaskUuid(), e);
        }
    }

    private void saveCheckpoint(Task task, TaskCheckpoint checkpoint) {
        try {
            task.setCheckpointJson(jsonUtil.toJson(checkpoint));
            task.setStatus(checkpoint.getCurrentState());
            taskMapper.updateCheckpoint(task);
        } catch (Exception e) {
            throw new RuntimeException("Checkpoint save failed for task="
                    + task.getTaskUuid(), e);
        }
    }

    // -----------------------------------------------------------------------
    // Plan persistence
    // -----------------------------------------------------------------------

    /**
     * Persists the completed plan and all its steps to MySQL.
     *
     * <p>First calls {@link MarkovPlanner#generateFinalSummary} to obtain a LLM-generated
     * title, summary, and per-step descriptions with realistic duration estimates.
     * Falls back to the previous hardcoded values if the LLM call fails.
     *
     * @return the generated plan ID (used in the COMPLETED SSE event)
     */
    private Long persistPlan(Task task, TaskCheckpoint checkpoint) {
        // Generate LLM summary — wrapped so a failure cannot prevent plan persistence
        FinalSummaryResult summary;
        try {
            summary = markovPlanner.generateFinalSummary(task, checkpoint, task.getTaskUuid());
        } catch (Exception e) {
            log.warn("[AgentService] Final summary generation failed, using defaults: {}", e.getMessage());
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
            plan.setTitle(checkpoint.getRegion() + " "
                    + checkpoint.getPlanningConfig().getTotalDays() + "-Day Trip");
            plan.setSummary(checkpoint.getUserIntent());
        }
        planMapper.insertPlan(plan);

        List<FinalSummaryResult.StepSummary> stepSummaries =
                (summary != null) ? summary.steps() : List.of();
        List<PlanStep> steps = buildPlanSteps(plan.getId(), checkpoint.getCompletedSteps(), stepSummaries);
        if (!steps.isEmpty()) {
            planMapper.insertSteps(steps);
        }
        log.info("[AgentService] Persisted plan id={} with {} steps for task={}",
                plan.getId(), steps.size(), task.getTaskUuid());
        return plan.getId();
    }

    private List<PlanStep> buildPlanSteps(Long planId,
                                          List<CompletedStep> completedSteps,
                                          List<FinalSummaryResult.StepSummary> stepSummaries) {
        // Build a lookup map so we can enrich each step with the LLM's description/duration
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

            // Apply LLM-provided duration and description, falling back to 90 min / null
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
                    String weatherNote = (weatherText + " " + temperatureText + " C").trim();
                    ps.setWeatherNote(weatherNote);
                }
            }
            steps.add(ps);
        }
        return steps;
    }

    // -----------------------------------------------------------------------
    // Step builder
    // -----------------------------------------------------------------------

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
        if (geocodeResult != null) toolResults.put(GeocodeTool.NAME, geocodeResult);
        if (weatherResult != null) toolResults.put(WeatherTool.NAME, weatherResult);
        if (trafficResult != null) toolResults.put(TrafficTimeTool.NAME, trafficResult);
        step.setToolCallResults(toolResults);
        return step;
    }

    // -----------------------------------------------------------------------
    // Utility
    // -----------------------------------------------------------------------

    private int resolveUserLevel(Long userId) {
        try {
            var user = userMapper.findById(userId);
            return user != null ? user.getUserLevel() : 1;
        } catch (Exception e) {
            return 1;
        }
    }
}
