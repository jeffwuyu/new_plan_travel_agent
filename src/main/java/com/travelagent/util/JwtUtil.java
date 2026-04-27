package com.travelagent.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT utility for signing and verifying tokens.
 *
 * Token payload claims:
 *   sub  - userId (String)
 *   lvl  - userLevel (int)
 *   iat  - issued at
 *   exp  - expiration
 *
 * On logout, the token is added to a Redis blacklist (key = jwt:blacklist:{token}).
 * The JwtAuthInterceptor checks the blacklist on every request.
 */

/**
 * 中文注释：工具类，封装 Jwt Util 相关的通用辅助能力。
 */

@Component
public class JwtUtil {

    private static final Logger log = LoggerFactory.getLogger(JwtUtil.class);

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration:86400000}")
    private long expirationMs;

    private SecretKey signingKey;

    /**
     * 处理init。
     */
    @PostConstruct
    public void init() {
        // Derive a secure key from the configured secret
        signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 处理generateToken。
     * @param userId 用户ID
     * @param userLevel 用户等级
     * @return 返回处理结果。
     */
    public String generateToken(Long userId, int userLevel) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);
        return Jwts.builder()
            .subject(String.valueOf(userId))
            .claim("lvl", userLevel)
            .issuedAt(now)
            .expiration(expiry)
            .signWith(signingKey)
            .compact();
    }

    /**
     * 解析token。
     * @param token t ok en 参数
     * @return 返回处理结果。
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
            .verifyWith(signingKey)
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }

    /**
     * 获取useridfromtoken。
     * @param token t ok en 参数
     * @return 返回处理结果。
     */
    public Long getUserIdFromToken(String token) {
        return Long.valueOf(parseToken(token).getSubject());
    }

    /**
     * 获取userlevelfromtoken。
     * @param token t ok en 参数
     * @return 返回处理结果。
     */
    public int getUserLevelFromToken(String token) {
        return parseToken(token).get("lvl", Integer.class);
    }

    /**
     * 获取expirationfromtoken。
     * @param token t ok en 参数
     * @return 返回处理结果。
     */
    public Date getExpirationFromToken(String token) {
        return parseToken(token).getExpiration();
    }

    /**
     * 获取remainingvalidityms。
     * @param token t ok en 参数
     * @return 返回处理结果。
     */
    public long getRemainingValidityMs(String token) {
        Date expiry = getExpirationFromToken(token);
        return Math.max(0, expiry.getTime() - System.currentTimeMillis());
    }

    /**
     * 判断tokenexpired。
     * @param token t ok en 参数
     * @return 是否满足当前条件。
     */
    public boolean isTokenExpired(String token) {
        try {
            return getExpirationFromToken(token).before(new Date());
        } catch (ExpiredJwtException e) {
            return true;
        }
    }
}
