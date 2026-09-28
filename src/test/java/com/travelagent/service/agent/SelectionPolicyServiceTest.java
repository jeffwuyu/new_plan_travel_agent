package com.travelagent.service.agent;

import com.travelagent.agent.planner.PlanningResult;
import com.travelagent.model.dto.LocationCandidateItem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SelectionPolicyServiceTest {

    @Test
    void manualOnly_neverAutoSelects() {
        SelectionPolicyService service = new SelectionPolicyService(SelectionPolicyService.MANUAL_ONLY, 0.82, 0.12);

        assertThat(service.chooseCandidate(result(candidate("a", 0.99)))).isEmpty();
    }

    @Test
    void autoHighConfidence_selectsWhenScoreAndGapPass() {
        SelectionPolicyService service = new SelectionPolicyService(SelectionPolicyService.AUTO_HIGH_CONFIDENCE, 0.82, 0.12);

        assertThat(service.chooseCandidate(result(candidate("a", 0.91), candidate("b", 0.70))))
                .get()
                .extracting(LocationCandidateItem::getCandidateId)
                .isEqualTo("a");
    }

    @Test
    void autoHighConfidence_keepsManualWhenScoresAreClose() {
        SelectionPolicyService service = new SelectionPolicyService(SelectionPolicyService.AUTO_HIGH_CONFIDENCE, 0.82, 0.12);

        assertThat(service.chooseCandidate(result(candidate("a", 0.91), candidate("b", 0.85)))).isEmpty();
    }

    @Test
    void autoFirst_selectsTopScoredCandidate() {
        SelectionPolicyService service = new SelectionPolicyService(SelectionPolicyService.AUTO_FIRST, 0.82, 0.12);

        assertThat(service.chooseCandidate(result(candidate("a", 0.40), candidate("b", 0.60))))
                .get()
                .extracting(LocationCandidateItem::getCandidateId)
                .isEqualTo("b");
    }

    private PlanningResult result(LocationCandidateItem... candidates) {
        return PlanningResult.forCandidates(List.of(candidates), 0, "route_candidate_selection",
                "route_candidate_selection", "rag_route", Map.of(), Map.of());
    }

    private LocationCandidateItem candidate(String id, double score) {
        LocationCandidateItem item = new LocationCandidateItem();
        item.setCandidateId(id);
        item.setName("candidate-" + id);
        item.setScore(score);
        return item;
    }
}
