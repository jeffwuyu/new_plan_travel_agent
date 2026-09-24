package com.travelagent.client.amap;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 高德 POI 归一化辅助类。
 *
 * <p>高德不同搜索接口返回字段形态略有差异，该类把前端和上层工具需要的字段
 * 统一为稳定 Map 结构。</p>
 */
class AmapPoiNormalizer {

    /**
     * 批量归一化 POI 列表。
     *
     * @param pois 高德原始 POI 列表
     * @return 归一化后的 POI 列表
     */
    List<Map<String, Object>> normalizePois(List<Map<String, Object>> pois) {
        return pois.stream()
                .map(this::normalizePoi)
                .toList();
    }

    /**
     * 归一化单个 POI。
     *
     * @param poi 高德原始 POI
     * @return 归一化后的 POI
     */
    private Map<String, Object> normalizePoi(Map<String, Object> poi) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("name", firstNonBlank(poi.get("name"), ""));
        normalized.put("address", firstNonBlank(poi.get("address"), ""));
        normalized.put("type", firstNonBlank(poi.get("type"), ""));
        normalized.put("typecode", firstNonBlank(poi.get("typecode"), ""));
        normalized.put("adcode", firstNonBlank(poi.get("adcode"), ""));
        normalized.put("cityname", firstNonBlank(poi.get("cityname"), ""));
        normalized.put("location", firstNonBlank(poi.get("location"), ""));
        Integer distanceMeters = toInteger(poi.get("distance"));
        if (distanceMeters != null) {
            normalized.put("distanceMeters", distanceMeters);
        }
        String location = firstNonBlank(poi.get("location"), "");
        if (location.contains(",")) {
            String[] parts = location.split(",");
            try {
                normalized.put("lng", Double.parseDouble(parts[0].trim()));
                normalized.put("lat", Double.parseDouble(parts[1].trim()));
            } catch (NumberFormatException ignored) {
                // 高德偶发返回不可解析坐标时，保留原始 location 字段即可。
            }
        }
        return normalized;
    }

    /**
     * 将对象转换为整数。
     *
     * @param value 原始值
     * @return 可解析整数，无法解析时返回 null
     */
    private Integer toInteger(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return (int) Math.round(Double.parseDouble(value.toString()));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * 返回第一个非空字符串。
     *
     * @param values 候选值
     * @return 第一个非空字符串，不存在时返回空字符串
     */
    private String firstNonBlank(Object... values) {
        if (values == null) {
            return "";
        }
        for (Object value : values) {
            if (value != null && !value.toString().isBlank()) {
                return value.toString();
            }
        }
        return "";
    }
}
