package com.travelagent.filter;

import com.travelagent.mapper.UserMapper;
import com.travelagent.util.JwtUtil;
import com.travelagent.util.RedisUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for JwtAuthInterceptor.
 */

/**
 * 中文注释：测试类，用于验证 Jwt Auth Interceptor Test 相关行为是否符合预期。
 */

@ExtendWith(MockitoExtension.class)
@DisplayName("JwtAuthInterceptor Tests")
class JwtAuthInterceptorTest {

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private RedisUtil redisUtil;

    @Mock
    private UserMapper userMapper;

    @InjectMocks
    private JwtAuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(interceptor, "headerName", "Authorization");
        ReflectionTestUtils.setField(interceptor, "tokenPrefix", "Bearer");
    }

    private Claims buildClaims(Long userId, int level) {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn(String.valueOf(userId));
        when(claims.get("lvl", Integer.class)).thenReturn(level);
        return claims;
    }

    @Test
    @DisplayName("有效 Token - 放行并设置 userId 和 userLevel 属性")
    void validToken_allowsRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("Authorization", "Bearer valid.jwt.token");

        // Build claims BEFORE outer when() to avoid nested stubbing (nested when() inside
        // thenReturn() argument leaves Mockito in an "UnfinishedStubbing" state which
        // contaminates subsequent test classes via thread-local state pollution).
        Claims claims = buildClaims(42L, 2);
        when(redisUtil.hasKey("jwt:blacklist:valid.jwt.token")).thenReturn(false);
        when(jwtUtil.parseToken("valid.jwt.token")).thenReturn(claims);
        when(userMapper.findUserActiveStatus(42L)).thenReturn(true);

        boolean result = interceptor.preHandle(request, response, null);

        assertTrue(result);
        assertEquals(42L, request.getAttribute(JwtAuthInterceptor.ATTR_USER_ID));
        assertEquals(2, request.getAttribute(JwtAuthInterceptor.ATTR_USER_LEVEL));
    }

    @Test
    @DisplayName("账号已被禁用 - 返回 401")
    void disabledUser_returns401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("Authorization", "Bearer valid.jwt.token");

        Claims claims = buildClaims(42L, 2);
        when(redisUtil.hasKey("jwt:blacklist:valid.jwt.token")).thenReturn(false);
        when(jwtUtil.parseToken("valid.jwt.token")).thenReturn(claims);
        when(userMapper.findUserActiveStatus(42L)).thenReturn(false);

        boolean result = interceptor.preHandle(request, response, null);

        assertFalse(result);
        assertEquals(401, response.getStatus());
        assertNull(request.getAttribute(JwtAuthInterceptor.ATTR_USER_ID));
    }

    @Test
    @DisplayName("缺少 Authorization 头 - 返回 401")
    void missingAuthHeader_returns401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean result = interceptor.preHandle(request, response, null);

        assertFalse(result);
        assertEquals(401, response.getStatus());
    }

    @Test
    @DisplayName("Token 已被加入黑名单（已登出）- 返回 401")
    void blacklistedToken_returns401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("Authorization", "Bearer blacklisted.token");

        when(redisUtil.hasKey("jwt:blacklist:blacklisted.token")).thenReturn(true);

        boolean result = interceptor.preHandle(request, response, null);

        assertFalse(result);
        assertEquals(401, response.getStatus());
        verify(jwtUtil, never()).parseToken(anyString());
    }

    @Test
    @DisplayName("Token 已过期 - 返回 401")
    void expiredToken_returns401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("Authorization", "Bearer expired.token");

        when(redisUtil.hasKey(anyString())).thenReturn(false);
        when(jwtUtil.parseToken("expired.token"))
            .thenThrow(new ExpiredJwtException(null, null, "Token expired"));

        boolean result = interceptor.preHandle(request, response, null);

        assertFalse(result);
        assertEquals(401, response.getStatus());
    }

    @Test
    @DisplayName("Authorization 头格式错误（无 Bearer 前缀）- 返回 401")
    void wrongHeaderFormat_returns401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("Authorization", "BasicToken some.token");

        boolean result = interceptor.preHandle(request, response, null);

        assertFalse(result);
        assertEquals(401, response.getStatus());
    }

    @Test
    @DisplayName("getUserId 静态方法 - 正确从 request 属性读取")
    void getUserId_readsFromRequestAttribute() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(JwtAuthInterceptor.ATTR_USER_ID, 99L);

        assertEquals(99L, JwtAuthInterceptor.getUserId(request));
    }
}
