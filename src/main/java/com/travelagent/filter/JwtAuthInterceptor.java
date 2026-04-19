package com.travelagent.filter;

import com.travelagent.util.JwtUtil;
import com.travelagent.util.RedisUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * JWT authentication interceptor.
 * Registered in WebMvcConfig for all /api/** paths except public endpoints.
 *
 * On valid token: sets userId and userLevel as request attributes for downstream use.
 * On invalid/missing token: returns 401.
 * On blacklisted token (logged-out): returns 401.
 *
 * Async tasks use userId from the task record, not from JWT,
 * to avoid token expiry issues during long-running execution.
 */

/**
 * 中文注释：过滤或拦截类，负责 Jwt Auth Interceptor 相关的请求前置处理。
 */

@Component
public class JwtAuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthInterceptor.class);

    public static final String ATTR_USER_ID    = "userId";
    public static final String ATTR_USER_LEVEL = "userLevel";

    private static final String BLACKLIST_KEY_PREFIX = "jwt:blacklist:";

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private RedisUtil redisUtil;

    @Value("${jwt.header:Authorization}")
    private String headerName;

    @Value("${jwt.prefix:Bearer}")
    private String tokenPrefix;

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {
        String header = request.getHeader(headerName);
        if (header == null || !header.startsWith(tokenPrefix + " ")) {
            sendUnauthorized(response, "缺少认证Token");
            return false;
        }

        String token = header.substring(tokenPrefix.length() + 1).trim();

        // Check blacklist (logged-out tokens)
        if (redisUtil.hasKey(BLACKLIST_KEY_PREFIX + token)) {
            sendUnauthorized(response, "Token已失效，请重新登录");
            return false;
        }

        try {
            Claims claims = jwtUtil.parseToken(token);
            Long userId = Long.valueOf(claims.getSubject());
            int userLevel = claims.get("lvl", Integer.class);
            request.setAttribute(ATTR_USER_ID, userId);
            request.setAttribute(ATTR_USER_LEVEL, userLevel);
            return true;
        } catch (ExpiredJwtException e) {
            sendUnauthorized(response, "Token已过期，请重新登录");
            return false;
        } catch (JwtException e) {
            log.warn("Invalid JWT token: {}", e.getMessage());
            sendUnauthorized(response, "无效的Token");
            return false;
        }
    }

    private void sendUnauthorized(HttpServletResponse response, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
            "{\"code\":401,\"message\":\"" + message + "\"}"
        );
    }

    /** Convenience method to get userId from request attributes (set by this interceptor). */
    public static Long getUserId(HttpServletRequest request) {
        return (Long) request.getAttribute(ATTR_USER_ID);
    }

    /** Convenience method to get userLevel from request attributes. */
    public static int getUserLevel(HttpServletRequest request) {
        Integer level = (Integer) request.getAttribute(ATTR_USER_LEVEL);
        return level != null ? level : 0;
    }
}
