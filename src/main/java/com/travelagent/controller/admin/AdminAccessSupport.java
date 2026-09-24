package com.travelagent.controller.admin;

import com.travelagent.exception.BusinessException;
import com.travelagent.filter.JwtAuthInterceptor;
import com.travelagent.model.enums.AdminPermission;
import com.travelagent.service.admin.AdminAuditService;
import com.travelagent.service.admin.AdminAuthorizationService;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Map;

/**
 * 管理后台访问控制与审计辅助类。
 *
 * <p>该类把管理员身份校验、可选 RBAC 权限校验和审计记录从控制器中抽离出来，
 * 让各个管理端路由只关注自己的业务参数与返回值。</p>
 */
public class AdminAccessSupport {

    private final AdminAuthorizationService adminAuthorizationService;
    private final AdminAuditService adminAuditService;

    /**
     * 创建管理后台访问辅助对象。
     *
     * @param adminAuthorizationService 可选 RBAC 授权服务，未启用时只校验 ADMIN 等级
     * @param adminAuditService 可选审计服务，未启用时忽略审计写入
     */
    public AdminAccessSupport(AdminAuthorizationService adminAuthorizationService,
                              AdminAuditService adminAuditService) {
        this.adminAuthorizationService = adminAuthorizationService;
        this.adminAuditService = adminAuditService;
    }

    /**
     * 校验当前请求是否具备指定管理权限。
     *
     * @param request 当前 HTTP 请求，用于读取 JWT 解析后的用户身份
     * @param permission 目标管理权限
     */
    public void requirePermission(HttpServletRequest request, AdminPermission permission) {
        requireAdmin(request);
        if (adminAuthorizationService != null) {
            adminAuthorizationService.require(request, permission);
        }
    }

    /**
     * 记录管理员操作审计。
     *
     * @param request 当前 HTTP 请求
     * @param permission 操作所需权限
     * @param action 审计动作编码
     * @param targetType 操作目标类型
     * @param targetId 操作目标标识
     * @param details 操作详情，会由审计服务序列化为 JSON
     */
    public void recordAudit(HttpServletRequest request,
                            AdminPermission permission,
                            String action,
                            String targetType,
                            Object targetId,
                            Map<String, ?> details) {
        if (adminAuditService != null) {
            adminAuditService.record(request, permission, action, targetType, targetId, details);
        }
    }

    /**
     * 校验当前用户是否为 ADMIN 等级。
     *
     * @param request 当前 HTTP 请求
     */
    private void requireAdmin(HttpServletRequest request) {
        int userLevel = JwtAuthInterceptor.getUserLevel(request);
        if (userLevel != 3) {
            throw new BusinessException(403, "此操作需要管理员权限（ADMIN）");
        }
    }
}
