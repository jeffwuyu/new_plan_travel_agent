package com.travelagent.util;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * Redis utility wrapping common operations.
 * Uses StringRedisTemplate for atomic counter operations (quota, rate limit).
 * Uses RedisTemplate<String, Object> for complex object storage.
 */

/**
 * 中文注释：工具类，封装 Redis Util 相关的通用辅助能力。
 */

@Component
public class RedisUtil {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    // ===================== String operations =====================

    public void set(String key, Object value, Duration ttl) {
        redisTemplate.opsForValue().set(key, value, ttl);
    }

    public void set(String key, Object value) {
        redisTemplate.opsForValue().set(key, value);
    }

    public Object get(String key) {
        return redisTemplate.opsForValue().get(key);
    }

    public boolean delete(String key) {
        return Boolean.TRUE.equals(redisTemplate.delete(key));
    }

    public boolean hasKey(String key) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    public boolean expire(String key, long seconds) {
        return Boolean.TRUE.equals(redisTemplate.expire(key, seconds, TimeUnit.SECONDS));
    }

    public Long getExpire(String key) {
        return redisTemplate.getExpire(key, TimeUnit.SECONDS);
    }

    // ===================== Atomic counter (for quota) =====================

    /**
     * Atomically increment a string counter and set TTL if the key is new.
     * Returns the value after increment.
     */
    public Long incrementWithTtl(String key, long delta, Duration ttl) {
        Long value = stringRedisTemplate.opsForValue().increment(key, delta);
        // Set TTL only on first creation (NX equivalent via getExpire check)
        if (value != null && value == delta) {
            stringRedisTemplate.expire(key, ttl);
        }
        return value;
    }

    public Long increment(String key, long delta) {
        return stringRedisTemplate.opsForValue().increment(key, delta);
    }

    public String getString(String key) {
        return stringRedisTemplate.opsForValue().get(key);
    }

    public void setString(String key, String value, Duration ttl) {
        stringRedisTemplate.opsForValue().set(key, value, ttl);
    }

    // ===================== SET NX (for idempotency and locks) =====================

    /**
     * Set key=value only if it doesn't exist (SETNX).
     * Returns true if set successfully (lock acquired).
     */
    public boolean setIfAbsent(String key, String value, Duration ttl) {
        return Boolean.TRUE.equals(
            stringRedisTemplate.opsForValue().setIfAbsent(key, value, ttl));
    }

    // ===================== Quota Lua scripts =====================

    private static final String QUOTA_CHECK_SCRIPT =
        "local current = tonumber(redis.call('GET', KEYS[1]) or '0') " +
        "local limit = tonumber(ARGV[1]) " +
        "if current >= limit then return 0 else return 1 end";

    private static final String QUOTA_DEBIT_SCRIPT =
        "local key = KEYS[1] " +
        "local cost = tonumber(ARGV[1]) " +
        "local limit = tonumber(ARGV[2]) " +
        "local new_val = redis.call('INCRBY', key, cost) " +
        "if new_val > limit then return -1 end " +
        "return new_val";

    /**
     * Atomically check if quota is available (1=available, 0=exhausted).
     */
    public boolean checkQuota(String key, long limit) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(QUOTA_CHECK_SCRIPT, Long.class);
        Long result = stringRedisTemplate.execute(script,
            Collections.singletonList(key), String.valueOf(limit));
        return Long.valueOf(1L).equals(result);
    }

    /**
     * Atomically debit tokens. Returns new total, or -1 if over limit.
     */
    public Long debitQuota(String key, long cost, long limit) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(QUOTA_DEBIT_SCRIPT, Long.class);
        return stringRedisTemplate.execute(script,
            Collections.singletonList(key), String.valueOf(cost), String.valueOf(limit));
    }
}
