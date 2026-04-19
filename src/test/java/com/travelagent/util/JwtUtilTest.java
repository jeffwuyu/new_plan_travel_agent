package com.travelagent.util;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for JwtUtil — no Spring context required.
 * Tests token generation, parsing, expiry, and blacklist key format.
 */

/**
 * 中文注释：测试类，用于验证 Jwt Util Test 相关行为是否符合预期。
 */

@DisplayName("JwtUtil Tests")
class JwtUtilTest {

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        // Inject test secret (must be ≥256 bits for HS256)
        ReflectionTestUtils.setField(jwtUtil, "secret",
            "test-secret-key-that-is-long-enough-for-hs256-algorithm-256bits!!");
        ReflectionTestUtils.setField(jwtUtil, "expirationMs", 3600000L); // 1hr
        jwtUtil.init();
    }

    @Test
    @DisplayName("生成 Token 后可正确解析 userId 和 userLevel")
    void testGenerateAndParseToken() {
        Long userId = 42L;
        int userLevel = 2;

        String token = jwtUtil.generateToken(userId, userLevel);

        assertNotNull(token);
        assertFalse(token.isEmpty());
        assertEquals(userId, jwtUtil.getUserIdFromToken(token));
        assertEquals(userLevel, jwtUtil.getUserLevelFromToken(token));
    }

    @Test
    @DisplayName("Token 的过期时间应在生成后约1小时")
    void testTokenExpiry() {
        String token = jwtUtil.generateToken(1L, 1);
        Date expiry = jwtUtil.getExpirationFromToken(token);

        long diff = expiry.getTime() - System.currentTimeMillis();
        // Should be roughly 1 hour (allow 5 seconds tolerance)
        assertTrue(diff > 3595_000L, "Expiry should be ~1 hour in future");
        assertTrue(diff <= 3600_000L + 1000L, "Expiry should not exceed 1 hour");
    }

    @Test
    @DisplayName("Token 未过期时 isTokenExpired 返回 false")
    void testTokenNotExpired() {
        String token = jwtUtil.generateToken(1L, 1);
        assertFalse(jwtUtil.isTokenExpired(token));
    }

    @Test
    @DisplayName("生成的 Token 包含正确的 sub 声明")
    void testTokenSubjectClaim() {
        Long userId = 123L;
        String token = jwtUtil.generateToken(userId, 1);
        Claims claims = jwtUtil.parseToken(token);
        assertEquals(String.valueOf(userId), claims.getSubject());
    }

    @Test
    @DisplayName("getRemainingValidityMs 应返回正数")
    void testRemainingValidity() {
        String token = jwtUtil.generateToken(1L, 1);
        long remaining = jwtUtil.getRemainingValidityMs(token);
        assertTrue(remaining > 0, "Remaining validity should be positive for a fresh token");
    }

    @Test
    @DisplayName("不同 userId 生成的 Token 不同")
    void testDifferentUsersGetDifferentTokens() {
        String token1 = jwtUtil.generateToken(1L, 1);
        String token2 = jwtUtil.generateToken(2L, 1);
        assertNotEquals(token1, token2);
    }

    @Test
    @DisplayName("过期的 Token 解析应抛出 ExpiredJwtException")
    void testExpiredToken() throws InterruptedException {
        // Create a JwtUtil with 1ms expiry
        JwtUtil shortLivedJwt = new JwtUtil();
        ReflectionTestUtils.setField(shortLivedJwt, "secret",
            "test-secret-key-that-is-long-enough-for-hs256-algorithm-256bits!!");
        ReflectionTestUtils.setField(shortLivedJwt, "expirationMs", 1L);
        shortLivedJwt.init();

        String token = shortLivedJwt.generateToken(1L, 1);
        Thread.sleep(10); // Wait for expiry

        assertTrue(shortLivedJwt.isTokenExpired(token));
    }
}
