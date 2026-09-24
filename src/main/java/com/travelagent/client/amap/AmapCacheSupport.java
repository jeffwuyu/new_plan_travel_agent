package com.travelagent.client.amap;

import com.fasterxml.jackson.core.type.TypeReference;
import com.travelagent.util.JsonUtil;
import com.travelagent.util.RedisUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 高德客户端缓存辅助类。
 *
 * <p>该类统一封装高德响应在 Redis 中的 JSON 读写，让 `AmapClient` 专注于 API
 * 入口和业务结果组装。</p>
 */
class AmapCacheSupport {

    private static final Logger log = LoggerFactory.getLogger(AmapCacheSupport.class);

    private final RedisUtil redisUtil;
    private final JsonUtil jsonUtil;
    private final Duration ttl;

    /**
     * 创建高德缓存辅助对象。
     *
     * @param redisUtil Redis 工具
     * @param jsonUtil JSON 工具
     * @param ttl 缓存过期时间
     */
    AmapCacheSupport(RedisUtil redisUtil, JsonUtil jsonUtil, Duration ttl) {
        this.redisUtil = redisUtil;
        this.jsonUtil = jsonUtil;
        this.ttl = ttl;
    }

    /**
     * 读取 Map 结构缓存。
     *
     * @param cacheKey 缓存键
     * @return 命中的 Map，未命中或反序列化失败时返回 null
     */
    Map<String, Object> getMap(String cacheKey) {
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
     * 写入 Map 结构缓存。
     *
     * @param cacheKey 缓存键
     * @param value 待缓存值
     */
    void putMap(String cacheKey, Map<String, Object> value) {
        try {
            redisUtil.setString(cacheKey, jsonUtil.toJson(value), ttl);
        } catch (Exception e) {
            log.warn("[AmapClient cache WRITE error] key={}: {}", cacheKey, e.getMessage());
        }
    }

    /**
     * 读取 POI 列表缓存。
     *
     * @param cacheKey 缓存键
     * @return 命中的 POI 列表，未命中或反序列化失败时返回 null
     */
    List<Map<String, Object>> getPoiList(String cacheKey) {
        try {
            String cached = redisUtil.getString(cacheKey);
            if (cached != null) {
                return jsonUtil.fromJson(cached, new TypeReference<List<Map<String, Object>>>() {});
            }
        } catch (Exception e) {
            log.warn("[AmapClient poi cache READ error] key={}: {}", cacheKey, e.getMessage());
        }
        return null;
    }

    /**
     * 写入 POI 列表缓存。
     *
     * @param cacheKey 缓存键
     * @param value 待缓存 POI 列表
     */
    void putPoiList(String cacheKey, List<Map<String, Object>> value) {
        try {
            redisUtil.setString(cacheKey, jsonUtil.toJson(value), ttl);
        } catch (Exception e) {
            log.warn("[AmapClient poi cache WRITE error] key={}: {}", cacheKey, e.getMessage());
        }
    }
}
