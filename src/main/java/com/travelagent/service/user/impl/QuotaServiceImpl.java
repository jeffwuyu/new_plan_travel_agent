package com.travelagent.service.user.impl;

import com.travelagent.exception.QuotaExhaustedException;
import com.travelagent.mapper.UserMapper;
import com.travelagent.mapper.UserQuotaConfigMapper;
import com.travelagent.mapper.UserQuotaUsageMapper;
import com.travelagent.model.entity.UserQuotaConfig;
import com.travelagent.model.entity.UserQuotaUsage;
import com.travelagent.service.user.QuotaService;
import com.travelagent.util.RedisUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 中文注释：服务实现类，负责承载 Quota Service Impl 对应的核心业务逻辑。
 */

@Service
public class QuotaServiceImpl implements QuotaService {

    private static final Logger log = LoggerFactory.getLogger(QuotaServiceImpl.class);

    private static final String DAILY_KEY_PREFIX   = "quota:user:%d:daily:%s";
    private static final String MONTHLY_KEY_PREFIX = "quota:user:%d:monthly:%s";
    private static final String CONFIG_KEY_PREFIX  = "quota:config:%d";

    @Autowired
    private RedisUtil redisUtil;

    @Autowired
    private UserQuotaConfigMapper quotaConfigMapper;

    @Autowired
    private UserQuotaUsageMapper quotaUsageMapper;

    @Autowired
    private UserMapper userMapper;

    @Override
    public UserQuotaConfig getQuotaConfig(int userLevel) {
        String cacheKey = String.format(CONFIG_KEY_PREFIX, userLevel);
        Object cached = redisUtil.get(cacheKey);
        if (cached instanceof UserQuotaConfig) {
            return (UserQuotaConfig) cached;
        }
        UserQuotaConfig config = quotaConfigMapper.findByUserLevel(userLevel);
        if (config != null) {
            redisUtil.set(cacheKey, config, Duration.ofMinutes(10));
        }
        return config;
    }

    @Override
    public void checkDailyQuota(Long userId, int userLevel) {
        UserQuotaConfig config = getQuotaConfig(userLevel);
        if (config == null) return; // No config = no limit (shouldn't happen)

        String key = dailyKey(userId);
        boolean available = redisUtil.checkQuota(key, config.getDailyTokenLimit());
        if (!available) {
            log.warn("Daily quota exhausted for userId={}", userId);
            throw new QuotaExhaustedException("daily");
        }
    }

    @Override
    public long debitTokens(Long userId, int userLevel, int tokens) {
        UserQuotaConfig config = getQuotaConfig(userLevel);
        long dailyLimit   = config != null ? config.getDailyTokenLimit()   : Long.MAX_VALUE;
        long monthlyLimit = config != null ? config.getMonthlyTokenLimit() : Long.MAX_VALUE;

        String dailyKey   = dailyKey(userId);
        String monthlyKey = monthlyKey(userId);

        // Debit daily counter (TTL 48hr to cover timezone edge cases)
        Long newDaily = redisUtil.incrementWithTtl(dailyKey, tokens, Duration.ofHours(48));
        // Debit monthly counter (TTL 35 days)
        redisUtil.incrementWithTtl(monthlyKey, tokens, Duration.ofDays(35));

        if (newDaily != null && newDaily > dailyLimit) {
            log.warn("Daily quota exceeded post-debit for userId={}, newDaily={}, limit={}",
                userId, newDaily, dailyLimit);
            throw new QuotaExhaustedException("daily");
        }

        return newDaily != null ? newDaily : 0;
    }

    @Override
    public long getDailyUsage(Long userId) {
        String val = redisUtil.getString(dailyKey(userId));
        return val != null ? Long.parseLong(val) : 0;
    }

    @Override
    public long getMonthlyUsage(Long userId) {
        String val = redisUtil.getString(monthlyKey(userId));
        return val != null ? Long.parseLong(val) : 0;
    }

    /**
     * 将单个用户的 Redis 配额计数器快照到 MySQL。
     *
     * 逻辑：
     *   1. 从 Redis 读取今日（daily）和本月（monthly）的已用 Token 数；
     *   2. 以 UPSERT（ON DUPLICATE KEY UPDATE）写入 user_quota_usage 表；
     *   3. Redis 重启后，可由此表重建计数器，防止配额数据丢失。
     *
     * 注：若 Redis 中无对应 key（用量为 0），仍会写入 0，避免恢复时误判。
     */
    @Override
    public void snapshotToDatabase(Long userId) {
        try {
            // --- 每日快照 ---
            String dailyPeriodKey = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
            long dailyUsed = getDailyUsage(userId);
            quotaUsageMapper.upsert(new UserQuotaUsage(userId, "daily", dailyPeriodKey, (int) dailyUsed));

            // --- 每月快照 ---
            String monthlyPeriodKey = YearMonth.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
            long monthlyUsed = getMonthlyUsage(userId);
            quotaUsageMapper.upsert(new UserQuotaUsage(userId, "monthly", monthlyPeriodKey, (int) monthlyUsed));

            log.debug("[QuotaSnapshot] userId={} daily={} monthly={}", userId, dailyUsed, monthlyUsed);
        } catch (Exception e) {
            // 快照失败不影响主流程，仅记录告警日志
            log.warn("[QuotaSnapshot] Failed to snapshot userId={}: {}", userId, e.getMessage());
        }
    }

    /**
     * 定时任务：每 60 秒将所有活跃用户的配额计数器批量快照到 MySQL。
     *
     * 触发条件：主应用已标注 @EnableScheduling（TravelAgentApplication）。
     * 实现步骤：
     *   1. 从 DB 查出所有未删除、状态正常的用户 ID 列表；
     *   2. 逐个调用 snapshotToDatabase(userId) 写入快照；
     *   3. 任何单用户快照失败不影响其他用户（异常在内层捕获）。
     *
     * 延迟策略：fixedDelay=60000ms，每次执行结束后 60 秒再触发下次，
     * 避免上一次执行超时导致下一次立即重叠。
     */
    @Override
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void snapshotAllActiveUsers() {
        try {
            List<Long> activeUserIds = userMapper.findAllActiveIds();
            if (activeUserIds == null || activeUserIds.isEmpty()) {
                return;
            }
            log.debug("[QuotaSnapshot] Snapshotting quota for {} active users", activeUserIds.size());
            for (Long userId : activeUserIds) {
                snapshotToDatabase(userId);
            }
        } catch (Exception e) {
            log.warn("[QuotaSnapshot] Batch snapshot failed: {}", e.getMessage());
        }
    }

    private String dailyKey(Long userId) {
        String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        return String.format(DAILY_KEY_PREFIX, userId, today);
    }

    private String monthlyKey(Long userId) {
        String month = YearMonth.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        return String.format(MONTHLY_KEY_PREFIX, userId, month);
    }
}
