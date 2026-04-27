package com.travelagent.controller;

import com.travelagent.filter.JwtAuthInterceptor;
import com.travelagent.model.dto.Result;
import com.travelagent.model.dto.UserProfileResponse;
import com.travelagent.model.entity.User;
import com.travelagent.model.enums.UserLevel;
import com.travelagent.service.user.QuotaService;
import com.travelagent.service.user.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

/**
 * 中文注释：控制器类，负责对外暴露 User Controller 相关接口并协调请求处理流程。
 */

@Tag(name = "用户信息")
@RestController
@RequestMapping("/api/user")
public class UserController {

    @Autowired
    private UserService userService;

    @Autowired
    private QuotaService quotaService;

    /**
     * 处理profile。
     * @param request 请求参数
     * @return 返回统一封装后的响应结果。
     */
    @Operation(summary ="获取当前用户信息")
    @GetMapping("/profile")
    public Result<UserProfileResponse> profile(HttpServletRequest request) {
        Long userId = JwtAuthInterceptor.getUserId(request);
        User user = userService.findById(userId);
        if (user == null) {
            return Result.notFound("用户不存在");
        }
        UserProfileResponse resp = new UserProfileResponse();
        resp.setId(user.getId());
        resp.setUsername(user.getUsername());
        resp.setEmail(user.getEmail());
        resp.setUserLevel(user.getUserLevel());
        resp.setUserLevelLabel(UserLevel.fromCode(user.getUserLevel()).getLabel());
        resp.setCreatedAt(user.getCreatedAt());
        return Result.success(resp);
    }

    /**
     * 处理quota。
     * @param request 请求参数
     * @return 返回统一封装后的响应结果。
     */
    @Operation(summary ="获取当前用户配额使用情况")
    @GetMapping("/quota")
    public Result<Map<String, Object>> quota(HttpServletRequest request) {
        Long userId = JwtAuthInterceptor.getUserId(request);
        int userLevel = JwtAuthInterceptor.getUserLevel(request);

        var config = quotaService.getQuotaConfig(userLevel);
        long dailyUsed   = quotaService.getDailyUsage(userId);
        long monthlyUsed = quotaService.getMonthlyUsage(userId);
        long dailyLimit = config != null && config.getDailyTokenLimit() != null ? config.getDailyTokenLimit() : 0L;
        long monthlyLimit = config != null && config.getMonthlyTokenLimit() != null ? config.getMonthlyTokenLimit() : 0L;

        Map<String, Object> data = new HashMap<>();
        data.put("dailyUsed",      dailyUsed);
        data.put("dailyLimit",     dailyLimit);
        data.put("dailyRemaining", remaining(dailyLimit, dailyUsed));
        data.put("monthlyUsed",    monthlyUsed);
        data.put("monthlyLimit",   monthlyLimit);
        data.put("monthlyRemaining", remaining(monthlyLimit, monthlyUsed));
        data.put("userLevel", userLevel);
        data.put("userLevelLabel", UserLevel.fromCode(userLevel).getLabel());
        data.put("maxConcurrentTasks", config != null ? config.getMaxConcurrentTasks() : 0);
        data.put("maxPlanSteps",   config != null ? config.getMaxPlanSteps() : 0);
        return Result.success(data);
    }

    /**
     * 处理remaining。
     * @param limit 返回数量上限
     * @param used u se d 参数
     * @return 返回处理结果。
     */
    private long remaining(long limit, long used) {
        if (limit <= 0) {
            return 0;
        }
        return Math.max(limit - used, 0);
    }
}
