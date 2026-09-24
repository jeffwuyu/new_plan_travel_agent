package com.travelagent.agent.validation;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class HallucinationDetectionResult {

    private final boolean valid;
    private final Instant checkedAt;
    private final List<HallucinationDetectionIssue> issues;
    private final String retryFeedback;

    public HallucinationDetectionResult(boolean valid,
                                        Instant checkedAt,
                                        List<HallucinationDetectionIssue> issues,
                                        String retryFeedback) {
        this.valid = valid;
        this.checkedAt = checkedAt == null ? Instant.now() : checkedAt;
        this.issues = issues == null ? List.of() : List.copyOf(issues);
        this.retryFeedback = retryFeedback == null ? "" : retryFeedback;
    }

    public static HallucinationDetectionResult valid() {
        return new HallucinationDetectionResult(true, Instant.now(), List.of(), "");
    }

    public boolean isValid() {
        return valid;
    }

    public Instant getCheckedAt() {
        return checkedAt;
    }

    public List<HallucinationDetectionIssue> getIssues() {
        return issues;
    }

    public String getRetryFeedback() {
        return retryFeedback;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", "hallucination_detection");
        map.put("valid", valid);
        map.put("checkedAt", checkedAt.toString());
        map.put("issueCodes", issues.stream().map(HallucinationDetectionIssue::code).distinct().toList());
        map.put("issues", issues.stream().map(HallucinationDetectionIssue::toMap).toList());
        map.put("retryFeedback", retryFeedback);
        return map;
    }
}
