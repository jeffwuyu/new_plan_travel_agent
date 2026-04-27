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

    /**
     * 处理around。
     * @param pjp p jp 参数
     * @param idempotentTool i de mp ot en tT oo l 参数
     * @return 返回处理结果。
     */
    @Around("@annotation(idempotentTool)")
    public Object around(ProceedingJoinPoint pjp, IdempotentTool idempotentTool) throws Throwable {
        String methodName = resolveMethodName(pjp);
        Object[] args = pjp.getArgs();
        if (args.length < 2 || !(args[1] instanceof String)) {
            log.warn("[IdempotencyAspect] Method {} has no idempotency key argument, skipping protection",
                    methodName);
            return pjp.proceed();
        }

        String idempotencyKey = (String) args[1];
        String resultRedisKey = "idempotency:" + idempotencyKey + RESULT_SUFFIX;

        try {
            String cached = redisUtil.getString(resultRedisKey);
            if (cached != null) {
                log.info("[IdempotencyAspect] Cache HIT method={} key={} redisKey={}",
                        methodName, idempotencyKey, resultRedisKey);
                return jsonUtil.fromJson(cached, new TypeReference<Map<String, Object>>() {});
            }
        } catch (Exception e) {
            log.warn("[IdempotencyAspect] Redis read failed for key={}, proceeding with execution: {}",
                    resultRedisKey, e.getMessage());
        }

        Object result = pjp.proceed();

        try {
            if (result != null) {
                redisUtil.setString(resultRedisKey, jsonUtil.toJson(result), TTL_24H);
                log.debug("[IdempotencyAspect] Stored result method={} key={} redisKey={}",
                        methodName, idempotencyKey, resultRedisKey);
            }
        } catch (Exception e) {
            log.warn("[IdempotencyAspect] Failed to store result for key={}: {}", resultRedisKey, e.getMessage());
        }

        return result;
    }

    /**
     * 解析并确定methodname。
     * @param pjp p jp 参数
     * @return 返回处理结果。
     */
    private String resolveMethodName(ProceedingJoinPoint pjp) {
        if (pjp == null || pjp.getSignature() == null || pjp.getSignature().getName() == null) {
            return "unknown";
        }
        return pjp.getSignature().getName();
    }
}
