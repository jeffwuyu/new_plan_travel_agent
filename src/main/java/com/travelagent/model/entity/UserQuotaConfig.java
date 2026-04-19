package com.travelagent.model.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Per-level quota configuration. Defines the limits for each user level.
 * Populated on application startup and modifiable by admins.
 */

/**
 * 中文注释：实体类，用于定义 User Quota Config 的持久化数据结构。
 */

@Data
@NoArgsConstructor
public class UserQuotaConfig {

    private Long id;

    /** 1=REGULAR, 2=VIP, 3=ADMIN */
    private Integer userLevel;

    private Integer dailyTokenLimit;

    private Integer monthlyTokenLimit;

    /** Max number of tasks running concurrently per user */
    private Integer maxConcurrentTasks;

    /** Max total planning steps per task */
    private Integer maxPlanSteps;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
