package com.travelagent.service.agent.impl;

import com.travelagent.agent.context.CompletedStep;
import com.travelagent.agent.context.DailyTimeWindow;
import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.agent.tools.GeocodeTool;
import com.travelagent.agent.tools.TrafficTimeTool;
import com.travelagent.agent.tools.WeatherTool;
import com.travelagent.client.amap.AmapClient;
import com.travelagent.mapper.TaskMapper;
import com.travelagent.mapper.UserMapper;
import com.travelagent.model.entity.Task;
import com.travelagent.util.JsonUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Checkpoint 的加载/保存、时间预算计算、CompletedStep 构建。
 */
@Component
public class AgentCheckpointHelper {

    static final int FALLBACK_RETURN_TO_DESTINATION_MIN = 45;

    @Autowired private TaskMapper taskMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private JsonUtil jsonUtil;
    @Autowired(required = false) private AmapClient amapClient;

    /**
     * 加载checkpoint。
     * @param task 任务实体
     * @return 返回处理结果。
     */
    public TaskCheckpoint loadCheckpoint(Task task) {
        if (task.getCheckpointJson() == null || task.getCheckpointJson().isBlank()) {
            return new TaskCheckpoint();
        }
        try {
            return jsonUtil.fromJson(task.getCheckpointJson(), TaskCheckpoint.class);
        } catch (Exception e) {
            throw new RuntimeException("Checkpoint deserialization failed for task=" + task.getTaskUuid(), e);
        }
    }

    /**
     * 保存checkpoint。
     * @param task 任务实体
     * @param checkpoint 任务检查点数据
     */
    public void saveCheckpoint(Task task, TaskCheckpoint checkpoint) {
        task.setCheckpointJson(jsonUtil.toJson(checkpoint));
        task.setStatus(checkpoint.getCurrentState());
        taskMapper.updateCheckpoint(task);
    }

    /**
     * 构建completedstep。
     * @param stepIndex s te pI nd ex 参数
     * @param dayNumber d ay Nu mb er 参数
     * @param attractionName 景点名称
     * @param lat 纬度
     * @param lng 经度
     * @param geocodeResult g eo co de Re su lt 参数
     * @param weatherResult w ea th er Re su lt 参数
     * @param trafficResult t ra ff ic Re su lt 参数
     * @param trafficMin t ra ff ic Mi n 参数
     * @param visitDurationMin v is it Du ra ti on Mi n 参数
     * @param plannedStart p la nn ed St ar t 参数
     * @param plannedEnd p la nn ed En d 参数
     * @param returnToDestinationMin r et ur nT oD es ti na ti on Mi n 参数
     * @return 返回处理结果。
     */
    public CompletedStep buildCompletedStep(int stepIndex, int dayNumber, String attractionName,
                                             double lat, double lng,
                                             Map<String, Object> geocodeResult,
                                             Map<String, Object> weatherResult,
                                             Map<String, Object> trafficResult,
                                             int trafficMin,
                                             int visitDurationMin,
                                             LocalDateTime plannedStart,
                                             LocalDateTime plannedEnd,
                                             int returnToDestinationMin) {
        CompletedStep step = new CompletedStep();
        step.setStepIndex(stepIndex);
        step.setDayNumber(dayNumber);
        step.setAttractionName(attractionName);
        step.setLat(lat);
        step.setLng(lng);
        step.setTrafficTimeFromPrevMin(trafficMin);
        step.setEstimatedVisitDurationMin(visitDurationMin);
        step.setTravelTimeToDestinationMin(returnToDestinationMin);
        step.setPlannedStartTime(plannedStart);
        step.setPlannedEndTime(plannedEnd);

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

    /**
     * 刷新remainingbudget。
     * @param checkpoint 任务检查点数据
     */
    public void refreshRemainingBudget(TaskCheckpoint checkpoint) {
        int totalAvailable = checkpoint.totalAvailableMinutes();
        int used = valueOrZero(checkpoint.getUsedTimeBudgetMin());
        int buffer = checkpoint.getPlanningConfig() == null ? 0 : checkpoint.getPlanningConfig().getDestinationBufferMin();
        int returnReserve = shouldReserveReturnToDestination(checkpoint)
                ? Math.max(valueOrZero(checkpoint.getProjectedReturnToDestinationMin()), FALLBACK_RETURN_TO_DESTINATION_MIN)
                : 0;
        checkpoint.setRemainingTimeBudgetMin(Math.max(0, totalAvailable - used - buffer - returnReserve));
    }

    /**
     * 判断是否可以执行plananotherstep。
     * @param checkpoint 任务检查点数据
     * @return 是否满足当前条件。
     */
    public boolean canPlanAnotherStep(TaskCheckpoint checkpoint) {
        if (checkpoint.getPlanningConfig() == null) {
            return false;
        }
        if (checkpoint.getCurrentStepIndex() >= checkpoint.totalPlannedSteps()) {
            return false;
        }
        refreshRemainingBudget(checkpoint);
        return valueOrZero(checkpoint.getRemainingTimeBudgetMin()) >= checkpoint.getPlanningConfig().getMinContinueBudgetMin();
    }

    /**
     * 解析并确定daynumberforoffset。
     * @param checkpoint 任务检查点数据
     * @param offsetMin o ff se tM in 参数
     * @return 返回处理结果。
     */
    public int resolveDayNumberForOffset(TaskCheckpoint checkpoint, Integer offsetMin) {
        int remaining = Math.max(0, valueOrZero(offsetMin));
        List<DailyTimeWindow> windows = checkpoint.getDailyTimeWindows();
        if (windows == null || windows.isEmpty()) {
            return 1;
        }
        for (DailyTimeWindow window : windows) {
            int dayMinutes = window.availableMinutes();
            if (remaining < dayMinutes) {
                return window.getDayNumber();
            }
            remaining -= dayMinutes;
        }
        return windows.get(windows.size() - 1).getDayNumber();
    }

    /**
     * 解析并确定datetimeforoffset。
     * @param checkpoint 任务检查点数据
     * @param offsetMin o ff se tM in 参数
     * @return 返回处理结果。
     */
    public LocalDateTime resolveDateTimeForOffset(TaskCheckpoint checkpoint, int offsetMin) {
        int remaining = Math.max(0, offsetMin);
        List<DailyTimeWindow> windows = checkpoint.getDailyTimeWindows();
        if (windows == null || windows.isEmpty()) {
            return checkpoint.getTripStartTime();
        }
        for (DailyTimeWindow window : windows) {
            int dayMinutes = window.availableMinutes();
            if (remaining <= dayMinutes) {
                return window.getStartTime().plusMinutes(remaining);
            }
            remaining -= dayMinutes;
        }
        return windows.get(windows.size() - 1).getEndTime();
    }

    /**
     * 处理estimateTravelTimeToDestination。
     * @param checkpoint 任务检查点数据
     * @param originLat 起点纬度
     * @param originLng 起点经度
     * @return 返回处理结果。
     */
    public int estimateTravelTimeToDestination(TaskCheckpoint checkpoint, double originLat, double originLng) {
        if (!shouldReserveReturnToDestination(checkpoint)) {
            return 0;
        }
        if (checkpoint.getSelectedDestination() == null
                || checkpoint.getSelectedDestination().getLatitude() == null
                || checkpoint.getSelectedDestination().getLongitude() == null) {
            return FALLBACK_RETURN_TO_DESTINATION_MIN;
        }
        try {
            Map<String, Object> duration = amapClient.getTravelDuration(
                    originLng, originLat,
                    checkpoint.getSelectedDestination().getLongitude(),
                    checkpoint.getSelectedDestination().getLatitude(),
                    checkpoint.getPlanningConfig().getTravelMode());
            return ((Number) duration.getOrDefault("durationMin", FALLBACK_RETURN_TO_DESTINATION_MIN)).intValue();
        } catch (Exception e) {
            return FALLBACK_RETURN_TO_DESTINATION_MIN;
        }
    }

    /**
     * 判断是否应执行excludeorigintravelfrombudget。
     * @param stepIndex s te pI nd ex 参数
     * @param checkpoint 任务检查点数据
     * @return 是否满足当前条件。
     */
    public boolean shouldExcludeOriginTravelFromBudget(int stepIndex, TaskCheckpoint checkpoint) {
        return stepIndex == 0
                && checkpoint.getSelectedOrigin() != null
                && checkpoint.getSelectedOrigin().getLatitude() != null
                && checkpoint.getSelectedOrigin().getLongitude() != null;
    }

    /**
     * 解析并确定userlevel。
     * @param userId 用户ID
     * @return 返回处理结果。
     */
    public int resolveUserLevel(Long userId) {
        try {
            var user = userMapper.findById(userId);
            return user != null ? user.getUserLevel() : 1;
        } catch (Exception e) {
            return 1;
        }
    }

    int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    /**
     * 判断是否应执行reservereturntodestination。
     * @param checkpoint 任务检查点数据
     * @return 是否满足当前条件。
     */
    private boolean shouldReserveReturnToDestination(TaskCheckpoint checkpoint) {
        if (checkpoint.getSelectedDestination() == null
                || checkpoint.getSelectedDestination().getLatitude() == null
                || checkpoint.getSelectedDestination().getLongitude() == null) {
            return false;
        }
        return resolveDayNumberForOffset(checkpoint, valueOrZero(checkpoint.getUsedTimeBudgetMin()))
                >= checkpoint.getPlanningConfig().getTotalDays();
    }
}
