package com.travelagent.client.amap;

import com.fasterxml.jackson.core.type.TypeReference;
import com.travelagent.exception.AgentErrorCode;
import com.travelagent.exception.AgentException;
import com.travelagent.util.JsonUtil;
import com.travelagent.util.RedisUtil;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

@Service
public class AmapClient {

    private static final Logger log = LoggerFactory.getLogger(AmapClient.class);
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

    private static final Duration CACHE_TTL = Duration.ofHours(1);
    private int retryMaxAttempts = 3;
    private long[] retryBackoffMillis = {1000L, 2000L, 5000L};

    @Value("${amap.api-key}")
    private String apiKey;

    @Value("${amap.geocode-url}")
    private String geocodeUrl;

    @Value("${amap.weather-url}")
    private String weatherUrl;

    @Value("${amap.direction-url}")
    private String directionUrl;

    @Value("${amap.walking-direction-url}")
    private String walkingDirectionUrl;

    @Value("${amap.bicycling-direction-url:https://restapi.amap.com/v4/direction/bicycling}")
    private String bicyclingDirectionUrl;

    @Value("${amap.transit-direction-url:https://restapi.amap.com/v3/direction/transit/integrated}")
    private String transitDirectionUrl;

    @Value("${amap.distance-url:https://restapi.amap.com/v3/distance}")
    private String distanceUrl;

    @Value("${amap.nearby-search-url}")
    private String nearbySearchUrl;

    @Autowired
    private OkHttpClient okHttpClient;

    @Autowired
    private RedisUtil redisUtil;

    @Autowired
    private JsonUtil jsonUtil;

    @Autowired
    private AmapRateLimiter rateLimiter;

    /**
     * 处理geocode。
     * @param attractionName 景点名称
     * @param region 区域信息
     * @return 返回处理后的映射结果。
     */
    public Map<String, Object> geocode(String attractionName, String region) {
        String cacheKey = "amap:geocode:" + attractionName + ":" + region;
        Map<String, Object> cached = getCached(cacheKey);
        if (cached != null) {
            return cached;
        }

        String url = geocodeUrl + "?key=" + apiKey + "&address=" + encode(attractionName);
        if (region != null && !region.isBlank()) {
            url += "&city=" + encode(region);
        }

        String finalUrl = url;
        return executeWithRetry("geocode", () -> {
            String body = executeGet(finalUrl, "geocode");
            Map<String, Object> result = parseGeocodeResponse(body);
            putCached(cacheKey, result);
            return result;
        });
    }

    /**
     * 获取weather。
     * @param adcode 行政区划编码
     * @return 返回处理后的映射结果。
     */
    public Map<String, Object> getWeather(String adcode) {
        String cacheKey = "amap:weather:" + adcode;
        Map<String, Object> cached = getCached(cacheKey);
        if (cached != null) {
            return cached;
        }

        String url = weatherUrl + "?key=" + apiKey + "&city=" + adcode + "&extensions=base";
        return executeWithRetry("weather", () -> {
            String body = executeGet(url, "weather");
            Map<String, Object> result = parseWeatherResponse(body);
            putCached(cacheKey, result);
            return result;
        });
    }

    /**
     * 获取drivingduration。
     * @param originLng 起点经度
     * @param originLat 起点纬度
     * @param destLng 终点经度
     * @param destLat 终点纬度
     * @return 返回处理后的映射结果。
     */
    public Map<String, Object> getDrivingDuration(double originLng, double originLat,
                                                  double destLng, double destLat) {
        return getTravelDuration(originLng, originLat, destLng, destLat, "driving");
    }

    /**
     * 获取travelduration。
     * @param originLng 起点经度
     * @param originLat 起点纬度
     * @param destLng 终点经度
     * @param destLat 终点纬度
     * @param travelMode 出行方式
     * @return 返回处理后的映射结果。
     */
    public Map<String, Object> getTravelDuration(double originLng, double originLat,
                                                 double destLng, double destLat,
                                                 String travelMode) {
        String origin = originLng + "," + originLat;
        String destination = destLng + "," + destLat;

        for (String routeMode : resolveTravelModeSequence(travelMode)) {
            String cacheKey = String.format("amap:traffic:%s:%.6f,%.6f:%.6f,%.6f",
                    routeMode, originLng, originLat, destLng, destLat);
            Map<String, Object> cached = getCached(cacheKey);
            if (cached != null) {
                return ensureRouteMode(cached, routeMode);
            }

            try {
                Map<String, Object> result = executeWithRetry("direction", () -> {
                    String body = executeGet(buildDirectionUrl(routeMode, origin, destination), "direction");
                    return parseDirectionResponse(body, routeMode);
                });
                putCached(cacheKey, result);
                return result;
            } catch (RuntimeException e) {
                if (!shouldFallbackToNextMode(routeMode, travelMode)) {
                    throw e;
                }
                log.warn("[AmapClient] direction mode={} failed, fallback to next mode: {}",
                        routeMode, e.getMessage());
            }
        }

        throw new AgentException(AgentErrorCode.TOOL_AMAP_ERROR,
                "No available Amap direction mode for travelMode=" + normalizeTravelMode(travelMode));
    }

    /**
     * 获取distance。
     * @param originLng 起点经度
     * @param originLat 起点纬度
     * @param destLng 终点经度
     * @param destLat 终点纬度
     * @return 返回处理后的映射结果。
     */
    public Map<String, Object> getDistance(double originLng, double originLat,
                                           double destLng, double destLat) {
        String cacheKey = String.format("amap:distance:%.6f,%.6f:%.6f,%.6f",
                originLng, originLat, destLng, destLat);
        Map<String, Object> cached = getCached(cacheKey);
        if (cached != null) {
            return cached;
        }

        String url = distanceUrl + "?key=" + apiKey
                + "&origins=" + originLng + "," + originLat
                + "&destination=" + destLng + "," + destLat
                + "&type=0";
        return executeWithRetry("distance", () -> {
            String body = executeGet(url, "distance");
            Map<String, Object> result = parseDistanceResponse(body);
            putCached(cacheKey, result);
            return result;
        });
    }

    /**
     * 搜索nearbypois。
     * @param lng 经度
     * @param lat 纬度
     * @param radius 搜索半径
     * @param keywords 关键词
     * @param types POI类型
     * @param page 页码
     * @param pageSize 每页数量
     * @return 返回处理后的列表结果。
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> searchNearbyPois(double lng, double lat,
                                                      int radius,
                                                      String keywords,
                                                      String types,
                                                      int page,
                                                      int pageSize) {
        String cacheKey = String.format("amap:nearby:%.6f,%.6f:%d:%s:%s:%d:%d",
                lng, lat, radius, blankToDash(keywords), blankToDash(types), page, pageSize);
        try {
            String cached = redisUtil.getString(cacheKey);
            if (cached != null) {
                return jsonUtil.fromJson(cached, new TypeReference<List<Map<String, Object>>>() {});
            }
        } catch (Exception e) {
            log.warn("[AmapClient nearby cache READ error] key={}: {}", cacheKey, e.getMessage());
        }

        StringBuilder url = new StringBuilder(nearbySearchUrl)
                .append("?key=").append(apiKey)
                .append("&location=").append(lng).append(",").append(lat)
                .append("&radius=").append(radius)
                .append("&page=").append(page)
                .append("&offset=").append(pageSize)
                .append("&sortrule=distance");
        if (keywords != null && !keywords.isBlank()) {
            url.append("&keywords=").append(encode(keywords));
        }
        if (types != null && !types.isBlank()) {
            url.append("&types=").append(encode(types));
        }

        return executeWithRetry("nearby", () -> {
            String body = executeGet(url.toString(), "nearby");
            try {
                Map<String, Object> root = jsonUtil.fromJson(body, new TypeReference<Map<String, Object>>() {});
                validateAmapStatus(root, body, "nearby");
                List<Map<String, Object>> pois = (List<Map<String, Object>>) root.get("pois");
                List<Map<String, Object>> safePois = pois == null ? List.of() : pois;
                redisUtil.setString(cacheKey, jsonUtil.toJson(safePois), CACHE_TTL);
                return safePois;
            } catch (AgentException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("Failed to parse Amap nearby POI response: " + e.getMessage(), e);
            }
        });
    }

    /**
     * 执行withretry。
     * @param apiName a pi Na me 参数
     * @param action a ct io n 参数
     * @return 返回处理结果。
     */
    private <T> T executeWithRetry(String apiName, Callable<T> action) {
        int maxAttempts = Math.max(1, retryMaxAttempts);
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.call();
            } catch (AgentException e) {
                if (!shouldRetry(e, attempt, maxAttempts)) {
                    throw e;
                }
                long backoffMillis = resolveBackoffMillis(attempt);
                log.warn("[AmapClient] api={} transient failure on attempt {}/{}: {}. retrying in {} ms",
                        apiName, attempt, maxAttempts, e.getMessage(), backoffMillis);
                sleepForRetry(backoffMillis, apiName);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("Amap call failed for " + apiName + ": " + e.getMessage(), e);
            }
        }
        throw new AgentException(AgentErrorCode.TOOL_AMAP_TRANSIENT,
                "Amap transient error retry budget exhausted for " + apiName);
    }

    /**
     * 执行get。
     * @param url 请求地址
     * @param apiName a pi Na me 参数
     * @return 返回处理结果。
     */
    private String executeGet(String url, String apiName) {
        if (rateLimiter != null) {
            rateLimiter.acquire(apiName);
        }
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new AgentException(AgentErrorCode.TOOL_AMAP_ERROR,
                        "Amap API returned HTTP " + response.code() + " for URL: " + url);
            }
            if (response.body() == null) {
                throw new AgentException(AgentErrorCode.TOOL_AMAP_ERROR,
                        "Amap API returned empty body for URL: " + url);
            }
            return response.body().string();
        } catch (AgentException ae) {
            throw ae;
        } catch (IOException e) {
            throw classifyAmapException(e, url);
        }
    }

    /**
     * 处理classifyAmapException。
     * @param e 异常对象
     * @param url 请求地址
     * @return 返回处理结果。
     */
    private AgentException classifyAmapException(Exception e, String url) {
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase(Locale.ROOT) : "";
        if (msg.contains("timeout") || msg.contains("timed out")
                || e.getCause() instanceof java.net.SocketTimeoutException) {
            return new AgentException(AgentErrorCode.TOOL_AMAP_TIMEOUT,
                    "Amap request timed out for URL: " + url, e);
        }
        return new AgentException(AgentErrorCode.TOOL_AMAP_ERROR,
                "Amap API error: " + e.getMessage(), e);
    }

    /**
     * 解析geocoderesponse。
     * @param json JSON字符串
     * @return 返回处理后的映射结果。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseGeocodeResponse(String json) {
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
     * 解析weatherresponse。
     * @param json JSON字符串
     * @return 返回处理后的映射结果。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseWeatherResponse(String json) {
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
     * 解析directionresponse。
     * @param json JSON字符串
     * @param routeMode 路线模式
     * @return 返回处理后的映射结果。
     */
    private Map<String, Object> parseDirectionResponse(String json, String routeMode) {
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
     * 解析standarddirectionresponse。
     * @param root 根节点数据
     * @param json JSON字符串
     * @param routeMode 路线模式
     * @return 返回处理后的映射结果。
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
        return buildDirectionResult(path.get("duration"), path.get("distance"), routeMode);
    }

    /**
     * 解析bicyclingdirectionresponse。
     * @param root 根节点数据
     * @param json JSON字符串
     * @return 返回处理后的映射结果。
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
        return buildDirectionResult(path.get("duration"), path.get("distance"), "bicycling");
    }

    /**
     * 解析transitdirectionresponse。
     * @param root 根节点数据
     * @param json JSON字符串
     * @return 返回处理后的映射结果。
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
        return buildDirectionResult(transit.get("duration"), transit.get("distance"), "transit");
    }

    /**
     * 解析distanceresponse。
     * @param json JSON字符串
     * @return 返回处理后的映射结果。
     */
    private Map<String, Object> parseDistanceResponse(String json) {
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
     * 构建directionresult。
     * @param duration d ur at io n 参数
     * @param distance d is ta nc e 参数
     * @param routeMode 路线模式
     * @return 返回处理后的映射结果。
     */
    private Map<String, Object> buildDirectionResult(Object duration, Object distance, String routeMode) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("durationMin", toDurationMinutes(duration));
        Integer distanceMeters = toInteger(distance);
        if (distanceMeters != null) {
            result.put("distanceMeters", distanceMeters);
        }
        result.put("routeMode", routeMode);
        return result;
    }

    /**
     * 校验amapstatus。
     * @param root 根节点数据
     * @param rawJson r aw Js on 参数
     * @param apiName a pi Na me 参数
     */
    private void validateAmapStatus(Map<String, Object> root, String rawJson, String apiName) {
        Object status = root.get("status");
        if (!"1".equals(String.valueOf(status))) {
            String info = String.valueOf(root.getOrDefault("info", "unknown"));
            String maskedRawJson = maskApiKey(rawJson);
            log.warn("[AmapClient] api={} status={} info={} payload={}", apiName, status, info, maskedRawJson);
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
     * 判断ratelimited。
     * @param info i nf o 参数
     * @return 是否满足当前条件。
     */
    private boolean isRateLimited(String info) {
        if (info == null || info.isBlank()) {
            return false;
        }
        String normalized = info.toUpperCase(Locale.ROOT);
        return RATE_LIMIT_HINTS.stream().anyMatch(normalized::contains);
    }

    /**
     * 判断transienterror。
     * @param info i nf o 参数
     * @return 是否满足当前条件。
     */
    private boolean isTransientError(String info) {
        if (info == null || info.isBlank()) {
            return false;
        }
        String normalized = info.toUpperCase(Locale.ROOT);
        return TRANSIENT_ERROR_HINTS.stream().anyMatch(normalized::contains);
    }

    /**
     * 判断是否应执行retry。
     * @param exception 异常对象
     * @param attempt a tt em pt 参数
     * @param maxAttempts m ax At te mp ts 参数
     * @return 是否满足当前条件。
     */
    private boolean shouldRetry(AgentException exception, int attempt, int maxAttempts) {
        return exception.getErrorCode() == AgentErrorCode.TOOL_AMAP_TRANSIENT && attempt < maxAttempts;
    }

    /**
     * 解析并确定backoffmillis。
     * @param attempt a tt em pt 参数
     * @return 返回处理结果。
     */
    private long resolveBackoffMillis(int attempt) {
        if (retryBackoffMillis == null || retryBackoffMillis.length == 0) {
            return 0L;
        }
        int index = Math.min(Math.max(0, attempt - 1), retryBackoffMillis.length - 1);
        return Math.max(0L, retryBackoffMillis[index]);
    }

    /**
     * 处理sleepForRetry。
     * @param millis m il li s 参数
     * @param apiName a pi Na me 参数
     */
    private void sleepForRetry(long millis, String apiName) {
        if (millis <= 0L) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentException(AgentErrorCode.TOOL_AMAP_TRANSIENT,
                    "Interrupted while waiting to retry Amap " + apiName, e);
        }
    }

    /**
     * 处理maskApiKey。
     * @param text 文本内容
     * @return 返回处理结果。
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
     * 获取cached。
     * @param cacheKey 缓存键
     * @return 返回处理后的映射结果。
     */
    private Map<String, Object> getCached(String cacheKey) {
        try {
            String json = redisUtil.getString(cacheKey);
            if (json != null) {
                log.debug("[AmapClient cache HIT] key={}", cacheKey);
                return jsonUtil.fromJson(json, new TypeReference<Map<String, Object>>() {});
            }
        } catch (Exception e) {
            log.warn("[AmapClient cache READ error] key={}: {}", cacheKey, e.getMessage());
        }
        return null;
    }

    /**
     * 处理putCached。
     * @param cacheKey 缓存键
     * @param value 键值
     */
    private void putCached(String cacheKey, Map<String, Object> value) {
        try {
            redisUtil.setString(cacheKey, jsonUtil.toJson(value), CACHE_TTL);
        } catch (Exception e) {
            log.warn("[AmapClient cache WRITE error] key={}: {}", cacheKey, e.getMessage());
        }
    }

    /**
     * 构建directionurl。
     * @param routeMode 路线模式
     * @param origin 起点坐标
     * @param destination 终点坐标
     * @return 返回处理结果。
     */
    private String buildDirectionUrl(String routeMode, String origin, String destination) {
        return switch (routeMode) {
            case "walking" -> walkingDirectionUrl + "?key=" + apiKey
                    + "&origin=" + origin
                    + "&destination=" + destination;
            case "bicycling" -> bicyclingDirectionUrl + "?key=" + apiKey
                    + "&origin=" + origin
                    + "&destination=" + destination;
            case "transit" -> transitDirectionUrl + "?key=" + apiKey
                    + "&origin=" + origin
                    + "&destination=" + destination
                    + "&city=auto"
                    + "&strategy=0";
            default -> directionUrl + "?key=" + apiKey
                    + "&origin=" + origin
                    + "&destination=" + destination
                    + "&strategy=0";
        };
    }

    /**
     * 解析并确定travelmodesequence。
     * @param travelMode 出行方式
     * @return 返回处理后的列表结果。
     */
    private List<String> resolveTravelModeSequence(String travelMode) {
        String normalized = normalizeTravelMode(travelMode);
        if ("walking".equals(normalized)) {
            return List.of("walking");
        }
        if ("transit".equals(normalized)) {
            return List.of("transit", "bicycling", "walking");
        }
        return List.of("driving");
    }

    /**
     * 规范化travelmode。
     * @param travelMode 出行方式
     * @return 返回处理结果。
     */
    private String normalizeTravelMode(String travelMode) {
        String normalized = travelMode == null ? "driving" : travelMode.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "walking", "transit", "bicycling" -> normalized;
            default -> "driving";
        };
    }

    /**
     * 判断是否应执行fallbacktonextmode。
     * @param attemptedMode a tt em pt ed Mo de 参数
     * @param requestedMode r eq ue st ed Mo de 参数
     * @return 是否满足当前条件。
     */
    private boolean shouldFallbackToNextMode(String attemptedMode, String requestedMode) {
        return "transit".equals(normalizeTravelMode(requestedMode))
                && ("transit".equals(attemptedMode) || "bicycling".equals(attemptedMode));
    }

    /**
     * 处理ensureRouteMode。
     * @param cached c ac he d 参数
     * @param routeMode 路线模式
     * @return 返回处理后的映射结果。
     */
    private Map<String, Object> ensureRouteMode(Map<String, Object> cached, String routeMode) {
        if (cached.get("routeMode") != null) {
            return cached;
        }
        Map<String, Object> adjusted = new LinkedHashMap<>(cached);
        adjusted.put("routeMode", routeMode);
        return adjusted;
    }

    /**
     * 将数据转换为durationminutes。
     * @param durationObj d ur at io nO bj 参数
     * @return 返回处理结果。
     */
    private int toDurationMinutes(Object durationObj) {
        if (durationObj == null) {
            return 0;
        }
        return (int) Math.ceil(Double.parseDouble(durationObj.toString()) / 60.0d);
    }

    /**
     * 将数据转换为integer。
     * @param value 键值
     * @return 返回处理结果。
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
     * 处理round。
     * @param value 键值
     * @return 返回处理结果。
     */
    private double round(double value) {
        return Math.round(value * 1000.0d) / 1000.0d;
    }

    /**
     * 处理encode。
     * @param value 键值
     * @return 返回处理结果。
     */
    private String encode(String value) {
        if (value == null) {
            return "";
        }
        try {
            return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
    }

    /**
     * 处理blankToDash。
     * @param value 键值
     * @return 返回处理结果。
     */
    private String blankToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
