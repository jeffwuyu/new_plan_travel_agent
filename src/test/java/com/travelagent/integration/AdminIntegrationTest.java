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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Admin Integration Tests")
class AdminIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserMapper userMapper;

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

    private String registerAsAdmin(String username, String email, String password) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"username\":\"%s\",\"email\":\"%s\",\"password\":\"%s\"}",
                                username, email, password)))
                .andExpect(status().isOk());

        User adminUser = userMapper.findByEmail(email);
        assertThat(adminUser).isNotNull();
        adminUser.setUserLevel(3);
        userMapper.update(adminUser);

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
                        .content(String.format("""
                                {
                                  "region":"%s",
                                  "userIntent":"%s",
                                  "startLocationQuery":"钟楼",
                                  "endLocationQuery":"西安北站",
                                  "startTime":"2026-04-22T09:00:00",
                                  "endTime":"2026-04-22T21:00:00",
                                  "travelMode":"driving"
                                }
                                """, region, intent)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.path("data").path("taskUuid").asText();
    }

    @Test
    @DisplayName("admin can list users without password hash")
    void listUsers_asAdmin_returnsPageInfo() throws Exception {
        String adminToken = registerAsAdmin("admin1", "admin1@test.com", "adminpw");
        registerAndLogin("regular1", "regular1@test.com", "pass123");

        mockMvc.perform(get("/api/admin/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.list[0].passwordHash").doesNotExist());
    }

    @Test
    @DisplayName("regular user cannot list users")
    void listUsers_asRegularUser_returns403() throws Exception {
        String token = registerAndLogin("reg2", "reg2@test.com", "pass123");

        mockMvc.perform(get("/api/admin/users")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("admin can list tasks across users")
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
                .andExpect(jsonPath("$.data.list[0].checkpointJson").doesNotExist());
    }

    @Test
    @DisplayName("admin task status filter works")
    void listTasks_withStatusFilter_pendingOnly() throws Exception {
        String adminToken = registerAsAdmin("admin3", "admin3@test.com", "adminpw");
        String userToken = registerAndLogin("taskuser2", "taskuser2@test.com", "pass123");

        createTask(userToken, "广州", "粤菜之旅");

        mockMvc.perform(get("/api/admin/tasks")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("status", "pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].status").value("pending"));

        mockMvc.perform(get("/api/admin/tasks")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("status", "completed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    @DisplayName("admin task pagination works")
    void listTasks_pagination_works() throws Exception {
        String adminToken = registerAsAdmin("admin4", "admin4@test.com", "adminpw");
        String userToken = registerAndLogin("taskuser3", "taskuser3@test.com", "pass123");

        createTask(userToken, "城市1", "游览1");
        createTask(userToken, "城市2", "游览2");
        createTask(userToken, "城市3", "游览3");

        MvcResult page1 = mockMvc.perform(get("/api/admin/tasks")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("page", "1")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.list.length()").value(2))
                .andReturn();

        JsonNode json1 = objectMapper.readTree(page1.getResponse().getContentAsString());
        assertThat(json1.path("data").path("pages").asInt()).isEqualTo(2);

        mockMvc.perform(get("/api/admin/tasks")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("page", "2")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list.length()").value(1));
    }

    @Test
    @DisplayName("regular user cannot list tasks")
    void listTasks_asNonAdmin_returns403() throws Exception {
        String token = registerAndLogin("reg3", "reg3@test.com", "pass123");

        mockMvc.perform(get("/api/admin/tasks")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("admin can read quota configs")
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
    @DisplayName("admin can update quota config")
    void updateQuotaConfig_and_read_back() throws Exception {
        String adminToken = registerAsAdmin("admin6", "admin6@test.com", "adminpw");

        mockMvc.perform(put("/api/admin/quota-configs/2")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dailyTokenLimit\":99999}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/quota-configs")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.VIP.dailyTokenLimit").value(99999));
    }

    @Test
    @DisplayName("admin can promote user to vip")
    void updateUserLevel_toVip() throws Exception {
        String adminToken = registerAsAdmin("admin7", "admin7@test.com", "adminpw");
        registerAndLogin("targetuser", "target@test.com", "pass123");

        User target = userMapper.findByEmail("target@test.com");
        assertThat(target).isNotNull();

        mockMvc.perform(put("/api/admin/users/{id}/level", target.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userLevel\":2}"))
                .andExpect(status().isOk());

        User updated = userMapper.findById(target.getId());
        assertThat(updated.getUserLevel()).isEqualTo(2);
    }

    @Test
    @DisplayName("promoted user can access admin endpoints with old token")
    void promotedUser_oldToken_canAccessAdminEndpointsAfterRefreshFlow() throws Exception {
        String bootstrapAdminToken = registerAsAdmin("admin9", "admin9@test.com", "adminpw");
        String promotedUserToken = registerAndLogin("promoted", "promoted@test.com", "pass123");

        User promoted = userMapper.findByEmail("promoted@test.com");
        assertThat(promoted).isNotNull();

        mockMvc.perform(put("/api/admin/users/{id}/level", promoted.getId())
                        .header("Authorization", "Bearer " + bootstrapAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userLevel\":3}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/users")
                        .header("Authorization", "Bearer " + promotedUserToken)
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/api/user/quota")
                        .header("Authorization", "Bearer " + promotedUserToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userLevel").value(3));
    }

    @Test
    @DisplayName("admin can disable user")
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
