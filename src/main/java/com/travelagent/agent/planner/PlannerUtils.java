package com.travelagent.agent.planner;

import java.util.List;

class PlannerUtils {

    private PlannerUtils() {}

    static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    static Integer parseInteger(Object value) {
        if (value == null) {
            return null;
        }
        try {
            String text = String.valueOf(value).replaceAll("[^0-9-]", "");
            return text.isBlank() ? null : Integer.parseInt(text);
        } catch (Exception e) {
            return null;
        }
    }

    static boolean booleanValue(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    static List<String> toStringList(Object value) {
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        return raw.stream().map(String::valueOf).filter(v -> !v.isBlank()).toList();
    }

    static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
