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
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Client for the Amap (高德地图) REST API v3.
 *
 * <p>All three methods cache their responses in Redis for 1 hour using
 * {@code StringRedisTemplate} (JSON strings) to avoid Jackson type-info issues.
 *
 * <p>Endpoints used:
 * <ul>
 *   <li>Geocoding: {@code /v3/geocode/geo} — converts attraction name → coordinates + adcode</li>
 *   <li>Weather:   {@code /v3/weather/weatherInfo} — current weather by city adcode</li>
 *   <li>Driving:   {@code /v3/direction/driving} — driving duration between two coordinates</li>
 * </ul>
 */

/**
 * 中文注释：客户端类，负责对接 Amap Client 对应的外部服务能力。
 */

@Service
public class AmapClient {

    private static final Logger log = LoggerFactory.getLogger(AmapClient.class);
    private static final int MAX_REQUESTS_PER_SECOND_PER_API = 3;
    private static final long RATE_LIMIT_WINDOW_MILLIS = 1000L;
    private static final List<String> RATE_LIMIT_HINTS = List.of(
            "CUQPS_HAS_EXCEEDED_THE_LIMIT",
            "DAILY_QUERY_OVER_LIMIT",
            "ACCESS_TOO_FREQUENT",
            "USER_DAILY_QUERY_OVER_LIMIT",
            "IP_QUERY_OVER_LIMIT"
    );

    private static final Duration CACHE_TTL = Duration.ofHours(1);
    private final ConcurrentMap<String, Deque<Long>> localRateWindows = new ConcurrentHashMap<>();

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

    @Value("${amap.nearby-search-url}")
    private String nearbySearchUrl;

    @Autowired
    private OkHttpClient okHttpClient;

    @Autowired
    private RedisUtil redisUtil;

    @Autowired
    private JsonUtil jsonUtil;

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Geocode an attraction name in a given region.
     *
     * @param attractionName Chinese name of the attraction (e.g. "兵马俑")
     * @param region         City or region for disambiguation (e.g. "西安市")
     * @return Map with keys: {@code lat} (Double), {@code lng} (Double), {@code adcode} (String)
     */
    public Map<String, Object> geocode(String attractionName, String region) {
        String cacheKey = "amap:geocode:" + attractionName + ":" + region;
        Map<String, Object> cached = getCached(cacheKey);
        if (cached != null) return cached;

        String url = geocodeUrl + "?key=" + apiKey
                + "&address=" + encode(attractionName)
                + "&city=" + encode(region);

        String body = executeGet(url, "geocode");
        Map<String, Object> result = parseGeocodeResponse(body);

        putCached(cacheKey, result);
        return result;
    }

    /**
     * Fetch current weather for a city by its Amap adcode.
     *
     * @param adcode Amap city administrative code (e.g. "610100" for Xi'an)
     * @return Map with keys: {@code weather}, {@code temperature}, {@code windDirection},
     *         {@code windPower}, {@code humidity}
     */
    public Map<String, Object> getWeather(String adcode) {
        String cacheKey = "amap:weather:" + adcode;
        Map<String, Object> cached = getCached(cacheKey);
        if (cached != null) return cached;

        String url = weatherUrl + "?key=" + apiKey
                + "&city=" + adcode
                + "&extensions=base";

        String body = executeGet(url, "weather");
        Map<String, Object> result = parseWeatherResponse(body);

        putCached(cacheKey, result);
        return result;
    }

    /**
     * Get driving duration between two coordinates.
     *
     * <p>Uses Amap Driving API v3. The response field {@code route.paths[0].duration}
     * is in <b>seconds</b> and is converted to minutes before returning.
     *
     * @return Map with key: {@code durationMin} (Integer)
     */
    public Map<String, Object> getDrivingDuration(double originLng, double originLat,
                                                   double destLng, double destLat) {
        return getTravelDuration(originLng, originLat, destLng, destLat, "driving");
    }

    public Map<String, Object> getTravelDuration(double originLng, double originLat,
                                                 double destLng, double destLat,
                                                 String travelMode) {
        String normalizedMode = travelMode == null ? "driving" : travelMode.trim().toLowerCase();
        String cacheKey = String.format("amap:traffic:%s:%.6f,%.6f:%.6f,%.6f",
                normalizedMode, originLng, originLat, destLng, destLat);
        Map<String, Object> cached = getCached(cacheKey);
        if (cached != null) return cached;

        String origin = originLng + "," + originLat;
        String destination = destLng + "," + destLat;
        String url;
        if ("walking".equals(normalizedMode)) {
            url = walkingDirectionUrl + "?key=" + apiKey
                    + "&origin=" + origin
                    + "&destination=" + destination;
        } else {
            url = directionUrl + "?key=" + apiKey
                    + "&origin=" + origin
                    + "&destination=" + destination
                    + "&strategy=0";
        }

        String body = executeGet(url, "direction");
        Map<String, Object> result = parseDirectionResponse(body);

        putCached(cacheKey, result);
        return result;
    }

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

        String body = executeGet(url.toString(), "nearby");
        try {
            Map<String, Object> root = jsonUtil.fromJson(body, new TypeReference<Map<String, Object>>() {});
            validateAmapStatus(root, body, "nearby");
            List<Map<String, Object>> pois = (List<Map<String, Object>>) root.get("pois");
            List<Map<String, Object>> safePois = pois == null ? List.of() : pois;
            redisUtil.setString(cacheKey, jsonUtil.toJson(safePois), CACHE_TTL);
            return safePois;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Amap nearby POI response: " + e.getMessage(), e);
        }
    }

    // -----------------------------------------------------------------------
    // Private: HTTP execution
    // -----------------------------------------------------------------------

    private String executeGet(String url, String apiName) {
        awaitRateLimitPermit(apiName);
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

    private AgentException classifyAmapException(Exception e, String url) {
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        if (msg.contains("timeout") || msg.contains("timed out")
                || e.getCause() instanceof java.net.SocketTimeoutException) {
            return new AgentException(AgentErrorCode.TOOL_AMAP_TIMEOUT,
                    "Amap request timed out for URL: " + url, e);
        }
        return new AgentException(AgentErrorCode.TOOL_AMAP_ERROR,
                "Amap API error: " + e.getMessage(), e);
    }

    private void awaitRateLimitPermit(String apiName) {
        Deque<Long> window = localRateWindows.computeIfAbsent(apiName, key -> new ArrayDeque<>());
        long waitMillis = 0L;
        synchronized (window) {
            long now = System.currentTimeMillis();
            trimExpired(window, now);
            if (window.size() >= MAX_REQUESTS_PER_SECOND_PER_API) {
                long oldest = window.peekFirst() == null ? now : window.peekFirst();
                waitMillis = Math.max(1L, RATE_LIMIT_WINDOW_MILLIS - (now - oldest));
            }
        }

        if (waitMillis > 0L) {
            try {
                Thread.sleep(waitMillis);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                throw new AgentException(AgentErrorCode.TOOL_AMAP_ERROR,
                        "Interrupted while waiting for local Amap rate limiter", interruptedException);
            }
        }

        synchronized (window) {
            long now = System.currentTimeMillis();
            trimExpired(window, now);
            while (window.size() >= MAX_REQUESTS_PER_SECOND_PER_API) {
                long oldest = window.peekFirst() == null ? now : window.peekFirst();
                long remainingMillis = Math.max(1L, RATE_LIMIT_WINDOW_MILLIS - (now - oldest));
                try {
                    Thread.sleep(remainingMillis);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw new AgentException(AgentErrorCode.TOOL_AMAP_ERROR,
                            "Interrupted while waiting for local Amap rate limiter", interruptedException);
                }
                now = System.currentTimeMillis();
                trimExpired(window, now);
            }
            window.addLast(now);
        }
    }

    private void trimExpired(Deque<Long> window, long now) {
        while (!window.isEmpty() && now - window.peekFirst() >= RATE_LIMIT_WINDOW_MILLIS) {
            window.pollFirst();
        }
    }

    // -----------------------------------------------------------------------
    // Private: JSON parsing
    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseGeocodeResponse(String json) {
        try {
            Map<String, Object> root = jsonUtil.fromJson(json, new TypeReference<Map<String, Object>>() {});
            validateAmapStatus(root, json, "geocode");

            java.util.List<Map<String, Object>> geocodes =
                    (java.util.List<Map<String, Object>>) root.get("geocodes");
            if (geocodes == null || geocodes.isEmpty()) {
                throw new RuntimeException("Amap geocode: no results in response: " + json);
            }
            Map<String, Object> first = geocodes.get(0);

            // location format: "lng,lat"
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseWeatherResponse(String json) {
        try {
            Map<String, Object> root = jsonUtil.fromJson(json, new TypeReference<Map<String, Object>>() {});
            validateAmapStatus(root, json, "weather");

            java.util.List<Map<String, Object>> lives =
                    (java.util.List<Map<String, Object>>) root.get("lives");
            if (lives == null || lives.isEmpty()) {
                throw new RuntimeException("Amap weather: no lives data in response: " + json);
            }
            Map<String, Object> live = lives.get(0);

            return Map.of(
                    "weather",       live.getOrDefault("weather", "未知"),
                    "temperature",   live.getOrDefault("temperature", ""),
                    "windDirection", live.getOrDefault("winddirection", ""),
                    "windPower",     live.getOrDefault("windpower", ""),
                    "humidity",      live.getOrDefault("humidity", "")
            );
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Amap weather response: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseDirectionResponse(String json) {
        try {
            Map<String, Object> root = jsonUtil.fromJson(json, new TypeReference<Map<String, Object>>() {});
            validateAmapStatus(root, json, "direction");

            Map<String, Object> route = (Map<String, Object>) root.get("route");
            if (route == null) {
                throw new RuntimeException("Amap direction: missing 'route' in response: " + json);
            }
            java.util.List<Map<String, Object>> paths =
                    (java.util.List<Map<String, Object>>) route.get("paths");
            if (paths == null || paths.isEmpty()) {
                throw new RuntimeException("Amap direction: no paths in response: " + json);
            }
            // duration is in seconds; convert to minutes
            Object durationObj = paths.get(0).get("duration");
            int durationMin = 0;
            if (durationObj != null) {
                durationMin = (int) Math.ceil(Double.parseDouble(durationObj.toString()) / 60.0);
            }
            return Map.of("durationMin", durationMin);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Amap direction response: " + e.getMessage(), e);
        }
    }

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
            throw new AgentException(AgentErrorCode.TOOL_AMAP_ERROR,
                    "Amap API error for " + apiName + ": status=" + status + ", info=" + info);
        }
    }

    private boolean isRateLimited(String info) {
        if (info == null || info.isBlank()) {
            return false;
        }
        String normalized = info.toUpperCase(Locale.ROOT);
        return RATE_LIMIT_HINTS.stream().anyMatch(normalized::contains);
    }

    private String maskApiKey(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        if (apiKey == null || apiKey.isBlank()) {
            return text;
        }
        return text.replace(apiKey, "***");
    }

    // -----------------------------------------------------------------------
    // Private: Redis cache helpers (use StringRedisTemplate via RedisUtil)
    // -----------------------------------------------------------------------

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

    private void putCached(String cacheKey, Map<String, Object> value) {
        try {
            redisUtil.setString(cacheKey, jsonUtil.toJson(value), CACHE_TTL);
        } catch (Exception e) {
            log.warn("[AmapClient cache WRITE error] key={}: {}", cacheKey, e.getMessage());
        }
    }

    /** URL-encode a Chinese string for query parameters. */
    private String encode(String value) {
        try {
            return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
    }

    private String blankToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
