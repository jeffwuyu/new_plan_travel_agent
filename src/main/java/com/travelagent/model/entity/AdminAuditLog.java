package com.travelagent.model.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AdminAuditLog {
    private Long id;
    private Long adminUserId;
    private String permission;
    private String action;
    private String targetType;
    private String targetId;
    private String requestIp;
    private String userAgent;
    private String detailsJson;
    private LocalDateTime createdAt;
}
