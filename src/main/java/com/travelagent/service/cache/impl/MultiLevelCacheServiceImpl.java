package com.travelagent.service.cache.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelagent.config.CacheConfig;
import com.travelagent.service.cache.MultiLevelCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Three-level cache implementation: Caffeine L1 → Redis L2 → MySQL L3 (via loader).
 *
 * <p>Redis key pattern: {@code cache:{cacheName}:{key}}
 *
 * <p>On Redis failure the service degrades gracefully — a warning is logged and
 * the loader is called directly, so the application stays functional.
 */

/**
 * 中文注释：服务实现类，负责承载 Multi Level Cache Service Impl 对应的核心业务逻辑。
 */

@Service
public class MultiLevelCacheServiceImpl implements MultiLevelCacheService {

    private static final Logger log = LoggerFactory.getLogger(MultiLevelCacheServiceImpl.class);

    private final CacheManager cacheManager;

    private final RedisTemplate<String, Object> redisTemplate;

    private final ObjectMapper objectMapper;

    /**
     * 初始化MultiLevelCacheServiceImpl 实例。
     * @param cacheManager c ac he Ma na ge r 参数
     * @param redisTemplate r ed is Te mp la te 参数
     * @param objectMapper o bj ec tM ap pe r 参数
     */
    public MultiLevelCacheServiceImpl(
            CacheManager cacheManager,
            RedisTemplate<String, Object> redisTemplate,
            ObjectMapper objectMapper
    ) {
        this.cacheManager = cacheManager;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * 处理get。
     * @param cacheName c ac he Na me 参数
     * @param key 键名
     * @param type t yp e 参数
     * @param loader l oa de r 参数
     * @return 返回处理结果。
     */
    @Override
    public <T> T get(String cacheName, String key, Class<T> type, Supplier<T> loader) {
        // L1 — Caffeine
        T l1 = getFromL1(cacheName, key, type);
        if (l1 != null) {
            log.debug("[Cache L1 HIT] cacheName={} key={}", cacheName, key);
            return l1;
        }

        String redisKey = redisKey(cacheName, key);

        // L2 — Redis
        try {
            Object raw = redisTemplate.opsForValue().get(redisKey);
            if (raw != null) {
                T value = objectMapper.convertValue(raw, type);
                log.debug("[Cache L2 HIT] cacheName={} key={}", cacheName, key);
                putToL1(cacheName, key, value);
                return value;
            }
        } catch (Exception e) {
            log.warn("[Cache L2 ERROR] Redis read failed for key={}, falling through to L3: {}", redisKey, e.getMessage());
        }

        // L3 — MySQL (loader)
        T loaded = loader.get();
        if (loaded != null) {
            log.debug("[Cache L3 HIT] cacheName={} key={}", cacheName, key);
            Duration ttl = getDefaultTtl(cacheName);
            try {
                redisTemplate.opsForValue().set(redisKey, loaded, ttl);
            } catch (Exception e) {
                log.warn("[Cache L2 WRITE ERROR] Failed to populate Redis key={}: {}", redisKey, e.getMessage());
            }
            putToL1(cacheName, key, loaded);
        }
        return loaded;
    }

    /**
     * 处理put。
     * @param cacheName c ac he Na me 参数
     * @param key 键名
     * @param value 键值
     * @param ttl 缓存有效期
     */
    @Override
    public void put(String cacheName, String key, Object value, Duration ttl) {
        putToL1(cacheName, key, value);
        String redisKey = redisKey(cacheName, key);
        try {
            redisTemplate.opsForValue().set(redisKey, value, ttl);
        } catch (Exception e) {
            log.warn("[Cache L2 WRITE ERROR] Failed to put Redis key={}: {}", redisKey, e.getMessage());
        }
    }

    /**
     * 处理evict。
     * @param cacheName c ac he Na me 参数
     * @param key 键名
     */
    @Override
    public void evict(String cacheName, String key) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            cache.evict(key);
        }
        String redisKey = redisKey(cacheName, key);
        try {
            redisTemplate.delete(redisKey);
        } catch (Exception e) {
            log.warn("[Cache EVICT ERROR] Failed to evict Redis key={}: {}", redisKey, e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * 获取froml1。
     * @param cacheName c ac he Na me 参数
     * @param key 键名
     * @param type t yp e 参数
     * @return 返回处理结果。
     */
    private <T> T getFromL1(String cacheName, String key, Class<T> type) {
        try {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache == null) return null;
            return cache.get(key, type);
        } catch (Exception e) {
            log.warn("[Cache L1 ERROR] Caffeine read failed for cacheName={} key={}: {}", cacheName, key, e.getMessage());
            return null;
        }
    }

    /**
     * 处理putToL1。
     * @param cacheName c ac he Na me 参数
     * @param key 键名
     * @param value 键值
     */
    private void putToL1(String cacheName, String key, Object value) {
        try {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                cache.put(key, value);
            }
        } catch (Exception e) {
            log.warn("[Cache L1 WRITE ERROR] Caffeine write failed for cacheName={} key={}: {}", cacheName, key, e.getMessage());
        }
    }

    /**
     * 处理redisKey。
     * @param cacheName c ac he Na me 参数
     * @param key 键名
     * @return 返回处理结果。
     */
    private String redisKey(String cacheName, String key) {
        return "cache:" + cacheName + ":" + key;
    }

    /**
     * 获取defaultttl。
     * @param cacheName c ac he Na me 参数
     * @return 返回处理结果。
     */
    private Duration getDefaultTtl(String cacheName) {
        return switch (cacheName) {
            case CacheConfig.ATTRACTION_BASIC -> Duration.ofHours(1);
            case CacheConfig.USER_PROFILE     -> Duration.ofMinutes(5);
            case CacheConfig.QUOTA_CONFIG     -> Duration.ofMinutes(10);
            case CacheConfig.TASK_STATUS      -> Duration.ofMinutes(1);
            case CacheConfig.RAG_CHUNK        -> Duration.ofMinutes(30);
            default -> Duration.ofMinutes(30);
        };
    }
}
