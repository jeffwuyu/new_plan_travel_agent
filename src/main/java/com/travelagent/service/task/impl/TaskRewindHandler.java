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
     * Mutates {@code checkpoint} in place to reflect a rewind to {@code targetStepIndex}.
     * Returns the list of retained steps so the caller can build SSE payloads without
     * re-reading the checkpoint.
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

    private int calculateUsedTimeBudget(List<CompletedStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return 0;
        }
        return steps.stream()
                .mapToInt(step -> valueOrZero(step.getTrafficTimeFromPrevMin()) + valueOrZero(step.getEstimatedVisitDurationMin()))
                .sum();
    }

    private int calculateRemainingTimeBudget(TaskCheckpoint checkpoint) {
        int totalAvailable = checkpoint.totalAvailableMinutes();
        int used = valueOrZero(checkpoint.getUsedTimeBudgetMin());
        int buffer = checkpoint.getPlanningConfig() == null ? 0 : checkpoint.getPlanningConfig().getDestinationBufferMin();
        int returnReserve = shouldReserveReturnToDestination(checkpoint)
                ? Math.max(valueOrZero(checkpoint.getProjectedReturnToDestinationMin()), 45)
                : 0;
        return Math.max(0, totalAvailable - used - buffer - returnReserve);
    }

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

    private int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }
}
