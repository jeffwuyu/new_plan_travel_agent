package com.travelagent.service.admin;

import com.travelagent.exception.BusinessException;
import com.travelagent.filter.JwtAuthInterceptor;
import com.travelagent.model.enums.AdminPermission;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AdminAuthorizationService {

    private final boolean rbacEnabled;
    private final Set<String> superAdminUserIds;
    private final String permissionConfig;

    public AdminAuthorizationService(@Value("${admin.rbac.enabled:false}") boolean rbacEnabled,
                                     @Value("${admin.rbac.super-admin-user-ids:}") String superAdminUserIds,
                                     @Value("${admin.rbac.user-permissions:}") String permissionConfig) {
        this.rbacEnabled = rbacEnabled;
        this.superAdminUserIds = split(superAdminUserIds);
        this.permissionConfig = permissionConfig == null ? "" : permissionConfig;
    }

    public void require(HttpServletRequest request, AdminPermission permission) {
        Long userId = JwtAuthInterceptor.getUserId(request);
        int userLevel = JwtAuthInterceptor.getUserLevel(request);
        if (userLevel != 3) {
            throw new BusinessException(403, "此操作需要管理员权限（ADMIN）");
        }
        if (!rbacEnabled || userId == null || superAdminUserIds.contains(String.valueOf(userId))) {
            return;
        }
        if (!permissionsFor(userId).contains(permission.name())) {
            throw new BusinessException(403, "管理员缺少权限：" + permission.name());
        }
    }

    private Set<String> permissionsFor(Long userId) {
        String prefix = userId + "=";
        for (String entry : permissionConfig.split(";")) {
            String trimmed = entry.trim();
            if (trimmed.startsWith(prefix)) {
                return split(trimmed.substring(prefix.length())).stream()
                        .map(value -> value.toUpperCase(Locale.ROOT))
                        .collect(Collectors.toSet());
            }
        }
        return Set.of();
    }

    private Set<String> split(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .collect(Collectors.toSet());
    }
}
