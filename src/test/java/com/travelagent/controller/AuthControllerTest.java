package com.travelagent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelagent.exception.BusinessException;
import com.travelagent.exception.GlobalExceptionHandler;
import com.travelagent.model.dto.LoginRequest;
import com.travelagent.model.dto.LoginResponse;
import com.travelagent.model.dto.RegisterRequest;
import com.travelagent.model.entity.User;
import com.travelagent.service.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthController Tests")
class AuthControllerTest {

    @Mock
    private UserService userService;

    @InjectMocks
    private AuthController authController;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(authController, "tokenPrefix", "Bearer");
        mockMvc = MockMvcBuilders.standaloneSetup(authController)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("注册成功 - 返回 200 和用户信息")
    void register_success() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("newuser");
        request.setEmail("new@example.com");
        request.setPassword("password123");

        User user = new User();
        user.setId(1L);
        user.setUsername("newuser");
        user.setEmail("new@example.com");
        user.setUserLevel(1);

        when(userService.register(any(RegisterRequest.class))).thenReturn(user);

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.username").value("newuser"))
            .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
    }

    @Test
    @DisplayName("注册 - 缺少用户名 - 返回 400")
    void register_missingUsername_returns400() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("valid@example.com");
        request.setPassword("password123");

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("注册 - 邮箱格式非法 - 返回 400")
    void register_invalidEmail_returns400() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("user");
        request.setEmail("not-an-email");
        request.setPassword("password123");

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("注册 - 邮箱重复 - 服务抛出异常 - 返回 400")
    void register_duplicateEmail_returns400() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("user");
        request.setEmail("dup@example.com");
        request.setPassword("password123");

        when(userService.register(any())).thenThrow(new BusinessException(400, "邮箱已被注册"));

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.message").value("邮箱已被注册"));
    }

    @Test
    @DisplayName("登录成功 - 返回包含 Token 的响应")
    void login_success() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setUsername("user");
        request.setPassword("password123");

        LoginResponse response = new LoginResponse("jwt.token.here", 1L, "user", 1,
            System.currentTimeMillis() + 3600000);

        when(userService.login(any(LoginRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.token").value("jwt.token.here"))
            .andExpect(jsonPath("$.data.userId").value(1));
    }

    @Test
    @DisplayName("登录 - 缺少用户名 - 返回 400")
    void login_missingUsername_returns400() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setPassword("password123");

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("用户名不能为空"));
    }

    @Test
    @DisplayName("登录 - 密码错误 - 返回 401")
    void login_wrongCredentials_returns401() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setUsername("user");
        request.setPassword("wrongpassword");

        when(userService.login(any())).thenThrow(new BusinessException(401, "用户名或密码错误"));

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("登出 - 有效 Token - 返回 200")
    void logout_success() throws Exception {
        doNothing().when(userService).logout(any(String.class));

        mockMvc.perform(post("/api/auth/logout")
                .header("Authorization", "Bearer valid.jwt.token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        verify(userService).logout("valid.jwt.token");
    }
}
