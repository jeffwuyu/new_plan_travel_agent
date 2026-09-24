package com.travelagent.model.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
public class PlanRouteMapResponse {
    private Long id;
    private Long planId;
    private Integer dayNumber;
    private String status;
    private Integer progressPercent;
    private String style;
    private String imageUrl;
    private String fallbackImageUrl;
    private String errorCode;
    private String errorMessage;
    private Integer manualRegenCount;
    private Integer manualRegenerateLimit;
    private Integer manualRegenerateRemaining;
    private List<Map<String, Object>> stops;
    private List<Map<String, Object>> segments;
    private LocalDateTime updatedAt;
}
