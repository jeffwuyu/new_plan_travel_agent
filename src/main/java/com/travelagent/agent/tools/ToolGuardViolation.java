package com.travelagent.agent.tools;

import java.util.Map;

public record ToolGuardViolation(String code,
                                 String message,
                                 Map<String, Object> details) {

    public ToolGuardViolation(String code, String message) {
        this(code, message, Map.of());
    }
}
