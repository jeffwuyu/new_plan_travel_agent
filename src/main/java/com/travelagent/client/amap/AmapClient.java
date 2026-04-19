package com.travelagent.client.amap;

import com.fasterxml.jackson.core.type.TypeReference;
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
import java.util.Map;

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

    private static final Duration CACHE_TTL = Duration.ofHours(1);

    @Value("${amap.api-key}")
    private String apiKey;

    @Value("${amap.geocode-url}")
    private String geocodeUrl;

    @Value("${amap.weather-url}")
    private String weatherUrl;

    @Value("${amap.direction-url}")
    private String directionUrl;

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

        String body = executeGet(url);
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

        String body = executeGet(url);
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
        String cacheKey = String.format("amap:traffic:%.6f,%.6f:%.6f,%.6f",
                originLng, originLat, destLng, destLat);
        Map<String, Object> cached = getCached(cacheKey);
        if (cached != null) return cached;

        String origin = originLng + "," + originLat;
        String destination = destLng + "," + destLat;
        String url = directionUrl + "?key=" + apiKey
                + "&origin=" + origin
                + "&destination=" + destination
                + "&strategy=0";

        String body = executeGet(url);
        Map<String, Object> result = parseDirectionResponse(body);

        putCached(cacheKey, result);
        return result;
    }

    // -----------------------------------------------------------------------
    // Private: HTTP execution
    // -----------------------------------------------------------------------

    private String executeGet(String url) {
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new RuntimeException("Amap API returned HTTP " + response.code() + " for URL: " + url);
            }
            if (response.body() == null) {
                throw new RuntimeException("Amap API returned empty body for URL: " + url);
            }
            return response.body().string();
        } catch (IOException e) {
            throw new RuntimeException("Amap API call failed: " + e.getMessage(), e);
        }
    }

    // -----------------------------------------------------------------------
    // Private: JSON parsing
    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseGeocodeResponse(String json) {
        try {
            Map<String, Object> root = jsonUtil.fromJson(json, new TypeReference<Map<String, Object>>() {});
            validateAmapStatus(root, json);

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
            validateAmapStatus(root, json);

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
            validateAmapStatus(root, json);

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

    private void validateAmapStatus(Map<String, Object> root, String rawJson) {
        Object status = root.get("status");
        if (!"1".equals(String.valueOf(status))) {
            String info = String.valueOf(root.getOrDefault("info", "unknown"));
            throw new RuntimeException("Amap API error: status=" + status + ", info=" + info + ", raw=" + rawJson);
        }
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
}
