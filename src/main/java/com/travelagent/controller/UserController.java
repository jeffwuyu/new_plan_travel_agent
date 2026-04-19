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

    @Operation(summary ="获取当前用户配额使用情况")
    @GetMapping("/quota")
    public Result<Map<String, Object>> quota(HttpServletRequest request) {
        Long userId = JwtAuthInterceptor.getUserId(request);
        int userLevel = JwtAuthInterceptor.getUserLevel(request);

        var config = quotaService.getQuotaConfig(userLevel);
        long dailyUsed   = quotaService.getDailyUsage(userId);
        long monthlyUsed = quotaService.getMonthlyUsage(userId);

        Map<String, Object> data = new HashMap<>();
        data.put("dailyUsed",      dailyUsed);
        data.put("dailyLimit",     config != null ? config.getDailyTokenLimit()   : 0);
        data.put("monthlyUsed",    monthlyUsed);
        data.put("monthlyLimit",   config != null ? config.getMonthlyTokenLimit() : 0);
        data.put("maxConcurrentTasks", config != null ? config.getMaxConcurrentTasks() : 0);
        data.put("maxPlanSteps",   config != null ? config.getMaxPlanSteps() : 0);
        return Result.success(data);
    }
}
