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

    /**
     * 处理set。
     * @param key 键名
     * @param value 键值
     * @param ttl 缓存有效期
     */
    public void set(String key, Object value, Duration ttl) {
        redisTemplate.opsForValue().set(key, value, ttl);
    }

    /**
     * 处理set。
     * @param key 键名
     * @param value 键值
     */
    public void set(String key, Object value) {
        redisTemplate.opsForValue().set(key, value);
    }

    /**
     * 处理get。
     * @param key 键名
     * @return 返回处理结果。
     */
    public Object get(String key) {
        return redisTemplate.opsForValue().get(key);
    }

    /**
     * 判断delete。
     * @param key 键名
     * @return 是否满足当前条件。
     */
    public boolean delete(String key) {
        return Boolean.TRUE.equals(redisTemplate.delete(key));
    }

    /**
     * 判断是否具备key。
     * @param key 键名
     * @return 是否满足当前条件。
     */
    public boolean hasKey(String key) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    /**
     * 判断expire。
     * @param key 键名
     * @param seconds s ec on ds 参数
     * @return 是否满足当前条件。
     */
    public boolean expire(String key, long seconds) {
        return Boolean.TRUE.equals(redisTemplate.expire(key, seconds, TimeUnit.SECONDS));
    }

    /**
     * 获取expire。
     * @param key 键名
     * @return 返回处理结果。
     */
    public Long getExpire(String key) {
        return redisTemplate.getExpire(key, TimeUnit.SECONDS);
    }

    // ===================== Atomic counter (for quota) =====================

    /**
     * 处理incrementWithTtl。
     * @param key 键名
     * @param delta d el ta 参数
     * @param ttl 缓存有效期
     * @return 返回处理结果。
     */
    public Long incrementWithTtl(String key, long delta, Duration ttl) {
        Long value = stringRedisTemplate.opsForValue().increment(key, delta);
        // Set TTL only on first creation (NX equivalent via getExpire check)
        if (value != null && value == delta) {
            stringRedisTemplate.expire(key, ttl);
        }
        return value;
    }

    /**
     * 处理increment。
     * @param key 键名
     * @param delta d el ta 参数
     * @return 返回处理结果。
     */
    public Long increment(String key, long delta) {
        return stringRedisTemplate.opsForValue().increment(key, delta);
    }

    /**
     * 获取string。
     * @param key 键名
     * @return 返回处理结果。
     */
    public String getString(String key) {
        return stringRedisTemplate.opsForValue().get(key);
    }

    /**
     * 处理setString。
     * @param key 键名
     * @param value 键值
     * @param ttl 缓存有效期
     */
    public void setString(String key, String value, Duration ttl) {
        stringRedisTemplate.opsForValue().set(key, value, ttl);
    }

    // ===================== SET NX (for idempotency and locks) =====================

    /**
     * 判断setIfAbsent。
     * @param key 键名
     * @param value 键值
     * @param ttl 缓存有效期
     * @return 是否满足当前条件。
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
     * 检查quota。
     * @param key 键名
     * @param limit 返回数量上限
     * @return 是否满足当前条件。
     */
    public boolean checkQuota(String key, long limit) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(QUOTA_CHECK_SCRIPT, Long.class);
        Long result = stringRedisTemplate.execute(script,
            Collections.singletonList(key), String.valueOf(limit));
        return Long.valueOf(1L).equals(result);
    }

    /**
     * 处理debitQuota。
     * @param key 键名
     * @param cost c os t 参数
     * @param limit 返回数量上限
     * @return 返回处理结果。
     */
    public Long debitQuota(String key, long cost, long limit) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(QUOTA_DEBIT_SCRIPT, Long.class);
        return stringRedisTemplate.execute(script,
            Collections.singletonList(key), String.valueOf(cost), String.valueOf(limit));
    }
}
