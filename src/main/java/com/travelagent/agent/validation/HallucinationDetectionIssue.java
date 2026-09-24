package com.travelagent.agent.validation;

import java.util.LinkedHashMap;
import java.util.Map;

public record HallucinationDetectionIssue(String code,
                                          String severity,
                                          String targetPath,
                                          String claimedValue,
                                          String message,
                                          Map<String, Object> details) {

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("code", code);
        map.put("severity", severity);
        map.put("targetPath", targetPath);
        map.put("claimedValue", claimedValue);
        map.put("message", message);
        map.put("details", details == null ? Map.of() : new LinkedHashMap<>(details));
        return map;
    }
}
