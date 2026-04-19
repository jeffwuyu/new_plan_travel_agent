package com.travelagent.aop;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 中文注释：标记一个接口需要执行匿名请求的 IP 限流。
 *
 * <p>该注解通常用于“允许匿名访问、但仍需要防刷”的公共接口。
 * {@link RateLimitAspect} 会在方法执行前判断当前请求是否属于匿名请求：
 * 如果请求已在拦截器中写入登录态，或公共接口携带了合法 JWT，则不会计入匿名额度；
 * 只有真正的匿名调用才会按 IP 进行小时级限流。
 *
 * <p>限流阈值由配置项 {@code ratelimit.anonymous.hourly-limit} 控制，
 * 默认值为每个 IP 每小时 10 次。超出阈值后会抛出
 * {@link com.travelagent.exception.RateLimitExceededException}，并由全局异常处理器映射为 HTTP 429。
 *
 * <p>示例：
 * <pre>{@code
 * @GetMapping("/api/destinations/suggest")
 * @RateLimit
 * public Result<List<String>> suggestDestinations(@RequestParam String keyword) { ... }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimit {
}
