package com.travelagent.service;

import com.travelagent.exception.BusinessException;
import com.travelagent.mapper.UserMapper;
import com.travelagent.model.dto.LoginRequest;
import com.travelagent.model.dto.LoginResponse;
import com.travelagent.model.dto.RegisterRequest;
import com.travelagent.model.entity.User;
import com.travelagent.service.user.impl.UserServiceImpl;
import com.travelagent.util.JwtUtil;
import com.travelagent.util.RedisUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mindrot.jbcrypt.BCrypt;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for UserServiceImpl.
 * All external dependencies are mocked with Mockito.
 */

/**
 * 中文注释：测试类，用于验证 User Service Test 相关行为是否符合预期。
 */

@ExtendWith(MockitoExtension.class)
@DisplayName("UserService Tests")
class UserServiceTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private RedisUtil redisUtil;

    @InjectMocks
    private UserServiceImpl userService;

    private RegisterRequest validRegisterRequest;
    private LoginRequest validLoginRequest;
    private User existingUser;

    @BeforeEach
    void setUp() {
        validRegisterRequest = new RegisterRequest();
        validRegisterRequest.setUsername("testuser");
        validRegisterRequest.setEmail("test@example.com");
        validRegisterRequest.setPassword("password123");

        validLoginRequest = new LoginRequest();
        validLoginRequest.setEmail("test@example.com");
        validLoginRequest.setPassword("password123");

        existingUser = new User();
        existingUser.setId(1L);
        existingUser.setUsername("testuser");
        existingUser.setEmail("test@example.com");
        existingUser.setPasswordHash(BCrypt.hashpw("password123", BCrypt.gensalt()));
        existingUser.setUserLevel(1);
        existingUser.setStatus(1);
    }

    // ===================== Register =====================

    @Test
    @DisplayName("正常注册 - 返回用户对象，密码哈希已清空")
    void register_success() {
        when(userMapper.existsByEmail(anyString())).thenReturn(false);
        when(userMapper.existsByUsername(anyString())).thenReturn(false);
        when(userMapper.insert(any(User.class))).thenReturn(1);

        User result = userService.register(validRegisterRequest);

        assertNotNull(result);
        assertNull(result.getPasswordHash(), "密码哈希不应返回给调用方");
        assertEquals("testuser", result.getUsername());
        assertEquals(1, result.getUserLevel());
        verify(userMapper).insert(any(User.class));
    }

    @Test
    @DisplayName("邮箱重复注册 - 抛出 BusinessException(400)")
    void register_duplicateEmail_throws() {
        when(userMapper.existsByEmail("test@example.com")).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class,
            () -> userService.register(validRegisterRequest));

        assertEquals(400, ex.getHttpStatus());
        assertTrue(ex.getMessage().contains("邮箱"));
        verify(userMapper, never()).insert(any());
    }

    @Test
    @DisplayName("用户名重复注册 - 抛出 BusinessException(400)")
    void register_duplicateUsername_throws() {
        when(userMapper.existsByEmail(anyString())).thenReturn(false);
        when(userMapper.existsByUsername("testuser")).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class,
            () -> userService.register(validRegisterRequest));

        assertEquals(400, ex.getHttpStatus());
        assertTrue(ex.getMessage().contains("用户名"));
    }

    // ===================== Login =====================

    @Test
    @DisplayName("正确凭证登录 - 返回包含 Token 的 LoginResponse")
    void login_success() {
        when(userMapper.findByEmail("test@example.com")).thenReturn(existingUser);
        when(jwtUtil.generateToken(1L, 1)).thenReturn("mock.jwt.token");
        when(jwtUtil.getExpirationFromToken("mock.jwt.token"))
            .thenReturn(new Date(System.currentTimeMillis() + 3600000));

        LoginResponse response = userService.login(validLoginRequest);

        assertNotNull(response);
        assertEquals("mock.jwt.token", response.getToken());
        assertEquals(1L, response.getUserId());
        assertEquals("testuser", response.getUsername());
        assertEquals(1, response.getUserLevel());
    }

    @Test
    @DisplayName("错误密码登录 - 抛出 BusinessException(401)")
    void login_wrongPassword_throws() {
        when(userMapper.findByEmail("test@example.com")).thenReturn(existingUser);

        LoginRequest wrongPwdRequest = new LoginRequest();
        wrongPwdRequest.setEmail("test@example.com");
        wrongPwdRequest.setPassword("wrongpassword");

        BusinessException ex = assertThrows(BusinessException.class,
            () -> userService.login(wrongPwdRequest));

        assertEquals(401, ex.getHttpStatus());
    }

    @Test
    @DisplayName("不存在的邮箱登录 - 抛出 BusinessException(401)")
    void login_userNotFound_throws() {
        when(userMapper.findByEmail("notfound@example.com")).thenReturn(null);

        LoginRequest req = new LoginRequest();
        req.setEmail("notfound@example.com");
        req.setPassword("anypassword");

        BusinessException ex = assertThrows(BusinessException.class,
            () -> userService.login(req));

        assertEquals(401, ex.getHttpStatus());
    }

    @Test
    @DisplayName("禁用账户登录 - 抛出 BusinessException(401)")
    void login_disabledAccount_throws() {
        existingUser.setStatus(0); // Disabled
        when(userMapper.findByEmail("test@example.com")).thenReturn(existingUser);

        BusinessException ex = assertThrows(BusinessException.class,
            () -> userService.login(validLoginRequest));

        assertEquals(401, ex.getHttpStatus());
    }

    // ===================== Logout =====================

    @Test
    @DisplayName("登出 - Token 被加入 Redis 黑名单")
    void logout_blacklistsToken() {
        String token = "valid.jwt.token";
        when(jwtUtil.getRemainingValidityMs(token)).thenReturn(3600000L);

        userService.logout(token);

        verify(redisUtil).set(eq("jwt:blacklist:" + token), eq("1"), any());
    }

    @Test
    @DisplayName("登出空 Token - 不抛出异常")
    void logout_nullToken_noOp() {
        assertDoesNotThrow(() -> userService.logout(null));
        verify(redisUtil, never()).set(anyString(), any(), any());
    }

    // ===================== Cancel Account =====================

    @Test
    @DisplayName("注销账户 - 执行软删除")
    void cancelAccount_softDeletes() {
        when(userMapper.findById(1L)).thenReturn(existingUser);
        when(userMapper.softDelete(1L)).thenReturn(1);

        assertDoesNotThrow(() -> userService.cancelAccount(1L));
        verify(userMapper).softDelete(1L);
    }

    @Test
    @DisplayName("注销不存在的用户 - 抛出 BusinessException(404)")
    void cancelAccount_notFound_throws() {
        when(userMapper.findById(999L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
            () -> userService.cancelAccount(999L));

        assertEquals(404, ex.getHttpStatus());
    }
}
