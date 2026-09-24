package com.travelagent.agent.tools;

import java.util.LinkedHashMap;
import java.util.Map;

public record ToolResultValidationIssue(String code,
                                        String message,
                                        String severity,
                                        String path,
                                        Map<String, Object> details) {

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("code", code);
        map.put("message", message);
        map.put("severity", severity);
        map.put("path", path);
        map.put("details", details == null ? Map.of() : new LinkedHashMap<>(details));
        return map;
    }
}
