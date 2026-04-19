package com.travelagent.service.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelagent.config.CacheConfig;
import com.travelagent.service.cache.impl.MultiLevelCacheServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 中文注释：测试类，用于验证 Multi Level Cache Service Impl Test 相关行为是否符合预期。
 */

@ExtendWith(MockitoExtension.class)
@DisplayName("MultiLevelCacheServiceImpl Tests")
class MultiLevelCacheServiceImplTest {

    @Mock private CacheManager cacheManager;
    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private Cache caffeineCacheRegion;
    @Mock private ValueOperations<String, Object> valueOps;

    private MultiLevelCacheServiceImpl cacheService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        cacheService = new MultiLevelCacheServiceImpl(cacheManager, redisTemplate, objectMapper);
    }

    private void stubValueOps() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    // -----------------------------------------------------------------------
    // L1 hit — should skip L2 and L3
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("L1 hit: return cached value, skip Redis and loader")
    void get_l1Hit_returnsValueWithoutRedisOrLoader() {
        String value = "cachedValue";
        when(cacheManager.getCache(CacheConfig.ATTRACTION_BASIC)).thenReturn(caffeineCacheRegion);
        when(caffeineCacheRegion.get("key", String.class)).thenReturn(value);

        AtomicInteger loaderCallCount = new AtomicInteger(0);
        String result = cacheService.get(CacheConfig.ATTRACTION_BASIC, "key", String.class,
                () -> { loaderCallCount.incrementAndGet(); return "loaderValue"; });

        assertThat(result).isEqualTo("cachedValue");
        assertThat(loaderCallCount.get()).isZero();
        verifyNoInteractions(valueOps);
    }

    // -----------------------------------------------------------------------
    // L2 hit — backfill L1, skip loader
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("L2 hit: return Redis value, backfill L1, skip loader")
    void get_l2Hit_backfillsL1AndSkipsLoader() {
        stubValueOps();
        when(cacheManager.getCache(CacheConfig.ATTRACTION_BASIC)).thenReturn(caffeineCacheRegion);
        when(caffeineCacheRegion.get("key", String.class)).thenReturn(null); // L1 miss

        String redisValue = "redisValue";
        when(valueOps.get("cache:attractionBasic:key")).thenReturn(redisValue);

        AtomicInteger loaderCallCount = new AtomicInteger(0);
        String result = cacheService.get(CacheConfig.ATTRACTION_BASIC, "key", String.class,
                () -> { loaderCallCount.incrementAndGet(); return "loaderValue"; });

        assertThat(result).isEqualTo("redisValue");
        assertThat(loaderCallCount.get()).isZero();
        // L1 should be backfilled
        verify(caffeineCacheRegion).put("key", "redisValue");
    }

    // -----------------------------------------------------------------------
    // L3 hit — write-through to L1 + L2
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("L3 hit: call loader, write-through to L2 and L1")
    void get_l3Hit_writesThroughToL1AndL2() {
        stubValueOps();
        when(cacheManager.getCache(CacheConfig.ATTRACTION_BASIC)).thenReturn(caffeineCacheRegion);
        when(caffeineCacheRegion.get("key", String.class)).thenReturn(null); // L1 miss
        when(valueOps.get("cache:attractionBasic:key")).thenReturn(null);    // L2 miss

        String result = cacheService.get(CacheConfig.ATTRACTION_BASIC, "key", String.class,
                () -> "loaderValue");

        assertThat(result).isEqualTo("loaderValue");
        // L2 write-through
        verify(valueOps).set(eq("cache:attractionBasic:key"), eq("loaderValue"), any(Duration.class));
        // L1 write-through
        verify(caffeineCacheRegion).put("key", "loaderValue");
    }

    // -----------------------------------------------------------------------
    // L3 miss — all levels miss, return null
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("All miss: return null when loader returns null")
    void get_allMiss_returnsNull() {
        stubValueOps();
        when(cacheManager.getCache(CacheConfig.ATTRACTION_BASIC)).thenReturn(caffeineCacheRegion);
        when(caffeineCacheRegion.get("key", String.class)).thenReturn(null);
        when(valueOps.get(any())).thenReturn(null);

        String result = cacheService.get(CacheConfig.ATTRACTION_BASIC, "key", String.class,
                () -> null);

        assertThat(result).isNull();
    }

    // -----------------------------------------------------------------------
    // Redis failure — degrade to L3
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Redis failure: degrade gracefully and call loader")
    void get_redisFailure_degradesToLoader() {
        stubValueOps();
        when(cacheManager.getCache(CacheConfig.ATTRACTION_BASIC)).thenReturn(caffeineCacheRegion);
        when(caffeineCacheRegion.get("key", String.class)).thenReturn(null);
        when(valueOps.get(any())).thenThrow(new RuntimeException("Redis connection refused"));

        String result = cacheService.get(CacheConfig.ATTRACTION_BASIC, "key", String.class,
                () -> "fallbackValue");

        assertThat(result).isEqualTo("fallbackValue");
    }

    // -----------------------------------------------------------------------
    // put() — writes to both L1 and L2
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("put: writes value to both L1 and L2")
    void put_writesToL1AndL2() {
        stubValueOps();
        when(cacheManager.getCache(CacheConfig.USER_PROFILE)).thenReturn(caffeineCacheRegion);

        cacheService.put(CacheConfig.USER_PROFILE, "user:1", "userData", Duration.ofMinutes(5));

        verify(caffeineCacheRegion).put("user:1", "userData");
        verify(valueOps).set(eq("cache:userProfile:user:1"), eq("userData"), eq(Duration.ofMinutes(5)));
    }

    // -----------------------------------------------------------------------
    // evict() — removes from both L1 and L2
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("evict: removes key from both L1 and L2")
    void evict_removesFromL1AndL2() {
        when(cacheManager.getCache(CacheConfig.ATTRACTION_BASIC)).thenReturn(caffeineCacheRegion);

        cacheService.evict(CacheConfig.ATTRACTION_BASIC, "key");

        verify(caffeineCacheRegion).evict("key");
        verify(redisTemplate).delete("cache:attractionBasic:key");
    }

    // -----------------------------------------------------------------------
    // Default TTL mapping
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("L3 hit with attractionBasic uses 1-hour TTL")
    void get_attractionBasic_usesOneHourTtl() {
        stubValueOps();
        when(cacheManager.getCache(CacheConfig.ATTRACTION_BASIC)).thenReturn(caffeineCacheRegion);
        when(caffeineCacheRegion.get("key", String.class)).thenReturn(null);
        when(valueOps.get(any())).thenReturn(null);

        cacheService.get(CacheConfig.ATTRACTION_BASIC, "key", String.class, () -> "value");

        verify(valueOps).set(any(), any(), eq(Duration.ofHours(1)));
    }
}
