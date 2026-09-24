package com.travelagent.agent.replan;

import com.travelagent.agent.validation.ValidatorIssue;
import org.springframework.stereotype.Service;

@Service
public class ReplanEngine {

    public ReplanResult plan(ReplanRequest request) {
        if (request == null || request.getTriggerType() == null) {
            throw new IllegalArgumentException("triggerType is required");
        }
        ReplanResult result = new ReplanResult();
        result.setReason(request.getTriggerReason() == null ? request.getTriggerType().name() : request.getTriggerReason());
        switch (request.getTriggerType()) {
            case WEATHER_RISK -> result.getLocalAdjustments().add("Move outdoor attractions to indoor alternatives or swap the affected day order.");
            case ATTRACTION_UNAVAILABLE -> result.getLocalAdjustments().add("Replace unavailable attraction with same-theme same-city fallback.");
            case BOOKING_FAILED -> result.getLocalAdjustments().add("Try adjacent booking slots, then replace with no-reservation attraction.");
            case TRAFFIC_TOO_LONG -> result.getLocalAdjustments().add("Cluster attractions by area and move the far stop to another day.");
            case BUDGET_EXCEEDED -> result.getLocalAdjustments().add("Compress paid attractions, accommodation tier and taxi usage.");
            case USER_CONSTRAINT_CHANGE -> {
                result.setFullRegenerationRequired(true);
                result.getLocalAdjustments().add("Regenerate plan because user changed core constraints.");
            }
            case VALIDATOR_ISSUE -> applyValidatorIssues(request, result);
        }
        if (result.getLocalAdjustments().isEmpty()) {
            result.setFullRegenerationRequired(true);
            result.getLocalAdjustments().add("No safe local adjustment found; regenerate the full itinerary.");
        }
        result.getPlanVersionNotes().add("Record trigger=" + request.getTriggerType());
        result.getPlanVersionNotes().add("Re-run Validator after re-plan.");
        return result;
    }

    private void applyValidatorIssues(ReplanRequest request, ReplanResult result) {
        if (request.getValidatorIssues() == null || request.getValidatorIssues().isEmpty()) {
            result.setFullRegenerationRequired(true);
            return;
        }
        for (ValidatorIssue issue : request.getValidatorIssues()) {
            if (issue.getType() == null) continue;
            switch (issue.getType()) {
                case "budget_exceeded" -> result.getLocalAdjustments().add("Apply budget compression to hotels, tickets and transport.");
                case "route_too_far" -> result.getLocalAdjustments().add("Move long-transfer attractions into closer day clusters.");
                case "walking_too_long", "too_many_stops" -> result.getLocalAdjustments().add("Reduce daily stops and internal walking distance.");
                default -> result.getLocalAdjustments().add(issue.getSuggestion() == null ? "Adjust itinerary locally." : issue.getSuggestion());
            }
        }
    }
}
