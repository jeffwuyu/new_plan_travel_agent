package com.travelagent.model.entity;

import java.time.LocalDateTime;

/**
 * 用途：用户配额用量快照实体。
 *
 * Redis 是配额计数器的主存储（INCRBY 原子操作），本表用于：
 *   1. Redis 重启后恢复配额数据（可从本表重建 Redis 计数器）；
 *   2. 历史用量审计与计费分析。
 *
 * 调度器每 60 秒将 Redis 中的 daily/monthly 计数器写入此表（UPSERT）。
 *
 * 对应 DDL 表：user_quota_usage
 */
public class UserQuotaUsage {

    /** 主键，自增。 */
    private Long id;

    /** 关联用户 ID（对应 users.id）。 */
    private Long userId;

    /**
     * 周期类型：
     *   "daily"   — 每日周期（period_key 格式 "2026-04-19"）
     *   "monthly" — 每月周期（period_key 格式 "2026-04"）
     */
    private String periodType;

    /**
     * 周期标识：
     *   daily   → yyyy-MM-dd
     *   monthly → yyyy-MM
     */
    private String periodKey;

    /** 本周期已使用的 Token 数量。 */
    private int tokensUsed;

    /** 最近更新时间（数据库自动维护 ON UPDATE CURRENT_TIMESTAMP）。 */
    private LocalDateTime updatedAt;

    // -----------------------------------------------------------------------
    // 构造方法
    // -----------------------------------------------------------------------

    public UserQuotaUsage() {}

    /**
     * 全参构造，供 QuotaServiceImpl 中快照写入时使用。
     *
     * @param userId     用户 ID
     * @param periodType "daily" 或 "monthly"
     * @param periodKey  日期字符串（"2026-04-19" 或 "2026-04"）
     * @param tokensUsed 本周期已消耗 Token 数
     */
    public UserQuotaUsage(Long userId, String periodType, String periodKey, int tokensUsed) {
        this.userId = userId;
        this.periodType = periodType;
        this.periodKey = periodKey;
        this.tokensUsed = tokensUsed;
    }

    // -----------------------------------------------------------------------
    // Getters / Setters
    // -----------------------------------------------------------------------

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getPeriodType() { return periodType; }
    public void setPeriodType(String periodType) { this.periodType = periodType; }

    public String getPeriodKey() { return periodKey; }
    public void setPeriodKey(String periodKey) { this.periodKey = periodKey; }

    public int getTokensUsed() { return tokensUsed; }
    public void setTokensUsed(int tokensUsed) { this.tokensUsed = tokensUsed; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
