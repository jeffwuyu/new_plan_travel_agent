package com.travelagent.service.ratelimit;

import com.travelagent.exception.RateLimitExceededException;
import com.travelagent.util.RedisUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Service
public class AgentRateLimitService {

    private static final String RESOURCE_TOOL = "tool";
    private static final String RESOURCE_LLM = "llm";
    private static final String POLICY_FAIL_OPEN = "fail_open";

    private final RedisUtil redisUtil;

    @Value("${ratelimit.agent.enabled:true}")
    private boolean enabled;

    @Value("${ratelimit.agent.window-seconds:60}")
    private long windowSeconds;

    @Value("${ratelimit.agent.redis-unavailable-policy:fail_closed}")
    private String redisUnavailablePolicy;

    @Value("${ratelimit.agent.tool.per-user-per-minute:20}")
    private long toolPerUserPerMinute;

    @Value("${ratelimit.agent.tool.per-ip-per-minute:60}")
    private long toolPerIpPerMinute;

    @Value("${ratelimit.agent.llm.per-user-per-minute:5}")
    private long llmPerUserPerMinute;

    @Value("${ratelimit.agent.llm.per-ip-per-minute:20}")
    private long llmPerIpPerMinute;

    public AgentRateLimitService(RedisUtil redisUtil) {
        this.redisUtil = redisUtil;
    }

    public void checkToolLimit(Long userId, String requestIp) {
        check(RESOURCE_TOOL, userId, requestIp, toolPerUserPerMinute, toolPerIpPerMinute);
    }

    public void checkLlmLimit(Long userId, String requestIp) {
        check(RESOURCE_LLM, userId, requestIp, llmPerUserPerMinute, llmPerIpPerMinute);
    }

    private void check(String resource, Long userId, String requestIp, long userLimit, long ipLimit) {
        if (!enabled) {
            return;
        }
        long effectiveWindowSeconds = Math.max(1, windowSeconds);
        long bucket = Instant.now().getEpochSecond() / effectiveWindowSeconds;
        Duration ttl = Duration.ofSeconds(effectiveWindowSeconds + 5);

        if (userId != null && userLimit > 0) {
            incrementAndCheck(key(resource, "user", String.valueOf(userId), bucket), ttl, userLimit, resource, "user");
        }
        String normalizedIp = normalizeIp(requestIp);
        if (normalizedIp != null && ipLimit > 0) {
            incrementAndCheck(key(resource, "ip", normalizedIp, bucket), ttl, ipLimit, resource, "ip");
        }
    }

    private void incrementAndCheck(String key, Duration ttl, long limit, String resource, String dimension) {
        Long count;
        try {
            count = redisUtil.incrementWithTtl(key, 1L, ttl);
        } catch (Exception ex) {
            if (POLICY_FAIL_OPEN.equalsIgnoreCase(redisUnavailablePolicy)) {
                return;
            }
            throw new RateLimitExceededException("RATE_LIMIT_REDIS_UNAVAILABLE");
        }
        if (count != null && count > limit) {
            throw new RateLimitExceededException("RATE_LIMIT_EXCEEDED: " + resource + " " + dimension + " per-minute limit exceeded");
        }
    }

    private String key(String resource, String dimension, String value, long bucket) {
        return "ratelimit:agent:" + resource + ":" + dimension + ":" + value + ":" + bucket;
    }

    private String normalizeIp(String requestIp) {
        if (requestIp == null || requestIp.isBlank()) {
            return null;
        }
        String normalized = requestIp.trim();
        int comma = normalized.indexOf(',');
        if (comma >= 0) {
            normalized = normalized.substring(0, comma).trim();
        }
        return normalized.isBlank() ? null : normalized;
    }
}
