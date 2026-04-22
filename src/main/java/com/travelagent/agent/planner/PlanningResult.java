package com.travelagent.agent.planner;

import com.travelagent.model.dto.LocationCandidateItem;
import com.travelagent.model.dto.SelectionOptionItem;

import java.util.List;
import java.util.Map;

/**
 * Result of a single Markov planning step: the selected attraction name and the
 * token count consumed by the LLM call (0 when streaming mode returns no usage data).
 */
public record PlanningResult(String attractionName,
                             int totalTokens,
                             List<LocationCandidateItem> recommendationCandidates,
                             String pendingInputType,
                             String selectionStage,
                             String selectedBranchType,
                             List<SelectionOptionItem> selectionOptions,
                             Map<String, Object> currentContext,
                             Map<String, Object> weatherContext) {

    public static PlanningResult forAttraction(String attractionName, int totalTokens) {
        return new PlanningResult(attractionName, totalTokens, List.of(), null, null, null,
                List.of(), Map.of(), Map.of());
    }

    public static PlanningResult forBranchSelection(List<SelectionOptionItem> selectionOptions,
                                                    Map<String, Object> currentContext,
                                                    Map<String, Object> weatherContext) {
        return new PlanningResult(null, 0, List.of(), "selection_branch", "branch_selection", null,
                selectionOptions == null ? List.of() : selectionOptions,
                currentContext == null ? Map.of() : currentContext,
                weatherContext == null ? Map.of() : weatherContext);
    }

    public static PlanningResult forCandidates(List<LocationCandidateItem> recommendationCandidates,
                                               int totalTokens,
                                               String pendingInputType,
                                               String selectionStage,
                                               String selectedBranchType,
                                               Map<String, Object> currentContext,
                                               Map<String, Object> weatherContext) {
        return new PlanningResult(null, totalTokens,
                recommendationCandidates == null ? List.of() : recommendationCandidates,
                pendingInputType, selectionStage, selectedBranchType, List.of(),
                currentContext == null ? Map.of() : currentContext,
                weatherContext == null ? Map.of() : weatherContext);
    }

    public boolean requiresUserSelection() {
        return pendingInputType != null && !pendingInputType.isBlank();
    }
}
