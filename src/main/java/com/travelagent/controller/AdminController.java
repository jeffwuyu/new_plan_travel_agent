package com.travelagent.controller;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.travelagent.exception.BusinessException;
import com.travelagent.filter.JwtAuthInterceptor;
import com.travelagent.mapper.TaskMapper;
import com.travelagent.mapper.UserMapper;
import com.travelagent.mapper.UserQuotaConfigMapper;
import com.travelagent.model.dto.Result;
import com.travelagent.model.entity.Task;
import com.travelagent.model.entity.User;
import com.travelagent.model.entity.UserQuotaConfig;
import com.travelagent.service.user.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 用途：管理员专用控制器。
 *
 * 提供以下管理端点，所有端点均要求 userLevel == 3（ADMIN），
 * 非管理员请求将返回 HTTP 403：
 *
 *   GET  /api/admin/users                    — 分页查询所有用户
 *   PUT  /api/admin/users/{userId}/level     — 修改指定用户等级（1/2/3）
 *   PUT  /api/admin/users/{userId}/status    — 启用/禁用指定用户
 *   GET  /api/admin/quota-configs            — 查询所有等级的配额配置
 *   PUT  /api/admin/quota-configs/{level}    — 修改指定等级的配额限额
 *   GET  /api/admin/tasks                    — 分页查询任务（可按状态过滤）
 *
 * 安全注意：本期 VIP 升级由管理员手动通过 PUT /level 端点操作，
 * 支付系统不在本期范围内（见计划说明）。
 */
@Tag(name = "管理员", description = "管理员专用：用户管理、配额配置、任务监控（需 ADMIN 权限）")
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private UserService userService;

    @Autowired
    private UserQuotaConfigMapper quotaConfigMapper;

    @Autowired
    private TaskMapper taskMapper;

    // -----------------------------------------------------------------------
    // 工具方法：管理员权限校验
    // -----------------------------------------------------------------------

    /**
     * 校验当前请求来自管理员（userLevel == 3）。
     * JwtAuthInterceptor 已将 userLevel 写入 request 属性。
     *
     * @throws BusinessException HTTP 403 若不是管理员
     */
    private void requireAdmin(HttpServletRequest request) {
        int userLevel = JwtAuthInterceptor.getUserLevel(request);
        if (userLevel != 3) {
            throw new BusinessException(403, "此操作需要管理员权限（ADMIN）");
        }
    }

    // -----------------------------------------------------------------------
    // 用户管理
    // -----------------------------------------------------------------------

    /**
     * 分页查询所有未软删除用户列表。
     *
     * <p>使用 PageHelper 透明分页：
     *   PageHelper.startPage(page, size) 在 SQL 执行前自动注入 LIMIT/OFFSET，
     *   并通过 PageInfo 返回总记录数、总页数等分页元数据。
     *
     * <p>返回数据不含 password_hash（UserServiceImpl.findById 会清空，
     * 但此处直接用 UserMapper；密码哈希字段在注册返回时已清空，列表不含敏感信息）。
     *
     * @param page 页码，从 1 开始
     * @param size 每页条数，默认 20
     */
    @Operation(summary = "分页查询所有用户",
               description = "管理员查看用户列表，支持分页。password_hash 不在返回字段中。")
    @GetMapping("/users")
    public Result<PageInfo<User>> listUsers(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        requireAdmin(request);
        PageHelper.startPage(page, size);
        List<User> users = userMapper.findAll();
        // 清空密码哈希，不向前端暴露
        users.forEach(u -> u.setPasswordHash(null));
        PageInfo<User> pageInfo = new PageInfo<>(users);
        return Result.success(pageInfo);
    }

    /**
     * 修改指定用户的账号等级。
     *
     * <p>合法等级值：
     *   1 → REGULAR（普通用户）
     *   2 → VIP
     *   3 → ADMIN（谨慎授予）
     *
     * <p>本期 VIP 付费升级由管理员手动调用此接口，支付系统不在范围内。
     *
     * @param userId   目标用户 ID
     * @param body     请求体：{@code {"userLevel": 2}}
     */
    @Operation(summary = "修改用户等级（REGULAR / VIP / ADMIN）",
               description = "合法值：1=普通, 2=VIP, 3=管理员。本期 VIP 升级由管理员手动操作。")
    @PutMapping("/users/{userId}/level")
    public Result<Void> updateUserLevel(
            @PathVariable Long userId,
            @RequestBody Map<String, Integer> body,
            HttpServletRequest request) {
        requireAdmin(request);

        Integer newLevel = body.get("userLevel");
        if (newLevel == null || newLevel < 1 || newLevel > 3) {
            throw new BusinessException(400, "userLevel 必须为 1（普通）、2（VIP）或 3（管理员）");
        }
        // 校验用户存在
        User user = userMapper.findById(userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        userService.updateUserLevel(userId, newLevel);
        return Result.success();
    }

    /**
     * 启用或禁用指定用户账号（status: 1=启用, 0=禁用）。
     *
     * <p>禁用用户后，其 JWT 仍然有效直到自然过期，
     * 但 JwtAuthInterceptor 不会主动拦截（本期不做实时禁用校验）。
     * 若需立即禁用，可配合 Redis 黑名单（logout 接口）实现。
     *
     * @param userId 目标用户 ID
     * @param body   请求体：{@code {"status": 0}}
     */
    @Operation(summary = "启用/禁用用户账号",
               description = "status=1 启用，status=0 禁用。禁用后已签发的 JWT 仍有效，如需立即失效请调用 /auth/logout。")
    @PutMapping("/users/{userId}/status")
    public Result<Void> updateUserStatus(
            @PathVariable Long userId,
            @RequestBody Map<String, Integer> body,
            HttpServletRequest request) {
        requireAdmin(request);

        Integer status = body.get("status");
        if (status == null || (status != 0 && status != 1)) {
            throw new BusinessException(400, "status 必须为 1（启用）或 0（禁用）");
        }
        User user = userMapper.findById(userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        User update = new User();
        update.setId(userId);
        update.setStatus(status);
        userMapper.update(update);
        return Result.success();
    }

    // -----------------------------------------------------------------------
    // 配额配置管理
    // -----------------------------------------------------------------------

    /**
     * 查询所有用户等级的配额配置（REGULAR / VIP / ADMIN 三条记录）。
     *
     * <p>返回数据包含：daily_token_limit、monthly_token_limit、
     * max_concurrent_tasks、max_plan_steps。
     */
    @Operation(summary = "查询所有等级的配额配置")
    @GetMapping("/quota-configs")
    public Result<Map<String, UserQuotaConfig>> listQuotaConfigs(HttpServletRequest request) {
        requireAdmin(request);
        // 直接按 3 个已知等级查询
        UserQuotaConfig regularCfg = quotaConfigMapper.findByUserLevel(1);
        UserQuotaConfig vipCfg     = quotaConfigMapper.findByUserLevel(2);
        UserQuotaConfig adminCfg   = quotaConfigMapper.findByUserLevel(3);
        return Result.success(Map.of(
                "REGULAR", regularCfg != null ? regularCfg : new UserQuotaConfig(),
                "VIP",     vipCfg     != null ? vipCfg     : new UserQuotaConfig(),
                "ADMIN",   adminCfg   != null ? adminCfg   : new UserQuotaConfig()
        ));
    }

    /**
     * 更新指定等级的配额配置。
     *
     * <p>请求体可包含以下字段（均为可选，仅修改传入的字段）：
     * <pre>
     * {
     *   "dailyTokenLimit":    50000,
     *   "monthlyTokenLimit":  500000,
     *   "maxConcurrentTasks": 5,
     *   "maxPlanSteps":       30
     * }
     * </pre>
     *
     * <p>修改后，Redis 缓存中的配额配置 Key（quota:config:{level}）将在下次
     * TTL（10 分钟）到期后自动刷新；如需立即生效可手动清除 Redis 该 Key。
     *
     * @param level 用户等级：1=REGULAR, 2=VIP, 3=ADMIN
     * @param config 部分更新的配额配置
     */
    @Operation(summary = "修改指定等级的配额配置",
               description = "可部分更新 dailyTokenLimit/monthlyTokenLimit/maxConcurrentTasks/maxPlanSteps。"
                           + "Redis 缓存 10 分钟内自动过期刷新。")
    @PutMapping("/quota-configs/{level}")
    public Result<Void> updateQuotaConfig(
            @PathVariable int level,
            @RequestBody UserQuotaConfig config,
            HttpServletRequest request) {
        requireAdmin(request);

        if (level < 1 || level > 3) {
            throw new BusinessException(400, "level 必须为 1、2 或 3");
        }
        // 校验配额值的合理范围（防止误设为 0 导致全部用户无法使用）
        if (config.getDailyTokenLimit() != null && config.getDailyTokenLimit() <= 0) {
            throw new BusinessException(400, "dailyTokenLimit 必须大于 0");
        }
        if (config.getMonthlyTokenLimit() != null && config.getMonthlyTokenLimit() <= 0) {
            throw new BusinessException(400, "monthlyTokenLimit 必须大于 0");
        }
        if (config.getMaxConcurrentTasks() != null && config.getMaxConcurrentTasks() <= 0) {
            throw new BusinessException(400, "maxConcurrentTasks 必须大于 0");
        }
        if (config.getMaxPlanSteps() != null && config.getMaxPlanSteps() <= 0) {
            throw new BusinessException(400, "maxPlanSteps 必须大于 0");
        }

        config.setUserLevel(level);
        quotaConfigMapper.update(config);
        return Result.success();
    }

    // -----------------------------------------------------------------------
    // 任务监控
    // -----------------------------------------------------------------------

    /**
     * 分页查询全体用户的任务列表（管理员视角，可跨用户）。
     *
     * <p>支持按 status 过滤，例如查询当前所有 {@code planning} 中的任务：
     * {@code GET /api/admin/tasks?status=planning&page=1&size=20}
     *
     * <p>当不传 status 时，查询所有状态的任务（按创建时间倒序）。
     * checkpoint_json 在 SQL 层排除，不会传输到前端。
     *
     * @param status 任务状态过滤（可选）：pending/planning/tool_calling/paused/resuming/completed/failed/cancelled
     * @param page   页码，从 1 开始，默认 1
     * @param size   每页条数，默认 50，上限 200
     */
    @Operation(summary = "分页查询任务列表（管理员视角）",
               description = "可按 status 过滤，不传则返回全部状态。checkpoint_json 不暴露。支持分页（page/size）。")
    @GetMapping("/tasks")
    public Result<PageInfo<Task>> listTasks(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size,
            HttpServletRequest request) {
        requireAdmin(request);

        if (page < 1) {
            throw new BusinessException(400, "page 必须大于 0");
        }
        if (size <= 0 || size > 200) {
            throw new BusinessException(400, "size 必须在 1 ~ 200 之间");
        }

        String statusFilter = (status != null && !status.isBlank()) ? status : null;
        PageHelper.startPage(page, size);
        List<Task> tasks = taskMapper.findAllWithFilter(statusFilter);
        // checkpoint_json is excluded at SQL level; this is a safety net
        tasks.forEach(t -> t.setCheckpointJson(null));
        return Result.success(new PageInfo<>(tasks));
    }
}
