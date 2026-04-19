package com.travelagent.mapper;

import com.travelagent.model.entity.UserQuotaUsage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 用途：用户配额用量快照 Mapper 接口。
 *
 * 配合 QuotaServiceImpl 的定时快照逻辑使用：
 *   每 60 秒将 Redis 中的 daily/monthly Token 计数器 UPSERT 写入 user_quota_usage 表，
 *   以便 Redis 重启后可从 DB 重建计数器，也支持历史用量查询与计费审计。
 *
 * 对应 MyBatis XML：mapper/UserQuotaUsageMapper.xml
 */
@Mapper
public interface UserQuotaUsageMapper {

    /**
     * 插入或更新（ON DUPLICATE KEY UPDATE）配额用量快照。
     * 唯一键：(user_id, period_type, period_key)
     *
     * @param usage 快照数据，tokensUsed 为本周期截至当前的累计 Token 数
     */
    int upsert(UserQuotaUsage usage);

    /**
     * 查询指定用户在特定周期的用量记录。
     *
     * @param userId     用户 ID
     * @param periodType "daily" 或 "monthly"
     * @param periodKey  日期字符串（"2026-04-19" 或 "2026-04"）
     * @return 对应快照记录，不存在则返回 null
     */
    UserQuotaUsage findByUserAndPeriod(
            @Param("userId") Long userId,
            @Param("periodType") String periodType,
            @Param("periodKey") String periodKey
    );

    /**
     * 查询指定用户的所有用量快照（供管理员审计使用）。
     *
     * @param userId 用户 ID
     * @return 该用户全部历史快照列表，按 updatedAt 降序
     */
    List<UserQuotaUsage> findByUserId(@Param("userId") Long userId);
}
