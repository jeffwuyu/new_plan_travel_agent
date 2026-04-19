package com.travelagent.model.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A single step (attraction visit) within a travel plan.
 */

/**
 * 中文注释：实体类，用于定义 Plan Step 的持久化数据结构。
 */

@Data
@NoArgsConstructor
public class PlanStep {

    private Long id;

    private Long planId;

    /** 0-based execution order within the plan */
    private Integer stepOrder;

    /** 1-based day number (day 1, 2, 3...) */
    private Integer dayNumber;

    private String attractionName;

    private BigDecimal latitude;

    private BigDecimal longitude;

    /** Estimated time to spend at this attraction, in minutes */
    private Integer estimatedDurationMin;

    /** Travel time from the previous step, in minutes (null for first step) */
    private Integer trafficTimeFromPrev;

    /** Weather note for this location and day */
    private String weatherNote;

    /** LLM-generated description and recommendation for this step */
    private String llmDescription;

    private LocalDateTime createdAt;
}
