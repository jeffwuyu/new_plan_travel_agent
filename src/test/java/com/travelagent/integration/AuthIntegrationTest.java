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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Auth Integration Tests")
class AuthIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("注册成功 - 返回 200，响应中不含 passwordHash")
    void register_success() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("alice", "alice@example.com", "password123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.email").value("alice@example.com"))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
    }

    @Test
    @DisplayName("注册重复邮箱 - 第二次返回 400")
    void register_duplicateEmail_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("alice", "dup@example.com", "password123")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("alice2", "dup@example.com", "password123")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("登录成功 - 返回含 token 的响应")
    void login_success() throws Exception {
        registerUser("bob", "bob@example.com", "secret");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("bob", "secret")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.token").isNotEmpty());
    }

    @Test
    @DisplayName("登录错误密码 - 返回 401")
    void login_wrongPassword_returns401() throws Exception {
        registerUser("carol", "carol@example.com", "correct");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("carol", "wrong")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("登出后使用同一 token 访问受保护接口 - 返回 401")
    void logout_then_protectedCall_returns401() throws Exception {
        registerUser("dave", "dave@example.com", "pass123");
        String token = loginAndGetToken("dave", "pass123");

        mockMvc.perform(get("/api/tasks")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        when(redisTemplate.hasKey(anyString())).thenReturn(true);

        mockMvc.perform(get("/api/tasks")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("注销账号（软删除）后登录返回 401")
    void cancelAccount_softDeletes_user() throws Exception {
        registerUser("eve", "eve@example.com", "evepw1");
        String token = loginAndGetToken("eve", "evepw1");

        mockMvc.perform(delete("/api/auth/account")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("eve", "evepw1")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("未携带 Authorization 头访问受保护接口 - 返回 401")
    void protectedEndpoint_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/tasks"))
                .andExpect(status().isUnauthorized());
    }

    private void registerUser(String username, String email, String password) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(username, email, password)))
                .andExpect(status().isOk());
    }

    String loginAndGetToken(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(username, password)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        String token = json.path("data").path("token").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    private String registerBody(String username, String email, String password) {
        return String.format("{\"username\":\"%s\",\"email\":\"%s\",\"password\":\"%s\"}",
            username, email, password);
    }

    private String loginBody(String username, String password) {
        return String.format("{\"username\":\"%s\",\"password\":\"%s\"}", username, password);
    }
}
