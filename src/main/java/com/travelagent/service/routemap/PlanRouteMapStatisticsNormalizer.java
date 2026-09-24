package com.travelagent.service.routemap;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 路线图统计结果归一化组件。
 *
 * <p>MyBatis 聚合查询在不同数据库/驱动下可能返回驼峰或下划线键名，该组件统一把
 * 原始统计 Map 转为管理后台稳定字段。</p>
 */
@Component
public class PlanRouteMapStatisticsNormalizer {

    /**
     * 归一化统计摘要。
     *
     * @param raw 原始摘要 Map
     * @return 管理后台稳定摘要字段
     */
    public Map<String, Object> normalizeSummary(Map<String, Object> raw) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalCount", longValue(raw, "totalCount"));
        summary.put("succeededCount", longValue(raw, "succeededCount"));
        summary.put("failedCount", longValue(raw, "failedCount"));
        summary.put("fallbackCount", longValue(raw, "fallbackCount"));
        summary.put("runningCount", longValue(raw, "runningCount"));
        summary.put("manualRegenerateCount", longValue(raw, "manualRegenerateCount"));
        summary.put("averageLatencyMs", nullableDoubleValue(raw, "averageLatencyMs"));
        return summary;
    }

    /**
     * 归一化统计列表。
     *
     * @param rawRows 原始统计行
     * @return 管理后台稳定统计行
     */
    public List<Map<String, Object>> normalizeRows(List<Map<String, Object>> rawRows) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (rawRows == null) {
            return rows;
        }
        for (Map<String, Object> row : rawRows) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            putIfPresent(normalized, "status", statValue(row, "status"));
            putIfPresent(normalized, "errorCode", statValue(row, "errorCode"));
            putIfPresent(normalized, "userId", statValue(row, "userId"));
            putIfPresent(normalized, "count", statValue(row, "count"));
            putIfPresent(normalized, "succeededCount", statValue(row, "succeededCount"));
            putIfPresent(normalized, "failedCount", statValue(row, "failedCount"));
            putIfPresent(normalized, "fallbackCount", statValue(row, "fallbackCount"));
            putIfPresent(normalized, "manualRegenerateCount", statValue(row, "manualRegenerateCount"));
            rows.add(normalized);
        }
        return rows;
    }

    /**
     * 仅在值存在时写入目标 Map。
     *
     * @param target 目标 Map
     * @param key 字段名
     * @param value 字段值
     */
    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    /**
     * 读取 long 统计值。
     *
     * @param raw 原始 Map
     * @param key 字段名
     * @return long 值，不存在时返回 0
     */
    private long longValue(Map<String, Object> raw, String key) {
        Object value = statValue(raw, key);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    /**
     * 读取可空 double 统计值。
     *
     * @param raw 原始 Map
     * @param key 字段名
     * @return double 值，不存在时返回 null
     */
    private Double nullableDoubleValue(Map<String, Object> raw, String key) {
        Object value = statValue(raw, key);
        return value instanceof Number number ? number.doubleValue() : null;
    }

    /**
     * 以忽略下划线和大小写的方式读取统计字段。
     *
     * @param raw 原始 Map
     * @param key 期望字段名
     * @return 字段值
     */
    private Object statValue(Map<String, Object> raw, String key) {
        if (raw == null || key == null) {
            return null;
        }
        String relaxedKey = key.replace("_", "");
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String candidate = entry.getKey() == null ? "" : entry.getKey().replace("_", "");
            if (relaxedKey.equalsIgnoreCase(candidate)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
