package com.travelagent.agent.planner;

import com.travelagent.model.dto.LocationCandidateItem;

import java.util.List;

/**
 * Result of a single Markov planning step: the selected attraction name and the
 * token count consumed by the LLM call (0 when streaming mode returns no usage data).
 */
public record PlanningResult(String attractionName, int totalTokens, List<LocationCandidateItem> recommendationCandidates) {

    public static PlanningResult forAttraction(String attractionName, int totalTokens) {
        return new PlanningResult(attractionName, totalTokens, List.of());
    }

    public static PlanningResult forCandidates(List<LocationCandidateItem> recommendationCandidates) {
        return new PlanningResult(null, 0, recommendationCandidates == null ? List.of() : recommendationCandidates);
    }

    public boolean requiresUserSelection() {
        return recommendationCandidates != null && !recommendationCandidates.isEmpty();
    }
}
