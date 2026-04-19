package com.travelagent.aop;

import com.fasterxml.jackson.core.type.TypeReference;
import com.travelagent.util.JsonUtil;
import com.travelagent.util.RedisUtil;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * AOP aspect that provides idempotency protection for agent tool calls.
 *
 * <p>When a method annotated with {@link IdempotentTool} is invoked:
 * <ol>
 *   <li>The idempotency key is extracted from the method's second argument.</li>
 *   <li>Redis is checked for a previously stored result under
 *       {@code idempotency:{key}:result}.</li>
 *   <li>If a result exists, it is returned immediately — the external API is
 *       <em>not</em> called again.</li>
 *   <li>If no result exists, the method proceeds normally. On success, the
 *       result is serialised to JSON and stored in Redis with a 24-hour TTL.</li>
 * </ol>
 *
 * <p>This prevents duplicate billing on Amap API (charged per call) when a
 * task resumes after a JVM restart or quota-pause.
 */

/**
 * 中文注释：AOP 类，用于处理 Idempotency Aspect 相关的横切逻辑。
 */

@Aspect
@Component
public class IdempotencyAspect {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyAspect.class);

    private static final Duration TTL_24H = Duration.ofHours(24);
    private static final String RESULT_SUFFIX = ":result";

    @Autowired
    private RedisUtil redisUtil;

    @Autowired
    private JsonUtil jsonUtil;

    @Around("@annotation(idempotentTool)")
    public Object around(ProceedingJoinPoint pjp, IdempotentTool idempotentTool) throws Throwable {
        Object[] args = pjp.getArgs();
        if (args.length < 2 || !(args[1] instanceof String)) {
            // No idempotency key available — proceed normally
            log.warn("[IdempotencyAspect] Method {} has no idempotency key argument, skipping protection",
                    pjp.getSignature().getName());
            return pjp.proceed();
        }

        String idempotencyKey = (String) args[1];
        String resultRedisKey = "idempotency:" + idempotencyKey + RESULT_SUFFIX;

        // 1. Check for a previously stored result
        try {
            String cached = redisUtil.getString(resultRedisKey);
            if (cached != null) {
                log.debug("[IdempotencyAspect] Cache HIT for key={}, returning stored result", idempotencyKey);
                return jsonUtil.fromJson(cached, new TypeReference<Map<String, Object>>() {});
            }
        } catch (Exception e) {
            log.warn("[IdempotencyAspect] Redis read failed for key={}, proceeding with execution: {}",
                    resultRedisKey, e.getMessage());
        }

        // 2. Execute the tool
        Object result = pjp.proceed();

        // 3. Persist the result for future idempotent calls
        try {
            if (result != null) {
                redisUtil.setString(resultRedisKey, jsonUtil.toJson(result), TTL_24H);
                log.debug("[IdempotencyAspect] Stored result for key={}", idempotencyKey);
            }
        } catch (Exception e) {
            log.warn("[IdempotencyAspect] Failed to store result for key={}: {}", resultRedisKey, e.getMessage());
            // Non-fatal — do not rethrow
        }

        return result;
    }
}
