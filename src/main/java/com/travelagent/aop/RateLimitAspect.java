package com.travelagent.aop;

import com.travelagent.exception.RateLimitExceededException;
import com.travelagent.filter.JwtAuthInterceptor;
import com.travelagent.util.JwtUtil;
import com.travelagent.util.RedisUtil;
import io.jsonwebtoken.JwtException;
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
 * 中文注释：匿名请求 IP 限流切面。
 *
 * <p>该切面会在命中 {@link RateLimit} 注解的方法执行前运行，只对匿名请求进行计数。
 * 已登录请求不会占用匿名限流额度，因此公共接口即使允许携带 JWT，也不会误伤已登录用户。
 *
 * <p>实现要点：
 * <ul>
 *   <li>Redis key 格式：{@code ratelimit:ip:{ip}:{yyyy-MM-dd-HH}}，按自然小时分桶。</li>
 *   <li>每次请求通过 Redis 自增计数，首次写入时补 2 小时 TTL，避免跨小时中途过期。</li>
 *   <li>没有 HTTP 请求上下文时直接跳过，因为限流只属于 Web 入口层逻辑。</li>
 *   <li>客户端 IP 优先读取 {@code X-Forwarded-For}，其次读取 {@code X-Real-IP}。</li>
 * </ul>
 */
@Aspect
@Component
public class RateLimitAspect {

    private static final Logger log = LoggerFactory.getLogger(RateLimitAspect.class);

    private static final String KEY_PATTERN = "ratelimit:ip:%s:%s";
    private static final String BLACKLIST_KEY_PREFIX = "jwt:blacklist:";
    private static final DateTimeFormatter HOUR_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH");

    @Value("${ratelimit.anonymous.hourly-limit:10}")
    private int hourlyLimit;

    @Value("${jwt.header:Authorization}")
    private String headerName;

    @Value("${jwt.prefix:Bearer}")
    private String tokenPrefix;

    @Autowired
    private RedisUtil redisUtil;

    @Autowired
    private JwtUtil jwtUtil;

    /**
     * 处理enforceRateLimit。
     * @param jp j p 参数
     * @param rateLimit r at eL im it 参数
     */
    @Before("@annotation(rateLimit)")
    public void enforceRateLimit(JoinPoint jp, RateLimit rateLimit) {
        ServletRequestAttributes attrs;
        try {
            attrs = (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
        } catch (IllegalStateException e) {
            // 非 HTTP 请求线程，例如异步任务执行线程，直接跳过限流检查。
            return;
        }

        HttpServletRequest request = attrs.getRequest();

        // 已由鉴权拦截器确认登录的请求，直接豁免匿名限流。
        if (request.getAttribute(JwtAuthInterceptor.ATTR_USER_ID) != null) {
            return;
        }

        // 公共接口如果带有合法 JWT，也应视为已登录请求，不计入匿名额度。
        if (isAuthenticatedRequest(request)) {
            return;
        }

        String ip = resolveClientIp(request);
        String hourBucket = LocalDateTime.now().format(HOUR_FORMATTER);
        String redisKey = String.format(KEY_PATTERN, ip, hourBucket);

        // 首次请求会创建计数器并设置 2 小时 TTL，覆盖当前小时和下一小时窗口。
        Long count = redisUtil.incrementWithTtl(redisKey, 1L, Duration.ofHours(2));

        if (count != null && count > hourlyLimit) {
            log.warn("[RateLimitAspect] Hourly limit ({}) exceeded for ip={} (count={})",
                    hourlyLimit, ip, count);
            throw new RateLimitExceededException(
                    "匿名用户请求过于频繁，每小时最多允许 " + hourlyLimit + " 次请求。");
        }

        log.debug("[RateLimitAspect] ip={} count={}/{}", ip, count, hourlyLimit);
    }

    /**
     * 判断authenticatedrequest。
     * @param request 请求参数
     * @return 是否满足当前条件。
     */
    private boolean isAuthenticatedRequest(HttpServletRequest request) {
        String header = request.getHeader(headerName);
        if (header == null || !header.startsWith(tokenPrefix + " ")) {
            return false;
        }

        String token = header.substring(tokenPrefix.length() + 1).trim();
        if (token.isEmpty()) {
            return false;
        }

        if (redisUtil.hasKey(BLACKLIST_KEY_PREFIX + token)) {
            return false;
        }

        try {
            jwtUtil.parseToken(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("[RateLimitAspect] Optional JWT parse failed, fallback to anonymous request: {}",
                    e.getMessage());
            return false;
        }
    }

    /**
     * 解析并确定clientip。
     * @param request 请求参数
     * @return 返回处理结果。
     */
    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }

        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }

        return request.getRemoteAddr();
    }
}
