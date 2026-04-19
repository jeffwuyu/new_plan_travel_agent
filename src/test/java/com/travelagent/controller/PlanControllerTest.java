package com.travelagent.controller;

import com.travelagent.exception.BusinessException;
import com.travelagent.exception.GlobalExceptionHandler;
import com.travelagent.filter.JwtAuthInterceptor;
import com.travelagent.mapper.PlanMapper;
import com.travelagent.mapper.TaskMapper;
import com.travelagent.model.entity.Plan;
import com.travelagent.model.entity.PlanStep;
import com.travelagent.model.entity.Task;
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

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 用途：PlanController 单元测试。
 *
 * 测试范围：
 *   1. GET /api/plans              — 当前用户规划列表（正常 + 空列表）
 *   2. GET /api/plans/{planId}     — 按 ID 查询（存在/不存在/越权）
 *   3. GET /api/plans/{planId}/steps — 步骤列表（正常/越权）
 *   4. GET /api/plans/by-task/{uuid} — 按任务 UUID 查询（正常/任务不存在/规划未生成）
 *
 * 测试策略：MockMvc standalone，所有依赖 Mock，不启动 Spring 上下文。
 * JwtAuthInterceptor 的静态方法通过 MockedStatic 注入测试用 userId/userLevel。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PlanController Tests")
class PlanControllerTest {

    @Mock
    private PlanMapper planMapper;

    @Mock
    private TaskMapper taskMapper;

    @InjectMocks
    private PlanController planController;

    private MockMvc mockMvc;

    // 测试用数据
    private static final Long USER_ID = 1L;
    private static final Long PLAN_ID = 100L;
    private static final Long TASK_ID = 10L;
    private static final String TASK_UUID = "test-task-uuid-001";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(planController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ===================== GET /api/plans =====================

    @Test
    @DisplayName("获取当前用户规划列表 - 返回 200 和规划数组")
    void listPlans_success() throws Exception {
        Plan plan = buildPlan(PLAN_ID, USER_ID);

        try (MockedStatic<JwtAuthInterceptor> mocked = mockStatic(JwtAuthInterceptor.class)) {
            mocked.when(() -> JwtAuthInterceptor.getUserId(any())).thenReturn(USER_ID);
            when(planMapper.findByUserId(USER_ID)).thenReturn(List.of(plan));

            mockMvc.perform(get("/api/plans").accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data[0].id").value(PLAN_ID));
        }
    }

    @Test
    @DisplayName("获取规划列表 - 无规划时返回空数组")
    void listPlans_empty_returnsEmptyArray() throws Exception {
        try (MockedStatic<JwtAuthInterceptor> mocked = mockStatic(JwtAuthInterceptor.class)) {
            mocked.when(() -> JwtAuthInterceptor.getUserId(any())).thenReturn(USER_ID);
            when(planMapper.findByUserId(USER_ID)).thenReturn(List.of());

            mockMvc.perform(get("/api/plans"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data").isEmpty());
        }
    }

    // ===================== GET /api/plans/{planId} =====================

    @Test
    @DisplayName("按 ID 查询规划 - 存在且归属正确 - 返回 200")
    void getPlan_found_success() throws Exception {
        Plan plan = buildPlan(PLAN_ID, USER_ID);

        try (MockedStatic<JwtAuthInterceptor> mocked = mockStatic(JwtAuthInterceptor.class)) {
            mocked.when(() -> JwtAuthInterceptor.getUserId(any())).thenReturn(USER_ID);
            when(planMapper.findById(PLAN_ID)).thenReturn(plan);

            mockMvc.perform(get("/api/plans/{planId}", PLAN_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(PLAN_ID))
                    .andExpect(jsonPath("$.data.region").value("西安市"));
        }
    }

    @Test
    @DisplayName("按 ID 查询规划 - 不存在 - 返回 404")
    void getPlan_notFound_returns404() throws Exception {
        try (MockedStatic<JwtAuthInterceptor> mocked = mockStatic(JwtAuthInterceptor.class)) {
            mocked.when(() -> JwtAuthInterceptor.getUserId(any())).thenReturn(USER_ID);
            when(planMapper.findById(PLAN_ID)).thenReturn(null);

            mockMvc.perform(get("/api/plans/{planId}", PLAN_ID))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    @DisplayName("按 ID 查询规划 - 归属不符 - 返回 403")
    void getPlan_wrongOwner_returns403() throws Exception {
        // 规划属于 userId=2，但当前登录用户是 1
        Plan plan = buildPlan(PLAN_ID, 2L);

        try (MockedStatic<JwtAuthInterceptor> mocked = mockStatic(JwtAuthInterceptor.class)) {
            mocked.when(() -> JwtAuthInterceptor.getUserId(any())).thenReturn(USER_ID);
            when(planMapper.findById(PLAN_ID)).thenReturn(plan);

            mockMvc.perform(get("/api/plans/{planId}", PLAN_ID))
                    .andExpect(status().isForbidden());
        }
    }

    // ===================== GET /api/plans/{planId}/steps =====================

    @Test
    @DisplayName("查询步骤列表 - 有步骤时返回有序步骤")
    void getSteps_success() throws Exception {
        Plan plan = buildPlan(PLAN_ID, USER_ID);
        PlanStep step1 = buildStep(1L, PLAN_ID, 0, 1, "兵马俑");
        PlanStep step2 = buildStep(2L, PLAN_ID, 1, 1, "华清宫");

        try (MockedStatic<JwtAuthInterceptor> mocked = mockStatic(JwtAuthInterceptor.class)) {
            mocked.when(() -> JwtAuthInterceptor.getUserId(any())).thenReturn(USER_ID);
            when(planMapper.findById(PLAN_ID)).thenReturn(plan);
            when(planMapper.findStepsByPlanId(PLAN_ID)).thenReturn(List.of(step1, step2));

            mockMvc.perform(get("/api/plans/{planId}/steps", PLAN_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data.length()").value(2))
                    .andExpect(jsonPath("$.data[0].attractionName").value("兵马俑"))
                    .andExpect(jsonPath("$.data[1].attractionName").value("华清宫"));
        }
    }

    @Test
    @DisplayName("查询步骤 - 规划归属不符 - 返回 403")
    void getSteps_wrongOwner_returns403() throws Exception {
        Plan plan = buildPlan(PLAN_ID, 99L); // 属于另一用户

        try (MockedStatic<JwtAuthInterceptor> mocked = mockStatic(JwtAuthInterceptor.class)) {
            mocked.when(() -> JwtAuthInterceptor.getUserId(any())).thenReturn(USER_ID);
            when(planMapper.findById(PLAN_ID)).thenReturn(plan);

            mockMvc.perform(get("/api/plans/{planId}/steps", PLAN_ID))
                    .andExpect(status().isForbidden());
        }
    }

    // ===================== GET /api/plans/by-task/{taskUuid} =====================

    @Test
    @DisplayName("按任务 UUID 查询规划 - 任务完成且规划已生成 - 返回规划和步骤数量")
    void getPlanByTask_success() throws Exception {
        Task task = buildTask(TASK_ID, USER_ID, TASK_UUID);
        Plan plan = buildPlan(PLAN_ID, USER_ID);
        plan.setTaskId(TASK_ID);
        PlanStep step = buildStep(1L, PLAN_ID, 0, 1, "兵马俑");

        try (MockedStatic<JwtAuthInterceptor> mocked = mockStatic(JwtAuthInterceptor.class)) {
            mocked.when(() -> JwtAuthInterceptor.getUserId(any())).thenReturn(USER_ID);
            when(taskMapper.findByUuid(TASK_UUID)).thenReturn(task);
            when(planMapper.findByTaskId(TASK_ID)).thenReturn(plan);
            when(planMapper.findStepsByPlanId(PLAN_ID)).thenReturn(List.of(step));

            mockMvc.perform(get("/api/plans/by-task/{uuid}", TASK_UUID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.plan.id").value(PLAN_ID))
                    .andExpect(jsonPath("$.data.stepCount").value(1));
        }
    }

    @Test
    @DisplayName("按任务 UUID 查询规划 - 任务不存在 - 返回 404")
    void getPlanByTask_taskNotFound_returns404() throws Exception {
        try (MockedStatic<JwtAuthInterceptor> mocked = mockStatic(JwtAuthInterceptor.class)) {
            mocked.when(() -> JwtAuthInterceptor.getUserId(any())).thenReturn(USER_ID);
            when(taskMapper.findByUuid(TASK_UUID)).thenReturn(null);

            mockMvc.perform(get("/api/plans/by-task/{uuid}", TASK_UUID))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    @DisplayName("按任务 UUID 查询规划 - 任务归属不符 - 返回 403")
    void getPlanByTask_taskWrongOwner_returns403() throws Exception {
        Task task = buildTask(TASK_ID, 99L, TASK_UUID); // 属于另一用户

        try (MockedStatic<JwtAuthInterceptor> mocked = mockStatic(JwtAuthInterceptor.class)) {
            mocked.when(() -> JwtAuthInterceptor.getUserId(any())).thenReturn(USER_ID);
            when(taskMapper.findByUuid(TASK_UUID)).thenReturn(task);

            mockMvc.perform(get("/api/plans/by-task/{uuid}", TASK_UUID))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("按任务 UUID 查询规划 - 任务存在但规划未生成（任务未完成）- 返回 404")
    void getPlanByTask_planNotYetGenerated_returns404() throws Exception {
        Task task = buildTask(TASK_ID, USER_ID, TASK_UUID);

        try (MockedStatic<JwtAuthInterceptor> mocked = mockStatic(JwtAuthInterceptor.class)) {
            mocked.when(() -> JwtAuthInterceptor.getUserId(any())).thenReturn(USER_ID);
            when(taskMapper.findByUuid(TASK_UUID)).thenReturn(task);
            when(planMapper.findByTaskId(TASK_ID)).thenReturn(null); // 规划未写入

            mockMvc.perform(get("/api/plans/by-task/{uuid}", TASK_UUID))
                    .andExpect(status().isNotFound());
        }
    }

    // -----------------------------------------------------------------------
    // 测试数据构造辅助方法
    // -----------------------------------------------------------------------

    private Plan buildPlan(Long id, Long userId) {
        Plan plan = new Plan();
        plan.setId(id);
        plan.setUserId(userId);
        plan.setTaskId(TASK_ID);
        plan.setRegion("西安市");
        plan.setTitle("西安市 3-Day Trip");
        plan.setTotalDays(3);
        plan.setSummary("3天西安历史文化游");
        plan.setCreatedAt(LocalDateTime.now());
        return plan;
    }

    private PlanStep buildStep(Long id, Long planId, int stepOrder, int dayNumber, String name) {
        PlanStep step = new PlanStep();
        step.setId(id);
        step.setPlanId(planId);
        step.setStepOrder(stepOrder);
        step.setDayNumber(dayNumber);
        step.setAttractionName(name);
        step.setCreatedAt(LocalDateTime.now());
        return step;
    }

    private Task buildTask(Long id, Long userId, String uuid) {
        Task task = new Task();
        task.setId(id);
        task.setUserId(userId);
        task.setTaskUuid(uuid);
        task.setStatus("completed");
        task.setRegion("西安市");
        return task;
    }
}
