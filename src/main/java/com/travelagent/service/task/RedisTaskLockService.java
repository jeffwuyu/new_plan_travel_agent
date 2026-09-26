package com.travelagent.service.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Service
public class RedisTaskLockService {

    private static final Logger log = LoggerFactory.getLogger(RedisTaskLockService.class);

    private final StringRedisTemplate redisTemplate;
    private final String ownerId;
    private final Duration lockTtl;
    private final Duration leaseTtl;
    private final String redisUnavailablePolicy;
    private final DefaultRedisScript<Long> renewLeaseScript;
    private final DefaultRedisScript<Long> releaseScript;
    private final Map<String, String> ownerTokens = new ConcurrentHashMap<>();
    private final ScheduledExecutorService renewScheduler = Executors.newScheduledThreadPool(1, runnable -> {
        Thread thread = new Thread(runnable, "agent-redis-lease-renewer");
        thread.setDaemon(true);
        return thread;
    });

    public RedisTaskLockService(StringRedisTemplate redisTemplate,
                                @Value("${agent.task.lock-ttl-ms:600000}") long lockTtlMs,
                                @Value("${agent.task.lease-ttl-ms:600000}") long leaseTtlMs,
                                @Value("${agent.instance-id:}") String configuredInstanceId,
                                @Value("${agent.task.redis-unavailable-policy:fail_closed}") String redisUnavailablePolicy) {
        this.redisTemplate = redisTemplate;
        this.ownerId = resolveOwnerId(configuredInstanceId);
        this.lockTtl = Duration.ofMillis(lockTtlMs);
        this.leaseTtl = Duration.ofMillis(leaseTtlMs);
        this.redisUnavailablePolicy = redisUnavailablePolicy == null ? "fail_closed" : redisUnavailablePolicy.trim();
        this.renewLeaseScript = new DefaultRedisScript<>(
                """
                local lockValue = redis.call('GET', KEYS[1])
                if lockValue and lockValue == ARGV[1] then
                    redis.call('SET', KEYS[2], lockValue, 'PX', ARGV[2])
                    return 1
                end
                return 0
                """,
                Long.class);
        this.releaseScript = new DefaultRedisScript<>(
                """
                local lockValue = redis.call('GET', KEYS[1])
                if lockValue and lockValue == ARGV[1] then
                    redis.call('DEL', KEYS[1])
                    redis.call('DEL', KEYS[2])
                    return 1
                end
                return 0
                """,
                Long.class);
    }

    public boolean acquire(String taskUuid, String trigger) {
        return acquireForDispatch(taskUuid, trigger).shouldDispatch();
    }

    public AcquireResult acquireForDispatch(String taskUuid, String trigger) {
        try {
            String value = ownerValue(trigger);
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey(taskUuid), value, lockTtl);
            if (!Boolean.TRUE.equals(acquired)) {
                return AcquireResult.ALREADY_LOCKED;
            }
            redisTemplate.opsForValue().set(leaseKey(taskUuid), value, leaseTtl);
            ownerTokens.put(taskUuid, value);
            return AcquireResult.ACQUIRED;
        } catch (Exception e) {
            if (allowLocalFallback()) {
                log.error("Redis task lock unavailable for task={}, trigger={}; explicitly falling back to local in-flight only: {}",
                        taskUuid, trigger, e.getMessage());
                return AcquireResult.REDIS_UNAVAILABLE_LOCAL_FALLBACK;
            }
            log.error("Redis task lock unavailable for task={}, trigger={}; dispatch blocked by policy={}: {}",
                    taskUuid, trigger, redisUnavailablePolicy, e.getMessage());
            return AcquireResult.REDIS_UNAVAILABLE_FAIL_CLOSED;
        }
    }

    public boolean renewLease(String taskUuid) {
        try {
            Long renewed = redisTemplate.execute(
                    renewLeaseScript,
                    List.of(lockKey(taskUuid), leaseKey(taskUuid)),
                    ownerTokens.getOrDefault(taskUuid, ownerId),
                    String.valueOf(leaseTtl.toMillis()));
            return Long.valueOf(1L).equals(renewed);
        } catch (Exception e) {
            log.warn("Redis task lease renew unavailable for task={}: {}", taskUuid, e.getMessage());
            return false;
        }
    }

    /**
     * Keeps a task lease alive while an external provider call is in flight.
     * The returned guard is deliberately owner-bound; a stale worker can never
     * renew a replacement owner's token because the Lua script checks the value.
     */
    public AutoCloseable startAutoRenew(String taskUuid) {
        long interval = Math.max(250L, leaseTtl.toMillis() / 3L);
        ScheduledFuture<?> future = renewScheduler.scheduleAtFixedRate(
                () -> renewLease(taskUuid), interval, interval, TimeUnit.MILLISECONDS);
        return () -> future.cancel(false);
    }

    public boolean hasValidLease(String taskUuid) {
        try {
            Long ttl = redisTemplate.getExpire(leaseKey(taskUuid));
            return ttl != null && ttl > 0;
        } catch (Exception e) {
            log.error("Redis task lease check unavailable for task={}; treating lease as invalid by policy={}: {}",
                    taskUuid, redisUnavailablePolicy, e.getMessage());
            return false;
        }
    }

    public Map<String, Object> getLeaseInfo(String taskUuid) {
        try {
            String lockValue = redisTemplate.opsForValue().get(lockKey(taskUuid));
            String leaseValue = redisTemplate.opsForValue().get(leaseKey(taskUuid));
            Long lockTtlSeconds = redisTemplate.getExpire(lockKey(taskUuid));
            Long leaseTtlSeconds = redisTemplate.getExpire(leaseKey(taskUuid));
            return Map.of(
                    "taskUuid", taskUuid,
                    "lockKey", lockKey(taskUuid),
                    "leaseKey", leaseKey(taskUuid),
                    "locked", lockValue != null,
                    "leaseValid", leaseTtlSeconds != null && leaseTtlSeconds > 0,
                    "lockTtlSeconds", lockTtlSeconds == null ? -2L : lockTtlSeconds,
                    "leaseTtlSeconds", leaseTtlSeconds == null ? -2L : leaseTtlSeconds,
                    "lockOwner", lockValue == null ? "" : lockValue,
                    "leaseOwner", leaseValue == null ? "" : leaseValue
            );
        } catch (Exception e) {
            return Map.of(
                    "taskUuid", taskUuid,
                    "available", false,
                    "error", e.getMessage() == null ? "Redis lease query failed" : e.getMessage()
            );
        }
    }

    public int incrementRecoveryAttempts(String taskUuid) {
        Long value = redisTemplate.opsForValue().increment(recoveryKey(taskUuid));
        redisTemplate.expire(recoveryKey(taskUuid), Duration.ofDays(7));
        return value == null ? 1 : value.intValue();
    }

    public void release(String taskUuid) {
        try {
            Long released = redisTemplate.execute(
                    releaseScript,
                    List.of(lockKey(taskUuid), leaseKey(taskUuid)),
                    ownerTokens.getOrDefault(taskUuid, ownerId));
            if (Long.valueOf(1L).equals(released)) {
                ownerTokens.remove(taskUuid);
            }
        } catch (Exception e) {
            log.warn("Redis task lock release unavailable for task={}: {}", taskUuid, e.getMessage());
        }
    }

    private String ownerValue(String trigger) {
        return ownerId + "|" + Thread.currentThread().getName() + "|" + System.currentTimeMillis()
                + "|" + (trigger == null ? "" : trigger);
    }

    private String lockKey(String taskUuid) {
        return "agent:task:lock:" + taskUuid;
    }

    private String leaseKey(String taskUuid) {
        return "agent:task:lease:" + taskUuid;
    }

    private String recoveryKey(String taskUuid) {
        return "agent:task:recovery-count:" + taskUuid;
    }

    private String resolveOwnerId(String configuredInstanceId) {
        if (configuredInstanceId != null && !configuredInstanceId.isBlank()) {
            return configuredInstanceId.trim();
        }
        try {
            return InetAddress.getLocalHost().getHostName() + "-" + UUID.randomUUID();
        } catch (Exception e) {
            return "agent-" + UUID.randomUUID();
        }
    }

    private boolean allowLocalFallback() {
        return "local_fallback".equalsIgnoreCase(redisUnavailablePolicy)
                || "allow_local".equalsIgnoreCase(redisUnavailablePolicy)
                || "fail_open".equalsIgnoreCase(redisUnavailablePolicy);
    }

    public enum AcquireResult {
        ACQUIRED(true),
        ALREADY_LOCKED(false),
        REDIS_UNAVAILABLE_LOCAL_FALLBACK(true),
        REDIS_UNAVAILABLE_FAIL_CLOSED(false);

        private final boolean shouldDispatch;

        AcquireResult(boolean shouldDispatch) {
            this.shouldDispatch = shouldDispatch;
        }

        public boolean shouldDispatch() {
            return shouldDispatch;
        }

        public boolean redisUnavailable() {
            return this == REDIS_UNAVAILABLE_LOCAL_FALLBACK || this == REDIS_UNAVAILABLE_FAIL_CLOSED;
        }
    }
}
