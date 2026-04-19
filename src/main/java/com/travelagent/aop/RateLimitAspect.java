package com.travelagent.aop;

import com.travelagent.exception.RateLimitExceededException;
import com.travelagent.util.RedisUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * AOP {@code @Before} aspect that enforces per-IP rate limiting on anonymous HTTP requests.
 *
 * <p>Only anonymous callers (no valid JWT / no {@code userId} request attribute set by
 * {@link com.travelagent.filter.JwtAuthInterceptor}) are subject to the limit.
 * Authenticated users are never counted.
 *
 * <p>Implementation details:
 * <ul>
 *   <li>Redis key: {@code ratelimit:ip:{ip}:{yyyy-MM-dd-HH}} — one key per calendar hour.</li>
 *   <li>Each request atomically increments the counter (via {@code INCR} + TTL set on
 *       first write).</li>
 *   <li>Key TTL: 2 hours — covers the current hour and the next, preventing a counter
 *       from expiring mid-hour.</li>
 *   <li>If the counter exceeds {@code ratelimit.anonymous.hourly-limit} (default: 10),
 *       {@link RateLimitExceededException} is thrown, which the global exception handler
 *       maps to HTTP 429.</li>
 * </ul>
 *
 * <p>If no HTTP request context is available (e.g. the method is called from an async
 * agent thread), the aspect silently skips the check — rate limiting is an HTTP-boundary
 * concern only.
 */
@Aspect
@Component
public class RateLimitAspect {

    private static final Logger log = LoggerFactory.getLogger(RateLimitAspect.class);

    private static final String KEY_PATTERN = "ratelimit:ip:%s:%s";
    private static final DateTimeFormatter HOUR_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH");

    @Value("${ratelimit.anonymous.hourly-limit:10}")
    private int hourlyLimit;

    @Autowired
    private RedisUtil redisUtil;

    /**
     * Enforces IP-based rate limiting before any method annotated with {@link RateLimit}.
     */
    @Before("@annotation(rateLimit)")
    public void enforceRateLimit(JoinPoint jp, RateLimit rateLimit) {
        ServletRequestAttributes attrs;
        try {
            attrs = (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
        } catch (IllegalStateException e) {
            // Not in an HTTP request thread (e.g. async agent executor) — skip
            return;
        }

        HttpServletRequest request = attrs.getRequest();

        // Authenticated users are exempt from IP-based rate limiting
        Object userId = request.getAttribute("userId");
        if (userId != null) {
            return;
        }

        String ip = resolveClientIp(request);
        String hourBucket = LocalDateTime.now().format(HOUR_FORMATTER);
        String redisKey = String.format(KEY_PATTERN, ip, hourBucket);

        // Atomically increment; TTL is set on first write (first request in this hour)
        Long count = redisUtil.incrementWithTtl(redisKey, 1L, Duration.ofHours(2));

        if (count != null && count > hourlyLimit) {
            log.warn("[RateLimitAspect] Hourly limit ({}) exceeded for ip={} (count={})",
                    hourlyLimit, ip, count);
            throw new RateLimitExceededException(
                    "Too many requests. Anonymous users are limited to "
                    + hourlyLimit + " requests per hour.");
        }

        log.debug("[RateLimitAspect] ip={} count={}/{}", ip, count, hourlyLimit);
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Resolves the real client IP, respecting reverse-proxy headers.
     * Falls back to {@code request.getRemoteAddr()} if no forwarding header is present.
     */
    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // X-Forwarded-For may contain a comma-separated chain; first entry is the client
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
