package com.travelagent.aop;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method for daily token quota enforcement by {@link QuotaInterceptor}.
 *
 * <p>The interceptor extracts {@code userId} and {@code userLevel} from the method
 * arguments at the specified indices, then calls
 * {@code QuotaService.checkDailyQuota(userId, userLevel)} before the method body runs.
 *
 * <p>If the quota is exhausted, {@link com.travelagent.exception.QuotaExhaustedException}
 * is thrown. In the agent execution context, {@code AgentServiceImpl} catches this and
 * transitions the task to {@code PAUSED}.
 *
 * <p>Contract: the annotated method must have a {@code Long userId} at
 * {@link #userIdArgIndex()} and an {@code int}/{@code Integer} {@code userLevel} at
 * {@link #userLevelArgIndex()}.
 *
 * <p>Example:
 * <pre>{@code
 * @QuotaGuarded(userIdArgIndex = 0, userLevelArgIndex = 1)
 * public void performLlmCall(Long userId, int userLevel, String prompt) { ... }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface QuotaGuarded {

    /**
     * Index of the {@code Long userId} argument in the annotated method's parameter list.
     * Default: {@code 0}.
     */
    int userIdArgIndex() default 0;

    /**
     * Index of the {@code int}/{@code Integer} userLevel argument.
     * Default: {@code 1}.
     */
    int userLevelArgIndex() default 1;
}
