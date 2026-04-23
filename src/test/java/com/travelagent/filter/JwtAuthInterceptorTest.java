package com.travelagent.filter;

import com.travelagent.mapper.UserMapper;
import com.travelagent.model.entity.User;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    private Claims buildClaims(Long userId) {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn(String.valueOf(userId));
        return claims;
    }

    private User user(Long userId, int level, int status) {
        User user = new User();
        user.setId(userId);
        user.setUserLevel(level);
        user.setStatus(status);
        return user;
    }

    @Test
    @DisplayName("valid token sets userId and userLevel")
    void validToken_allowsRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("Authorization", "Bearer valid.jwt.token");

        Claims claims = buildClaims(42L);
        when(redisUtil.hasKey("jwt:blacklist:valid.jwt.token")).thenReturn(false);
        when(jwtUtil.parseToken("valid.jwt.token")).thenReturn(claims);
        when(userMapper.findById(42L)).thenReturn(user(42L, 2, 1));

        boolean result = interceptor.preHandle(request, response, null);

        assertTrue(result);
        assertEquals(42L, request.getAttribute(JwtAuthInterceptor.ATTR_USER_ID));
        assertEquals(2, request.getAttribute(JwtAuthInterceptor.ATTR_USER_LEVEL));
    }

    @Test
    @DisplayName("disabled user returns 401")
    void disabledUser_returns401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("Authorization", "Bearer valid.jwt.token");

        Claims claims = buildClaims(42L);
        when(redisUtil.hasKey("jwt:blacklist:valid.jwt.token")).thenReturn(false);
        when(jwtUtil.parseToken("valid.jwt.token")).thenReturn(claims);
        when(userMapper.findById(42L)).thenReturn(user(42L, 2, 0));

        boolean result = interceptor.preHandle(request, response, null);

        assertFalse(result);
        assertEquals(401, response.getStatus());
        assertNull(request.getAttribute(JwtAuthInterceptor.ATTR_USER_ID));
    }

    @Test
    @DisplayName("missing auth header returns 401")
    void missingAuthHeader_returns401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean result = interceptor.preHandle(request, response, null);

        assertFalse(result);
        assertEquals(401, response.getStatus());
    }

    @Test
    @DisplayName("blacklisted token returns 401")
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
    @DisplayName("expired token returns 401")
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
    @DisplayName("wrong auth header format returns 401")
    void wrongHeaderFormat_returns401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("Authorization", "BasicToken some.token");

        boolean result = interceptor.preHandle(request, response, null);

        assertFalse(result);
        assertEquals(401, response.getStatus());
    }

    @Test
    @DisplayName("getUserId reads from request attribute")
    void getUserId_readsFromRequestAttribute() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(JwtAuthInterceptor.ATTR_USER_ID, 99L);

        assertEquals(99L, JwtAuthInterceptor.getUserId(request));
    }

    @Test
    @DisplayName("JWT level claim does not override database level")
    void userLevel_isLoadedFromDatabaseInsteadOfJwtClaim() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("Authorization", "Bearer stale-level.jwt");

        Claims claims = buildClaims(7L);
        when(redisUtil.hasKey("jwt:blacklist:stale-level.jwt")).thenReturn(false);
        when(jwtUtil.parseToken("stale-level.jwt")).thenReturn(claims);
        when(userMapper.findById(7L)).thenReturn(user(7L, 3, 1));

        boolean result = interceptor.preHandle(request, response, null);

        assertTrue(result);
        assertEquals(3, request.getAttribute(JwtAuthInterceptor.ATTR_USER_LEVEL));
    }
}
