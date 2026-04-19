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

    @PostConstruct
    public void init() {
        // Derive a secure key from the configured secret
        signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Generate a JWT token for the given user.
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
     * Parse and validate a JWT token. Returns claims if valid.
     * Throws ExpiredJwtException if expired.
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
            .verifyWith(signingKey)
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }

    public Long getUserIdFromToken(String token) {
        return Long.valueOf(parseToken(token).getSubject());
    }

    public int getUserLevelFromToken(String token) {
        return parseToken(token).get("lvl", Integer.class);
    }

    public Date getExpirationFromToken(String token) {
        return parseToken(token).getExpiration();
    }

    public long getRemainingValidityMs(String token) {
        Date expiry = getExpirationFromToken(token);
        return Math.max(0, expiry.getTime() - System.currentTimeMillis());
    }

    public boolean isTokenExpired(String token) {
        try {
            return getExpirationFromToken(token).before(new Date());
        } catch (ExpiredJwtException e) {
            return true;
        }
    }
}
