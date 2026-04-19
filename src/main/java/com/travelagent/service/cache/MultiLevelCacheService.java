package com.travelagent.service.cache;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Three-level cache abstraction: Caffeine (L1) → Redis (L2) → MySQL (L3).
 *
 * <p>Read path: L1 hit → return; L2 hit → backfill L1, return;
 * L3 (loader) hit → write-through L2 + L1, return; all miss → return null.
 *
 * <p>Write path ({@link #put}): writes to both L1 and L2 simultaneously.
 */

/**
 * 中文注释：服务接口，定义 Multi Level Cache Service 相关业务能力。
 */

public interface MultiLevelCacheService {

    /**
     * Get a value from the cache, falling through to {@code loader} on a miss.
     *
     * @param cacheName Caffeine cache region name (e.g. {@code CacheConfig.ATTRACTION_BASIC})
     * @param key       cache key within the region
     * @param type      expected value type for deserialization
     * @param loader    L3 supplier — called only on a full cache miss
     * @return cached or loaded value, or {@code null} if the loader also returns null
     */
    <T> T get(String cacheName, String key, Class<T> type, Supplier<T> loader);

    /**
     * Explicitly put a value into L1 and L2.
     *
     * @param ttl Redis TTL; L1 TTL is governed by the Caffeine region configuration
     */
    void put(String cacheName, String key, Object value, Duration ttl);

    /**
     * Evict a key from both L1 and L2.
     */
    void evict(String cacheName, String key);
}
