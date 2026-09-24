package com.travelagent.service.agent.impl;

import com.travelagent.agent.context.PendingToolCall;
import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.agent.observation.CleanedObservation;
import com.travelagent.agent.observation.ObservationCleaningService;
import com.travelagent.agent.scratchpad.ScratchpadManagementService;
import com.travelagent.agent.tools.AgentTool;
import com.travelagent.agent.tools.PendingToolReplayPolicy;
import com.travelagent.agent.tools.ToolGuard;
import com.travelagent.agent.tools.ToolGuardContext;
import com.travelagent.agent.tools.ToolGuardDecision;
import com.travelagent.agent.tools.ToolCallRequest;
import com.travelagent.agent.tools.ToolCallResult;
import com.travelagent.agent.tools.ToolCallStatus;
import com.travelagent.agent.tools.ToolRegistry;
import com.travelagent.agent.tools.ToolResultValidationResult;
import com.travelagent.agent.tools.ToolResultValidator;
import com.travelagent.agent.tools.WebSearchTool;
import com.travelagent.exception.RateLimitExceededException;
import com.travelagent.model.entity.Task;
import com.travelagent.model.enums.TaskStatus;
import com.travelagent.monitoring.TaskMetricsService;
import com.travelagent.mapper.ToolExecutionRecordMapper;
import com.travelagent.service.notification.SseEvent;
import com.travelagent.service.notification.SseNotificationService;
import com.travelagent.service.ratelimit.AgentRateLimitService;
import com.travelagent.service.task.TaskProgressService;
import com.travelagent.util.JsonUtil;
import com.travelagent.validation.JsonSchemaValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 负责单次工具调用的执行、幂等键生成和 pending tool call 的重放。
 */
@Component
public class AgentToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgentToolExecutor.class);

    private static final String EVT_TOOL_START = "TOOL_START";
    private static final String EVT_TOOL_DONE = "TOOL_DONE";
    private static final String EVT_TOOL_RETRY = "TOOL_RETRY";
    private static final String EVT_TOOL_DEGRADED = "TOOL_DEGRADED";
    private static final String EVT_TOOL_RESULT_VALIDATION_WARNING = "TOOL_RESULT_VALIDATION_WARNING";
    private static final String EVT_TOOL_GUARD_REQUIRES_CONFIRMATION = "TOOL_GUARD_REQUIRES_CONFIRMATION";
    private static final String EVT_TOOL_GUARD_DENIED = "TOOL_GUARD_DENIED";
    private static final String EVT_PENDING_TOOL_REPLAY_REQUIRES_CONFIRMATION = "PENDING_TOOL_REPLAY_REQUIRES_CONFIRMATION";
    private static final String PAUSE_REASON_PENDING_TOOL_CONFIRMATION = "pending_tool_replay_requires_confirmation";
    public static final String PAUSE_REASON_TOOL_GUARD_CONFIRMATION = "tool_guard_requires_confirmation";
    private static final long COMPLETED_IDEMPOTENCY_CACHE_MS = TimeUnit.MINUTES.toMillis(10);
    private static final int DURABLE_RESULT_WAIT_ATTEMPTS = 30;

    @Autowired private ToolRegistry toolRegistry;
    @Autowired private ToolGuard toolGuard;
    @Autowired private AgentCheckpointHelper checkpointHelper;
    @Autowired private TaskProgressService taskProgressService;
    @Autowired private TaskMetricsService taskMetricsService;
    @Autowired private SseNotificationService sseNotificationService;
    @Autowired private JsonUtil jsonUtil;
    @Autowired private ToolResultValidator toolResultValidator;
    @Autowired(required = false) private AgentRateLimitService agentRateLimitService;
    @Autowired(required = false) private ObservationCleaningService observationCleaningService;
    @Autowired(required = false) private ScratchpadManagementService scratchpadManagementService;
    @Autowired(required = false) private ToolExecutionRecordMapper toolExecutionRecordMapper;

    private final Map<String, InFlightToolCall> idempotentCalls = new ConcurrentHashMap<>();

    /**
     * 处理runToolWithCheckpoint。
     * @param task 任务实体
     * @param checkpoint 任务检查点数据
     * @param toolName t oo lN am e 参数
     * @param arguments 工具调用参数
     * @param taskUuid 任务唯一标识
     * @param stepIndex s te pI nd ex 参数
     * @return 返回处理后的映射结果。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> runToolWithCheckpoint(Task task, TaskCheckpoint checkpoint,
                                                      String toolName, Map<String, Object> arguments,
                                                      String taskUuid, int stepIndex) {
        String idempotencyKey = buildToolIdempotencyKey(taskUuid, stepIndex, toolName, arguments);
        ToolCallRequest request = ToolCallRequest.of(toolName, arguments, idempotencyKey);
        request.setMaxRetries(0);
        request.setDegradeOnFailure(false);
        ToolCallResult structured = runStructuredToolWithCheckpoint(task, checkpoint, request, taskUuid, stepIndex);
        if (structured.getStatus() == ToolCallStatus.FAILED) {
            throw new IllegalStateException(structured.getErrorMessage());
        }
        if (structured.getStatus() == ToolCallStatus.DEGRADED) {
            return structured.toMap();
        }
        return structured.getOutput();
    }

    public ToolCallResult runStructuredToolWithCheckpoint(Task task, TaskCheckpoint checkpoint,
                                                          ToolCallRequest request,
                                                          String taskUuid, int stepIndex) {
        if (request == null) {
            throw new IllegalArgumentException("tool call request is required");
        }
        String toolName = request.getToolName();
        Map<String, Object> arguments = request.getArguments() == null ? Map.of() : request.getArguments();
        String idempotencyKey = request.getIdempotencyKey() == null
                ? buildToolIdempotencyKey(taskUuid, stepIndex, toolName, arguments)
                : request.getIdempotencyKey();
        String argumentFingerprint = hashArguments(arguments);

        ToolCallResult durableResult = lookupDurableResult(taskUuid, idempotencyKey, argumentFingerprint, request);
        if (durableResult != null) {
            return durableResult;
        }

        InFlightToolCall candidate = new InFlightToolCall(argumentFingerprint);
        InFlightToolCall existing = idempotentCalls.putIfAbsent(idempotencyKey, candidate);
        if (existing != null) {
            if (existing.isExpired()) {
                idempotentCalls.remove(idempotencyKey, existing);
                existing = null;
            }
        }
        if (existing != null) {
            if (!existing.argumentFingerprint().equals(argumentFingerprint)) {
                return idempotencyConflict(request, "idempotency key is already bound to different arguments");
            }
            try {
                return existing.future().join();
            } catch (CompletionException failedInFlight) {
                // A failed attempt must not poison a retry with the same key.
                // The durable record is reset conditionally by the next owner.
                idempotentCalls.remove(idempotencyKey, existing);
                Throwable cause = failedInFlight.getCause();
                if (cause instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw failedInFlight;
            }
        }
        int inserted = persistToolStart(task, toolName, idempotencyKey, argumentFingerprint);
        if (inserted == 0 && toolExecutionRecordMapper != null && taskUuid != null) {
            ToolCallResult concurrentResult = awaitDurableResult(taskUuid, idempotencyKey, argumentFingerprint, request);
            if (concurrentResult != null) {
                idempotentCalls.remove(idempotencyKey, candidate);
                return concurrentResult;
            }
        }
        try {
            ToolCallResult result = executeStructuredToolWithCheckpoint(task, checkpoint, request, taskUuid, stepIndex);
            candidate.future().complete(result);
            candidate.markCompleted();
            persistToolFinish(taskUuid, idempotencyKey, result);
            return result;
        } catch (RuntimeException e) {
            candidate.future().completeExceptionally(e);
            candidate.markCompleted();
            persistToolFailure(taskUuid, idempotencyKey, e);
            throw e;
        }
    }

    private ToolCallResult executeStructuredToolWithCheckpoint(Task task, TaskCheckpoint checkpoint,
                                                               ToolCallRequest request,
                                                               String taskUuid, int stepIndex) {
        String toolName = request.getToolName();
        Map<String, Object> arguments = request.getArguments() == null ? Map.of() : request.getArguments();
        String idempotencyKey = request.getIdempotencyKey() == null
                ? buildToolIdempotencyKey(taskUuid, stepIndex, toolName, arguments)
                : request.getIdempotencyKey();
        log.debug("[AgentToolExecutor] tool={} step={} idempotencyKey={} arguments={}",
                toolName, stepIndex, idempotencyKey, arguments);
        recordScratchpadAction(checkpoint, stepIndex, toolName, arguments);
        AgentTool tool = toolRegistry.getTool(toolName);
        Instant startedAt = Instant.now();
        ToolGuardDecision guardDecision;
        try {
            guardDecision = evaluateToolGuard(task, checkpoint, tool, toolName, arguments, false, false);
        } catch (JsonSchemaValidationException e) {
            ToolCallResult result = buildFailureResult(tool, toolName, arguments, startedAt, 0, e, request.isDegradeOnFailure());
            checkpoint.setPendingToolCall(null);
            recordScratchpadObservation(checkpoint, stepIndex, toolName, result.getOutput(), result.getStatus().name());
            checkpoint.recordToolResult(toolName, result.toMap(), result.getRetryCount(), result.getErrorMessage());
            checkpointHelper.saveCheckpoint(task, checkpoint);
            if (result.getStatus() == ToolCallStatus.DEGRADED) {
                taskProgressService.recordEvent(taskUuid, EVT_TOOL_DEGRADED, null, stepIndex, null,
                        "Tool degraded: " + toolName, result.toMap());
            }
            taskMetricsService.recordToolCall(false);
            return result;
        }
        if (guardDecision.requiresConfirmation()) {
            PendingToolCall pending = new PendingToolCall(toolName, arguments, idempotencyKey);
            checkpoint.setPendingToolCall(pending);
            pauseForToolGuardConfirmation(task, checkpoint, taskUuid, pending, guardDecision);
            taskMetricsService.recordToolCall(false);
            return buildGuardBlockedResult(tool, toolName, arguments, startedAt, guardDecision, ToolCallStatus.FAILED);
        }
        if (guardDecision.denied()) {
            ToolCallResult result = buildGuardBlockedResult(tool, toolName, arguments, startedAt, guardDecision, ToolCallStatus.FAILED);
            checkpoint.setPendingToolCall(null);
            recordScratchpadObservation(checkpoint, stepIndex, toolName, result.getOutput(), result.getStatus().name());
            checkpoint.recordToolResult(toolName, result.toMap(), 0, result.getErrorMessage());
            checkpointHelper.saveCheckpoint(task, checkpoint);
            taskProgressService.recordEvent(taskUuid, EVT_TOOL_GUARD_DENIED, task == null ? null : task.getStatus(),
                    stepIndex, checkpoint.totalPlannedSteps(), "Tool guard denied: " + toolName, result.toMap());
            taskMetricsService.recordToolCall(false);
            return result;
        }

        checkpoint.setPendingToolCall(new PendingToolCall(toolName, arguments, idempotencyKey));
        checkpointHelper.saveCheckpoint(task, checkpoint);
        taskProgressService.recordEvent(taskUuid, EVT_TOOL_START, null, stepIndex, null, "Calling tool: " + toolName, arguments);

        int attempt = 0;
        Exception lastError = null;
        int maxRetries = Math.max(0, request.getMaxRetries());
        while (attempt <= maxRetries) {
            try {
                checkToolRateLimit(task);
                Map<String, Object> output = tool.execute(arguments, idempotencyKey);
                output = cleanObservationIfNeeded(task, toolName, output, idempotencyKey);
                ToolResultValidationResult validation = validateToolResult(tool, output);
                ToolCallResult result = buildSuccessResult(tool, arguments, validation.getOutput(), startedAt, attempt);
                checkpoint.setPendingToolCall(null);
                recordScratchpadObservation(checkpoint, stepIndex, toolName, result.getOutput(), result.getStatus().name());
                checkpoint.recordToolResult(toolName, result.toMap(), result.getRetryCount(), null);
                try {
                    checkpointHelper.saveCheckpoint(task, checkpoint);
                } catch (RuntimeException persistenceFailure) {
                    ToolCallResult unknown = buildUnknownResult(tool, arguments, validation.getOutput(), startedAt,
                            attempt, persistenceFailure);
                    checkpoint.setPendingToolCall(null);
                    recordScratchpadObservation(checkpoint, stepIndex, toolName, unknown.getOutput(), unknown.getStatus().name());
                    checkpoint.recordToolResult(toolName, unknown.toMap(), unknown.getRetryCount(), unknown.getErrorMessage());
                    taskMetricsService.recordToolCall(false);
                    return unknown;
                }
                recordResultValidationWarning(taskUuid, stepIndex, toolName, validation);
                taskProgressService.recordEvent(taskUuid, EVT_TOOL_DONE, null, stepIndex, null,
                        "Tool completed: " + toolName, result.toMap());
                taskMetricsService.recordToolCall(true);
                return result;
            } catch (RateLimitExceededException e) {
                checkpoint.setPendingToolCall(null);
                checkpointHelper.saveCheckpoint(task, checkpoint);
                taskMetricsService.recordToolCall(false);
                throw e;
            } catch (Exception e) {
                lastError = e;
                if (attempt < maxRetries) {
                    attempt++;
                    Map<String, Object> retryPayload = Map.of(
                            "toolName", toolName,
                            "retryCount", attempt,
                            "maxRetries", maxRetries,
                            "errorMessage", String.valueOf(e.getMessage())
                    );
                    taskProgressService.recordEvent(taskUuid, EVT_TOOL_RETRY, null, stepIndex, null,
                            "Retrying tool: " + toolName, retryPayload);
                    continue;
                }
                ToolCallResult result = buildFailureResult(tool, toolName, arguments, startedAt, attempt, e, request.isDegradeOnFailure());
                if (result.getStatus() == ToolCallStatus.DEGRADED) {
                    checkpoint.setPendingToolCall(null);
                    recordScratchpadObservation(checkpoint, stepIndex, toolName, result.getOutput(), result.getStatus().name());
                    checkpoint.recordToolResult(toolName, result.toMap(), result.getRetryCount(), result.getErrorMessage());
                    checkpointHelper.saveCheckpoint(task, checkpoint);
                    taskProgressService.recordEvent(taskUuid, EVT_TOOL_DEGRADED, null, stepIndex, null,
                            "Tool degraded: " + toolName, result.toMap());
                    taskMetricsService.recordToolCall(false);
                    return result;
                }
                checkpoint.setPendingToolCall(null);
                recordScratchpadObservation(checkpoint, stepIndex, toolName, result.getOutput(), result.getStatus().name());
                checkpoint.recordToolResult(toolName, result.toMap(), result.getRetryCount(), result.getErrorMessage());
                checkpointHelper.saveCheckpoint(task, checkpoint);
                taskMetricsService.recordToolCall(false);
                throw e;
            }
        }
        ToolCallResult result = buildFailureResult(tool, toolName, arguments, startedAt, maxRetries, lastError, request.isDegradeOnFailure());
        recordScratchpadObservation(checkpoint, stepIndex, toolName, result.getOutput(), result.getStatus().name());
        checkpoint.recordToolResult(toolName, result.toMap(), result.getRetryCount(), result.getErrorMessage());
        checkpointHelper.saveCheckpoint(task, checkpoint);
        taskMetricsService.recordToolCall(false);
        return result;
    }

    /**
     * 处理replayPendingToolCall。
     * @param task 任务实体
     * @param checkpoint 任务检查点数据
     * @param taskUuid 任务唯一标识
     */
    public PendingToolReplayResult replayPendingToolCall(Task task, TaskCheckpoint checkpoint, String taskUuid) {
        PendingToolCall pending = checkpoint.getPendingToolCall();
        if (pending == null) {
            return PendingToolReplayResult.NONE;
        }
        AgentTool tool = toolRegistry.getTool(pending.getToolName());
        ToolGuardDecision guardDecision;
        try {
            guardDecision = evaluateToolGuard(task, checkpoint, tool, pending.getToolName(),
                    pending.getArguments(), pending.isManualReplayApproved(), true);
        } catch (JsonSchemaValidationException e) {
            log.warn("[AgentToolExecutor] Replay of pending tool {} failed schema validation for task={}: {}",
                    pending.getToolName(), taskUuid, e.getMessage());
            return PendingToolReplayResult.FAILED;
        }
        if (guardDecision.requiresConfirmation()) {
            pauseForToolGuardConfirmation(task, checkpoint, taskUuid, pending, guardDecision);
            return PendingToolReplayResult.PAUSED_FOR_CONFIRMATION;
        }
        if (guardDecision.denied()) {
            log.warn("[AgentToolExecutor] Replay of pending tool {} denied by guard for task={}: {}",
                    pending.getToolName(), taskUuid,
                    guardDecision.violation() == null ? "denied" : guardDecision.violation().message());
            checkpoint.setPendingToolCall(null);
            checkpoint.recordFailure("Tool guard denied " + pending.getToolName() + ": "
                    + (guardDecision.violation() == null ? "denied" : guardDecision.violation().message()));
            checkpointHelper.saveCheckpoint(task, checkpoint);
            return PendingToolReplayResult.FAILED;
        }
        if (tool.getReplayPolicy() == PendingToolReplayPolicy.REQUIRE_MANUAL_CONFIRMATION
                && !pending.isManualReplayApproved()) {
            pauseForManualReplayConfirmation(task, checkpoint, taskUuid, pending, tool);
            return PendingToolReplayResult.PAUSED_FOR_CONFIRMATION;
        }
        try {
            checkToolRateLimit(task);
            tool.execute(pending.getArguments(), pending.getIdempotencyKey());
            checkpoint.setPendingToolCall(null);
            checkpointHelper.saveCheckpoint(task, checkpoint);
            return PendingToolReplayResult.REPLAYED;
        } catch (RateLimitExceededException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[AgentToolExecutor] Replay of pending tool {} failed for task={}: {}",
                    pending.getToolName(), taskUuid, e.getMessage());
            return PendingToolReplayResult.FAILED;
        }
    }

    private ToolGuardDecision evaluateToolGuard(Task task, TaskCheckpoint checkpoint, AgentTool tool,
                                                String toolName, Map<String, Object> arguments,
                                                boolean manualConfirmationApproved,
                                                boolean replay) {
        if (toolGuard == null) {
            return ToolGuardDecision.allow();
        }
        return toolGuard.evaluate(new ToolGuardContext(
                task,
                checkpoint,
                tool,
                toolName,
                arguments == null ? Map.of() : arguments,
                manualConfirmationApproved,
                replay));
    }

    private void recordScratchpadAction(TaskCheckpoint checkpoint,
                                        int stepIndex,
                                        String toolName,
                                        Map<String, Object> arguments) {
        if (scratchpadManagementService != null) {
            scratchpadManagementService.recordAction(checkpoint, stepIndex, toolName, arguments);
        }
    }

    private void recordScratchpadObservation(TaskCheckpoint checkpoint,
                                             int stepIndex,
                                             String toolName,
                                             Map<String, Object> observation,
                                             String status) {
        if (scratchpadManagementService != null) {
            scratchpadManagementService.recordObservation(checkpoint, stepIndex, toolName, observation, status);
        }
    }

    private void checkToolRateLimit(Task task) {
        if (agentRateLimitService != null) {
            agentRateLimitService.checkToolLimit(task == null ? null : task.getUserId(),
                    task == null ? null : task.getRequestIp());
        }
    }

    private ToolResultValidationResult validateToolResult(AgentTool tool, Map<String, Object> output) {
        if (toolResultValidator == null) {
            Map<String, Object> safeOutput = output == null ? Map.of() : new LinkedHashMap<>(output);
            return new ToolResultValidationResult(true, false, Instant.now(), List.of(), safeOutput);
        }
        return toolResultValidator.validate(tool.getName(), output);
    }

    private Map<String, Object> cleanObservationIfNeeded(Task task,
                                                         String toolName,
                                                         Map<String, Object> output,
                                                         String idempotencyKey) {
        if (observationCleaningService == null
                || output == null
                || !WebSearchTool.NAME.equals(toolName)
                || !observationCleaningService.isEnabled()) {
            return output;
        }
        boolean rawStored = false;
        if (observationCleaningService.shouldStoreRawArtifact()) {
            rawStored = checkpointHelper.writeRawObservationArtifact(task, "raw_web_observation", output);
        }
        CleanedObservation cleaned = observationCleaningService.cleanWebObservation(
                output,
                task == null ? null : task.getId(),
                task == null ? null : task.getUserId(),
                idempotencyKey);
        return observationCleaningService.markRawArtifactStored(cleaned.toolOutput(), rawStored);
    }

    private void recordResultValidationWarning(String taskUuid, int stepIndex, String toolName,
                                               ToolResultValidationResult validation) {
        if (validation == null || !validation.hasIssues()) {
            return;
        }
        Map<String, Object> payload = validation.toEventPayload(toolName);
        String message = validation.isValid()
                ? "Tool result validation sanitized: " + toolName
                : "Tool result validation warning: " + toolName;
        payload.put("message", message);
        taskProgressService.recordEvent(taskUuid, EVT_TOOL_RESULT_VALIDATION_WARNING, null, stepIndex, null,
                message, payload);
        if (sseNotificationService != null) {
            sseNotificationService.sendEvent(taskUuid, SseEvent.TOOL_RESULT_VALIDATION_WARNING, payload);
        }
    }

    private void pauseForManualReplayConfirmation(Task task, TaskCheckpoint checkpoint, String taskUuid,
                                                  PendingToolCall pending, AgentTool tool) {
        checkpoint.setCurrentState(TaskStatus.PAUSED.getCode());
        checkpoint.setPauseReason(PAUSE_REASON_PENDING_TOOL_CONFIRMATION);
        checkpointHelper.saveCheckpoint(task, checkpoint);
        task.setStatus(TaskStatus.PAUSED.getCode());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reason", PAUSE_REASON_PENDING_TOOL_CONFIRMATION);
        payload.put("taskUuid", taskUuid);
        payload.put("toolName", pending.getToolName());
        payload.put("idempotencyKey", pending.getIdempotencyKey());
        payload.put("replayPolicy", tool.getReplayPolicy().name());
        payload.put("retryable", true);
        payload.put("message", "Pending tool replay requires manual confirmation.");

        taskProgressService.recordEvent(taskUuid, EVT_PENDING_TOOL_REPLAY_REQUIRES_CONFIRMATION,
                TaskStatus.PAUSED.getCode(), checkpoint.getCurrentStepIndex(), checkpoint.totalPlannedSteps(),
                "Pending tool replay requires manual confirmation: " + pending.getToolName(), payload);
        sseNotificationService.sendEvent(taskUuid, SseEvent.PAUSED, payload);
        sseNotificationService.sendEvent(taskUuid, SseEvent.STATE_CHANGE, Map.of(
                "status", TaskStatus.PAUSED.getCode(),
                "taskUuid", taskUuid,
                "pauseReason", PAUSE_REASON_PENDING_TOOL_CONFIRMATION,
                "pendingToolName", pending.getToolName()
        ));
    }

    private void pauseForToolGuardConfirmation(Task task, TaskCheckpoint checkpoint, String taskUuid,
                                               PendingToolCall pending, ToolGuardDecision decision) {
        checkpoint.setCurrentState(TaskStatus.PAUSED.getCode());
        checkpoint.setPauseReason(PAUSE_REASON_TOOL_GUARD_CONFIRMATION);
        checkpointHelper.saveCheckpoint(task, checkpoint);
        task.setStatus(TaskStatus.PAUSED.getCode());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reason", PAUSE_REASON_TOOL_GUARD_CONFIRMATION);
        payload.put("taskUuid", taskUuid);
        payload.put("toolName", pending.getToolName());
        payload.put("idempotencyKey", pending.getIdempotencyKey());
        payload.put("retryable", true);
        payload.put("guard", toolGuard == null ? Map.of() : toolGuard.violationPayload(decision));
        payload.put("message", "Tool guard requires manual confirmation.");

        taskProgressService.recordEvent(taskUuid, EVT_TOOL_GUARD_REQUIRES_CONFIRMATION,
                TaskStatus.PAUSED.getCode(), checkpoint.getCurrentStepIndex(), checkpoint.totalPlannedSteps(),
                "Tool guard requires manual confirmation: " + pending.getToolName(), payload);
        sseNotificationService.sendEvent(taskUuid, SseEvent.PAUSED, payload);
        sseNotificationService.sendEvent(taskUuid, SseEvent.STATE_CHANGE, Map.of(
                "status", TaskStatus.PAUSED.getCode(),
                "taskUuid", taskUuid,
                "pauseReason", PAUSE_REASON_TOOL_GUARD_CONFIRMATION,
                "pendingToolName", pending.getToolName(),
                "pendingToolGuardRequired", true
        ));
    }

    private ToolCallResult buildSuccessResult(AgentTool tool, Map<String, Object> input,
                                              Map<String, Object> output, Instant startedAt,
                                              int retryCount) {
        Instant completedAt = Instant.now();
        Map<String, Object> safeOutput = output == null ? Map.of() : new LinkedHashMap<>(output);
        ToolCallResult result = new ToolCallResult();
        result.setToolName(tool.getName());
        result.setStatus(ToolCallStatus.SUCCESS);
        result.setInput(input);
        result.setOutput(ensureSourceAndTime(safeOutput, tool, completedAt));
        result.setSource(resolveSource(safeOutput, tool));
        result.setQueryTime(resolveQueryTime(safeOutput, completedAt));
        result.setStartedAt(startedAt);
        result.setCompletedAt(completedAt);
        result.setDurationMs(Duration.between(startedAt, completedAt).toMillis());
        result.setRetryCount(retryCount);
        result.setRealtime(tool.isRealtime());
        return result;
    }

    private ToolCallResult buildFailureResult(AgentTool tool, String requestedToolName, Map<String, Object> input,
                                              Instant startedAt, int retryCount,
                                              Exception error, boolean degradeOnFailure) {
        Instant completedAt = Instant.now();
        String toolName = defaultToolName(tool, requestedToolName);
        String source = defaultSource(tool, toolName);
        ToolCallResult result = new ToolCallResult();
        result.setToolName(toolName);
        result.setStatus(degradeOnFailure ? ToolCallStatus.DEGRADED : ToolCallStatus.FAILED);
        result.setInput(input);
        result.setStartedAt(startedAt);
        result.setCompletedAt(completedAt);
        result.setDurationMs(Duration.between(startedAt, completedAt).toMillis());
        result.setRetryCount(retryCount);
        result.setErrorMessage(error == null ? null : error.getMessage());
        result.setSource(source);
        result.setQueryTime(completedAt);
        result.setRealtime(tool.isRealtime());
        result.setDegraded(degradeOnFailure);
        result.setDegradationReason(degradeOnFailure ? "real-time tool unavailable after retry budget exhausted" : null);
        result.setOutput(Map.of(
                "available", false,
                "source", source,
                "queryTime", completedAt.toString(),
                "message", "Unable to fetch real-time information for " + toolName
        ));
        return result;
    }

    private ToolCallResult buildGuardBlockedResult(AgentTool tool, String requestedToolName,
                                                   Map<String, Object> input, Instant startedAt,
                                                   ToolGuardDecision decision,
                                                   ToolCallStatus status) {
        Instant completedAt = Instant.now();
        String toolName = defaultToolName(tool, requestedToolName);
        String message = decision == null || decision.violation() == null
                ? "Tool guard blocked tool call"
                : decision.violation().message();
        ToolCallResult result = new ToolCallResult();
        result.setToolName(toolName);
        result.setStatus(status);
        result.setInput(input);
        result.setStartedAt(startedAt);
        result.setCompletedAt(completedAt);
        result.setDurationMs(Duration.between(startedAt, completedAt).toMillis());
        result.setRetryCount(0);
        result.setErrorMessage(message);
        result.setSource(defaultSource(tool, toolName));
        result.setQueryTime(completedAt);
        result.setRealtime(tool.isRealtime());
        result.setOutput(Map.of(
                "available", false,
                "source", defaultSource(tool, toolName),
                "queryTime", completedAt.toString(),
                "message", message,
                "guard", toolGuard == null ? Map.of() : toolGuard.violationPayload(decision)
        ));
        return result;
    }

    private Map<String, Object> ensureSourceAndTime(Map<String, Object> output, AgentTool tool, Instant completedAt) {
        Map<String, Object> enriched = new LinkedHashMap<>(output);
        enriched.putIfAbsent("source", resolveSource(output, tool));
        enriched.putIfAbsent("queryTime", completedAt.toString());
        return enriched;
    }

    private String resolveSource(Map<String, Object> output, AgentTool tool) {
        Object source = output.get("source");
        if (source == null) {
            source = output.get("provider");
        }
        if (source == null) {
            source = output.get("mcpProvider");
        }
        return source == null ? defaultSource(tool, tool.getName()) : String.valueOf(source);
    }

    private String defaultSource(AgentTool tool, String requestedToolName) {
        String source = tool.getSource();
        if (source == null || source.isBlank()) {
            source = defaultToolName(tool, requestedToolName);
        }
        return source;
    }

    private String defaultToolName(AgentTool tool, String requestedToolName) {
        String toolName = tool.getName();
        if (toolName == null || toolName.isBlank()) {
            toolName = requestedToolName;
        }
        if (toolName == null || toolName.isBlank()) {
            toolName = "unknown";
        }
        return toolName;
    }

    private Instant resolveQueryTime(Map<String, Object> output, Instant fallback) {
        Object queryTime = output.get("queryTime");
        if (queryTime == null) {
            queryTime = output.get("queriedAt");
        }
        if (queryTime instanceof Instant instant) {
            return instant;
        }
        if (queryTime instanceof String text) {
            try {
                return Instant.parse(text);
            } catch (Exception ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    /**
     * 构建toolidempotencykey。
     * @param taskUuid 任务唯一标识
     * @param stepIndex s te pI nd ex 参数
     * @param toolName t oo lN am e 参数
     * @param arguments 工具调用参数
     * @return 返回处理结果。
     */
    private String buildToolIdempotencyKey(String taskUuid, int stepIndex, String toolName, Map<String, Object> arguments) {
        String argsFingerprint = hashArguments(arguments);
        return taskUuid + "-step" + stepIndex + "-" + toolName + "-" + argsFingerprint;
    }

    /**
     * 判断hashArguments。
     * @param arguments 工具调用参数
     * @return 返回处理结果。
     */
    private String hashArguments(Map<String, Object> arguments) {
        try {
            Object normalized = normalizeForFingerprint(arguments == null ? Map.of() : arguments);
            String normalizedJson;
            try {
                normalizedJson = jsonUtil.toJson(normalized);
            } catch (RuntimeException unavailableJsonUtil) {
                normalizedJson = new ObjectMapper().findAndRegisterModules().writeValueAsString(normalized);
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(normalizedJson.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < bytes.length; i++) {
                builder.append(String.format("%02x", bytes[i]));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build tool idempotency fingerprint", e);
        }
    }

    private ToolCallResult idempotencyConflict(ToolCallRequest request, String message) {
        ToolCallResult result = new ToolCallResult();
        result.setToolName(request.getToolName());
        result.setStatus(ToolCallStatus.IDEMPOTENCY_CONFLICT);
        result.setInput(request.getArguments());
        result.setErrorMessage(message);
        return result;
    }

    private int persistToolStart(Task task, String toolName, String key, String fingerprint) {
        if (toolExecutionRecordMapper == null) return 1;
        try {
            String taskUuid = task == null ? null : task.getTaskUuid();
            int inserted = toolExecutionRecordMapper.insertRunning(taskUuid,
                    task == null ? null : task.getUserId(), toolName, key, fingerprint);
            if (inserted == 0 && taskUuid != null) {
                Map<String, Object> existing = safeFindDurable(taskUuid, key);
                if (existing != null && "FAILED".equalsIgnoreCase(stringValue(existing.get("status")))) {
                    return toolExecutionRecordMapper.resetFailedForRetry(taskUuid, key, fingerprint);
                }
            }
            return inserted;
        } catch (RuntimeException e) {
            log.warn("Unable to persist tool execution start key={}: {}", key, e.getMessage());
            return 1;
        }
    }

    private void persistToolFinish(String taskUuid, String key, ToolCallResult result) {
        if (toolExecutionRecordMapper == null) return;
        try {
            toolExecutionRecordMapper.finish(taskUuid, key, result.getStatus() == ToolCallStatus.SUCCESS ? "SUCCEEDED" : result.getStatus().name(),
                    jsonUtil.toJson(result.toMap()), result.getErrorMessage());
        } catch (RuntimeException e) {
            log.warn("Unable to persist tool execution result key={}: {}", key, e.getMessage());
        }
    }

    private void persistToolFailure(String taskUuid, String key, RuntimeException error) {
        if (toolExecutionRecordMapper == null) return;
        try {
            toolExecutionRecordMapper.finish(taskUuid, key, "FAILED", null, error.getMessage());
        } catch (RuntimeException e) {
            log.warn("Unable to persist tool execution failure key={}: {}", key, e.getMessage());
        }
    }

    private ToolCallResult lookupDurableResult(String taskUuid, String key, String fingerprint,
                                               ToolCallRequest request) {
        if (toolExecutionRecordMapper == null || taskUuid == null) {
            return null;
        }
        Map<String, Object> row = safeFindDurable(taskUuid, key);
        if (row == null) {
            return null;
        }
        String storedFingerprint = stringValue(row.get("argument_fingerprint"));
        if (storedFingerprint != null && !storedFingerprint.equals(fingerprint)) {
            return idempotencyConflict(request, "idempotency key is already bound to different arguments");
        }
        String status = stringValue(row.get("status"));
        if ("SUCCEEDED".equalsIgnoreCase(status)) {
            ToolCallResult result = deserializePersistedResult(stringValue(row.get("result_json")));
            if (result != null) {
                return result;
            }
        }
        if ("UNKNOWN".equalsIgnoreCase(status)) {
            return buildUnknownFromRecord(request, stringValue(row.get("error_message")));
        }
        if ("FAILED".equalsIgnoreCase(status)) {
            // The owner that wins insertRunning/resetFailedForRetry is responsible for the retry.
            // Returning null here lets persistToolStart perform that conditional claim.
            return null;
        }
        if ("RUNNING".equalsIgnoreCase(status)) {
            return awaitDurableResult(taskUuid, key, fingerprint, request);
        }
        return null;
    }

    private ToolCallResult awaitDurableResult(String taskUuid, String key, String fingerprint,
                                              ToolCallRequest request) {
        for (int attempt = 0; attempt < DURABLE_RESULT_WAIT_ATTEMPTS; attempt++) {
            Map<String, Object> row = safeFindDurable(taskUuid, key);
            if (row == null) {
                return null;
            }
            String storedFingerprint = stringValue(row.get("argument_fingerprint"));
            if (storedFingerprint != null && !storedFingerprint.equals(fingerprint)) {
                return idempotencyConflict(request, "idempotency key is already bound to different arguments");
            }
            String status = stringValue(row.get("status"));
            if ("SUCCEEDED".equalsIgnoreCase(status)) {
                ToolCallResult result = deserializePersistedResult(stringValue(row.get("result_json")));
                if (result != null) {
                    return result;
                }
            }
            if ("UNKNOWN".equalsIgnoreCase(status)) {
                return buildUnknownFromRecord(request, stringValue(row.get("error_message")));
            }
            if ("FAILED".equalsIgnoreCase(status)) {
                return null;
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return buildUnknownFromRecord(request, "Interrupted while waiting for in-flight tool execution");
            }
        }
        return buildUnknownFromRecord(request, "Tool execution is still running; result is not yet known");
    }

    private Map<String, Object> safeFindDurable(String taskUuid, String key) {
        try {
            return toolExecutionRecordMapper.findByKey(taskUuid, key);
        } catch (RuntimeException e) {
            log.warn("Unable to query tool execution key={}: {}", key, e.getMessage());
            return null;
        }
    }

    private ToolCallResult deserializePersistedResult(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            Map<?, ?> map = new ObjectMapper().findAndRegisterModules().readValue(json, Map.class);
            ToolCallResult result = new ToolCallResult();
            result.setToolName(stringValue(map.get("toolName")));
            result.setStatus(parseStatus(stringValue(map.get("status"))));
            result.setInput(asMap(map.get("input")));
            result.setOutput(asMap(map.get("output")));
            result.setSource(stringValue(map.get("source")));
            result.setQueryTime(parseInstant(map.get("queryTime")));
            result.setStartedAt(parseInstant(map.get("startedAt")));
            result.setCompletedAt(parseInstant(map.get("completedAt")));
            result.setDurationMs(longValue(map.get("durationMs")));
            result.setErrorMessage(stringValue(map.get("errorMessage")));
            result.setRetryCount((int) longValue(map.get("retryCount")));
            result.setRealtime(Boolean.TRUE.equals(map.get("realtime")));
            result.setDegraded(Boolean.TRUE.equals(map.get("degraded")));
            result.setDegradationReason(stringValue(map.get("degradationReason")));
            return result;
        } catch (Exception e) {
            log.warn("Unable to deserialize persisted tool result: {}", e.getMessage());
            return null;
        }
    }

    private ToolCallResult buildUnknownFromRecord(ToolCallRequest request, String message) {
        ToolCallResult result = new ToolCallResult();
        result.setToolName(request.getToolName());
        result.setStatus(ToolCallStatus.UNKNOWN);
        result.setInput(request.getArguments());
        result.setErrorMessage(message == null ? "Tool execution outcome is unknown" : message);
        return result;
    }

    private ToolCallResult buildUnknownResult(AgentTool tool, Map<String, Object> input,
                                              Map<String, Object> output, Instant startedAt,
                                              int retryCount, RuntimeException error) {
        ToolCallResult result = buildSuccessResult(tool, input, output, startedAt, retryCount);
        result.setStatus(ToolCallStatus.UNKNOWN);
        result.setErrorMessage("Provider completed but local result persistence failed: " + error.getMessage());
        return result;
    }

    private ToolCallStatus parseStatus(String value) {
        if (value == null) return ToolCallStatus.UNKNOWN;
        for (ToolCallStatus status : ToolCallStatus.values()) {
            if (status.name().equalsIgnoreCase(value) || status.getCode().equalsIgnoreCase(value)) {
                return status;
            }
        }
        return ToolCallStatus.UNKNOWN;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private Instant parseInstant(Object value) {
        if (value == null) return null;
        try {
            return Instant.parse(String.valueOf(value));
        } catch (Exception ignored) {
            return null;
        }
    }

    private long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        try { return value == null ? 0L : Long.parseLong(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return 0L; }
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static final class InFlightToolCall {
        private final String argumentFingerprint;
        private final CompletableFuture<ToolCallResult> future = new CompletableFuture<>();
        private volatile long completedAt;

        private InFlightToolCall(String argumentFingerprint) {
            this.argumentFingerprint = argumentFingerprint;
        }

        private String argumentFingerprint() { return argumentFingerprint; }
        private CompletableFuture<ToolCallResult> future() { return future; }
        private void markCompleted() { completedAt = System.currentTimeMillis(); }
        private boolean isExpired() {
            return completedAt > 0 && System.currentTimeMillis() - completedAt > COMPLETED_IDEMPOTENCY_CACHE_MS;
        }
    }

    /**
     * 规范化forfingerprint。
     * @param value 键值
     * @return 返回处理结果。
     */
    @SuppressWarnings("unchecked")
    private Object normalizeForFingerprint(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.entrySet().stream()
                    .sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                    .forEach(entry -> normalized.put(
                            String.valueOf(entry.getKey()),
                            normalizeForFingerprint(entry.getValue())
                    ));
            return normalized;
        }
        if (value instanceof List<?> list) {
            List<Object> normalized = new ArrayList<>(list.size());
            for (Object item : list) {
                normalized.add(normalizeForFingerprint(item));
            }
            return normalized;
        }
        return value;
    }
}
