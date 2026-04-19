package com.travelagent.model.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Final travel plan produced by a completed task.
 * Contains ordered PlanStep entries fetched via PlanMapper.
 */

/**
 * 中文注释：实体类，用于定义 Plan 的持久化数据结构。
 */

@Data
@NoArgsConstructor
public class Plan {

    private Long id;

    private Long taskId;

    private Long userId;

    private String title;

    private String region;

    /** LLM-generated narrative summary of the full itinerary */
    private String summary;

    private Integer totalDays;

    private LocalDateTime createdAt;

    /** Populated by the service layer (not a DB column) */
    private List<PlanStep> steps;
}
