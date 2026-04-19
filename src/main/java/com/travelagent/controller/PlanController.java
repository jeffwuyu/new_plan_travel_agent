package com.travelagent.controller;

import com.travelagent.exception.BusinessException;
import com.travelagent.filter.JwtAuthInterceptor;
import com.travelagent.mapper.PlanMapper;
import com.travelagent.mapper.TaskMapper;
import com.travelagent.model.dto.Result;
import com.travelagent.model.entity.Plan;
import com.travelagent.model.entity.PlanStep;
import com.travelagent.model.entity.Task;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 用途：旅行规划结果查询控制器。
 *
 * 提供以下端点（均需要有效 JWT）：
 *   GET /api/plans                    — 查询当前用户的所有规划列表（最新在前）
 *   GET /api/plans/{planId}           — 按规划 ID 查询规划详情（含元信息）
 *   GET /api/plans/{planId}/steps     — 查询规划内所有有序景点步骤
 *   GET /api/plans/by-task/{taskUuid} — 按任务 UUID 查询对应规划（任务完成后可用）
 *
 * 所有查询均校验 userId 归属，防止越权访问。
 * 规划数据由 AgentServiceImpl 在任务 COMPLETED 时写入 plans + plan_steps 表。
 */
@Tag(name = "规划结果", description = "旅行规划结果查询：规划详情与景点步骤")
@RestController
@RequestMapping("/api/plans")
public class PlanController {

    @Autowired
    private PlanMapper planMapper;

    @Autowired
    private TaskMapper taskMapper;

    // -----------------------------------------------------------------------
    // 当前用户规划列表
    // -----------------------------------------------------------------------

    /**
     * 查询当前登录用户的所有规划（不含 steps，轻量列表）。
     * 按创建时间倒序排列，最新规划在前。
     */
    @Operation(summary = "获取当前用户的所有旅行规划列表")
    @GetMapping
    public Result<List<Plan>> listPlans(HttpServletRequest request) {
        Long userId = JwtAuthInterceptor.getUserId(request);
        List<Plan> plans = planMapper.findByUserId(userId);
        return Result.success(plans);
    }

    // -----------------------------------------------------------------------
    // 按 planId 查询
    // -----------------------------------------------------------------------

    /**
     * 按规划 ID 查询规划元信息（不含步骤）。
     * 仅允许规划归属用户访问，否则返回 403。
     *
     * @param planId 规划主键 ID（从 COMPLETED SSE 事件的 planId 字段获取）
     */
    @Operation(summary = "按规划 ID 获取规划详情",
               description = "返回规划的元信息（标题、地区、总天数、摘要）。步骤列表请使用 /steps 端点。")
    @GetMapping("/{planId}")
    public Result<Plan> getPlan(@PathVariable Long planId, HttpServletRequest request) {
        Long userId = JwtAuthInterceptor.getUserId(request);
        Plan plan = planMapper.findById(planId);
        if (plan == null) {
            throw new BusinessException(404, "规划不存在");
        }
        // 校验归属
        if (!plan.getUserId().equals(userId)) {
            throw new BusinessException(403, "无权访问该规划");
        }
        return Result.success(plan);
    }

    /**
     * 查询规划内全部有序景点步骤。
     * 步骤按 step_order 升序排列，包含：景点名称、坐标、估计游览时长、
     * 交通时长、天气备注、LLM 叙述。
     *
     * @param planId 规划主键 ID
     */
    @Operation(summary = "获取规划的所有景点步骤",
               description = "返回按 step_order 升序排列的景点列表，包含坐标、天气备注和交通时长。")
    @GetMapping("/{planId}/steps")
    public Result<List<PlanStep>> getSteps(@PathVariable Long planId, HttpServletRequest request) {
        Long userId = JwtAuthInterceptor.getUserId(request);
        Plan plan = planMapper.findById(planId);
        if (plan == null) {
            throw new BusinessException(404, "规划不存在");
        }
        if (!plan.getUserId().equals(userId)) {
            throw new BusinessException(403, "无权访问该规划");
        }
        List<PlanStep> steps = planMapper.findStepsByPlanId(planId);
        return Result.success(steps);
    }

    // -----------------------------------------------------------------------
    // 按 taskUuid 查询
    // -----------------------------------------------------------------------

    /**
     * 按任务 UUID 查询对应规划（含步骤数量摘要）。
     * 任务进入 COMPLETED 状态后，本接口即可返回规划详情。
     *
     * <p>返回格式：{@code {"plan": Plan, "stepCount": int}} 的 Map，
     * 避免在详情接口再单独请求 /steps。
     *
     * @param taskUuid 任务唯一标识（从 TaskController 的 createTask 响应中获取）
     */
    @Operation(summary = "按任务 UUID 获取规划（含步骤数量）",
               description = "任务完成后调用，返回规划基本信息和步骤数量。完整步骤请调用 /{planId}/steps。")
    @GetMapping("/by-task/{taskUuid}")
    public Result<Map<String, Object>> getPlanByTask(@PathVariable String taskUuid,
                                                      HttpServletRequest request) {
        Long userId = JwtAuthInterceptor.getUserId(request);

        // 校验任务归属
        Task task = taskMapper.findByUuid(taskUuid);
        if (task == null) {
            throw new BusinessException(404, "任务不存在");
        }
        if (!task.getUserId().equals(userId)) {
            throw new BusinessException(403, "无权访问该任务");
        }

        Plan plan = planMapper.findByTaskId(task.getId());
        if (plan == null) {
            throw new BusinessException(404, "规划尚未生成，请等待任务完成");
        }

        List<PlanStep> steps = planMapper.findStepsByPlanId(plan.getId());
        return Result.success(Map.of(
                "plan", plan,
                "stepCount", steps.size()
        ));
    }
}
