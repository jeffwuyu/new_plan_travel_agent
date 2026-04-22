package com.travelagent.controller;

import com.travelagent.exception.GlobalExceptionHandler;
import com.travelagent.filter.JwtAuthInterceptor;
import com.travelagent.model.entity.User;
import com.travelagent.model.entity.UserQuotaConfig;
import com.travelagent.service.user.QuotaService;
import com.travelagent.service.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserController Tests")
class UserControllerTest {

    @Mock
    private UserService userService;

    @Mock
    private QuotaService quotaService;

    @InjectMocks
    private UserController userController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(userController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("quota returns used, remaining, and user level fields")
    void quota_returnsRemainingAndLevelFields() throws Exception {
        UserQuotaConfig config = new UserQuotaConfig();
        config.setDailyTokenLimit(1000);
        config.setMonthlyTokenLimit(10000);
        config.setMaxConcurrentTasks(2);
        config.setMaxPlanSteps(15);

        try (MockedStatic<JwtAuthInterceptor> mocked = mockStatic(JwtAuthInterceptor.class)) {
            mocked.when(() -> JwtAuthInterceptor.getUserId(any())).thenReturn(7L);
            mocked.when(() -> JwtAuthInterceptor.getUserLevel(any())).thenReturn(1);
            when(quotaService.getQuotaConfig(1)).thenReturn(config);
            when(quotaService.getDailyUsage(7L)).thenReturn(400L);
            when(quotaService.getMonthlyUsage(7L)).thenReturn(2500L);

            mockMvc.perform(get("/api/user/quota"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.dailyUsed").value(400))
                    .andExpect(jsonPath("$.data.dailyRemaining").value(600))
                    .andExpect(jsonPath("$.data.monthlyRemaining").value(7500))
                    .andExpect(jsonPath("$.data.userLevel").value(1))
                    .andExpect(jsonPath("$.data.userLevelLabel").isNotEmpty());
        }
    }

    @Test
    @DisplayName("quota returns zero remaining when no limit is configured")
    void quota_withoutLimit_returnsZeroRemaining() throws Exception {
        try (MockedStatic<JwtAuthInterceptor> mocked = mockStatic(JwtAuthInterceptor.class)) {
            mocked.when(() -> JwtAuthInterceptor.getUserId(any())).thenReturn(8L);
            mocked.when(() -> JwtAuthInterceptor.getUserLevel(any())).thenReturn(1);
            when(quotaService.getQuotaConfig(1)).thenReturn(null);
            when(quotaService.getDailyUsage(8L)).thenReturn(50L);
            when(quotaService.getMonthlyUsage(8L)).thenReturn(70L);

            mockMvc.perform(get("/api/user/quota"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.dailyLimit").value(0))
                    .andExpect(jsonPath("$.data.dailyRemaining").value(0))
                    .andExpect(jsonPath("$.data.monthlyRemaining").value(0));
        }
    }

    @Test
    @DisplayName("profile returns user level label")
    void profile_returnsUserLevelLabel() throws Exception {
        User user = new User();
        user.setId(11L);
        user.setUsername("alice");
        user.setEmail("alice@test.com");
        user.setUserLevel(2);

        try (MockedStatic<JwtAuthInterceptor> mocked = mockStatic(JwtAuthInterceptor.class)) {
            mocked.when(() -> JwtAuthInterceptor.getUserId(any())).thenReturn(11L);
            when(userService.findById(11L)).thenReturn(user);

            mockMvc.perform(get("/api/user/profile"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.username").value("alice"))
                    .andExpect(jsonPath("$.data.userLevelLabel").isNotEmpty());
        }
    }
}
