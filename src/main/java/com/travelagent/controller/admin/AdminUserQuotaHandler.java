package com.travelagent.controller.admin;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.travelagent.exception.BusinessException;
import com.travelagent.filter.JwtAuthInterceptor;
import com.travelagent.mapper.UserMapper;
import com.travelagent.mapper.UserQuotaConfigMapper;
import com.travelagent.model.entity.User;
import com.travelagent.model.entity.UserQuotaConfig;
import com.travelagent.model.enums.AdminPermission;
import com.travelagent.service.admin.AdminTaskOpsService;
import com.travelagent.service.user.UserService;
import com.travelagent.util.RedisUtil;
import jakarta.servlet.http.HttpServletRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理后台用户与配额处理器。
 *
 * <p>该类承接用户列表、用户等级/状态变更和配额配置修改，避免这些校验与审计细节
 * 堆积在 `AdminController` 中。</p>
 */
public class AdminUserQuotaHandler {

    private final UserMapper userMapper;
    private final UserService userService;
    private final UserQuotaConfigMapper quotaConfigMapper;
    private final RedisUtil redisUtil;
    private final AdminTaskOpsService adminTaskOpsService;
    private final AdminAccessSupport accessSupport;

    /**
     * 创建用户与配额处理器。
     *
     * @param userMapper 用户数据访问对象
     * @param userService 用户服务
     * @param quotaConfigMapper 配额配置数据访问对象
     * @param redisUtil Redis 工具，用于清理配额缓存
     * @param adminTaskOpsService 可选任务运维服务，用于禁用用户时取消活跃任务
     * @param accessSupport 管理权限与审计辅助对象
     */
    public AdminUserQuotaHandler(UserMapper userMapper,
                                 UserService userService,
                                 UserQuotaConfigMapper quotaConfigMapper,
                                 RedisUtil redisUtil,
                                 AdminTaskOpsService adminTaskOpsService,
                                 AdminAccessSupport accessSupport) {
        this.userMapper = userMapper;
        this.userService = userService;
        this.quotaConfigMapper = quotaConfigMapper;
        this.redisUtil = redisUtil;
        this.adminTaskOpsService = adminTaskOpsService;
        this.accessSupport = accessSupport;
    }

    /**
     * 分页查询用户并隐藏密码哈希。
     *
     * @param page 页码，从 1 开始
     * @param size 每页数量
     * @param request 当前 HTTP 请求
     * @return 用户分页结果
     */
    public PageInfo<User> listUsers(int page, int size, HttpServletRequest request) {
        accessSupport.requirePermission(request, AdminPermission.USER_READ);
        PageHelper.startPage(page, size);
        List<User> users = userMapper.findAll();
        users.forEach(user -> user.setPasswordHash(null));
        return new PageInfo<>(users);
    }

    /**
     * 更新用户等级。
     *
     * @param userId 用户 ID
     * @param body 请求体，读取 `userLevel`
     * @param request 当前 HTTP 请求
     */
    public void updateUserLevel(Long userId, Map<String, Integer> body, HttpServletRequest request) {
        accessSupport.requirePermission(request, AdminPermission.USER_WRITE);
        Integer newLevel = body.get("userLevel");
        if (newLevel == null || newLevel < 1 || newLevel > 3) {
            throw new BusinessException(400, "userLevel must be 1, 2, or 3");
        }
        User user = userMapper.findById(userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        userService.updateUserLevel(userId, newLevel);
        accessSupport.recordAudit(request, AdminPermission.USER_WRITE, "update_user_level", "user", userId,
                Map.of("oldUserLevel", user.getUserLevel(), "newUserLevel", newLevel));
    }

    /**
     * 启用或禁用用户。
     *
     * @param userId 用户 ID
     * @param body 请求体，读取 `status`
     * @param request 当前 HTTP 请求
     */
    public void updateUserStatus(Long userId, Map<String, Integer> body, HttpServletRequest request) {
        accessSupport.requirePermission(request, AdminPermission.USER_WRITE);
        Integer status = body.get("status");
        if (status == null || (status != 0 && status != 1)) {
            throw new BusinessException(400, "status must be 1 or 0");
        }
        User user = userMapper.findById(userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }

        User update = new User();
        update.setId(userId);
        update.setStatus(status);
        userMapper.update(update);

        Integer cancelledTaskCount = null;
        if (status == 0 && adminTaskOpsService != null) {
            cancelledTaskCount = adminTaskOpsService.cancelActiveTasksForDisabledUser(
                    user, JwtAuthInterceptor.getUserId(request));
        }

        Map<String, Object> auditDetails = new LinkedHashMap<>();
        auditDetails.put("oldStatus", user.getStatus());
        auditDetails.put("newStatus", status);
        if (cancelledTaskCount != null) {
            auditDetails.put("cancelledActiveTasks", cancelledTaskCount);
        }
        accessSupport.recordAudit(request, AdminPermission.USER_WRITE,
                "update_user_status", "user", userId, auditDetails);
    }

    /**
     * 查询三个用户等级的配额配置。
     *
     * @param request 当前 HTTP 请求
     * @return REGULAR/VIP/ADMIN 三档配额配置
     */
    public Map<String, UserQuotaConfig> listQuotaConfigs(HttpServletRequest request) {
        accessSupport.requirePermission(request, AdminPermission.QUOTA_READ);
        UserQuotaConfig regularCfg = quotaConfigMapper.findByUserLevel(1);
        UserQuotaConfig vipCfg = quotaConfigMapper.findByUserLevel(2);
        UserQuotaConfig adminCfg = quotaConfigMapper.findByUserLevel(3);
        return Map.of(
                "REGULAR", regularCfg != null ? regularCfg : new UserQuotaConfig(),
                "VIP", vipCfg != null ? vipCfg : new UserQuotaConfig(),
                "ADMIN", adminCfg != null ? adminCfg : new UserQuotaConfig()
        );
    }

    /**
     * 更新指定等级的配额配置并清理缓存。
     *
     * @param level 用户等级
     * @param config 新配额配置
     * @param request 当前 HTTP 请求
     */
    public void updateQuotaConfig(int level, UserQuotaConfig config, HttpServletRequest request) {
        accessSupport.requirePermission(request, AdminPermission.QUOTA_WRITE);
        validateQuotaConfig(level, config);

        UserQuotaConfig previous = quotaConfigMapper.findByUserLevel(level);
        config.setUserLevel(level);
        quotaConfigMapper.update(config);
        redisUtil.delete("quota:config:" + level);

        Map<String, Object> auditDetails = new LinkedHashMap<>();
        auditDetails.put("previous", previous);
        auditDetails.put("updated", config);
        accessSupport.recordAudit(request, AdminPermission.QUOTA_WRITE,
                "update_quota_config", "quota_config", level, auditDetails);
    }

    /**
     * 校验配额配置的等级和正整数约束。
     *
     * @param level 用户等级
     * @param config 待保存的配额配置
     */
    private void validateQuotaConfig(int level, UserQuotaConfig config) {
        if (level < 1 || level > 3) {
            throw new BusinessException(400, "level must be 1, 2, or 3");
        }
        if (config.getDailyTokenLimit() != null && config.getDailyTokenLimit() <= 0) {
            throw new BusinessException(400, "dailyTokenLimit must be positive");
        }
        if (config.getMonthlyTokenLimit() != null && config.getMonthlyTokenLimit() <= 0) {
            throw new BusinessException(400, "monthlyTokenLimit must be positive");
        }
        if (config.getMaxConcurrentTasks() != null && config.getMaxConcurrentTasks() <= 0) {
            throw new BusinessException(400, "maxConcurrentTasks must be positive");
        }
        if (config.getMaxPlanSteps() != null && config.getMaxPlanSteps() <= 0) {
            throw new BusinessException(400, "maxPlanSteps must be positive");
        }
        if (config.getRouteMapDailyLimit() != null && config.getRouteMapDailyLimit() <= 0) {
            throw new BusinessException(400, "routeMapDailyLimit must be positive");
        }
    }
}
