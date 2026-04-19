package com.travelagent.controller;

import com.travelagent.filter.JwtAuthInterceptor;
import com.travelagent.model.dto.CreateTaskRequest;
import com.travelagent.model.dto.Result;
import com.travelagent.model.dto.TaskResponse;
import com.travelagent.service.task.TaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for agent task lifecycle management.
 *
 * <p>All endpoints require a valid JWT (enforced by {@link JwtAuthInterceptor}).
 * The user's {@code userId} and {@code userLevel} are read from request attributes
 * set by the interceptor — callers never supply these directly.
 */

/**
 * 中文注释：控制器类，负责对外暴露 Task Controller 相关接口并协调请求处理流程。
 */

@Tag(name = "任务管理", description = "创建、查询、取消和恢复旅行规划任务")
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    @Autowired
    private TaskService taskService;

    // -----------------------------------------------------------------------
    // Create
    // -----------------------------------------------------------------------

    @Operation(summary = "创建旅行规划任务",
               description = "校验配额和并发限制后创建 PENDING 任务，由 TaskDispatcher 自动派发执行")
    @PostMapping
    public Result<TaskResponse> createTask(@Valid @RequestBody CreateTaskRequest request,
                                           HttpServletRequest httpRequest) {
        Long userId    = JwtAuthInterceptor.getUserId(httpRequest);
        int  userLevel = JwtAuthInterceptor.getUserLevel(httpRequest);
        return Result.success(taskService.createTask(userId, userLevel, request));
    }

    // -----------------------------------------------------------------------
    // Read
    // -----------------------------------------------------------------------

    @Operation(summary = "查询单个任务详情（含断点摘要）")
    @GetMapping("/{taskUuid}")
    public Result<TaskResponse> getTask(@PathVariable String taskUuid,
                                        HttpServletRequest httpRequest) {
        Long userId = JwtAuthInterceptor.getUserId(httpRequest);
        return Result.success(taskService.getTask(taskUuid, userId));
    }

    @Operation(summary = "查询当前用户的所有任务（最新在前）")
    @GetMapping
    public Result<List<TaskResponse>> listTasks(HttpServletRequest httpRequest) {
        Long userId = JwtAuthInterceptor.getUserId(httpRequest);
        return Result.success(taskService.listTasks(userId));
    }

    // -----------------------------------------------------------------------
    // Cancel
    // -----------------------------------------------------------------------

    @Operation(summary = "取消任务",
               description = "可取消任何非终态任务（PENDING / PLANNING / TOOL_CALLING / PAUSED / RESUMING）")
    @DeleteMapping("/{taskUuid}")
    public Result<Void> cancelTask(@PathVariable String taskUuid,
                                   HttpServletRequest httpRequest) {
        Long userId = JwtAuthInterceptor.getUserId(httpRequest);
        taskService.cancelTask(taskUuid, userId);
        return Result.success();
    }

    // -----------------------------------------------------------------------
    // Resume
    // -----------------------------------------------------------------------

    @Operation(summary = "恢复暂停的任务",
               description = "将 PAUSED 任务转为 RESUMING，由 TaskDispatcher 自动重新派发")
    @PostMapping("/{taskUuid}/resume")
    public Result<TaskResponse> resumeTask(@PathVariable String taskUuid,
                                           HttpServletRequest httpRequest) {
        Long userId = JwtAuthInterceptor.getUserId(httpRequest);
        return Result.success(taskService.resumeTask(taskUuid, userId));
    }
}
