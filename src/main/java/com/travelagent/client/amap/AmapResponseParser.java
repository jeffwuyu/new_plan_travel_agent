package com.travelagent.client.amap;

import com.fasterxml.jackson.core.type.TypeReference;
import com.travelagent.exception.AgentErrorCode;
import com.travelagent.exception.AgentException;
import com.travelagent.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 高德响应解析器。
 *
 * <p>该类集中处理高德 JSON 状态校验、地理编码、天气、路线、距离和 polyline 解析，
 * 让 `AmapClient` 专注于请求执行、缓存和对外门面方法。</p>
 */
class AmapResponseParser {

    private static final Logger log = LoggerFactory.getLogger(AmapResponseParser.class);
    private static final List<String> RATE_LIMIT_HINTS = List.of(
            "CUQPS_HAS_EXCEEDED_THE_LIMIT",
            "DAILY_QUERY_OVER_LIMIT",
            "ACCESS_TOO_FREQUENT",
            "USER_DAILY_QUERY_OVER_LIMIT",
            "IP_QUERY_OVER_LIMIT"
    );
    private static final List<String> TRANSIENT_ERROR_HINTS = List.of(
            "ENGINE_RESPONSE_DATA_ERROR"
    );

    private final JsonUtil jsonUtil;
    private final String apiKey;

    /**
     * 创建高德响应解析器。
     *
     * @param jsonUtil JSON 工具
     * @param apiKey 当前高德 API Key，用于日志脱敏
     */
    AmapResponseParser(JsonUtil jsonUtil, String apiKey) {
        this.jsonUtil = jsonUtil;
        this.apiKey = apiKey;
    }

    /**
     * 解析高德地理编码响应。
     *
     * @param json 高德原始 JSON 响应
     * @return 包含经纬度和 adcode 的结果
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> parseGeocodeResponse(String json) {
        try {
            Map<String, Object> root = jsonUtil.fromJson(json, new TypeReference<Map<String, Object>>() {});
            validateAmapStatus(root, json, "geocode");

            List<Map<String, Object>> geocodes = (List<Map<String, Object>>) root.get("geocodes");
            if (geocodes == null || geocodes.isEmpty()) {
                throw new RuntimeException("Amap geocode: no results in response: " + json);
            }
            Map<String, Object> first = geocodes.get(0);
            String location = (String) first.get("location");
            if (location == null || !location.contains(",")) {
                throw new RuntimeException("Amap geocode: missing location in response: " + json);
            }
            String[] parts = location.split(",");
            double lng = Double.parseDouble(parts[0].trim());
            double lat = Double.parseDouble(parts[1].trim());
            String adcode = (String) first.getOrDefault("adcode", "");

            return Map.of("lat", lat, "lng", lng, "adcode", adcode);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Amap geocode response: " + e.getMessage(), e);
        }
    }

    /**
     * 解析高德实时天气响应。
     *
     * @param json 高德原始 JSON 响应
     * @return 前端和工具链使用的天气字段
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> parseWeatherResponse(String json) {
        try {
            Map<String, Object> root = jsonUtil.fromJson(json, new TypeReference<Map<String, Object>>() {});
            validateAmapStatus(root, json, "weather");

            List<Map<String, Object>> lives = (List<Map<String, Object>>) root.get("lives");
            if (lives == null || lives.isEmpty()) {
                throw new RuntimeException("Amap weather: no lives data in response: " + json);
            }
            Map<String, Object> live = lives.get(0);

            return Map.of(
                    "weather", live.getOrDefault("weather", "未知"),
                    "temperature", live.getOrDefault("temperature", ""),
                    "windDirection", live.getOrDefault("winddirection", ""),
                    "windPower", live.getOrDefault("windpower", ""),
                    "humidity", live.getOrDefault("humidity", "")
            );
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Amap weather response: " + e.getMessage(), e);
        }
    }

    /**
     * 解析高德天气预报响应。
     *
     * @param json 高德原始 JSON 响应
     * @return 当前天气摘要和多日预报列表
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> parseWeatherForecastResponse(String json) {
        try {
            Map<String, Object> root = jsonUtil.fromJson(json, new TypeReference<Map<String, Object>>() {});
            validateAmapStatus(root, json, "weatherForecast");

            List<Map<String, Object>> forecasts = (List<Map<String, Object>>) root.get("forecasts");
            if (forecasts == null || forecasts.isEmpty()) {
                throw new RuntimeException("Amap weather forecast: no forecasts data in response: " + json);
            }
            Map<String, Object> forecast = forecasts.get(0);
            List<Map<String, Object>> casts = (List<Map<String, Object>>) forecast.get("casts");
            if (casts == null || casts.isEmpty()) {
                throw new RuntimeException("Amap weather forecast: no casts data in response: " + json);
            }

            Map<String, Object> first = casts.get(0);
            List<Map<String, Object>> forecastDays = casts.stream()
                    .map(this::buildForecastDay)
                    .toList();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("weather", firstNonBlank(first.get("dayweather"), first.get("nightweather"), "未知"));
            result.put("temperature", firstNonBlank(first.get("daytemp"), first.get("nighttemp"), ""));
            result.put("windDirection", firstNonBlank(first.get("daywind"), first.get("nightwind"), ""));
            result.put("windPower", firstNonBlank(first.get("daypower"), first.get("nightpower"), ""));
            result.put("humidity", "");
            result.put("province", firstNonBlank(forecast.get("province"), ""));
            result.put("city", firstNonBlank(forecast.get("city"), ""));
            result.put("adcode", firstNonBlank(forecast.get("adcode"), ""));
            result.put("reportTime", firstNonBlank(forecast.get("reporttime"), ""));
            result.put("forecastDays", forecastDays);
            return result;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Amap weather forecast response: " + e.getMessage(), e);
        }
    }

    /**
     * 按路线模式解析高德路线响应。
     *
     * @param json 高德原始 JSON 响应
     * @param routeMode 已选路线模式
     * @return 耗时、距离和路线几何信息
     */
    Map<String, Object> parseDirectionResponse(String json, String routeMode) {
        try {
            Map<String, Object> root = jsonUtil.fromJson(json, new TypeReference<Map<String, Object>>() {});
            validateAmapStatus(root, json, "direction");
            return switch (routeMode) {
                case "transit" -> parseTransitDirectionResponse(root, json);
                case "bicycling" -> parseBicyclingDirectionResponse(root, json);
                default -> parseStandardDirectionResponse(root, json, routeMode);
            };
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Amap direction response: " + e.getMessage(), e);
        }
    }

    /**
     * 解析高德距离测量响应。
     *
     * @param json 高德原始 JSON 响应
     * @return 米和公里维度的距离结果
     */
    Map<String, Object> parseDistanceResponse(String json) {
        try {
            Map<String, Object> root = jsonUtil.fromJson(json, new TypeReference<Map<String, Object>>() {});
            validateAmapStatus(root, json, "distance");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> results = (List<Map<String, Object>>) root.get("results");
            if (results == null || results.isEmpty()) {
                throw new RuntimeException("Amap distance: no results in response: " + json);
            }
            Integer distanceMeters = toInteger(results.get(0).get("distance"));
            if (distanceMeters == null) {
                throw new RuntimeException("Amap distance: missing distance in response: " + json);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("distanceMeters", distanceMeters);
            result.put("distanceKm", round(distanceMeters / 1000.0d));
            return result;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Amap distance response: " + e.getMessage(), e);
        }
    }

    /**
     * 校验高德业务状态，并把限流、瞬时错误映射到 Agent 错误码。
     *
     * @param root 根节点数据
     * @param rawJson 原始 JSON，用于脱敏日志
     * @param apiName API 名称
     */
    void validateAmapStatus(Map<String, Object> root, String rawJson, String apiName) {
        Object status = root.get("status");
        if (!"1".equals(String.valueOf(status))) {
            String info = String.valueOf(root.getOrDefault("info", "unknown"));
            String maskedRawJson = maskApiKey(rawJson);
            log.warn("[AmapResponseParser] api={} status={} info={} payload={}", apiName, status, info, maskedRawJson);
            if (isRateLimited(info)) {
                throw new AgentException(AgentErrorCode.TOOL_AMAP_RATE_LIMIT,
                        "Amap rate limit exceeded for " + apiName + ": status=" + status + ", info=" + info);
            }
            if (isTransientError(info)) {
                throw new AgentException(AgentErrorCode.TOOL_AMAP_TRANSIENT,
                        "Amap transient error for " + apiName + ": status=" + status + ", info=" + info);
            }
            throw new AgentException(AgentErrorCode.TOOL_AMAP_ERROR,
                    "Amap API error for " + apiName + ": status=" + status + ", info=" + info);
        }
    }

    /**
     * 组装单日天气预报字段。
     *
     * @param cast 高德 casts 数组中的单日记录
     * @return 归一化后的单日天气字段
     */
    private Map<String, Object> buildForecastDay(Map<String, Object> cast) {
        Map<String, Object> day = new LinkedHashMap<>();
        day.put("date", firstNonBlank(cast.get("date"), ""));
        day.put("week", firstNonBlank(cast.get("week"), ""));
        day.put("dayWeather", firstNonBlank(cast.get("dayweather"), ""));
        day.put("nightWeather", firstNonBlank(cast.get("nightweather"), ""));
        day.put("tempHigh", firstNonBlank(cast.get("daytemp"), ""));
        day.put("tempLow", firstNonBlank(cast.get("nighttemp"), ""));
        day.put("dayWind", firstNonBlank(cast.get("daywind"), ""));
        day.put("nightWind", firstNonBlank(cast.get("nightwind"), ""));
        day.put("dayWindPower", firstNonBlank(cast.get("daypower"), ""));
        day.put("nightWindPower", firstNonBlank(cast.get("nightpower"), ""));
        return day;
    }

    /**
     * 解析驾车或步行等标准路线响应。
     *
     * @param root 根节点数据
     * @param json 原始 JSON，用于错误上下文
     * @param routeMode 路线模式
     * @return 归一化路线结果
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseStandardDirectionResponse(Map<String, Object> root,
                                                               String json,
                                                               String routeMode) {
        Map<String, Object> route = (Map<String, Object>) root.get("route");
        if (route == null) {
            throw new RuntimeException("Amap direction: missing route in response: " + json);
        }
        List<Map<String, Object>> paths = (List<Map<String, Object>>) route.get("paths");
        if (paths == null || paths.isEmpty()) {
            throw new RuntimeException("Amap direction: no paths in response: " + json);
        }
        Map<String, Object> path = paths.get(0);
        String rawPolyline = collectPolyline(path);
        return buildDirectionResult(path.get("duration"), path.get("distance"), routeMode, rawPolyline);
    }

    /**
     * 解析高德骑行路线响应。
     *
     * @param root 根节点数据
     * @param json 原始 JSON，用于错误上下文
     * @return 归一化路线结果
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseBicyclingDirectionResponse(Map<String, Object> root, String json) {
        Map<String, Object> data = (Map<String, Object>) root.get("data");
        if (data == null) {
            throw new RuntimeException("Amap bicycling: missing data in response: " + json);
        }
        List<Map<String, Object>> paths = (List<Map<String, Object>>) data.get("paths");
        if (paths == null || paths.isEmpty()) {
            throw new RuntimeException("Amap bicycling: no paths in response: " + json);
        }
        Map<String, Object> path = paths.get(0);
        String rawPolyline = collectPolyline(path);
        return buildDirectionResult(path.get("duration"), path.get("distance"), "bicycling", rawPolyline);
    }

    /**
     * 解析高德公交路线响应。
     *
     * @param root 根节点数据
     * @param json 原始 JSON，用于错误上下文
     * @return 归一化路线结果
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseTransitDirectionResponse(Map<String, Object> root, String json) {
        Map<String, Object> route = (Map<String, Object>) root.get("route");
        if (route == null) {
            throw new RuntimeException("Amap transit: missing route in response: " + json);
        }
        List<Map<String, Object>> transits = (List<Map<String, Object>>) route.get("transits");
        if (transits == null || transits.isEmpty()) {
            throw new RuntimeException("Amap transit: no transits in response: " + json);
        }
        Map<String, Object> transit = transits.get(0);
        String rawPolyline = collectPolyline(transit);
        return buildDirectionResult(transit.get("duration"), transit.get("distance"), "transit", rawPolyline);
    }

    /**
     * 构建包含路线几何的路线结果。
     *
     * @param duration 高德返回的秒级耗时
     * @param distance 高德返回的米级距离
     * @param routeMode 路线模式
     * @param rawPolyline 高德原始 polyline 字符串，可为空
     * @return 归一化路线结果
     */
    private Map<String, Object> buildDirectionResult(Object duration, Object distance, String routeMode, String rawPolyline) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("durationMin", toDurationMinutes(duration));
        Integer distanceMeters = toInteger(distance);
        if (distanceMeters != null) {
            result.put("distanceMeters", distanceMeters);
        }
        result.put("routeMode", routeMode);
        if (rawPolyline != null && !rawPolyline.isBlank()) {
            result.put("rawPolyline", rawPolyline);
            result.put("polyline", parsePolyline(rawPolyline));
            result.put("fallback", false);
        }
        return result;
    }

    /**
     * 从不同路线响应结构中收集 polyline 片段。
     *
     * @param pathLike 高德 path/transit 等路线节点
     * @return 拼接后的原始 polyline，缺失时返回 null
     */
    private String collectPolyline(Map<String, Object> pathLike) {
        if (pathLike == null) {
            return null;
        }
        List<String> parts = new java.util.ArrayList<>();
        Object direct = pathLike.get("polyline");
        if (direct != null && !direct.toString().isBlank()) {
            parts.add(direct.toString());
        }
        Object stepsObj = pathLike.get("steps");
        if (stepsObj instanceof List<?> steps) {
            for (Object stepObj : steps) {
                if (stepObj instanceof Map<?, ?> step) {
                    Object polyline = step.get("polyline");
                    if (polyline != null && !polyline.toString().isBlank()) {
                        parts.add(polyline.toString());
                    }
                }
            }
        }
        Object segmentsObj = pathLike.get("segments");
        if (segmentsObj instanceof List<?> segments) {
            for (Object segmentObj : segments) {
                if (!(segmentObj instanceof Map<?, ?> segment)) {
                    continue;
                }
                Object walking = segment.get("walking");
                if (walking instanceof Map<?, ?> walkingMap) {
                    Object steps = walkingMap.get("steps");
                    if (steps instanceof List<?> walkingSteps) {
                        for (Object stepObj : walkingSteps) {
                            if (stepObj instanceof Map<?, ?> step) {
                                Object polyline = step.get("polyline");
                                if (polyline != null && !polyline.toString().isBlank()) {
                                    parts.add(polyline.toString());
                                }
                            }
                        }
                    }
                }
            }
        }
        return parts.isEmpty() ? null : String.join(";", parts);
    }

    /**
     * 将高德 polyline 字符串解析为经纬度点列表。
     *
     * @param rawPolyline 高德以分号分隔的坐标串
     * @return 经纬度点列表，非法点会被跳过
     */
    private List<Map<String, Object>> parsePolyline(String rawPolyline) {
        if (rawPolyline == null || rawPolyline.isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> points = new java.util.ArrayList<>();
        for (String token : rawPolyline.split(";")) {
            String[] parts = token.trim().split(",");
            if (parts.length < 2) {
                continue;
            }
            try {
                points.add(Map.of(
                        "lng", Double.parseDouble(parts[0].trim()),
                        "lat", Double.parseDouble(parts[1].trim())
                ));
            } catch (NumberFormatException ignored) {
                // 高德偶发返回无法解析坐标时跳过该点，保留其余可用路线。
            }
        }
        return points;
    }

    /**
     * 判断高德错误信息是否表示限流。
     *
     * @param info 高德 info 字段
     * @return 命中限流提示时返回 true
     */
    private boolean isRateLimited(String info) {
        if (info == null || info.isBlank()) {
            return false;
        }
        String normalized = info.toUpperCase(Locale.ROOT);
        return RATE_LIMIT_HINTS.stream().anyMatch(normalized::contains);
    }

    /**
     * 判断高德错误信息是否表示可重试的瞬时错误。
     *
     * @param info 高德 info 字段
     * @return 命中瞬时错误提示时返回 true
     */
    private boolean isTransientError(String info) {
        if (info == null || info.isBlank()) {
            return false;
        }
        String normalized = info.toUpperCase(Locale.ROOT);
        return TRANSIENT_ERROR_HINTS.stream().anyMatch(normalized::contains);
    }

    /**
     * 对日志中的高德 API Key 做脱敏。
     *
     * @param text 原始文本
     * @return 脱敏后的文本
     */
    private String maskApiKey(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        if (apiKey == null || apiKey.isBlank()) {
            return text;
        }
        return text.replace(apiKey, "***");
    }

    /**
     * 将高德秒级耗时转换为向上取整的分钟数。
     *
     * @param durationObj 高德 duration 字段
     * @return 分钟数，缺失时返回 0
     */
    private int toDurationMinutes(Object durationObj) {
        if (durationObj == null) {
            return 0;
        }
        return (int) Math.ceil(Double.parseDouble(durationObj.toString()) / 60.0d);
    }

    /**
     * 将高德数字字段转换为整数。
     *
     * @param value 原始数字值
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
     * 将浮点数保留到三位小数。
     *
     * @param value 原始浮点数
     * @return 四舍五入后的值
     */
    private double round(double value) {
        return Math.round(value * 1000.0d) / 1000.0d;
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
