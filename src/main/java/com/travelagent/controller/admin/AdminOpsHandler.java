package com.travelagent.controller.admin;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.travelagent.client.oss.OssClient;
import com.travelagent.exception.BusinessException;
import com.travelagent.filter.JwtAuthInterceptor;
import com.travelagent.mapper.TaskMapper;
import com.travelagent.model.entity.AdminAuditLog;
import com.travelagent.model.entity.Task;
import com.travelagent.model.entity.TaskExecutionEvent;
import com.travelagent.model.enums.AdminPermission;
import com.travelagent.monitoring.ExternalCapabilityHealth;
import com.travelagent.monitoring.ExternalCapabilityHealthService;
import com.travelagent.monitoring.TaskMetricsService;
import com.travelagent.service.admin.AdminAuditService;
import com.travelagent.service.admin.AdminTaskOpsService;
import com.travelagent.service.routemap.PlanRouteMapService;
import jakarta.servlet.http.HttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 管理后台运维处理器。
 *
 * <p>该类集中处理指标、外部能力探活、任务排障、路线图排障和审计查询，
 * 让控制器避免承载多条运维链路的细节。</p>
 */
public class AdminOpsHandler {

    private final TaskMapper taskMapper;
    private final TaskMetricsService taskMetricsService;
    private final ExternalCapabilityHealthService externalCapabilityHealthService;
    private final AdminTaskOpsService adminTaskOpsService;
    private final PlanRouteMapService planRouteMapService;
    private final OssClient ossClient;
    private final AdminAuditService adminAuditService;
    private final AdminAccessSupport accessSupport;

    /**
     * 创建管理后台运维处理器。
     *
     * @param taskMapper 任务数据访问对象
     * @param taskMetricsService 任务指标服务
     * @param externalCapabilityHealthService 外部能力健康服务
     * @param adminTaskOpsService 可选任务运维服务
     * @param planRouteMapService 可选路线图服务
     * @param ossClient 可选 OSS 客户端
     * @param adminAuditService 可选审计查询服务
     * @param accessSupport 管理权限与审计辅助对象
     */
    public AdminOpsHandler(TaskMapper taskMapper,
                           TaskMetricsService taskMetricsService,
                           ExternalCapabilityHealthService externalCapabilityHealthService,
                           AdminTaskOpsService adminTaskOpsService,
                           PlanRouteMapService planRouteMapService,
                           OssClient ossClient,
                           AdminAuditService adminAuditService,
                           AdminAccessSupport accessSupport) {
        this.taskMapper = taskMapper;
        this.taskMetricsService = taskMetricsService;
        this.externalCapabilityHealthService = externalCapabilityHealthService;
        this.adminTaskOpsService = adminTaskOpsService;
        this.planRouteMapService = planRouteMapService;
        this.ossClient = ossClient;
        this.adminAuditService = adminAuditService;
        this.accessSupport = accessSupport;
    }

    /**
     * 获取运行时指标快照。
     *
     * @param request 当前 HTTP 请求
     * @return 指标快照
     */
    public Map<String, Object> getMetrics(HttpServletRequest request) {
        accessSupport.requirePermission(request, AdminPermission.METRICS_READ);
        return taskMetricsService.getSnapshot();
    }

    /**
     * 查询外部能力健康矩阵。
     *
     * @param request 当前 HTTP 请求
     * @return 外部能力健康列表
     */
    public List<ExternalCapabilityHealth> getCapabilityHealth(HttpServletRequest request) {
        accessSupport.requirePermission(request, AdminPermission.METRICS_READ);
        return externalCapabilityHealthService.getHealthMatrix();
    }

    /**
     * 执行 OSS 上传、下载、签名 URL 和删除探活。
     *
     * @param request 当前 HTTP 请求
     * @return 探活过程和结果
     */
    public Map<String, Object> runOssProbe(HttpServletRequest request) {
        accessSupport.requirePermission(request, AdminPermission.METRICS_READ);
        if (ossClient == null) {
            throw new BusinessException(503, "OssClient is not enabled");
        }

        String objectKey = "ops/probes/oss-probe-" + UUID.randomUUID() + ".txt";
        byte[] payload = ("travel-agent oss probe " + objectKey).getBytes(StandardCharsets.UTF_8);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("objectKey", objectKey);
        result.put("uploaded", false);
        result.put("downloaded", false);
        result.put("contentMatched", false);
        result.put("signedUrlGenerated", false);
        result.put("deleted", false);
        result.put("probePassed", false);
        try {
            ossClient.uploadObject(objectKey, payload, "text/plain; charset=utf-8");
            result.put("uploaded", true);
            byte[] downloaded = ossClient.downloadObject(objectKey);
            result.put("downloaded", true);
            boolean matched = java.util.Arrays.equals(payload, downloaded);
            result.put("contentMatched", matched);
            String signedUrl = ossClient.generateSignedUrl(objectKey, Duration.ofMinutes(5));
            result.put("signedUrlGenerated", signedUrl != null && !signedUrl.isBlank());
            result.put("probePassed", matched && Boolean.TRUE.equals(result.get("signedUrlGenerated")));
            return result;
        } catch (Exception e) {
            result.put("error", e.getMessage());
            return result;
        } finally {
            try {
                ossClient.deleteDocument(objectKey);
                result.put("deleted", true);
            } catch (Exception e) {
                result.put("deleteError", e.getMessage());
            }
            accessSupport.recordAudit(request, AdminPermission.METRICS_READ,
                    "run_oss_probe", "capability", "oss", result);
        }
    }

    /**
     * 分页查询任务并隐藏 checkpoint JSON。
     *
     * @param status 状态过滤，可为空
     * @param page 页码，从 1 开始
     * @param size 每页数量，最大 200
     * @param request 当前 HTTP 请求
     * @return 任务分页结果
     */
    public PageInfo<Task> listTasks(String status, int page, int size, HttpServletRequest request) {
        accessSupport.requirePermission(request, AdminPermission.TASK_READ);
        if (page < 1) {
            throw new BusinessException(400, "page must be greater than 0");
        }
        if (size <= 0 || size > 200) {
            throw new BusinessException(400, "size must be between 1 and 200");
        }

        String statusFilter = (status != null && !status.isBlank()) ? status : null;
        PageHelper.startPage(page, size);
        List<Task> tasks = taskMapper.findAllWithFilter(statusFilter);
        tasks.forEach(task -> task.setCheckpointJson(null));
        return new PageInfo<>(tasks);
    }

    /**
     * 查询任务执行事件时间线。
     *
     * @param taskUuid 任务 UUID
     * @param limit 最大事件数量
     * @param request 当前 HTTP 请求
     * @return 任务事件列表
     */
    public List<TaskExecutionEvent> listTaskEvents(String taskUuid, int limit, HttpServletRequest request) {
        accessSupport.requirePermission(request, AdminPermission.TASK_READ);
        if (limit <= 0 || limit > 500) {
            throw new BusinessException(400, "limit must be between 1 and 500");
        }
        return adminTaskOpsService == null
                ? List.of()
                : adminTaskOpsService.getTaskTimeline(taskUuid, limit);
    }

    /**
     * 查询任务 Redis lock/lease 快照。
     *
     * @param taskUuid 任务 UUID
     * @param request 当前 HTTP 请求
     * @return lease 信息
     */
    public Map<String, Object> getTaskLease(String taskUuid, HttpServletRequest request) {
        accessSupport.requirePermission(request, AdminPermission.TASK_READ);
        return adminTaskOpsService == null
                ? Map.of("taskUuid", taskUuid, "available", false, "error", "AdminTaskOpsService is not enabled")
                : adminTaskOpsService.getLeaseInfo(taskUuid);
    }

    /**
     * 查询任务派发队列快照。
     *
     * @param request 当前 HTTP 请求
     * @return 队列消费状态
     */
    public Map<String, Object> getTaskDispatchQueue(HttpServletRequest request) {
        accessSupport.requirePermission(request, AdminPermission.TASK_READ);
        return adminTaskOpsService == null
                ? Map.of("available", false, "error", "AdminTaskOpsService is not enabled")
                : adminTaskOpsService.getDispatchQueueSnapshot();
    }

    /**
     * 触发任务生命周期超时治理扫描。
     *
     * @param body 请求体，读取触发来源
     * @param request 当前 HTTP 请求
     * @return 扫描结果
     */
    public Map<String, Object> runTaskLifecycleScan(Map<String, String> body, HttpServletRequest request) {
        accessSupport.requirePermission(request, AdminPermission.TASK_WRITE);
        if (adminTaskOpsService == null) {
            throw new BusinessException(503, "AdminTaskOpsService is not enabled");
        }
        String trigger = body == null ? "admin_manual" : body.getOrDefault("trigger", "admin_manual");
        Map<String, Object> result = adminTaskOpsService.runLifecycleScan(trigger);
        accessSupport.recordAudit(request, AdminPermission.TASK_WRITE,
                "run_task_lifecycle_scan", "task", "lifecycle", result);
        return result;
    }

    /**
     * 管理员重派发失败或暂停任务。
     *
     * @param taskUuid 任务 UUID
     * @param body 请求体，读取重派发原因
     * @param request 当前 HTTP 请求
     * @return 重派发结果
     */
    public Map<String, Object> redispatchTask(String taskUuid, Map<String, String> body, HttpServletRequest request) {
        accessSupport.requirePermission(request, AdminPermission.TASK_WRITE);
        if (adminTaskOpsService == null) {
            throw new BusinessException(503, "AdminTaskOpsService is not enabled");
        }
        String reason = body == null ? null : body.get("reason");
        Map<String, Object> result = adminTaskOpsService.redispatchTask(
                taskUuid, JwtAuthInterceptor.getUserId(request), reason);
        accessSupport.recordAudit(request, AdminPermission.TASK_WRITE,
                "redispatch_task", "task", taskUuid, result);
        return result;
    }

    /**
     * 管理员重派发路线图作业。
     *
     * @param routeMapId 路线图记录 ID
     * @param body 请求体，读取重派发原因
     * @param request 当前 HTTP 请求
     * @return 重派发结果
     */
    public Map<String, Object> redispatchRouteMap(Long routeMapId,
                                                  Map<String, String> body,
                                                  HttpServletRequest request) {
        accessSupport.requirePermission(request, AdminPermission.TASK_WRITE);
        if (planRouteMapService == null) {
            throw new BusinessException(503, "PlanRouteMapService is not enabled");
        }
        String reason = body == null ? null : body.get("reason");
        Map<String, Object> result = planRouteMapService.adminRedispatch(
                routeMapId, JwtAuthInterceptor.getUserId(request), reason);
        accessSupport.recordAudit(request, AdminPermission.TASK_WRITE,
                "redispatch_route_map", "route_map", routeMapId, result);
        return result;
    }

    /**
     * 触发路线图派发补偿扫描。
     *
     * @param body 请求体，读取触发来源
     * @param request 当前 HTTP 请求
     * @return 补偿扫描结果
     */
    public Map<String, Object> runRouteMapDispatchCompensationScan(Map<String, String> body,
                                                                   HttpServletRequest request) {
        accessSupport.requirePermission(request, AdminPermission.TASK_WRITE);
        if (planRouteMapService == null) {
            throw new BusinessException(503, "PlanRouteMapService is not enabled");
        }
        String trigger = body == null ? "admin_manual" : body.getOrDefault("trigger", "admin_manual");
        Map<String, Object> result = planRouteMapService.compensateDispatchFailures(trigger);
        accessSupport.recordAudit(request, AdminPermission.TASK_WRITE,
                "route_map_dispatch_compensation_scan", "route_map", "dispatch_compensation", result);
        return result;
    }

    /**
     * 查询路线图生成统计。
     *
     * @param startDate 开始日期，可为空
     * @param endDate 结束日期，可为空
     * @param limit 聚合列表数量
     * @param request 当前 HTTP 请求
     * @return 路线图统计结果
     */
    public Map<String, Object> getRouteMapStatistics(LocalDate startDate,
                                                     LocalDate endDate,
                                                     int limit,
                                                     HttpServletRequest request) {
        accessSupport.requirePermission(request, AdminPermission.METRICS_READ);
        if (planRouteMapService == null) {
            throw new BusinessException(503, "PlanRouteMapService is not enabled");
        }
        return planRouteMapService.getAdminStatistics(startDate, endDate, limit);
    }

    /**
     * 查询管理员审计日志。
     *
     * @param adminUserId 管理员用户 ID，可为空
     * @param action 操作编码，可为空
     * @param limit 最大返回数量
     * @param request 当前 HTTP 请求
     * @return 审计日志列表
     */
    public List<AdminAuditLog> listAuditLogs(Long adminUserId,
                                             String action,
                                             int limit,
                                             HttpServletRequest request) {
        accessSupport.requirePermission(request, AdminPermission.AUDIT_READ);
        if (limit <= 0 || limit > 200) {
            throw new BusinessException(400, "limit must be between 1 and 200");
        }
        return adminAuditService == null
                ? List.of()
                : adminAuditService.recent(adminUserId, action, limit);
    }
}
