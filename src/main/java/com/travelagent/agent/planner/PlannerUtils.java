package com.travelagent.agent.planner;

import java.util.List;

class PlannerUtils {

    /**
     * 初始化PlannerUtils 实例。
     */
    private PlannerUtils() {}

    /**
     * 处理stringValue。
     * @param value 键值
     * @return 返回处理结果。
     */
    static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    /**
     * 解析integer。
     * @param value 键值
     * @return 返回处理结果。
     */
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

    /**
     * 判断booleanValue。
     * @param value 键值
     * @return 是否满足当前条件。
     */
    static boolean booleanValue(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    /**
     * 将数据转换为stringlist。
     * @param value 键值
     * @return 返回处理后的列表结果。
     */
    static List<String> toStringList(Object value) {
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        return raw.stream().map(String::valueOf).filter(v -> !v.isBlank()).toList();
    }

    /**
     * 处理firstNonBlank。
     * @param values v al ue s 参数
     * @return 返回处理结果。
     */
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
