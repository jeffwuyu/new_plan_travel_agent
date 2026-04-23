package com.travelagent.controller;

import com.travelagent.exception.BusinessException;
import com.travelagent.filter.JwtAuthInterceptor;
import com.travelagent.model.dto.ConfirmOriginSelectionRequest;
import com.travelagent.model.dto.CreateTaskRequest;
import com.travelagent.model.dto.NodeChatRequest;
import com.travelagent.model.dto.RewindTaskRequest;
import com.travelagent.model.dto.Result;
import com.travelagent.model.dto.TaskExecutionProgressResponse;
import com.travelagent.model.dto.TaskResponse;
import com.travelagent.service.task.TaskProgressService;
import com.travelagent.service.task.TaskService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    @Autowired private TaskService taskService;
    @Autowired private TaskProgressService taskProgressService;

    @PostMapping
    public Result<TaskResponse> createTask(@Valid @RequestBody CreateTaskRequest request,
                                           HttpServletRequest httpRequest) {
        Long userId = JwtAuthInterceptor.getUserId(httpRequest);
        int userLevel = JwtAuthInterceptor.getUserLevel(httpRequest);
        return Result.success(taskService.createTask(userId, userLevel, request));
    }

    @GetMapping("/{taskUuid}")
    public Result<TaskResponse> getTask(@PathVariable String taskUuid, HttpServletRequest httpRequest) {
        Long userId = JwtAuthInterceptor.getUserId(httpRequest);
        return Result.success(taskService.getTask(taskUuid, userId));
    }

    @GetMapping
    public Result<List<TaskResponse>> listTasks(HttpServletRequest httpRequest) {
        Long userId = JwtAuthInterceptor.getUserId(httpRequest);
        return Result.success(taskService.listTasks(userId));
    }

    @DeleteMapping("/{taskUuid}")
    public Result<Void> cancelTask(@PathVariable String taskUuid, HttpServletRequest httpRequest) {
        Long userId = JwtAuthInterceptor.getUserId(httpRequest);
        taskService.cancelTask(taskUuid, userId);
        return Result.success();
    }

    @PostMapping("/{taskUuid}/resume")
    public Result<TaskResponse> resumeTask(@PathVariable String taskUuid, HttpServletRequest httpRequest) {
        Long userId = JwtAuthInterceptor.getUserId(httpRequest);
        return Result.success(taskService.resumeTask(taskUuid, userId));
    }

    @PostMapping("/{taskUuid}/origin-selection")
    public Result<TaskResponse> confirmOriginSelection(@PathVariable String taskUuid,
                                                       @Valid @RequestBody ConfirmOriginSelectionRequest request,
                                                       HttpServletRequest httpRequest) {
        Long userId = JwtAuthInterceptor.getUserId(httpRequest);
        return Result.success(taskService.confirmOriginSelection(taskUuid, userId, request));
    }

    @PostMapping("/{taskUuid}/selection")
    public Result<TaskResponse> confirmTaskSelection(@PathVariable String taskUuid,
                                                     @Valid @RequestBody ConfirmOriginSelectionRequest request,
                                                     HttpServletRequest httpRequest) {
        Long userId = JwtAuthInterceptor.getUserId(httpRequest);
        return Result.success(taskService.confirmOriginSelection(taskUuid, userId, request));
    }

    @PostMapping("/{taskUuid}/rewind")
    public Result<TaskResponse> rewindTask(@PathVariable String taskUuid,
                                           @Valid @RequestBody RewindTaskRequest request,
                                           HttpServletRequest httpRequest) {
        Long userId = JwtAuthInterceptor.getUserId(httpRequest);
        return Result.success(taskService.rewindTask(taskUuid, userId, request));
    }

    @PostMapping("/{taskUuid}/node-chat")
    public Result<TaskResponse> refreshNodeSelection(@PathVariable String taskUuid,
                                                     @Valid @RequestBody NodeChatRequest request,
                                                     HttpServletRequest httpRequest) {
        Long userId = JwtAuthInterceptor.getUserId(httpRequest);
        return Result.success(taskService.refreshNodeSelection(taskUuid, userId, request));
    }

    @GetMapping("/{taskUuid}/progress")
    public Result<TaskExecutionProgressResponse> getTaskProgress(@PathVariable String taskUuid,
                                                                 @RequestParam(defaultValue = "20") int limit,
                                                                 HttpServletRequest httpRequest) {
        Long userId = JwtAuthInterceptor.getUserId(httpRequest);
        taskService.getTaskEntity(taskUuid, userId);
        if (limit < 1 || limit > 100) {
            throw new BusinessException(400, "limit must be between 1 and 100");
        }
        return Result.success(taskProgressService.getProgress(taskUuid, limit));
    }
}
