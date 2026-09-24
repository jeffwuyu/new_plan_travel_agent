package com.travelagent.model.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
public class PlanDayRouteMap {

    private Long id;
    private Long planId;
    private Long userId;
    private Integer dayNumber;
    private String style;
    private String status;
    private Integer progressPercent;
    private String skeletonOssKey;
    private String aiRawOssKey;
    private String finalOssKey;
    private String routeGeometryJson;
    private String stopsJson;
    private String segmentsJson;
    private String boundsJson;
    private String model;
    private String requestId;
    private String taskId;
    private Integer retryCount;
    private Integer manualRegenCount;
    private String errorCode;
    private String errorMessage;
    private Long latencyMs;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
