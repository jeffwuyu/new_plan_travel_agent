package com.travelagent.aop;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a REST controller method for IP-based rate limiting on anonymous requests.
 *
 * <p>{@link RateLimitAspect} intercepts methods annotated with this annotation.
 * Authenticated users (those with a valid JWT, indicated by a non-null {@code userId}
 * request attribute) are exempt — only unauthenticated (anonymous) callers are counted.
 *
 * <p>Limits are configured via {@code ratelimit.anonymous.hourly-limit} (default: 10
 * requests per hour per IP). Exceeding the limit throws
 * {@link com.travelagent.exception.RateLimitExceededException} (HTTP 429).
 *
 * <p>Typical usage — annotate public-facing controller endpoints that should be
 * accessible without authentication but still need abuse protection:
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
