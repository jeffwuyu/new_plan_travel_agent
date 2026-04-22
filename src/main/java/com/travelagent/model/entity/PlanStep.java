package com.travelagent.model.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
public class PlanStep {

    private Long id;
    private Long planId;
    private Integer stepOrder;
    private Integer dayNumber;
    private String attractionName;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private Integer estimatedDurationMin;
    private Integer trafficTimeFromPrev;
    private String weatherNote;
    private String llmDescription;
    private LocalDateTime plannedStartTime;
    private LocalDateTime plannedEndTime;
    private Integer travelTimeToDestinationMin;
    private LocalDateTime createdAt;
}
