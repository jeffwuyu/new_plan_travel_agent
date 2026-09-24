package com.travelagent.controller;

import com.github.pagehelper.PageInfo;
import com.travelagent.client.oss.OssClient;
import com.travelagent.controller.admin.AdminAccessSupport;
import com.travelagent.controller.admin.AdminOpsHandler;
import com.travelagent.controller.admin.AdminUserQuotaHandler;
import com.travelagent.mapper.TaskMapper;
import com.travelagent.mapper.UserMapper;
import com.travelagent.mapper.UserQuotaConfigMapper;
import com.travelagent.model.dto.Result;
import com.travelagent.model.entity.AdminAuditLog;
import com.travelagent.model.entity.Task;
import com.travelagent.model.entity.TaskExecutionEvent;
import com.travelagent.model.entity.User;
import com.travelagent.model.entity.UserQuotaConfig;
import com.travelagent.monitoring.ExternalCapabilityHealth;
import com.travelagent.monitoring.ExternalCapabilityHealthService;
import com.travelagent.monitoring.TaskMetricsService;
import com.travelagent.service.admin.AdminAuditService;
import com.travelagent.service.admin.AdminAuthorizationService;
import com.travelagent.service.admin.AdminTaskOpsService;
import com.travelagent.service.routemap.PlanRouteMapService;
import com.travelagent.service.user.UserService;
import com.travelagent.util.RedisUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 管理后台 REST 路由门面。
 *
 * <p>该控制器保留 `/api/admin` 的外部契约，具体用户、配额、任务、路线图、
 * 健康探活和审计逻辑委托给内部 handler，避免单个控制器继续承载过多职责。</p>
 */
@Tag(name = "Admin", description = "Administrator APIs for users, quotas, tasks, metrics, and audits")
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminUserQuotaHandler userQuotaHandler;
    private final AdminOpsHandler opsHandler;

    /**
     * 创建管理后台控制器并组装内部 handler。
     *
     * @param userMapper 用户数据访问对象
     * @param userService 用户服务
     * @param quotaConfigMapper 配额配置数据访问对象
     * @param taskMapper 任务数据访问对象
     * @param taskMetricsService 任务指标服务
     * @param externalCapabilityHealthService 外部能力健康服务
     * @param redisUtil Redis 工具
     * @param adminAuthorizationService 可选 RBAC 授权服务
     * @param adminAuditService 可选审计服务
     * @param adminTaskOpsService 可选任务运维服务
     * @param planRouteMapService 可选路线图服务
     * @param ossClient 可选 OSS 客户端
     */
    @Autowired
    public AdminController(UserMapper userMapper,
                           UserService userService,
                           UserQuotaConfigMapper quotaConfigMapper,
                           TaskMapper taskMapper,
                           TaskMetricsService taskMetricsService,
                           ExternalCapabilityHealthService externalCapabilityHealthService,
                           RedisUtil redisUtil,
                           @Autowired(required = false) AdminAuthorizationService adminAuthorizationService,
                           @Autowired(required = false) AdminAuditService adminAuditService,
                           @Autowired(required = false) AdminTaskOpsService adminTaskOpsService,
                           @Autowired(required = false) PlanRouteMapService planRouteMapService,
                           @Autowired(required = false) OssClient ossClient) {
        AdminAccessSupport accessSupport = new AdminAccessSupport(adminAuthorizationService, adminAuditService);
        this.userQuotaHandler = new AdminUserQuotaHandler(
                userMapper, userService, quotaConfigMapper, redisUtil, adminTaskOpsService, accessSupport);
        this.opsHandler = new AdminOpsHandler(
                taskMapper,
                taskMetricsService,
                externalCapabilityHealthService,
                adminTaskOpsService,
                planRouteMapService,
                ossClient,
                adminAuditService,
                accessSupport);
    }

    /**
     * 分页查询用户列表。
     *
     * @param page 页码
     * @param size 每页数量
     * @param request 当前 HTTP 请求
     * @return 用户分页响应
     */
    @Operation(summary = "List users")
    @GetMapping("/users")
    public Result<PageInfo<User>> listUsers(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        return Result.success(userQuotaHandler.listUsers(page, size, request));
    }

    /**
     * 修改用户等级。
     *
     * @param userId 用户 ID
     * @param body 请求体，包含 `userLevel`
     * @param request 当前 HTTP 请求
     * @return 空成功响应
     */
    @Operation(summary = "Update user level")
    @PutMapping("/users/{userId}/level")
    public Result<Void> updateUserLevel(
            @PathVariable Long userId,
            @RequestBody Map<String, Integer> body,
            HttpServletRequest request) {
        userQuotaHandler.updateUserLevel(userId, body, request);
        return Result.success();
    }

    /**
     * 启用或禁用用户。
     *
     * @param userId 用户 ID
     * @param body 请求体，包含 `status`
     * @param request 当前 HTTP 请求
     * @return 空成功响应
     */
    @Operation(summary = "Enable or disable user")
    @PutMapping("/users/{userId}/status")
    public Result<Void> updateUserStatus(
            @PathVariable Long userId,
            @RequestBody Map<String, Integer> body,
            HttpServletRequest request) {
        userQuotaHandler.updateUserStatus(userId, body, request);
        return Result.success();
    }

    /**
     * 查询用户等级配额配置。
     *
     * @param request 当前 HTTP 请求
     * @return 三个等级的配额配置
     */
    @Operation(summary = "List quota configs")
    @GetMapping("/quota-configs")
    public Result<Map<String, UserQuotaConfig>> listQuotaConfigs(HttpServletRequest request) {
        return Result.success(userQuotaHandler.listQuotaConfigs(request));
    }

    /**
     * 更新用户等级配额配置。
     *
     * @param level 用户等级
     * @param config 配额配置
     * @param request 当前 HTTP 请求
     * @return 空成功响应
     */
    @Operation(summary = "Update quota config")
    @PutMapping("/quota-configs/{level}")
    public Result<Void> updateQuotaConfig(
            @PathVariable int level,
            @RequestBody UserQuotaConfig config,
            HttpServletRequest request) {
        userQuotaHandler.updateQuotaConfig(level, config, request);
        return Result.success();
    }

    /**
     * 查询运行时指标。
     *
     * @param request 当前 HTTP 请求
     * @return 指标快照
     */
    @Operation(summary = "Get runtime metrics")
    @GetMapping("/metrics")
    public Result<Map<String, Object>> getMetrics(HttpServletRequest request) {
        return Result.success(opsHandler.getMetrics(request));
    }

    /**
     * 查询外部能力健康矩阵。
     *
     * @param request 当前 HTTP 请求
     * @return 外部能力健康列表
     */
    @Operation(summary = "Get capability health matrix")
    @GetMapping("/capabilities/health")
    public Result<List<ExternalCapabilityHealth>> getCapabilityHealth(HttpServletRequest request) {
        return Result.success(opsHandler.getCapabilityHealth(request));
    }

    /**
     * 执行 OSS 探活。
     *
     * @param request 当前 HTTP 请求
     * @return 探活结果
     */
    @Operation(summary = "Run OSS upload/download probe")
    @PostMapping("/capabilities/oss-probe")
    public Result<Map<String, Object>> runOssProbe(HttpServletRequest request) {
        return Result.success(opsHandler.runOssProbe(request));
    }

    /**
     * 分页查询任务列表。
     *
     * @param status 状态过滤
     * @param page 页码
     * @param size 每页数量
     * @param request 当前 HTTP 请求
     * @return 任务分页响应
     */
    @Operation(summary = "List tasks")
    @GetMapping("/tasks")
    public Result<PageInfo<Task>> listTasks(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size,
            HttpServletRequest request) {
        return Result.success(opsHandler.listTasks(status, page, size, request));
    }

    /**
     * 查询任务事件时间线。
     *
     * @param taskUuid 任务 UUID
     * @param limit 返回数量上限
     * @param request 当前 HTTP 请求
     * @return 任务事件列表
     */
    @Operation(summary = "Get task events")
    @GetMapping("/tasks/{taskUuid}/events")
    public Result<List<TaskExecutionEvent>> listTaskEvents(
            @PathVariable String taskUuid,
            @RequestParam(defaultValue = "100") int limit,
            HttpServletRequest request) {
        return Result.success(opsHandler.listTaskEvents(taskUuid, limit, request));
    }

    /**
     * 查询任务 lease 信息。
     *
     * @param taskUuid 任务 UUID
     * @param request 当前 HTTP 请求
     * @return lease 快照
     */
    @Operation(summary = "Get task lease info")
    @GetMapping("/tasks/{taskUuid}/lease")
    public Result<Map<String, Object>> getTaskLease(
            @PathVariable String taskUuid,
            HttpServletRequest request) {
        return Result.success(opsHandler.getTaskLease(taskUuid, request));
    }

    /**
     * 查询任务派发队列快照。
     *
     * @param request 当前 HTTP 请求
     * @return 队列快照
     */
    @Operation(summary = "Get task dispatch queue snapshot")
    @GetMapping("/tasks/queue")
    public Result<Map<String, Object>> getTaskDispatchQueue(HttpServletRequest request) {
        return Result.success(opsHandler.getTaskDispatchQueue(request));
    }

    /**
     * 触发任务生命周期超时扫描。
     *
     * @param body 请求体，包含触发来源
     * @param request 当前 HTTP 请求
     * @return 扫描结果
     */
    @Operation(summary = "Run task lifecycle timeout scan")
    @PostMapping("/tasks/lifecycle-scan")
    public Result<Map<String, Object>> runTaskLifecycleScan(
            @RequestBody(required = false) Map<String, String> body,
            HttpServletRequest request) {
        return Result.success(opsHandler.runTaskLifecycleScan(body, request));
    }

    /**
     * 管理员重派发任务。
     *
     * @param taskUuid 任务 UUID
     * @param body 请求体，包含原因
     * @param request 当前 HTTP 请求
     * @return 重派发结果
     */
    @Operation(summary = "Redispatch failed or paused task")
    @PostMapping("/tasks/{taskUuid}/redispatch")
    public Result<Map<String, Object>> redispatchTask(
            @PathVariable String taskUuid,
            @RequestBody(required = false) Map<String, String> body,
            HttpServletRequest request) {
        return Result.success(opsHandler.redispatchTask(taskUuid, body, request));
    }

    /**
     * 管理员重派发路线图作业。
     *
     * @param routeMapId 路线图记录 ID
     * @param body 请求体，包含原因
     * @param request 当前 HTTP 请求
     * @return 重派发结果
     */
    @Operation(summary = "Redispatch route map job")
    @PostMapping("/route-maps/{routeMapId}/redispatch")
    public Result<Map<String, Object>> redispatchRouteMap(
            @PathVariable Long routeMapId,
            @RequestBody(required = false) Map<String, String> body,
            HttpServletRequest request) {
        return Result.success(opsHandler.redispatchRouteMap(routeMapId, body, request));
    }

    /**
     * 触发路线图派发补偿扫描。
     *
     * @param body 请求体，包含触发来源
     * @param request 当前 HTTP 请求
     * @return 补偿扫描结果
     */
    @Operation(summary = "Run route map dispatch compensation scan")
    @PostMapping("/route-maps/dispatch-compensation-scan")
    public Result<Map<String, Object>> runRouteMapDispatchCompensationScan(
            @RequestBody(required = false) Map<String, String> body,
            HttpServletRequest request) {
        return Result.success(opsHandler.runRouteMapDispatchCompensationScan(body, request));
    }

    /**
     * 查询路线图生成统计。
     *
     * @param startDate 开始日期
     * @param endDate 结束日期
     * @param limit 返回数量上限
     * @param request 当前 HTTP 请求
     * @return 统计结果
     */
    @Operation(summary = "Get route map generation statistics")
    @GetMapping("/route-maps/statistics")
    public Result<Map<String, Object>> getRouteMapStatistics(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "10") int limit,
            HttpServletRequest request) {
        return Result.success(opsHandler.getRouteMapStatistics(startDate, endDate, limit, request));
    }

    /**
     * 查询管理员审计日志。
     *
     * @param adminUserId 管理员用户 ID
     * @param action 操作编码
     * @param limit 返回数量上限
     * @param request 当前 HTTP 请求
     * @return 审计日志列表
     */
    @Operation(summary = "List admin audit logs")
    @GetMapping("/audit-logs")
    public Result<List<AdminAuditLog>> listAuditLogs(
            @RequestParam(required = false) Long adminUserId,
            @RequestParam(required = false) String action,
            @RequestParam(defaultValue = "50") int limit,
            HttpServletRequest request) {
        return Result.success(opsHandler.listAuditLogs(adminUserId, action, limit, request));
    }
}
