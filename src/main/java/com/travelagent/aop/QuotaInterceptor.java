package com.travelagent.aop;

import com.travelagent.service.user.QuotaService;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * AOP {@code @Before} aspect that enforces daily token quota on methods annotated
 * with {@link QuotaGuarded}.
 *
 * <p>Flow:
 * <ol>
 *   <li>Extract {@code userId} and {@code userLevel} from the annotated method's arguments
 *       at the indices declared on {@link QuotaGuarded}.</li>
 *   <li>Call {@link QuotaService#checkDailyQuota(Long, int)}, which atomically reads
 *       the Redis counter and compares it against the configured limit.</li>
 *   <li>If exhausted, {@link com.travelagent.exception.QuotaExhaustedException} propagates
 *       to the caller — {@code AgentServiceImpl} catches it and transitions the task to
 *       {@code PAUSED}, saves a checkpoint, and notifies the client via SSE.</li>
 * </ol>
 *
 * <p>If the argument indices are out of range or the types do not match, the check is
 * skipped with a warning rather than aborting — this prevents misconfiguration from
 * blocking legitimate calls.
 */
@Aspect
@Component
public class QuotaInterceptor {

    private static final Logger log = LoggerFactory.getLogger(QuotaInterceptor.class);

    @Autowired
    private QuotaService quotaService;

    /**
     * Intercepts any method annotated with {@link QuotaGuarded} and enforces the daily
     * token quota before execution proceeds.
     */
    @Before("@annotation(quotaGuarded)")
    public void checkQuota(JoinPoint jp, QuotaGuarded quotaGuarded) {
        Object[] args = jp.getArgs();
        int userIdIdx    = quotaGuarded.userIdArgIndex();
        int userLevelIdx = quotaGuarded.userLevelArgIndex();

        int requiredArgs = Math.max(userIdIdx, userLevelIdx) + 1;
        if (args == null || args.length < requiredArgs) {
            log.warn("[QuotaInterceptor] Method '{}' has only {} args; need at least {}. Skipping quota check.",
                    jp.getSignature().toShortString(), args == null ? 0 : args.length, requiredArgs);
            return;
        }

        Object userIdArg    = args[userIdIdx];
        Object userLevelArg = args[userLevelIdx];

        if (!(userIdArg instanceof Long)) {
            log.warn("[QuotaInterceptor] Arg at index {} in '{}' is not a Long (got {}). Skipping.",
                    userIdIdx, jp.getSignature().toShortString(),
                    userIdArg == null ? "null" : userIdArg.getClass().getSimpleName());
            return;
        }
        if (!(userLevelArg instanceof Number)) {
            log.warn("[QuotaInterceptor] Arg at index {} in '{}' is not a Number (got {}). Skipping.",
                    userLevelIdx, jp.getSignature().toShortString(),
                    userLevelArg == null ? "null" : userLevelArg.getClass().getSimpleName());
            return;
        }

        Long userId    = (Long) userIdArg;
        int  userLevel = ((Number) userLevelArg).intValue();

        log.debug("[QuotaInterceptor] Checking daily quota for userId={}, userLevel={}",
                userId, userLevel);

        // Throws QuotaExhaustedException (HTTP 429) if limit is reached
        quotaService.checkDailyQuota(userId, userLevel);
    }
}
