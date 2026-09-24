package com.travelagent.service.task.impl;

import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.model.dto.TaskResponse;
import com.travelagent.model.entity.Task;
import com.travelagent.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 任务 checkpoint 编解码与响应组装辅助类。
 *
 * <p>该组件把 checkpoint JSON 解析、序列化和 `TaskResponse` 组装从
 * `TaskServiceImpl` 中拆出，减少任务生命周期服务中的重复胶水代码。</p>
 */
@Component
public class TaskCheckpointCodec {

    private static final Logger log = LoggerFactory.getLogger(TaskCheckpointCodec.class);

    private final JsonUtil jsonUtil;

    /**
     * 创建 checkpoint 编解码辅助类。
     *
     * @param jsonUtil JSON 工具
     */
    public TaskCheckpointCodec(JsonUtil jsonUtil) {
        this.jsonUtil = jsonUtil;
    }

    /**
     * 从任务实体解析 checkpoint。
     *
     * @param task 任务实体
     * @return checkpoint 对象，缺失或解析失败时返回 null
     */
    public TaskCheckpoint parse(Task task) {
        if (task.getCheckpointJson() == null || task.getCheckpointJson().isBlank()) {
            return null;
        }
        try {
            return jsonUtil.fromJson(task.getCheckpointJson(), TaskCheckpoint.class);
        } catch (Exception e) {
            log.warn("Failed to parse checkpoint for task={}: {}", task.getTaskUuid(), e.getMessage());
            return null;
        }
    }

    /**
     * 将 checkpoint 序列化为 JSON。
     *
     * @param checkpoint checkpoint 对象
     * @return JSON 字符串
     */
    public String toJson(TaskCheckpoint checkpoint) {
        return jsonUtil.toJson(checkpoint);
    }

    /**
     * 组装任务响应。
     *
     * @param task 任务实体
     * @return 任务响应 DTO
     */
    public TaskResponse toResponse(Task task) {
        return TaskResponse.from(task, parse(task));
    }

    /**
     * 使用已解析的 checkpoint 组装任务响应。
     *
     * @param task 任务实体
     * @param checkpoint 已解析 checkpoint
     * @return 任务响应 DTO
     */
    public TaskResponse toResponse(Task task, TaskCheckpoint checkpoint) {
        return TaskResponse.from(task, checkpoint);
    }
}
