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

    /**
     * 初始化UserQuotaUsage 实例。
     */
    public UserQuotaUsage() {}

    /**
     * 初始化UserQuotaUsage 实例。
     * @param userId 用户ID
     * @param periodType p er io dT yp e 参数
     * @param periodKey p er io dK ey 参数
     * @param tokensUsed t ok en sU se d 参数
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

    /**
     * 获取id。
     * @return 返回处理结果。
     */
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    /**
     * 获取userid。
     * @return 返回处理结果。
     */
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    /**
     * 获取periodtype。
     * @return 返回处理结果。
     */
    public String getPeriodType() { return periodType; }
    public void setPeriodType(String periodType) { this.periodType = periodType; }

    /**
     * 获取periodkey。
     * @return 返回处理结果。
     */
    public String getPeriodKey() { return periodKey; }
    public void setPeriodKey(String periodKey) { this.periodKey = periodKey; }

    /**
     * 获取tokensused。
     * @return 返回处理结果。
     */
    public int getTokensUsed() { return tokensUsed; }
    public void setTokensUsed(int tokensUsed) { this.tokensUsed = tokensUsed; }

    /**
     * 获取updatedat。
     * @return 返回处理结果。
     */
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
