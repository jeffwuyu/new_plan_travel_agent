package com.travelagent.model.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
public class PlanRouteMapJob {

    private Long id;
    private Long routeMapId;
    private Long planId;
    private Long userId;
    private Integer dayNumber;
    private String style;
    private String status;
    private String triggerType;
    private String lockedBy;
    private LocalDateTime lockedUntil;
    private Integer attempts;
    private Integer maxAttempts;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime availableAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
