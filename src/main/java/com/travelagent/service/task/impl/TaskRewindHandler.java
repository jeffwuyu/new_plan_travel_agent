package com.travelagent.service.task.impl;

import com.travelagent.agent.context.CompletedStep;
import com.travelagent.agent.context.DailyTimeWindow;
import com.travelagent.agent.context.RetryState;
import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.model.enums.TaskStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 任务回退处理器。
 *
 * <p>该组件只负责修改 checkpoint 内存对象，不写数据库、不发送 SSE；调用方负责持久化和通知。</p>
 */
@Component
public class TaskRewindHandler {

    /**
     * 将 checkpoint 回退到指定已完成步骤。
     *
     * @param checkpoint 任务检查点数据
     * @param targetStepIndex 目标步骤序号
     * @return 回退后保留的已完成步骤
     */
    public List<CompletedStep> applyRewind(TaskCheckpoint checkpoint, int targetStepIndex) {
        List<CompletedStep> retainedSteps = checkpoint.getCompletedSteps().stream()
                .filter(step -> step.getStepIndex() <= targetStepIndex)
                .map(this::copyCompletedStep)
                .collect(Collectors.toCollection(ArrayList::new));

        checkpoint.setCompletedSteps(retainedSteps);
        checkpoint.setCurrentStepIndex(retainedSteps.size());
        checkpoint.setUsedTimeBudgetMin(calculateUsedTimeBudget(retainedSteps));
        checkpoint.setProjectedReturnToDestinationMin(retainedSteps.isEmpty()
                ? 0
                : valueOrZero(retainedSteps.get(retainedSteps.size() - 1).getTravelTimeToDestinationMin()));
        checkpoint.setRemainingTimeBudgetMin(calculateRemainingTimeBudget(checkpoint));
        checkpoint.setPendingInputType(null);
        checkpoint.setSelectionStage(null);
        checkpoint.setSelectedBranchType(null);
        checkpoint.setSelectionOptions(new ArrayList<>());
        checkpoint.setRecommendationCandidates(new ArrayList<>());
        checkpoint.setSelectedAttractionCandidate(null);
        checkpoint.setCurrentContext(new LinkedHashMap<>());
        checkpoint.setWeatherContext(new LinkedHashMap<>());
        checkpoint.setPendingToolCall(null);
        checkpoint.setPauseReason(null);
        checkpoint.setResumableAt(null);
        checkpoint.setRetryState(new RetryState(0, 3));
        checkpoint.setLlmConversationHistory(new ArrayList<>());
        checkpoint.setHistoryTrimmedAt(null);
        checkpoint.setCurrentState(TaskStatus.RESUMING.getCode());

        return retainedSteps;
    }

    /**
     * 复制已完成步骤，避免回退时继续引用原列表中的可变对象。
     *
     * @param original 原始已完成步骤
     * @return 复制后的步骤
     */
    private CompletedStep copyCompletedStep(CompletedStep original) {
        CompletedStep copied = new CompletedStep();
        copied.setStepIndex(original.getStepIndex());
        copied.setDayNumber(original.getDayNumber());
        copied.setAttractionName(original.getAttractionName());
        copied.setLat(original.getLat());
        copied.setLng(original.getLng());
        copied.setTrafficTimeFromPrevMin(original.getTrafficTimeFromPrevMin());
        copied.setEstimatedVisitDurationMin(original.getEstimatedVisitDurationMin());
        copied.setTravelTimeToDestinationMin(original.getTravelTimeToDestinationMin());
        copied.setPlannedStartTime(original.getPlannedStartTime());
        copied.setPlannedEndTime(original.getPlannedEndTime());
        copied.setToolCallResults(original.getToolCallResults());
        return copied;
    }

    /**
     * 重新计算已使用时间预算。
     *
     * @param steps 回退后保留的步骤列表
     * @return 已使用分钟数
     */
    private int calculateUsedTimeBudget(List<CompletedStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return 0;
        }
        return steps.stream()
                .mapToInt(step -> valueOrZero(step.getTrafficTimeFromPrevMin()) + valueOrZero(step.getEstimatedVisitDurationMin()))
                .sum();
    }

    /**
     * 根据回退后的步骤、总时间窗和返程预留重新计算剩余时间。
     *
     * @param checkpoint 任务检查点数据
     * @return 剩余可规划分钟数
     */
    private int calculateRemainingTimeBudget(TaskCheckpoint checkpoint) {
        int totalAvailable = checkpoint.totalAvailableMinutes();
        int used = valueOrZero(checkpoint.getUsedTimeBudgetMin());
        int buffer = checkpoint.getPlanningConfig() == null ? 0 : checkpoint.getPlanningConfig().getDestinationBufferMin();
        int returnReserve = shouldReserveReturnToDestination(checkpoint)
                ? Math.max(valueOrZero(checkpoint.getProjectedReturnToDestinationMin()), 45)
                : 0;
        return Math.max(0, totalAvailable - used - buffer - returnReserve);
    }

    /**
     * 判断是否需要继续为返回终点预留时间。
     *
     * @param checkpoint 任务检查点数据
     * @return 需要预留返程时间时返回 true
     */
    private boolean shouldReserveReturnToDestination(TaskCheckpoint checkpoint) {
        if (checkpoint.getSelectedDestination() == null
                || checkpoint.getSelectedDestination().getLatitude() == null
                || checkpoint.getSelectedDestination().getLongitude() == null
                || checkpoint.getPlanningConfig() == null) {
            return false;
        }
        return resolveDayNumberForOffset(checkpoint, checkpoint.getUsedTimeBudgetMin())
                >= checkpoint.getPlanningConfig().getTotalDays();
    }

    /**
     * 根据已用分钟偏移推断当前所在天数。
     *
     * @param checkpoint 任务检查点数据
     * @param offsetMin 已用分钟偏移
     * @return 对应行程天数
     */
    private int resolveDayNumberForOffset(TaskCheckpoint checkpoint, Integer offsetMin) {
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
     * 将可空整数转换为预算计算可用的非空值。
     *
     * @param value 原始整数
     * @return 原值或 0
     */
    private int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }
}
