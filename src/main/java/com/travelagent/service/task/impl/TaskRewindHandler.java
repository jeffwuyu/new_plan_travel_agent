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
 * Handles checkpoint mutation for the rewind operation.
 * Stateless — no DB writes, no SSE notifications. Callers own persistence.
 */
@Component
public class TaskRewindHandler {

    /**
     * 处理applyRewind。
     * @param checkpoint 任务检查点数据
     * @param targetStepIndex t ar ge tS te pI nd ex 参数
     * @return 返回处理后的列表结果。
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
     * 处理copyCompletedStep。
     * @param original o ri gi na l 参数
     * @return 返回处理结果。
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
     * 处理calculateUsedTimeBudget。
     * @param steps 步骤列表
     * @return 返回处理结果。
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
     * 处理calculateRemainingTimeBudget。
     * @param checkpoint 任务检查点数据
     * @return 返回处理结果。
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
     * 判断是否应执行reservereturntodestination。
     * @param checkpoint 任务检查点数据
     * @return 是否满足当前条件。
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
     * 解析并确定daynumberforoffset。
     * @param checkpoint 任务检查点数据
     * @param offsetMin o ff se tM in 参数
     * @return 返回处理结果。
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
     * 处理valueOrZero。
     * @param value 键值
     * @return 返回处理结果。
     */
    private int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }
}
