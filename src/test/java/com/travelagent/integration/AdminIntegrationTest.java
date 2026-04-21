package com.travelagent.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelagent.mapper.UserMapper;
import com.travelagent.model.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for AdminController endpoints.
 *
 * Focuses on: user listing, task listing with the new findAllWithFilter SQL,
 * quota config read/update, and user level management.
 *
 * Admin setup: register a regular user, promote to level 3 via UserMapper directly,
 * then log in to get a JWT with lvl=3 embedded.
 */
@DisplayName("Admin Integration Tests")
class AdminIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserMapper userMapper;

    // ======================== setup helpers ========================

    private String registerAndLogin(String username, String email, String password) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"username\":\"%s\",\"email\":\"%s\",\"password\":\"%s\"}",
                                username, email, password)))
                .andExpect(status().isOk());

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"username\":\"%s\",\"password\":\"%s\"}", username, password)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        String token = json.path("data").path("token").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    /** Register user, promote to admin in DB, re-login to get lvl=3 JWT. */
    private String registerAsAdmin(String username, String email, String password) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"username\":\"%s\",\"email\":\"%s\",\"password\":\"%s\"}",
                                username, email, password)))
                .andExpect(status().isOk());

        // Directly promote in DB; JWT will include new level on next login
        User adminUser = userMapper.findByEmail(email);
        assertThat(adminUser).isNotNull();
        adminUser.setUserLevel(3);
        userMapper.update(adminUser);

        // Re-login to get JWT with lvl=3
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"username\":\"%s\",\"password\":\"%s\"}", username, password)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        String token = json.path("data").path("token").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    private String createTask(String token, String region, String intent) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/tasks")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"region\":\"%s\",\"userIntent\":\"%s\"}", region, intent)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.path("data").path("taskUuid").asText();
    }

    // ======================== user listing ========================

    @Test
    @DisplayName("管理员分页查询用户列表 - 返回 PageInfo，不含 passwordHash")
    void listUsers_asAdmin_returnsPageInfo() throws Exception {
        String adminToken = registerAsAdmin("admin1", "admin1@test.com", "adminpw");
        registerAndLogin("regular1", "regular1@test.com", "pass123");

        mockMvc.perform(get("/api/admin/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("page", "1").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.list[0].passwordHash").doesNotExist());
    }

    @Test
    @DisplayName("非管理员访问用户列表 - 返回 403")
    void listUsers_asRegularUser_returns403() throws Exception {
        String token = registerAndLogin("reg2", "reg2@test.com", "pass123");

        mockMvc.perform(get("/api/admin/users")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    // ======================== task listing (tests findAllWithFilter SQL) ========================

    @Test
    @DisplayName("管理员查询所有任务（无状态过滤）- 返回全部用户的任务")
    void listTasks_noFilter_returnsAllTasks() throws Exception {
        String adminToken = registerAsAdmin("admin2", "admin2@test.com", "adminpw");
        String userToken = registerAndLogin("taskuser1", "taskuser1@test.com", "pass123");

        createTask(adminToken, "北京", "帝都游");
        createTask(userToken, "上海", "魔都游");

        mockMvc.perform(get("/api/admin/tasks")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(2))
                // checkpoint_json excluded at SQL level
                .andExpect(jsonPath("$.data.list[0].checkpointJson").doesNotExist());
    }

    @Test
    @DisplayName("管理员按状态过滤任务 - 只返回 pending 任务，completed 返回 0")
    void listTasks_withStatusFilter_pendingOnly() throws Exception {
        String adminToken = registerAsAdmin("admin3", "admin3@test.com", "adminpw");
        String userToken = registerAndLogin("taskuser2", "taskuser2@test.com", "pass123");

        createTask(userToken, "广州", "粤菜之旅");

        // pending filter should return the task
        mockMvc.perform(get("/api/admin/tasks")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("status", "pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].status").value("pending"));

        // completed filter should return nothing
        mockMvc.perform(get("/api/admin/tasks")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("status", "completed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    @DisplayName("管理员任务列表分页 - page=1/size=2 返回 2 条，page=2 返回剩余")
    void listTasks_pagination_works() throws Exception {
        String adminToken = registerAsAdmin("admin4", "admin4@test.com", "adminpw");
        String userToken = registerAndLogin("taskuser3", "taskuser3@test.com", "pass123");

        createTask(userToken, "城市1", "游览1");
        createTask(userToken, "城市2", "游览2");
        createTask(userToken, "城市3", "游览3");

        // Page 1
        MvcResult page1 = mockMvc.perform(get("/api/admin/tasks")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.list.length()").value(2))
                .andReturn();

        JsonNode json1 = objectMapper.readTree(page1.getResponse().getContentAsString());
        assertThat(json1.path("data").path("pages").asInt()).isEqualTo(2);

        // Page 2
        mockMvc.perform(get("/api/admin/tasks")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("page", "2").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list.length()").value(1));
    }

    @Test
    @DisplayName("非管理员查询任务列表 - 返回 403")
    void listTasks_asNonAdmin_returns403() throws Exception {
        String token = registerAndLogin("reg3", "reg3@test.com", "pass123");

        mockMvc.perform(get("/api/admin/tasks")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    // ======================== quota config ========================

    @Test
    @DisplayName("管理员查询配额配置 - 返回 REGULAR/VIP/ADMIN 三个等级")
    void listQuotaConfigs_returnsThreeLevels() throws Exception {
        String adminToken = registerAsAdmin("admin5", "admin5@test.com", "adminpw");

        mockMvc.perform(get("/api/admin/quota-configs")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.REGULAR").exists())
                .andExpect(jsonPath("$.data.VIP").exists())
                .andExpect(jsonPath("$.data.ADMIN").exists())
                .andExpect(jsonPath("$.data.REGULAR.dailyTokenLimit").value(10000));
    }

    @Test
    @DisplayName("管理员修改配额配置并读回 - 新值持久化到 H2")
    void updateQuotaConfig_and_read_back() throws Exception {
        String adminToken = registerAsAdmin("admin6", "admin6@test.com", "adminpw");

        // Update VIP daily limit to 99999
        mockMvc.perform(put("/api/admin/quota-configs/2")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dailyTokenLimit\":99999}"))
                .andExpect(status().isOk());

        // Read back and verify
        mockMvc.perform(get("/api/admin/quota-configs")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.VIP.dailyTokenLimit").value(99999));
    }

    // ======================== user level management ========================

    @Test
    @DisplayName("管理员将普通用户升级为 VIP - 用户列表中 level=2")
    void updateUserLevel_toVip() throws Exception {
        String adminToken = registerAsAdmin("admin7", "admin7@test.com", "adminpw");
        registerAndLogin("targetuser", "target@test.com", "pass123");

        // Find target user ID
        User target = userMapper.findByEmail("target@test.com");
        assertThat(target).isNotNull();

        // Promote to VIP
        mockMvc.perform(put("/api/admin/users/{id}/level", target.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userLevel\":2}"))
                .andExpect(status().isOk());

        // Verify level is now 2 in DB
        User updated = userMapper.findById(target.getId());
        assertThat(updated.getUserLevel()).isEqualTo(2);
    }

    @Test
    @DisplayName("管理员禁用用户 - 状态变为 0")
    void updateUserStatus_disable_success() throws Exception {
        String adminToken = registerAsAdmin("admin8", "admin8@test.com", "adminpw");
        registerAndLogin("disableuser", "disable@test.com", "pass123");

        User target = userMapper.findByEmail("disable@test.com");
        assertThat(target).isNotNull();

        mockMvc.perform(put("/api/admin/users/{id}/status", target.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":0}"))
                .andExpect(status().isOk());

        User updated = userMapper.findById(target.getId());
        assertThat(updated.getStatus()).isEqualTo(0);
    }
}
