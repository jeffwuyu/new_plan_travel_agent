package com.travelagent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelagent.exception.BusinessException;
import com.travelagent.exception.GlobalExceptionHandler;
import com.travelagent.exception.QuotaExhaustedException;
import com.travelagent.exception.TaskNotFoundException;
import com.travelagent.filter.JwtAuthInterceptor;
import com.travelagent.model.dto.CreateTaskRequest;
import com.travelagent.model.dto.TaskResponse;
import com.travelagent.model.enums.TaskStatus;
import com.travelagent.service.task.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for TaskController using MockMvc standalone setup.
 * No Spring context — userId is injected via requestAttr to bypass JWT interceptor.
 */

/**
 * 中文注释：测试类，用于验证 Task Controller Test 相关行为是否符合预期。
 */

@ExtendWith(MockitoExtension.class)
@DisplayName("TaskController Tests")
class TaskControllerTest {

    @Mock
    private TaskService taskService;

    @InjectMocks
    private TaskController taskController;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    private static final Long   USER_ID   = 1L;
    private static final int    USER_LEVEL = 1;
    private static final String TASK_UUID = "test-uuid-0001";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(taskController)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
        objectMapper = new ObjectMapper();
    }

    // -----------------------------------------------------------------------
    // POST /api/tasks — create
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("createTask: 有效请求 → 200 + TaskResponse")
    void createTask_validRequest_returns200() throws Exception {
        TaskResponse response = buildTaskResponse(TaskStatus.PENDING.getCode());
        when(taskService.createTask(eq(USER_ID), eq(USER_LEVEL), any(CreateTaskRequest.class)))
            .thenReturn(response);

        mockMvc.perform(post("/api/tasks")
                .requestAttr(JwtAuthInterceptor.ATTR_USER_ID, USER_ID)
                .requestAttr(JwtAuthInterceptor.ATTR_USER_LEVEL, USER_LEVEL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(buildRequest())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.status").value("pending"))
            .andExpect(jsonPath("$.data.taskUuid").value(TASK_UUID));
    }

    @Test
    @DisplayName("createTask: region 为空 → 400 校验失败")
    void createTask_missingRegion_returns400() throws Exception {
        CreateTaskRequest req = buildRequest();
        req.setRegion(""); // blank — violates @NotBlank

        mockMvc.perform(post("/api/tasks")
                .requestAttr(JwtAuthInterceptor.ATTR_USER_ID, USER_ID)
                .requestAttr(JwtAuthInterceptor.ATTR_USER_LEVEL, USER_LEVEL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("createTask: totalDays=0 → 400 (低于最小值)")
    void createTask_totalDaysBelowMin_returns400() throws Exception {
        CreateTaskRequest req = buildRequest();
        req.setTotalDays(0);

        mockMvc.perform(post("/api/tasks")
                .requestAttr(JwtAuthInterceptor.ATTR_USER_ID, USER_ID)
                .requestAttr(JwtAuthInterceptor.ATTR_USER_LEVEL, USER_LEVEL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("createTask: totalDays=15 → 400 (超过最大值14)")
    void createTask_totalDaysAboveMax_returns400() throws Exception {
        CreateTaskRequest req = buildRequest();
        req.setTotalDays(15);

        mockMvc.perform(post("/api/tasks")
                .requestAttr(JwtAuthInterceptor.ATTR_USER_ID, USER_ID)
                .requestAttr(JwtAuthInterceptor.ATTR_USER_LEVEL, USER_LEVEL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("createTask: 配额耗尽 → 429")
    void createTask_quotaExhausted_returns429() throws Exception {
        when(taskService.createTask(anyLong(), anyInt(), any()))
            .thenThrow(new QuotaExhaustedException("daily"));

        mockMvc.perform(post("/api/tasks")
                .requestAttr(JwtAuthInterceptor.ATTR_USER_ID, USER_ID)
                .requestAttr(JwtAuthInterceptor.ATTR_USER_LEVEL, USER_LEVEL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(buildRequest())))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value(429));
    }

    @Test
    @DisplayName("createTask: 并发限制 → 429")
    void createTask_concurrentLimit_returns429() throws Exception {
        when(taskService.createTask(anyLong(), anyInt(), any()))
            .thenThrow(new BusinessException(429, "已达到最大并发任务数"));

        mockMvc.perform(post("/api/tasks")
                .requestAttr(JwtAuthInterceptor.ATTR_USER_ID, USER_ID)
                .requestAttr(JwtAuthInterceptor.ATTR_USER_LEVEL, USER_LEVEL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(buildRequest())))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value(429));
    }

    // -----------------------------------------------------------------------
    // GET /api/tasks/{taskUuid}
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("getTask: 任务存在 → 200 + TaskResponse")
    void getTask_exists_returns200() throws Exception {
        TaskResponse response = buildTaskResponse(TaskStatus.PLANNING.getCode());
        when(taskService.getTask(TASK_UUID, USER_ID)).thenReturn(response);

        mockMvc.perform(get("/api/tasks/{uuid}", TASK_UUID)
                .requestAttr(JwtAuthInterceptor.ATTR_USER_ID, USER_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.taskUuid").value(TASK_UUID))
            .andExpect(jsonPath("$.data.status").value("planning"));
    }

    @Test
    @DisplayName("getTask: 任务不存在 → 404")
    void getTask_notFound_returns404() throws Exception {
        when(taskService.getTask(TASK_UUID, USER_ID))
            .thenThrow(new TaskNotFoundException(TASK_UUID));

        mockMvc.perform(get("/api/tasks/{uuid}", TASK_UUID)
                .requestAttr(JwtAuthInterceptor.ATTR_USER_ID, USER_ID))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("getTask: 非所有者 → 403")
    void getTask_wrongOwner_returns403() throws Exception {
        when(taskService.getTask(TASK_UUID, USER_ID))
            .thenThrow(new BusinessException(403, "无权操作该任务"));

        mockMvc.perform(get("/api/tasks/{uuid}", TASK_UUID)
                .requestAttr(JwtAuthInterceptor.ATTR_USER_ID, USER_ID))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(403));
    }

    // -----------------------------------------------------------------------
    // GET /api/tasks
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("listTasks: 返回任务列表 → 200 + 数组")
    void listTasks_returns200WithList() throws Exception {
        List<TaskResponse> list = List.of(
            buildTaskResponse(TaskStatus.PENDING.getCode()),
            buildTaskResponse(TaskStatus.COMPLETED.getCode())
        );
        when(taskService.listTasks(USER_ID)).thenReturn(list);

        mockMvc.perform(get("/api/tasks")
                .requestAttr(JwtAuthInterceptor.ATTR_USER_ID, USER_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2));
    }

    // -----------------------------------------------------------------------
    // DELETE /api/tasks/{taskUuid}
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("cancelTask: PENDING 任务 → 200")
    void cancelTask_pending_returns200() throws Exception {
        doNothing().when(taskService).cancelTask(TASK_UUID, USER_ID);

        mockMvc.perform(delete("/api/tasks/{uuid}", TASK_UUID)
                .requestAttr(JwtAuthInterceptor.ATTR_USER_ID, USER_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("cancelTask: 已为终态 → 400")
    void cancelTask_alreadyTerminal_returns400() throws Exception {
        doThrow(new BusinessException(400, "任务已处于终态"))
            .when(taskService).cancelTask(TASK_UUID, USER_ID);

        mockMvc.perform(delete("/api/tasks/{uuid}", TASK_UUID)
                .requestAttr(JwtAuthInterceptor.ATTR_USER_ID, USER_ID))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400));
    }

    // -----------------------------------------------------------------------
    // POST /api/tasks/{taskUuid}/resume
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("resumeTask: PAUSED 任务 → 200 + RESUMING 状态")
    void resumeTask_paused_returns200() throws Exception {
        TaskResponse response = buildTaskResponse(TaskStatus.RESUMING.getCode());
        when(taskService.resumeTask(TASK_UUID, USER_ID)).thenReturn(response);

        mockMvc.perform(post("/api/tasks/{uuid}/resume", TASK_UUID)
                .requestAttr(JwtAuthInterceptor.ATTR_USER_ID, USER_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("resuming"));
    }

    @Test
    @DisplayName("resumeTask: 非 PAUSED 状态 → 400")
    void resumeTask_notPaused_returns400() throws Exception {
        when(taskService.resumeTask(TASK_UUID, USER_ID))
            .thenThrow(new BusinessException(400, "任务当前状态不可恢复"));

        mockMvc.perform(post("/api/tasks/{uuid}/resume", TASK_UUID)
                .requestAttr(JwtAuthInterceptor.ATTR_USER_ID, USER_ID))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private TaskResponse buildTaskResponse(String status) {
        TaskResponse r = new TaskResponse();
        r.setTaskUuid(TASK_UUID);
        r.setStatus(status);
        r.setRegion("北京市");
        r.setTotalTokensUsed(0);
        r.setCreatedAt(LocalDateTime.now());
        return r;
    }

    private CreateTaskRequest buildRequest() {
        CreateTaskRequest req = new CreateTaskRequest();
        req.setRegion("北京市");
        req.setUserIntent("3天北京游");
        req.setTotalDays(3);
        req.setAttractionsPerDay(3);
        req.setTravelMode("driving");
        return req;
    }
}
