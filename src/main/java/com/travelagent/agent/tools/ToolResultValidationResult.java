package com.travelagent.agent.tools;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ToolResultValidationResult {

    private final boolean valid;
    private final boolean sanitized;
    private final Instant checkedAt;
    private final List<ToolResultValidationIssue> issues;
    private final Map<String, Object> output;

    public ToolResultValidationResult(boolean valid,
                                      boolean sanitized,
                                      Instant checkedAt,
                                      List<ToolResultValidationIssue> issues,
                                      Map<String, Object> output) {
        this.valid = valid;
        this.sanitized = sanitized;
        this.checkedAt = checkedAt == null ? Instant.now() : checkedAt;
        this.issues = issues == null ? List.of() : List.copyOf(issues);
        this.output = output == null ? new LinkedHashMap<>() : new LinkedHashMap<>(output);
    }

    public boolean isValid() {
        return valid;
    }

    public boolean isSanitized() {
        return sanitized;
    }

    public Instant getCheckedAt() {
        return checkedAt;
    }

    public List<ToolResultValidationIssue> getIssues() {
        return issues;
    }

    public Map<String, Object> getOutput() {
        return new LinkedHashMap<>(output);
    }

    public boolean hasIssues() {
        return !issues.isEmpty();
    }

    public Map<String, Object> toMetadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("valid", valid);
        metadata.put("issues", issues.stream().map(ToolResultValidationIssue::toMap).toList());
        metadata.put("checkedAt", checkedAt.toString());
        metadata.put("sanitized", sanitized);
        return metadata;
    }

    public Map<String, Object> toEventPayload(String toolName) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("toolName", toolName);
        payload.put("resultValidation", toMetadata());
        payload.put("valid", valid);
        payload.put("sanitized", sanitized);
        payload.put("checkedAt", checkedAt.toString());
        payload.put("issueCodes", issues.stream().map(ToolResultValidationIssue::code).toList());
        return payload;
    }
}
