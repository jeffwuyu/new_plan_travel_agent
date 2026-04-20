package com.travelagent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelagent.exception.BusinessException;
import com.travelagent.exception.GlobalExceptionHandler;
import com.travelagent.filter.JwtAuthInterceptor;
import com.travelagent.mapper.TaskMapper;
import com.travelagent.mapper.UserMapper;
import com.travelagent.mapper.UserQuotaConfigMapper;
import com.travelagent.model.entity.Task;
import com.travelagent.model.entity.User;
import com.travelagent.model.entity.UserQuotaConfig;
import com.travelagent.service.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 用途：AdminController 单元测试。
 *
 * 测试范围：
 *   1. 管理员权限校验（非 ADMIN 用户 → 403）
 *   2. GET  /api/admin/users              — 用户列表分页（正常/密码哈希清除验证）
 *   3. PUT  /api/admin/users/{id}/level   — 修改用户等级（正常/非法值/用户不存在）
 *   4. PUT  /api/admin/users/{id}/status  — 修改用户状态（正常/非法值）
 *   5. GET  /api/admin/quota-configs      — 查询配额配置
 *   6. PUT  /api/admin/quota-configs/{l}  — 更新配额配置（正常/非法值/非法等级）
 *   7. GET  /api/admin/tasks              — 任务列表（按状态过滤/不过滤）
 *
 * 测试策略：MockMvc standalone，所有依赖 Mock，不启动 Spring 上下文。
 * JwtAuthInterceptor 的静态方法通过 MockedStatic 注入 userLevel。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminController Tests")
class AdminControllerTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private UserService userService;

    @Mock
    private UserQuotaConfigMapper quotaConfigMapper;

    @Mock
    private TaskMapper taskMapper;

    @InjectMocks
    private AdminController adminController;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    private static final Long ADMIN_USER_ID  = 1L;
    private static final Long TARGET_USER_ID = 2L;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(adminController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        objectMapper = new ObjectMapper();
    }

    // ===================== 权限校验 =====================

    @Test
    @DisplayName("非管理员访问 /admin/users - 返回 403")
    void nonAdmin_accessUsers_returns403() throws Exception {
        try (MockedStatic<JwtAuthInterceptor> mocked = mockStatic(JwtAuthInterceptor.class)) {
            // userLevel=1 表示普通用户，不是管理员
            mocked.when(() -> JwtAuthInterceptor.getUserId(any())).thenReturn(ADMIN_USER_ID);
            mocked.when(() -> JwtAuthInterceptor.getUserLevel(any())).thenReturn(1);

            mockMvc.perform(get("/api/admin/users"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(403));
        }
    }

    @Test
    @DisplayName("VIP 用户（level=2）访问管理接口 - 返回 403")
    void vipUser_accessAdmin_returns403() throws Exception {
        try (MockedStatic<JwtAuthInterceptor> mocked = mockStatic(JwtAuthInterceptor.class)) {
            mocked.when(() -> JwtAuthInterceptor.getUserId(any())).thenReturn(ADMIN_USER_ID);
            mocked.when(() -> JwtAuthInterceptor.getUserLevel(any())).thenReturn(2);

            mockMvc.perform(get("/api/admin/users"))
                    .andExpect(status().isForbidden());
        }
    }

    // ===================== GET /api/admin/users =====================

    @Test
    @DisplayName("管理员分页查询用户列表 - 返回 200，密码哈希字段为空")
    void listUsers_admin_success() throws Exception {
        User user = buildUser(TARGET_USER_ID, "alice", "alice@example.com", 1);
        // 故意设置 passwordHash，测试是否被清除
        user.setPasswordHash("$2a$10$hashed");

        try (MockedStatic<JwtAuthInterceptor> mocked = mockStatic(JwtAuthInterceptor.class)) {
            mocked.when(() -> JwtAuthInterceptor.getUserId(any())).thenReturn(ADMIN_USER_ID);
            mocked.when(() -> JwtAuthInterceptor.getUserLevel(any())).thenReturn(3);
            when(userMapper.findAll()).thenReturn(List.of(user));

            mockMvc.perform(get("/api/admin/users").param("page", "1").param("size", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    // 密码哈希必须在响应中不存在
                    .andExpect(jsonPath("$.data.list[0].passwordHash").doesNotExist());
        }
    }

    // ===================== PUT /api/admin/users/{userId}/level =====================

    @Test
    @DisplayName("管理员修改用户等级为 VIP - 返回 200")
    void updateUserLevel_toVip_success() throws Exception {
        User user = buildUser(TARGET_USER_ID, "alice", "alice@example.com", 1);

        try (MockedStatic<JwtAuthInterceptor> mocked = mockStatic(JwtAuthInterceptor.class)) {
            mocked.when(() -> JwtAuthInterceptor.getUserId(any())).thenReturn(ADMIN_USER_ID);
            mocked.when(() -> JwtAuthInterceptor.getUserLevel(any())).thenReturn(3);
            when(userMapper.findById(TARGET_USER_ID)).thenReturn(user);
            doNothing().when(userService).updateUserLevel(TARGET_USER_ID, 2);

            mockMvc.perform(put("/api/admin/users/{id}/level", TARGET_USER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("userLevel", 2))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            verify(userService).updateUserLevel(TARGET_USER_ID, 2);
        }
    }

    @Test
    @DisplayName("管理员修改用户等级 - 非法等级值（0）- 返回 400")
    void updateUserLevel_invalidLevel_returns400() throws Exception {
        try (MockedStatic<JwtAuthInterceptor> mocked = mockStatic(JwtAuthInterceptor.class)) {
            mocked.when(() -> JwtAuthInterceptor.getUserId(any())).thenReturn(ADMIN_USER_ID);
            mocked.when(() -> JwtAuthInterceptor.getUserLevel(any())).thenReturn(3);

            mockMvc.perform(put("/api/admin/users/{id}/level", TARGET_USER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("userLevel", 0))))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    @DisplayName("管理员修改用户等级 - 用户不存在 - 返回 404")
    void updateUserLevel_userNotFound_returns404() throws Exception {
        try (MockedStatic<JwtAuthInterceptor> mocked = mockStatic(JwtAuthInterceptor.class)) {
            mocked.when(() -> JwtAuthInterceptor.getUserId(any())).thenReturn(ADMIN_USER_ID);
            mocked.when(() -> JwtAuthInterceptor.getUserLevel(any())).thenReturn(3);
            when(userMapper.findById(TARGET_USER_ID)).thenReturn(null);

            mockMvc.perform(put("/api/admin/users/{id}/level", TARGET_USER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("userLevel", 2))))
                    .andExpect(status().isNotFound());
        }
    }

    // ===================== PUT /api/admin/users/{userId}/status =====================

    @Test
    @DisplayName("管理员禁用用户 - 返回 200")
    void updateUserStatus_disable_success() throws Exception {
        User user = buildUser(TARGET_USER_ID, "alice", "alice@example.com", 1);

        try (MockedStatic<JwtAuthInterceptor> mocked = mockStatic(JwtAuthInterceptor.class)) {
            mocked.when(() -> JwtAuthInterceptor.getUserId(any())).thenReturn(ADMIN_USER_ID);
            mocked.when(() -> JwtAuthInterceptor.getUserLevel(any())).thenReturn(3);
            when(userMapper.findById(TARGET_USER_ID)).thenReturn(user);
            when(userMapper.update(any(User.class))).thenReturn(1);

            mockMvc.perform(put("/api/admin/users/{id}/status", TARGET_USER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("status", 0))))
                    .andExpect(status().isOk());

            verify(userMapper).update(argThat(u -> u.getId().equals(TARGET_USER_ID) && u.getStatus() == 0));
        }
    }

    @Test
    @DisplayName("管理员修改状态 - 非法状态值（2）- 返回 400")
    void updateUserStatus_invalidStatus_returns400() throws Exception {
        try (MockedStatic<JwtAuthInterceptor> mocked = mockStatic(JwtAuthInterceptor.class)) {
            mocked.when(() -> JwtAuthInterceptor.getUserId(any())).thenReturn(ADMIN_USER_ID);
            mocked.when(() -> JwtAuthInterceptor.getUserLevel(any())).thenReturn(3);

            mockMvc.perform(put("/api/admin/users/{id}/status", TARGET_USER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("status", 2))))
                    .andExpect(status().isBadRequest());
        }
    }

    // ===================== GET /api/admin/quota-configs =====================

    @Test
    @DisplayName("管理员查询所有配额配置 - 返回三个等级配置")
    void listQuotaConfigs_success() throws Exception {
        UserQuotaConfig regular = buildConfig(1, 10000, 100000, 2, 15);
        UserQuotaConfig vip     = buildConfig(2, 50000, 500000, 5, 30);
        UserQuotaConfig admin   = buildConfig(3, 999999, 9999999, 10, 50);

        try (MockedStatic<JwtAuthInterceptor> mocked = mockStatic(JwtAuthInterceptor.class)) {
            mocked.when(() -> JwtAuthInterceptor.getUserId(any())).thenReturn(ADMIN_USER_ID);
            mocked.when(() -> JwtAuthInterceptor.getUserLevel(any())).thenReturn(3);
            when(quotaConfigMapper.findByUserLevel(1)).thenReturn(regular);
            when(quotaConfigMapper.findByUserLevel(2)).thenReturn(vip);
            when(quotaConfigMapper.findByUserLevel(3)).thenReturn(admin);

            mockMvc.perform(get("/api/admin/quota-configs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.REGULAR.dailyTokenLimit").value(10000))
                    .andExpect(jsonPath("$.data.VIP.dailyTokenLimit").value(50000))
                    .andExpect(jsonPath("$.data.ADMIN.dailyTokenLimit").value(999999));
        }
    }

    // ===================== PUT /api/admin/quota-configs/{level} =====================

    @Test
    @DisplayName("管理员修改 VIP 配额上限 - 返回 200")
    void updateQuotaConfig_vip_success() throws Exception {
        UserQuotaConfig newConfig = new UserQuotaConfig();
        newConfig.setDailyTokenLimit(80000);
        newConfig.setMonthlyTokenLimit(800000);

        try (MockedStatic<JwtAuthInterceptor> mocked = mockStatic(JwtAuthInterceptor.class)) {
            mocked.when(() -> JwtAuthInterceptor.getUserId(any())).thenReturn(ADMIN_USER_ID);
            mocked.when(() -> JwtAuthInterceptor.getUserLevel(any())).thenReturn(3);
            when(quotaConfigMapper.update(any(UserQuotaConfig.class))).thenReturn(1);

            mockMvc.perform(put("/api/admin/quota-configs/{level}", 2)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(newConfig)))
                    .andExpect(status().isOk());

            verify(quotaConfigMapper).update(argThat(c -> c.getUserLevel() == 2));
        }
    }

    @Test
    @DisplayName("修改配额 - 非法等级值（4）- 返回 400")
    void updateQuotaConfig_invalidLevel_returns400() throws Exception {
        UserQuotaConfig config = new UserQuotaConfig();
        config.setDailyTokenLimit(10000);

        try (MockedStatic<JwtAuthInterceptor> mocked = mockStatic(JwtAuthInterceptor.class)) {
            mocked.when(() -> JwtAuthInterceptor.getUserId(any())).thenReturn(ADMIN_USER_ID);
            mocked.when(() -> JwtAuthInterceptor.getUserLevel(any())).thenReturn(3);

            mockMvc.perform(put("/api/admin/quota-configs/{level}", 4)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(config)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    @DisplayName("修改配额 - dailyTokenLimit 为 0（非法）- 返回 400")
    void updateQuotaConfig_zeroLimit_returns400() throws Exception {
        UserQuotaConfig config = new UserQuotaConfig();
        config.setDailyTokenLimit(0); // 非法，必须大于 0

        try (MockedStatic<JwtAuthInterceptor> mocked = mockStatic(JwtAuthInterceptor.class)) {
            mocked.when(() -> JwtAuthInterceptor.getUserId(any())).thenReturn(ADMIN_USER_ID);
            mocked.when(() -> JwtAuthInterceptor.getUserLevel(any())).thenReturn(3);

            mockMvc.perform(put("/api/admin/quota-configs/{level}", 1)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(config)))
                    .andExpect(status().isBadRequest());
        }
    }

    // ===================== GET /api/admin/tasks =====================

    @Test
    @DisplayName("管理员查询运行中任务（按状态过滤）- 返回 PageInfo，不含 checkpoint_json")
    void listTasks_byStatus_success() throws Exception {
        Task task = buildTask("planning");

        try (MockedStatic<JwtAuthInterceptor> mocked = mockStatic(JwtAuthInterceptor.class)) {
            mocked.when(() -> JwtAuthInterceptor.getUserId(any())).thenReturn(ADMIN_USER_ID);
            mocked.when(() -> JwtAuthInterceptor.getUserLevel(any())).thenReturn(3);
            when(taskMapper.findAllWithFilter(eq("planning"))).thenReturn(List.of(task));

            mockMvc.perform(get("/api/admin/tasks").param("status", "planning"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.list[0].status").value("planning"))
                    .andExpect(jsonPath("$.data.total").value(1))
                    // checkpoint_json 已被清空，不暴露
                    .andExpect(jsonPath("$.data.list[0].checkpointJson").doesNotExist());

            verify(taskMapper).findAllWithFilter("planning");
            verify(taskMapper, never()).findByStatus(any(), anyInt());
        }
    }

    @Test
    @DisplayName("管理员查询任务（不过滤状态）- 使用 null 调用 findAllWithFilter")
    void listTasks_noFilter_usesNull() throws Exception {
        Task task1 = buildTask("pending");
        Task task2 = buildTask("completed");

        try (MockedStatic<JwtAuthInterceptor> mocked = mockStatic(JwtAuthInterceptor.class)) {
            mocked.when(() -> JwtAuthInterceptor.getUserId(any())).thenReturn(ADMIN_USER_ID);
            mocked.when(() -> JwtAuthInterceptor.getUserLevel(any())).thenReturn(3);
            when(taskMapper.findAllWithFilter(null)).thenReturn(List.of(task1, task2));

            mockMvc.perform(get("/api/admin/tasks"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.total").value(2));

            verify(taskMapper).findAllWithFilter(null);
            verify(taskMapper, never()).findByStatus(any(), anyInt());
        }
    }

    @Test
    @DisplayName("管理员查询任务 - checkpoint_json 不在响应中暴露")
    void listTasks_checkpointJson_notExposed() throws Exception {
        Task task = buildTask("planning");

        try (MockedStatic<JwtAuthInterceptor> mocked = mockStatic(JwtAuthInterceptor.class)) {
            mocked.when(() -> JwtAuthInterceptor.getUserId(any())).thenReturn(ADMIN_USER_ID);
            mocked.when(() -> JwtAuthInterceptor.getUserLevel(any())).thenReturn(3);
            when(taskMapper.findAllWithFilter(any())).thenReturn(List.of(task));

            mockMvc.perform(get("/api/admin/tasks").param("status", "planning"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.list[0].checkpointJson").doesNotExist());
        }
    }

    @Test
    @DisplayName("管理员查询任务 - size 超出上限 200 - 返回 400")
    void listTasks_sizeExceedsLimit_returns400() throws Exception {
        try (MockedStatic<JwtAuthInterceptor> mocked = mockStatic(JwtAuthInterceptor.class)) {
            mocked.when(() -> JwtAuthInterceptor.getUserId(any())).thenReturn(ADMIN_USER_ID);
            mocked.when(() -> JwtAuthInterceptor.getUserLevel(any())).thenReturn(3);

            mockMvc.perform(get("/api/admin/tasks").param("size", "201"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    @DisplayName("管理员查询任务 - page 为 0 - 返回 400")
    void listTasks_pageZero_returns400() throws Exception {
        try (MockedStatic<JwtAuthInterceptor> mocked = mockStatic(JwtAuthInterceptor.class)) {
            mocked.when(() -> JwtAuthInterceptor.getUserId(any())).thenReturn(ADMIN_USER_ID);
            mocked.when(() -> JwtAuthInterceptor.getUserLevel(any())).thenReturn(3);

            mockMvc.perform(get("/api/admin/tasks").param("page", "0"))
                    .andExpect(status().isBadRequest());
        }
    }

    // -----------------------------------------------------------------------
    // 测试数据构造辅助方法
    // -----------------------------------------------------------------------

    private User buildUser(Long id, String username, String email, int level) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setEmail(email);
        user.setUserLevel(level);
        user.setStatus(1);
        return user;
    }

    private UserQuotaConfig buildConfig(int level, int daily, int monthly, int maxTasks, int maxSteps) {
        UserQuotaConfig cfg = new UserQuotaConfig();
        cfg.setUserLevel(level);
        cfg.setDailyTokenLimit(daily);
        cfg.setMonthlyTokenLimit(monthly);
        cfg.setMaxConcurrentTasks(maxTasks);
        cfg.setMaxPlanSteps(maxSteps);
        return cfg;
    }

    private Task buildTask(String status) {
        Task task = new Task();
        task.setId(1L);
        task.setUserId(2L);
        task.setTaskUuid("task-uuid-001");
        task.setStatus(status);
        task.setRegion("西安市");
        task.setCheckpointJson("{\"schemaVersion\":\"1.0\"}"); // 应在响应中被清空
        return task;
    }
}
