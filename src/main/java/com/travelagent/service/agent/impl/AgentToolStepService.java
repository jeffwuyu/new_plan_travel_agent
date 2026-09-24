package com.travelagent.service.agent.impl;

import com.travelagent.agent.context.CompletedStep;
import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.agent.statemachine.AgentEvent;
import com.travelagent.agent.statemachine.AgentStateMachine;
import com.travelagent.agent.tools.GeocodeTool;
import com.travelagent.agent.tools.TrafficTimeTool;
import com.travelagent.agent.tools.WeatherTool;
import com.travelagent.mapper.TaskMapper;
import com.travelagent.model.entity.Task;
import com.travelagent.model.enums.TaskStatus;
import com.travelagent.service.notification.SseEvent;
import com.travelagent.service.notification.SseNotificationService;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Agent 单步工具阶段服务，负责 geocode、weather、traffic 工具调用及工具阶段状态推进。
 */
@Component
public class AgentToolStepService {

    private final TaskMapper taskMapper;
    private final AgentStateMachine stateMachine;
    private final SseNotificationService sseNotificationService;
    private final AgentToolExecutor toolExecutor;

    /**
     * 创建 Agent 单步工具阶段服务。
     *
     * @param taskMapper 任务 Mapper
     * @param stateMachine Agent 状态机
     * @param sseNotificationService SSE 通知服务
     * @param toolExecutor 工具执行器
     */
    public AgentToolStepService(TaskMapper taskMapper,
                                AgentStateMachine stateMachine,
                                SseNotificationService sseNotificationService,
                                AgentToolExecutor toolExecutor) {
        this.taskMapper = taskMapper;
        this.stateMachine = stateMachine;
        this.sseNotificationService = sseNotificationService;
        this.toolExecutor = toolExecutor;
    }

    /**
     * 进入工具调用状态并执行当前景点所需工具。
     *
     * @param task 任务实体
     * @param checkpoint 任务 checkpoint
     * @param taskUuid 任务唯一标识
     * @param stepIndex 当前步骤索引
     * @param attractionName 已确定景点名
     * @return 工具调用结果集合
     */
    public AgentToolStepResult startAndRunTools(Task task,
                                                TaskCheckpoint checkpoint,
                                                String taskUuid,
                                                int stepIndex,
                                                String attractionName) {
        TaskStatus toolCalling = stateMachine.transition(TaskStatus.PLANNING, AgentEvent.START_TOOL_CALL);
        task.setStatus(toolCalling.getCode());
        checkpoint.setCurrentState(toolCalling.getCode());
        taskMapper.updateStatus(task.getId(), toolCalling.getCode());
        sendStateChange(taskUuid, checkpoint, toolCalling.getCode(), task);

        Map<String, Object> geocodeArgs = Map.of("name", attractionName, "region", checkpoint.getRegion());
        Map<String, Object> geocodeResult = toolExecutor.runToolWithCheckpoint(
                task, checkpoint, GeocodeTool.NAME, geocodeArgs, taskUuid, stepIndex);

        Map<String, Object> weatherResult = runWeatherTool(task, checkpoint, taskUuid, stepIndex, geocodeResult);
        Map<String, Object> trafficResult = runTrafficTool(task, checkpoint, taskUuid, stepIndex, geocodeResult);
        return new AgentToolStepResult(geocodeResult, weatherResult, trafficResult);
    }

    /**
     * 在主循环确认任务仍可继续后，推送工具结果并把状态切回 planning。
     *
     * @param task 任务实体
     * @param checkpoint 任务 checkpoint
     * @param taskUuid 任务唯一标识
     * @param result 工具调用结果
     */
    public void finishToolStage(Task task, TaskCheckpoint checkpoint, String taskUuid, AgentToolStepResult result) {
        Map<String, Object> toolPayload = new HashMap<>();
        toolPayload.put("geocode", result.geocodeResult());
        toolPayload.put("weather", result.weatherResult());
        toolPayload.put("traffic_time", result.trafficResult());
        sseNotificationService.sendEvent(taskUuid, SseEvent.TOOL_RESULT, toolPayload);

        TaskStatus backToPlanning = stateMachine.transition(TaskStatus.TOOL_CALLING, AgentEvent.TOOL_CALL_DONE);
        task.setStatus(backToPlanning.getCode());
        checkpoint.setCurrentState(backToPlanning.getCode());
        taskMapper.updateStatus(task.getId(), backToPlanning.getCode());
        sendStateChange(taskUuid, checkpoint, backToPlanning.getCode(), task);
    }

    /**
     * 根据 geocode 结果补全天气信息；缺少 adcode 时返回未知天气快照。
     *
     * @param task 任务实体
     * @param checkpoint 任务 checkpoint
     * @param taskUuid 任务唯一标识
     * @param stepIndex 当前步骤索引
     * @param geocodeResult 地理编码结果
     * @return 天气结果
     */
    private Map<String, Object> runWeatherTool(Task task,
                                               TaskCheckpoint checkpoint,
                                               String taskUuid,
                                               int stepIndex,
                                               Map<String, Object> geocodeResult) {
        String adcode = (String) geocodeResult.getOrDefault("adcode", "");
        if (adcode.isBlank()) {
            return Map.of("weather", "Unknown", "temperature", "", "windDirection", "", "windPower", "", "humidity", "");
        }
        return toolExecutor.runToolWithCheckpoint(
                task, checkpoint, WeatherTool.NAME, Map.of("adcode", adcode), taskUuid, stepIndex);
    }

    /**
     * 根据上一站或已确认起点计算到当前景点的交通耗时。
     *
     * @param task 任务实体
     * @param checkpoint 任务 checkpoint
     * @param taskUuid 任务唯一标识
     * @param stepIndex 当前步骤索引
     * @param geocodeResult 当前景点 geocode 结果
     * @return 交通结果；首站且没有起点坐标时返回 null
     */
    private Map<String, Object> runTrafficTool(Task task,
                                               TaskCheckpoint checkpoint,
                                               String taskUuid,
                                               int stepIndex,
                                               Map<String, Object> geocodeResult) {
        if (stepIndex > 0) {
            CompletedStep prevStep = checkpoint.getCompletedSteps().get(stepIndex - 1);
            return toolExecutor.runToolWithCheckpoint(task, checkpoint, TrafficTimeTool.NAME, Map.of(
                    "originLng", prevStep.getLng(),
                    "originLat", prevStep.getLat(),
                    "destLng", geocodeResult.get("lng"),
                    "destLat", geocodeResult.get("lat"),
                    "travelMode", checkpoint.getPlanningConfig().getTravelMode()
            ), taskUuid, stepIndex);
        }
        if (checkpoint.getSelectedOrigin() != null
                && checkpoint.getSelectedOrigin().getLatitude() != null
                && checkpoint.getSelectedOrigin().getLongitude() != null) {
            return toolExecutor.runToolWithCheckpoint(task, checkpoint, TrafficTimeTool.NAME, Map.of(
                    "originLng", checkpoint.getSelectedOrigin().getLongitude(),
                    "originLat", checkpoint.getSelectedOrigin().getLatitude(),
                    "destLng", geocodeResult.get("lng"),
                    "destLat", geocodeResult.get("lat"),
                    "travelMode", checkpoint.getPlanningConfig().getTravelMode()
            ), taskUuid, stepIndex);
        }
        return null;
    }

    /**
     * 推送工具阶段状态变化。
     *
     * @param taskUuid 任务唯一标识
     * @param checkpoint 任务 checkpoint
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

    /**
     * 单步工具调用结果。
     *
     * @param geocodeResult 地理编码结果
     * @param weatherResult 天气结果
     * @param trafficResult 交通结果
     */
    public record AgentToolStepResult(Map<String, Object> geocodeResult,
                                      Map<String, Object> weatherResult,
                                      Map<String, Object> trafficResult) {
    }
}
