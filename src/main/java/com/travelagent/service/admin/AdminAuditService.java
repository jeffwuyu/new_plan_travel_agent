package com.travelagent.service.admin;

import com.travelagent.filter.JwtAuthInterceptor;
import com.travelagent.mapper.AdminAuditLogMapper;
import com.travelagent.model.entity.AdminAuditLog;
import com.travelagent.model.enums.AdminPermission;
import com.travelagent.util.JsonUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class AdminAuditService {

    private final AdminAuditLogMapper mapper;
    private final JsonUtil jsonUtil;

    public AdminAuditService(AdminAuditLogMapper mapper, JsonUtil jsonUtil) {
        this.mapper = mapper;
        this.jsonUtil = jsonUtil;
    }

    public void record(HttpServletRequest request,
                       AdminPermission permission,
                       String action,
                       String targetType,
                       Object targetId,
                       Map<String, ?> details) {
        AdminAuditLog log = new AdminAuditLog();
        log.setAdminUserId(JwtAuthInterceptor.getUserId(request));
        log.setPermission(permission.name());
        log.setAction(action);
        log.setTargetType(targetType);
        log.setTargetId(targetId == null ? null : String.valueOf(targetId));
        log.setRequestIp(resolveClientIp(request));
        log.setUserAgent(request.getHeader("User-Agent"));
        log.setDetailsJson(details == null ? null : jsonUtil.toJson(details));
        mapper.insert(log);
    }

    public List<AdminAuditLog> recent(Long adminUserId, String action, int limit) {
        return mapper.findRecent(adminUserId, action, Math.max(1, Math.min(limit, 200)));
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
