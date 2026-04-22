package com.travelagent.model.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Data
@NoArgsConstructor
public class Plan {

    private Long id;
    private Long taskId;
    private Long userId;
    private String title;
    private String region;
    private String summary;
    private Integer totalDays;
    private String startLocationQuery;
    private String endLocationQuery;
    private LocalDateTime tripStartTime;
    private LocalDateTime tripEndTime;
    private LocalTime fullDayStartTime;
    private LocalTime fullDayEndTime;
    private Integer destinationBufferMin;
    private LocalDateTime createdAt;
    private List<PlanStep> steps;
}
