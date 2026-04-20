package com.travelagent.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Integration tests for the task CRUD lifecycle.
 *
 * TaskDispatcher is disabled via application-test.yml (scheduling pool size=0),
 * so tasks remain in PENDING state throughout all tests.
 * QuotaService is mocked in BaseIntegrationTest to avoid Redis dependency.
 */
@DisplayName("Task Integration Tests")
class TaskIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ======================== createTask ========================

    @Test
    @DisplayName("创建任务成功 - 返回 200，status=pending，taskUuid 非空")
    void createTask_success() throws Exception {
        String token = registerAndLogin("user1", "user1@test.com", "pass123");

        mockMvc.perform(post("/api/tasks")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(taskBody("北京", "3天北京文化游")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.taskUuid").isNotEmpty())
                .andExpect(jsonPath("$.data.status").value("pending"))
                .andExpect(jsonPath("$.data.region").value("北京"));
    }

    @Test
    @DisplayName("创建任务 - region 为空 - 返回 400（Bean Validation）")
    void createTask_missingRegion_returns400() throws Exception {
        String token = registerAndLogin("user2", "user2@test.com", "pass123");

        mockMvc.perform(post("/api/tasks")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"region\":\"\",\"userIntent\":\"游览\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("创建任务 - userIntent 为空 - 返回 400（Bean Validation）")
    void createTask_missingUserIntent_returns400() throws Exception {
        String token = registerAndLogin("user3", "user3@test.com", "pass123");

        mockMvc.perform(post("/api/tasks")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"region\":\"上海\",\"userIntent\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    // ======================== getTask ========================

    @Test
    @DisplayName("查询任务详情成功 - 返回 200，status=pending")
    void getTask_success() throws Exception {
        String token = registerAndLogin("user4", "user4@test.com", "pass123");
        String taskUuid = createTaskAndGetUuid(token, "西安", "历史文化游");

        mockMvc.perform(get("/api/tasks/{uuid}", taskUuid)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskUuid").value(taskUuid))
                .andExpect(jsonPath("$.data.status").value("pending"));
    }

    @Test
    @DisplayName("查询其他用户的任务 - 返回 403")
    void getTask_otherUser_returns403() throws Exception {
        String tokenA = registerAndLogin("userA", "userA@test.com", "pass123");
        String tokenB = registerAndLogin("userB", "userB@test.com", "pass123");

        String uuidA = createTaskAndGetUuid(tokenA, "成都", "美食之旅");

        // User B tries to access User A's task
        mockMvc.perform(get("/api/tasks/{uuid}", uuidA)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isForbidden());
    }

    // ======================== listTasks ========================

    @Test
    @DisplayName("查询任务列表 - 只返回当前用户的任务")
    void listTasks_returnsOnlyOwnTasks() throws Exception {
        String tokenA = registerAndLogin("listA", "listA@test.com", "pass123");
        String tokenB = registerAndLogin("listB", "listB@test.com", "pass123");

        createTaskAndGetUuid(tokenA, "杭州", "西湖游览");
        createTaskAndGetUuid(tokenB, "苏州", "园林之旅");

        // A's list should have exactly 1 task
        mockMvc.perform(get("/api/tasks")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].region").value("杭州"));

        // B's list should have exactly 1 task
        mockMvc.perform(get("/api/tasks")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].region").value("苏州"));
    }

    // ======================== cancelTask ========================

    @Test
    @DisplayName("取消 PENDING 任务 - 返回 200，状态变为 cancelled")
    void cancelTask_pendingTask_success() throws Exception {
        String token = registerAndLogin("cancel1", "cancel1@test.com", "pass123");
        String taskUuid = createTaskAndGetUuid(token, "重庆", "山城夜景游");

        mockMvc.perform(delete("/api/tasks/{uuid}", taskUuid)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // Verify task is now cancelled
        mockMvc.perform(get("/api/tasks/{uuid}", taskUuid)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.status").value("cancelled"));
    }

    @Test
    @DisplayName("未认证用户取消任务 - 返回 401")
    void cancelTask_withoutAuth_returns401() throws Exception {
        mockMvc.perform(delete("/api/tasks/any-uuid"))
                .andExpect(status().isUnauthorized());
    }

    // ======================== helpers ========================

    private String registerAndLogin(String username, String email, String password) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"username\":\"%s\",\"email\":\"%s\",\"password\":\"%s\"}",
                                username, email, password)))
                .andExpect(status().isOk());

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"email\":\"%s\",\"password\":\"%s\"}", email, password)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        String token = json.path("data").path("token").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    private String createTaskAndGetUuid(String token, String region, String intent) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/tasks")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(taskBody(region, intent)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        String uuid = json.path("data").path("taskUuid").asText();
        assertThat(uuid).isNotBlank();
        return uuid;
    }

    private String taskBody(String region, String intent) {
        return String.format("{\"region\":\"%s\",\"userIntent\":\"%s\"}", region, intent);
    }
}
