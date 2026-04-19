package com.travelagent.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * Caffeine L1 cache configuration.
 * Each cache region has independent TTL and capacity settings.
 *
 * Cache regions:
 *   userProfile      - user entity by userId, TTL 5min
 *   quotaConfig      - quota config by userLevel, TTL 10min
 *   attractionBasic  - attraction POJOs by amapPoiId, TTL 1hr
 *   taskStatus       - task status string by taskUuid, TTL 1min (short, Redis is L2)
 *   ragChunk         - RAG chunk text by dashvectorId, TTL 30min
 *
 * The MultiLevelCacheService reads L1 first; on miss it checks Redis (L2),
 * then MySQL (L3), and writes the result back up the chain.
 */

/**
 * 中文注释：配置类，用于集中声明 Cache Config 相关的 Spring Bean 或运行参数。
 */

@Configuration
@EnableCaching
public class CacheConfig {

    public static final String USER_PROFILE     = "userProfile";
    public static final String QUOTA_CONFIG     = "quotaConfig";
    public static final String ATTRACTION_BASIC = "attractionBasic";
    public static final String TASK_STATUS      = "taskStatus";
    public static final String RAG_CHUNK        = "ragChunk";

    @Bean
    public CacheManager cacheManager() {
        SimpleCacheManager cacheManager = new SimpleCacheManager();
        cacheManager.setCaches(Arrays.asList(
            buildCache(USER_PROFILE, 5, TimeUnit.MINUTES, 500),
            buildCache(QUOTA_CONFIG, 10, TimeUnit.MINUTES, 20),
            buildCache(ATTRACTION_BASIC, 60, TimeUnit.MINUTES, 2000),
            buildCache(TASK_STATUS, 1, TimeUnit.MINUTES, 200),
            buildCache(RAG_CHUNK, 30, TimeUnit.MINUTES, 1000)
        ));
        return cacheManager;
    }

    private CaffeineCache buildCache(String name, long ttl, TimeUnit unit, int maxSize) {
        return new CaffeineCache(name,
            Caffeine.newBuilder()
                .expireAfterWrite(ttl, unit)
                .maximumSize(maxSize)
                .recordStats()
                .build());
    }
}
