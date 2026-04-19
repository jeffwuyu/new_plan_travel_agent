package com.travelagent.service.agent.impl;

import com.travelagent.agent.context.CompletedStep;
import com.travelagent.agent.context.PendingToolCall;
import com.travelagent.agent.context.RetryState;
import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.agent.planner.MarkovPlanner;
import com.travelagent.agent.statemachine.AgentEvent;
import com.travelagent.agent.statemachine.AgentStateMachine;
import com.travelagent.agent.tools.GeocodeTool;
import com.travelagent.agent.tools.ToolRegistry;
import com.travelagent.agent.tools.TrafficTimeTool;
import com.travelagent.agent.tools.WeatherTool;
import com.travelagent.exception.QuotaExhaustedException;
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
import com.travelagent.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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

    @Autowired private TaskMapper taskMapper;
    @Autowired private AgentStateMachine stateMachine;
    @Autowired private SseNotificationService sseNotificationService;
    @Autowired private MarkovPlanner markovPlanner;
    @Autowired private ToolRegistry toolRegistry;
    @Autowired private QuotaService quotaService;
    @Autowired private JsonUtil jsonUtil;
    @Autowired private PlanMapper planMapper;
    @Autowired private UserMapper userMapper;

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

        try {
            runPlanningLoop(task, taskUuid, current);
        } catch (Exception e) {
            log.error("[AgentService] Unhandled error in task={}: {}", taskUuid, e.getMessage(), e);
            markFailed(task, taskUuid, e.getMessage());
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

        // Resume: replay any tool call that was interrupted mid-execution
        if (checkpoint.getPendingToolCall() != null) {
            replayPendingToolCall(task, checkpoint, taskUuid);
        }

        // Main Markov planning loop
        while (!checkpoint.isAllStepsDone()) {
            int stepIndex = checkpoint.getCurrentStepIndex();
            int dayNumber = (stepIndex / checkpoint.getPlanningConfig().getAttractionsPerDay()) + 1;

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
                attractionName = markovPlanner.planNextAttraction(task, checkpoint, taskUuid);
            } catch (Exception e) {
                handleRetryOrFail(task, checkpoint, taskUuid, e.getMessage());
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
                handleRetryOrFail(task, checkpoint, taskUuid, e.getMessage());
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

        Map<String, Object> result = (Map<String, Object>) toolRegistry
                .getTool(toolName)
                .execute(arguments, idempotencyKey);

        checkpoint.setPendingToolCall(null);
        saveCheckpoint(task, checkpoint);
        return result;
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

        sseNotificationService.sendEvent(taskUuid, SseEvent.PAUSED, Map.of(
                "reason", "daily_quota_exhausted",
                "resumableAt", tomorrow.toString()
        ));
        log.info("[AgentService] Task {} paused due to quota exhaustion; resumable at {}",
                taskUuid, tomorrow);
    }

    /**
     * Increments retry counter. If exhausted, marks the task as FAILED;
     * otherwise saves the checkpoint and returns (the dispatcher will re-queue).
     */
    private void handleRetryOrFail(Task task, TaskCheckpoint checkpoint,
                                   String taskUuid, String errorMsg) {
        if (checkpoint.getRetryState() == null) {
            checkpoint.setRetryState(new RetryState());
        }
        checkpoint.getRetryState().increment();
        if (checkpoint.getRetryState().isExhausted()) {
            markFailed(task, taskUuid, "Retry budget exhausted: " + errorMsg);
        } else {
            log.warn("[AgentService] Step failed for task={}, retry {}/{}: {}",
                    taskUuid, checkpoint.getRetryState().getCurrentStepRetryCount(),
                    checkpoint.getRetryState().getMaxRetries(), errorMsg);
            saveCheckpoint(task, checkpoint);
        }
    }

    private void markFailed(Task task, String taskUuid, String errorMsg) {
        try {
            Task fresh = taskMapper.findByUuid(taskUuid);
            if (fresh != null && !TaskStatus.fromCode(fresh.getStatus()).isTerminal()) {
                fresh.setErrorMessage(errorMsg);
                taskMapper.updateStatus(fresh.getId(), TaskStatus.FAILED.getCode());
                fresh.setStatus(TaskStatus.FAILED.getCode());
                taskMapper.update(fresh);
                sseNotificationService.sendEvent(taskUuid, SseEvent.ERROR,
                        Map.of("message", errorMsg));
                sseNotificationService.completeEmitter(taskUuid);
            }
        } catch (Exception ex) {
            log.error("[AgentService] Failed to mark task={} as FAILED: {}",
                    taskUuid, ex.getMessage());
        }
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
     * Returns the generated plan ID (used in the COMPLETED SSE event).
     */
    private Long persistPlan(Task task, TaskCheckpoint checkpoint) {
        Plan plan = new Plan();
        plan.setTaskId(task.getId());
        plan.setUserId(task.getUserId());
        plan.setRegion(checkpoint.getRegion());
        plan.setTitle(checkpoint.getRegion() + " "
                + checkpoint.getPlanningConfig().getTotalDays() + "-Day Trip");
        plan.setTotalDays(checkpoint.getPlanningConfig().getTotalDays());
        plan.setSummary(checkpoint.getUserIntent());
        planMapper.insertPlan(plan);

        List<PlanStep> steps = buildPlanSteps(plan.getId(), checkpoint.getCompletedSteps());
        if (!steps.isEmpty()) {
            planMapper.insertSteps(steps);
        }
        log.info("[AgentService] Persisted plan id={} with {} steps for task={}",
                plan.getId(), steps.size(), task.getTaskUuid());
        return plan.getId();
    }

    private List<PlanStep> buildPlanSteps(Long planId, List<CompletedStep> completedSteps) {
        List<PlanStep> steps = new ArrayList<>();
        for (CompletedStep cs : completedSteps) {
            PlanStep ps = new PlanStep();
            ps.setPlanId(planId);
            ps.setStepOrder(cs.getStepIndex());
            ps.setDayNumber(cs.getDayNumber());
            ps.setAttractionName(cs.getAttractionName());
            ps.setLatitude(cs.getLat() != null ? BigDecimal.valueOf(cs.getLat()) : null);
            ps.setLongitude(cs.getLng() != null ? BigDecimal.valueOf(cs.getLng()) : null);
            ps.setEstimatedDurationMin(90); // default 90 min per attraction

            Map<String, Object> toolResults = cs.getToolCallResults();
            if (toolResults != null) {
                Map<?, ?> traffic = (Map<?, ?>) toolResults.get(TrafficTimeTool.NAME);
                if (traffic != null && traffic.get("durationMin") != null) {
                    ps.setTrafficTimeFromPrev(((Number) traffic.get("durationMin")).intValue());
                }
                Map<?, ?> weather = (Map<?, ?>) toolResults.get(WeatherTool.NAME);
                if (weather != null) {
                    String weatherNote = weather.getOrDefault("weather", "").toString().trim()
                            + " " + weather.getOrDefault("temperature", "").toString().trim() + "°C";
                    ps.setWeatherNote(weatherNote.trim());
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
