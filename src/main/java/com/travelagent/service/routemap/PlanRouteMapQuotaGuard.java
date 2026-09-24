package com.travelagent.service.routemap;

import com.travelagent.exception.BusinessException;
import com.travelagent.mapper.PlanRouteMapMapper;
import com.travelagent.mapper.UserMapper;
import com.travelagent.mapper.UserQuotaConfigMapper;
import com.travelagent.model.entity.Plan;
import com.travelagent.model.entity.User;
import com.travelagent.model.entity.UserQuotaConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 路线图生成配额守卫，集中计算用户等级对应的每日生成上限。
 */
@Component
public class PlanRouteMapQuotaGuard {

    private final PlanRouteMapMapper routeMapMapper;
    private final UserMapper userMapper;
    private final UserQuotaConfigMapper quotaConfigMapper;

    @Value("${route-map.daily-generate-limit-per-user:20}")
    private int dailyGenerateLimitPerUser;

    /**
     * 创建路线图配额守卫。
     *
     * @param routeMapMapper 路线图记录数据访问器
     * @param userMapper 用户数据访问器
     * @param quotaConfigMapper 用户等级配额数据访问器
     */
    public PlanRouteMapQuotaGuard(PlanRouteMapMapper routeMapMapper,
                                  UserMapper userMapper,
                                  UserQuotaConfigMapper quotaConfigMapper) {
        this.routeMapMapper = routeMapMapper;
        this.userMapper = userMapper;
        this.quotaConfigMapper = quotaConfigMapper;
    }

    /**
     * 校验计划所属用户当天是否仍有路线图生成配额。
     *
     * @param plan 计划实体
     */
    public void assertDailyGenerateQuota(Plan plan) {
        int effectiveLimit = effectiveRouteMapDailyLimit(plan);
        if (effectiveLimit <= 0) {
            return;
        }
        LocalDate today = LocalDate.now();
        LocalDateTime start = today.atStartOfDay();
        LocalDateTime end = today.plusDays(1).atStartOfDay();
        int createdToday = routeMapMapper.countCreatedByUserBetween(plan.getUserId(), start, end);
        if (createdToday >= effectiveLimit) {
            throw new BusinessException(429, "ROUTE_MAP_DAILY_LIMIT_EXCEEDED");
        }
    }

    /**
     * 解析用户等级配置中的路线图每日生成上限，缺省时回退到系统默认值。
     *
     * @param plan 计划实体
     * @return 生效的每日生成上限
     */
    int effectiveRouteMapDailyLimit(Plan plan) {
        if (plan == null || plan.getUserId() == null) {
            return dailyGenerateLimitPerUser;
        }
        User user = userMapper.findById(plan.getUserId());
        if (user == null || user.getUserLevel() == null) {
            return dailyGenerateLimitPerUser;
        }
        UserQuotaConfig config = quotaConfigMapper.findByUserLevel(user.getUserLevel());
        if (config == null || config.getRouteMapDailyLimit() == null) {
            return dailyGenerateLimitPerUser;
        }
        return config.getRouteMapDailyLimit();
    }
}
