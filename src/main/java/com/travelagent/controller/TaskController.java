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

    /**
     * 创建task。
     * @param request 请求参数
     * @param httpRequest HTTP请求对象
     * @return 返回统一封装后的响应结果。
     */
    @PostMapping
    public Result<TaskResponse> createTask(@Valid @RequestBody CreateTaskRequest request,
                                           HttpServletRequest httpRequest) {
        Long userId = JwtAuthInterceptor.getUserId(httpRequest);
        int userLevel = JwtAuthInterceptor.getUserLevel(httpRequest);
        return Result.success(taskService.createTask(userId, userLevel, request));
    }

    /**
     * 获取task。
     * @param taskUuid 任务唯一标识
     * @param httpRequest HTTP请求对象
     * @return 返回统一封装后的响应结果。
     */
    @GetMapping("/{taskUuid}")
    public Result<TaskResponse> getTask(@PathVariable String taskUuid, HttpServletRequest httpRequest) {
        Long userId = JwtAuthInterceptor.getUserId(httpRequest);
        return Result.success(taskService.getTask(taskUuid, userId));
    }

    /**
     * 获取。
     * @param httpRequest HTTP请求对象
     * @return 返回统一封装后的响应结果。
     */
    @GetMapping
    public Result<List<TaskResponse>> listTasks(HttpServletRequest httpRequest) {
        Long userId = JwtAuthInterceptor.getUserId(httpRequest);
        return Result.success(taskService.listTasks(userId));
    }

    /**
     * 取消task。
     * @param taskUuid 任务唯一标识
     * @param httpRequest HTTP请求对象
     * @return 返回统一封装后的响应结果。
     */
    @DeleteMapping("/{taskUuid}")
    public Result<Void> cancelTask(@PathVariable String taskUuid, HttpServletRequest httpRequest) {
        Long userId = JwtAuthInterceptor.getUserId(httpRequest);
        taskService.cancelTask(taskUuid, userId);
        return Result.success();
    }

    /**
     * 恢复task。
     * @param taskUuid 任务唯一标识
     * @param httpRequest HTTP请求对象
     * @return 返回统一封装后的响应结果。
     */
    @PostMapping("/{taskUuid}/resume")
    public Result<TaskResponse> resumeTask(@PathVariable String taskUuid, HttpServletRequest httpRequest) {
        Long userId = JwtAuthInterceptor.getUserId(httpRequest);
        return Result.success(taskService.resumeTask(taskUuid, userId));
    }

    /**
     * 确认originselection。
     * @param taskUuid 任务唯一标识
     * @param request 请求参数
     * @param httpRequest HTTP请求对象
     * @return 返回统一封装后的响应结果。
     */
    @PostMapping("/{taskUuid}/origin-selection")
    public Result<TaskResponse> confirmOriginSelection(@PathVariable String taskUuid,
                                                       @Valid @RequestBody ConfirmOriginSelectionRequest request,
                                                       HttpServletRequest httpRequest) {
        Long userId = JwtAuthInterceptor.getUserId(httpRequest);
        return Result.success(taskService.confirmOriginSelection(taskUuid, userId, request));
    }

    /**
     * 确认taskselection。
     * @param taskUuid 任务唯一标识
     * @param request 请求参数
     * @param httpRequest HTTP请求对象
     * @return 返回统一封装后的响应结果。
     */
    @PostMapping("/{taskUuid}/selection")
    public Result<TaskResponse> confirmTaskSelection(@PathVariable String taskUuid,
                                                     @Valid @RequestBody ConfirmOriginSelectionRequest request,
                                                     HttpServletRequest httpRequest) {
        Long userId = JwtAuthInterceptor.getUserId(httpRequest);
        return Result.success(taskService.confirmOriginSelection(taskUuid, userId, request));
    }

    /**
     * 回退task。
     * @param taskUuid 任务唯一标识
     * @param request 请求参数
     * @param httpRequest HTTP请求对象
     * @return 返回统一封装后的响应结果。
     */
    @PostMapping("/{taskUuid}/rewind")
    public Result<TaskResponse> rewindTask(@PathVariable String taskUuid,
                                           @Valid @RequestBody RewindTaskRequest request,
                                           HttpServletRequest httpRequest) {
        Long userId = JwtAuthInterceptor.getUserId(httpRequest);
        return Result.success(taskService.rewindTask(taskUuid, userId, request));
    }

    /**
     * 刷新nodeselection。
     * @param taskUuid 任务唯一标识
     * @param request 请求参数
     * @param httpRequest HTTP请求对象
     * @return 返回统一封装后的响应结果。
     */
    @PostMapping("/{taskUuid}/node-chat")
    public Result<TaskResponse> refreshNodeSelection(@PathVariable String taskUuid,
                                                     @Valid @RequestBody NodeChatRequest request,
                                                     HttpServletRequest httpRequest) {
        Long userId = JwtAuthInterceptor.getUserId(httpRequest);
        return Result.success(taskService.refreshNodeSelection(taskUuid, userId, request));
    }

    /**
     * 获取taskprogress。
     * @param taskUuid 任务唯一标识
     * @param limit 返回数量上限
     * @param httpRequest HTTP请求对象
     * @return 返回统一封装后的响应结果。
     */
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
