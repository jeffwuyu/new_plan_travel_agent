package com.travelagent.service.agent;

import com.travelagent.agent.planner.PlanningResult;
import com.travelagent.model.dto.LocationCandidateItem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
public class SelectionPolicyService {

    public static final String MANUAL_ONLY = "manual_only";
    public static final String AUTO_HIGH_CONFIDENCE = "auto_high_confidence";
    public static final String AUTO_FIRST = "auto_first";

    private final String policy;
    private final double confidenceThreshold;
    private final double scoreGapThreshold;

    public SelectionPolicyService(@Value("${agent.selection.policy:auto_high_confidence}") String policy,
                                  @Value("${agent.selection.confidence-threshold:0.82}") double confidenceThreshold,
                                  @Value("${agent.selection.score-gap-threshold:0.12}") double scoreGapThreshold) {
        this.policy = policy == null || policy.isBlank() ? AUTO_HIGH_CONFIDENCE : policy.trim().toLowerCase();
        this.confidenceThreshold = confidenceThreshold;
        this.scoreGapThreshold = scoreGapThreshold;
    }

    public Optional<LocationCandidateItem> chooseCandidate(PlanningResult result) {
        if (result == null || result.recommendationCandidates() == null
                || result.recommendationCandidates().isEmpty()
                || MANUAL_ONLY.equals(policy)) {
            return Optional.empty();
        }
        List<LocationCandidateItem> sorted = result.recommendationCandidates().stream()
                .filter(candidate -> candidate != null && candidate.getName() != null && !candidate.getName().isBlank())
                .sorted(Comparator.comparing(this::score).reversed())
                .toList();
        if (sorted.isEmpty()) {
            return Optional.empty();
        }
        if (AUTO_FIRST.equals(policy)) {
            return Optional.of(sorted.get(0));
        }
        if (!AUTO_HIGH_CONFIDENCE.equals(policy)) {
            return Optional.empty();
        }

        LocationCandidateItem first = sorted.get(0);
        double firstScore = score(first);
        double secondScore = sorted.size() > 1 ? score(sorted.get(1)) : 0.0d;
        if (firstScore >= confidenceThreshold && (sorted.size() == 1 || firstScore - secondScore >= scoreGapThreshold)) {
            return Optional.of(first);
        }
        return Optional.empty();
    }

    public String policy() {
        return policy;
    }

    public double confidenceThreshold() {
        return confidenceThreshold;
    }

    public double scoreGapThreshold() {
        return scoreGapThreshold;
    }

    private double score(LocationCandidateItem candidate) {
        return candidate.getScore() == null ? 0.0d : candidate.getScore();
    }
}
