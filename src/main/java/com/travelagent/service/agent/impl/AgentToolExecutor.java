package com.travelagent.service.agent.impl;

import com.travelagent.agent.context.PendingToolCall;
import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.agent.tools.ToolRegistry;
import com.travelagent.model.entity.Task;
import com.travelagent.monitoring.TaskMetricsService;
import com.travelagent.service.task.TaskProgressService;
import com.travelagent.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 负责单次工具调用的执行、幂等键生成和 pending tool call 的重放。
 */
@Component
public class AgentToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgentToolExecutor.class);

    private static final String EVT_TOOL_START = "TOOL_START";
    private static final String EVT_TOOL_DONE = "TOOL_DONE";

    @Autowired private ToolRegistry toolRegistry;
    @Autowired private AgentCheckpointHelper checkpointHelper;
    @Autowired private TaskProgressService taskProgressService;
    @Autowired private TaskMetricsService taskMetricsService;
    @Autowired private JsonUtil jsonUtil;

    @SuppressWarnings("unchecked")
    public Map<String, Object> runToolWithCheckpoint(Task task, TaskCheckpoint checkpoint,
                                                      String toolName, Map<String, Object> arguments,
                                                      String taskUuid, int stepIndex) {
        String idempotencyKey = buildToolIdempotencyKey(taskUuid, stepIndex, toolName, arguments);
        log.debug("[AgentToolExecutor] tool={} step={} idempotencyKey={} arguments={}",
                toolName, stepIndex, idempotencyKey, arguments);
        checkpoint.setPendingToolCall(new PendingToolCall(toolName, arguments, idempotencyKey));
        checkpointHelper.saveCheckpoint(task, checkpoint);
        taskProgressService.recordEvent(taskUuid, EVT_TOOL_START, null, stepIndex, null, "Calling tool: " + toolName, arguments);

        boolean toolSuccess = true;
        try {
            Map<String, Object> result = (Map<String, Object>) toolRegistry.getTool(toolName).execute(arguments, idempotencyKey);
            checkpoint.setPendingToolCall(null);
            checkpointHelper.saveCheckpoint(task, checkpoint);
            taskProgressService.recordEvent(taskUuid, EVT_TOOL_DONE, null, stepIndex, null, "Tool completed: " + toolName, result);
            return result;
        } catch (Exception e) {
            toolSuccess = false;
            throw e;
        } finally {
            taskMetricsService.recordToolCall(toolSuccess);
        }
    }

    public void replayPendingToolCall(Task task, TaskCheckpoint checkpoint, String taskUuid) {
        PendingToolCall pending = checkpoint.getPendingToolCall();
        try {
            toolRegistry.getTool(pending.getToolName()).execute(pending.getArguments(), pending.getIdempotencyKey());
            checkpoint.setPendingToolCall(null);
            checkpointHelper.saveCheckpoint(task, checkpoint);
        } catch (Exception e) {
            log.warn("[AgentToolExecutor] Replay of pending tool {} failed for task={}: {}",
                    pending.getToolName(), taskUuid, e.getMessage());
        }
    }

    private String buildToolIdempotencyKey(String taskUuid, int stepIndex, String toolName, Map<String, Object> arguments) {
        String argsFingerprint = hashArguments(arguments);
        return taskUuid + "-step" + stepIndex + "-" + toolName + "-" + argsFingerprint;
    }

    private String hashArguments(Map<String, Object> arguments) {
        try {
            String normalizedJson = jsonUtil.toJson(normalizeForFingerprint(arguments == null ? Map.of() : arguments));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(normalizedJson.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < Math.min(bytes.length, 8); i++) {
                builder.append(String.format("%02x", bytes[i]));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build tool idempotency fingerprint", e);
        }
    }

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
