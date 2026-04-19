package com.travelagent.aop;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 中文注释：AOP 注解类型，用于为工具方法声明幂等缓存语义。
 */
/**
 * Marks an agent tool method as idempotent.
 *
 * <p>The {@link IdempotencyAspect} intercepts methods annotated with this
 * annotation and caches their results in Redis. On repeated calls with the
 * same idempotency key the cached result is returned without re-invoking the
 * external API.
 *
 * <p><b>Contract:</b> The annotated method must accept
 * {@code (Map<String, Object> arguments, String idempotencyKey)} as its two
 * parameters. The idempotency key is extracted from the second argument.
 *
 * <p>Typical usage:
 * <pre>{@code
 * @IdempotentTool(ttl = "24h")
 * public Map<String, Object> execute(Map<String, Object> arguments, String idempotencyKey) { ... }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface IdempotentTool {

    /**
     * Time-to-live for the cached result.
     * Currently only {@code "24h"} is supported.
     */
    String ttl() default "24h";
}
