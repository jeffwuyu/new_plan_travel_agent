package com.travelagent.service.user;

import com.travelagent.model.entity.UserQuotaConfig;

/**
 * Manages token quota checks and debits for LLM calls.
 * Redis is the primary store; MySQL is a periodic snapshot.
 */

/**
 * 中文注释：服务接口，定义 Quota Service 相关业务能力。
 */

public interface QuotaService {

    /** Get the quota configuration for a given user level. */
    UserQuotaConfig getQuotaConfig(int userLevel);

    /**
     * Check if the user has sufficient daily quota.
     * @throws com.travelagent.exception.QuotaExhaustedException if exhausted
     */
    void checkDailyQuota(Long userId, int userLevel);

    /**
     * Debit tokens after a successful LLM call.
     * Returns the new total daily usage.
     */
    long debitTokens(Long userId, int userLevel, int tokens);

    /** Get the user's token usage for today. */
    long getDailyUsage(Long userId);

    /** Get the user's token usage for this month. */
    long getMonthlyUsage(Long userId);

    /** Snapshot Redis quota counters to MySQL (called by scheduled task). */
    void snapshotToDatabase(Long userId);

    /**
     * 批量快照所有活跃用户的配额用量到 MySQL。
     * 由 @Scheduled 每 60 秒触发一次，在 QuotaServiceImpl 内部实现。
     * Redis 重启后可依据此表中的数据重建计数器。
     */
    void snapshotAllActiveUsers();
}
