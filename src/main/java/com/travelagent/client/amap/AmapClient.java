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

/**
 * 高德开放平台客户端门面。
 *
 * <p>该类保留地理编码、天气、路线、距离和 POI 搜索的对外方法，内部把缓存、
 * POI 标准化和路线模式策略拆到专门辅助类，降低单个客户端类的职责密度。</p>
 */
@Service
public class AmapClient {

    private static final Logger log = LoggerFactory.getLogger(AmapClient.class);
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

    @Value("${amap.text-search-url:https://restapi.amap.com/v3/place/text}")
    private String textSearchUrl;

    @Autowired
    private OkHttpClient okHttpClient;

    @Autowired
    private RedisUtil redisUtil;

    @Autowired
    private JsonUtil jsonUtil;

    @Autowired
    private AmapRateLimiter rateLimiter;

    private AmapCacheSupport cacheSupport;
    private AmapPoiNormalizer poiNormalizer;
    private AmapResponseParser responseParser;

    /**
     * 延迟创建高德缓存辅助对象。
     *
     * @return 缓存辅助对象
     */
    private AmapCacheSupport cacheSupport() {
        if (cacheSupport == null) {
            cacheSupport = new AmapCacheSupport(redisUtil, jsonUtil, CACHE_TTL);
        }
        return cacheSupport;
    }

    /**
     * 延迟创建 POI 归一化辅助对象。
     *
     * @return POI 归一化辅助对象
     */
    private AmapPoiNormalizer poiNormalizer() {
        if (poiNormalizer == null) {
            poiNormalizer = new AmapPoiNormalizer();
        }
        return poiNormalizer;
    }

    /**
     * 延迟创建高德响应解析器。
     *
     * @return 响应解析器
     */
    private AmapResponseParser responseParser() {
        if (responseParser == null) {
            responseParser = new AmapResponseParser(jsonUtil, apiKey);
        }
        return responseParser;
    }

    /**
     * 根据当前配置创建路线模式辅助对象。
     *
     * @return 路线模式辅助对象
     */
    private AmapRouteModeSupport routeModeSupport() {
        return new AmapRouteModeSupport(
                apiKey, directionUrl, walkingDirectionUrl, bicyclingDirectionUrl, transitDirectionUrl);
    }

    /**
     * 根据景点名称和区域解析经纬度。
     *
     * @param attractionName 景点名称
     * @param region 区域信息，可为空
     * @return 包含 lat、lng 和 adcode 的地理编码结果
     */
    public Map<String, Object> geocode(String attractionName, String region) {
        String cacheKey = "amap:geocode:" + attractionName + ":" + region;
        Map<String, Object> cached = cacheSupport().getMap(cacheKey);
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
            Map<String, Object> result = responseParser().parseGeocodeResponse(body);
            cacheSupport().putMap(cacheKey, result);
            return result;
        });
    }

    /**
     * 获取指定行政区划的实时天气。
     *
     * @param adcode 行政区划编码
     * @return 归一化后的天气结果
     */
    public Map<String, Object> getWeather(String adcode) {
        String cacheKey = "amap:weather:" + adcode;
        Map<String, Object> cached = cacheSupport().getMap(cacheKey);
        if (cached != null) {
            return cached;
        }

        String url = weatherUrl + "?key=" + apiKey + "&city=" + adcode + "&extensions=base";
        return executeWithRetry("weather", () -> {
            String body = executeGet(url, "weather");
            Map<String, Object> result = responseParser().parseWeatherResponse(body);
            cacheSupport().putMap(cacheKey, result);
            return result;
        });
    }

    /**
     * 获取指定城市的天气预报。
     *
     * @param cityCode 城市行政区划编码或高德支持的城市标识
     * @return 当前天气摘要和预报天列表
     */
    public Map<String, Object> getWeatherForecast(String cityCode) {
        String cacheKey = "amap:weather:forecast:" + cityCode;
        Map<String, Object> cached = cacheSupport().getMap(cacheKey);
        if (cached != null) {
            return cached;
        }

        String url = weatherUrl + "?key=" + apiKey + "&city=" + cityCode + "&extensions=all";
        return executeWithRetry("weatherForecast", () -> {
            String body = executeGet(url, "weatherForecast");
            Map<String, Object> result = responseParser().parseWeatherForecastResponse(body);
            cacheSupport().putMap(cacheKey, result);
            return result;
        });
    }

    /**
     * 获取驾车路线耗时。
     *
     * @param originLng 起点经度
     * @param originLat 起点纬度
     * @param destLng 终点经度
     * @param destLat 终点纬度
     * @return 归一化后的交通耗时结果
     */
    public Map<String, Object> getDrivingDuration(double originLng, double originLat,
                                                  double destLng, double destLat) {
        return getTravelDuration(originLng, originLat, destLng, destLat, "driving");
    }

    /**
     * 根据出行方式获取路线耗时、距离和可选路线几何。
     *
     * @param originLng 起点经度
     * @param originLat 起点纬度
     * @param destLng 终点经度
     * @param destLat 终点纬度
     * @param travelMode 出行方式
     * @return 归一化后的路线结果
     */
    public Map<String, Object> getTravelDuration(double originLng, double originLat,
                                                 double destLng, double destLat,
                                                 String travelMode) {
        String origin = originLng + "," + originLat;
        String destination = destLng + "," + destLat;
        AmapRouteModeSupport routeModeSupport = routeModeSupport();

        for (String routeMode : routeModeSupport.resolveTravelModeSequence(travelMode)) {
            String cacheKey = String.format("amap:traffic:%s:%.6f,%.6f:%.6f,%.6f",
                    routeMode, originLng, originLat, destLng, destLat);
            Map<String, Object> cached = cacheSupport().getMap(cacheKey);
            if (cached != null) {
                return ensureRouteMode(cached, routeMode);
            }

            try {
                Map<String, Object> result = executeWithRetry("direction", () -> {
                    String body = executeGet(
                            routeModeSupport.buildDirectionUrl(routeMode, origin, destination), "direction");
                    return responseParser().parseDirectionResponse(body, routeMode);
                });
                cacheSupport().putMap(cacheKey, result);
                return result;
            } catch (RuntimeException e) {
                if (!routeModeSupport.shouldFallbackToNextMode(routeMode, travelMode)) {
                    throw e;
                }
                log.warn("[AmapClient] direction mode={} failed, fallback to next mode: {}",
                        routeMode, e.getMessage());
            }
        }

        throw new AgentException(AgentErrorCode.TOOL_AMAP_ERROR,
                "No available Amap direction mode for travelMode="
                        + routeModeSupport.normalizeTravelMode(travelMode));
    }

    /**
     * 获取两点间直线或驾车距离估算。
     *
     * @param originLng 起点经度
     * @param originLat 起点纬度
     * @param destLng 终点经度
     * @param destLat 终点纬度
     * @return 包含米和公里维度的距离结果
     */
    public Map<String, Object> getDistance(double originLng, double originLat,
                                           double destLng, double destLat) {
        String cacheKey = String.format("amap:distance:%.6f,%.6f:%.6f,%.6f",
                originLng, originLat, destLng, destLat);
        Map<String, Object> cached = cacheSupport().getMap(cacheKey);
        if (cached != null) {
            return cached;
        }

        String url = distanceUrl + "?key=" + apiKey
                + "&origins=" + originLng + "," + originLat
                + "&destination=" + destLng + "," + destLat
                + "&type=0";
        return executeWithRetry("distance", () -> {
            String body = executeGet(url, "distance");
            Map<String, Object> result = responseParser().parseDistanceResponse(body);
            cacheSupport().putMap(cacheKey, result);
            return result;
        });
    }

    /**
     * 获取路线几何信息。
     *
     * @param originLng 起点经度
     * @param originLat 起点纬度
     * @param destLng 终点经度
     * @param destLat 终点纬度
     * @param travelMode 出行方式
     * @return 包含耗时、距离和 polyline 的路线结果
     */
    public Map<String, Object> getRouteGeometry(double originLng, double originLat,
                                                double destLng, double destLat,
                                                String travelMode) {
        return getTravelDuration(originLng, originLat, destLng, destLat, travelMode);
    }

    /**
     * 按关键词搜索 POI。
     *
     * @param keywords 关键词
     * @param city 城市，可为空
     * @param types POI 类型，可为空
     * @param page 页码
     * @param pageSize 每页数量
     * @return 归一化后的 POI 列表
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> searchPois(String keywords,
                                                String city,
                                                String types,
                                                int page,
                                                int pageSize) {
        String cacheKey = String.format("amap:poi:text:%s:%s:%s:%d:%d",
                blankToDash(keywords), blankToDash(city), blankToDash(types), page, pageSize);
        List<Map<String, Object>> cached = cacheSupport().getPoiList(cacheKey);
        if (cached != null) {
            return cached;
        }

        StringBuilder url = new StringBuilder(textSearchUrl)
                .append("?key=").append(apiKey)
                .append("&keywords=").append(encode(keywords))
                .append("&page=").append(page)
                .append("&offset=").append(pageSize)
                .append("&extensions=base");
        if (city != null && !city.isBlank()) {
            url.append("&city=").append(encode(city));
            url.append("&citylimit=false");
        }
        if (types != null && !types.isBlank()) {
            url.append("&types=").append(encode(types));
        }

        return executeWithRetry("poiText", () -> {
            String body = executeGet(url.toString(), "poiText");
            try {
                Map<String, Object> root = jsonUtil.fromJson(body, new TypeReference<Map<String, Object>>() {});
                responseParser().validateAmapStatus(root, body, "poiText");
                List<Map<String, Object>> pois = (List<Map<String, Object>>) root.get("pois");
                List<Map<String, Object>> normalized = poiNormalizer().normalizePois(
                        pois == null ? List.of() : pois);
                cacheSupport().putPoiList(cacheKey, normalized);
                return normalized;
            } catch (AgentException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("Failed to parse Amap text POI response: " + e.getMessage(), e);
            }
        });
    }

    /**
     * 按坐标搜索周边 POI。
     *
     * @param lng 中心点经度
     * @param lat 中心点纬度
     * @param radius 搜索半径
     * @param keywords 关键词，可为空
     * @param types POI 类型，可为空
     * @param page 页码
     * @param pageSize 每页数量
     * @return 归一化后的周边 POI 列表
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
        List<Map<String, Object>> cached = cacheSupport().getPoiList(cacheKey);
        if (cached != null) {
            return cached;
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
                responseParser().validateAmapStatus(root, body, "nearby");
                List<Map<String, Object>> pois = (List<Map<String, Object>>) root.get("pois");
                List<Map<String, Object>> normalized = poiNormalizer().normalizePois(
                        pois == null ? List.of() : pois);
                cacheSupport().putPoiList(cacheKey, normalized);
                return normalized;
            } catch (AgentException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("Failed to parse Amap nearby POI response: " + e.getMessage(), e);
            }
        });
    }

    /**
     * 按高德瞬时错误策略执行带重试的调用。
     *
     * @param apiName API 名称，用于日志和错误提示
     * @param action 实际调用逻辑
     * @return 调用成功后的业务结果
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
     * 执行高德 HTTP GET 请求，并统一处理限流和 HTTP 错误。
     *
     * @param url 请求地址
     * @param apiName API 名称，用于限流维度
     * @return 响应体字符串
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
     * 将网络异常归类为高德工具错误码。
     *
     * @param e 原始异常
     * @param url 请求地址
     * @return 可被 Agent 上层识别的业务异常
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
     * 判断当前异常是否还应继续重试。
     *
     * @param exception 高德调用异常
     * @param attempt 当前尝试次数
     * @param maxAttempts 最大尝试次数
     * @return 仍有重试预算且错误可重试时返回 true
     */
    private boolean shouldRetry(AgentException exception, int attempt, int maxAttempts) {
        return exception.getErrorCode() == AgentErrorCode.TOOL_AMAP_TRANSIENT && attempt < maxAttempts;
    }

    /**
     * 根据当前尝试次数解析退避时长。
     *
     * @param attempt 当前尝试次数
     * @return 毫秒级退避时长
     */
    private long resolveBackoffMillis(int attempt) {
        if (retryBackoffMillis == null || retryBackoffMillis.length == 0) {
            return 0L;
        }
        int index = Math.min(Math.max(0, attempt - 1), retryBackoffMillis.length - 1);
        return Math.max(0L, retryBackoffMillis[index]);
    }

    /**
     * 执行重试等待，并在中断时恢复线程中断标记。
     *
     * @param millis 等待毫秒数
     * @param apiName API 名称
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
     * 为旧缓存结果补齐路线模式字段。
     *
     * @param cached 缓存结果
     * @param routeMode 当前路线模式
     * @return 包含 routeMode 的结果
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
     * 对 URL 查询参数做 UTF-8 编码。
     *
     * @param value 原始参数值
     * @return 编码后的参数值
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
     * 把空字符串转换为缓存键占位符。
     *
     * @param value 原始字符串
     * @return 非空原值或短横线占位符
     */
    private String blankToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

}
