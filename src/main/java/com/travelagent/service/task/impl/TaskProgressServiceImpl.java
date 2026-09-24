package com.travelagent.service.task.impl;

import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.mapper.TaskExecutionEventMapper;
import com.travelagent.mapper.TaskMapper;
import com.travelagent.model.dto.SelectionPromptDto;
import com.travelagent.model.dto.TaskExecutionProgressResponse;
import com.travelagent.model.entity.Task;
import com.travelagent.model.entity.TaskExecutionEvent;
import com.travelagent.service.task.TaskProgressService;
import com.travelagent.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class TaskProgressServiceImpl implements TaskProgressService {

    private static final Logger log = LoggerFactory.getLogger(TaskProgressServiceImpl.class);

    @Autowired private TaskExecutionEventMapper eventMapper;
    @Autowired private TaskMapper taskMapper;
    @Autowired private JsonUtil jsonUtil;

    /**
     * 记录任务执行事件，用于刷新后恢复进度时间线和管理后台排障。
     *
     * @param taskUuid 任务唯一标识
     * @param eventType 事件类型
     * @param status 事件发生时的任务状态
     * @param stepIndex 当前步骤序号
     * @param totalSteps 总步骤数
     * @param message 提示信息
     * @param detailsPayload 事件详情载荷
     */
    @Override
    public void recordEvent(String taskUuid, String eventType, String status,
                            Integer stepIndex, Integer totalSteps,
                            String message, Object detailsPayload) {
        try {
            TaskExecutionEvent event = new TaskExecutionEvent();
            event.setTaskUuid(taskUuid);
            event.setEventType(eventType);
            event.setStatus(status);
            event.setStepIndex(stepIndex);
            event.setTotalSteps(totalSteps);
            if (message != null && message.length() > 512) {
                message = message.substring(0, 509) + "...";
            }
            event.setMessage(message);
            if (detailsPayload != null) {
                event.setDetailsJson(jsonUtil.toJson(detailsPayload));
            }
            eventMapper.insert(event);
        } catch (Exception e) {
            log.warn("[TaskProgressService] Failed to record event type={} for task={}: {}",
                    eventType, taskUuid, e.getMessage());
        }
    }

    /**
     * 查询任务进度快照，合并持久化事件和当前 checkpoint 派生状态。
     *
     * @param taskUuid 任务唯一标识
     * @param limit 返回数量上限
     * @return 任务进度响应
     */
    @Override
    public TaskExecutionProgressResponse getProgress(String taskUuid, int limit) {
        TaskExecutionProgressResponse resp = new TaskExecutionProgressResponse();
        resp.setTaskUuid(taskUuid);

        Task task = taskMapper.findByUuid(taskUuid);
        TaskCheckpoint checkpoint = parseCheckpoint(task);
        if (task != null) {
            resp.setCurrentStatus(task.getStatus());
            resp.setTotalTokensUsed(task.getTotalTokensUsed());
        }

        int total = eventMapper.countByTaskUuid(taskUuid);
        List<TaskExecutionEvent> events = eventMapper.findByTaskUuid(taskUuid, limit);
        resp.setEvents(events);
        resp.setTotalEventCount(total);

        if (checkpoint != null) {
            resp.setCurrentStepIndex(checkpoint.getCurrentStepIndex());
            resp.setTotalSteps(checkpoint.totalPlannedSteps());
            resp.setPendingInputType(checkpoint.getPendingInputType());
            resp.setSelectionStage(checkpoint.getSelectionStage());
            resp.setSelectedBranchType(checkpoint.getSelectedBranchType());
            resp.setAwaitingUserInput(checkpoint.getPendingInputType() != null && !checkpoint.getPendingInputType().isBlank());
            resp.setPauseReason(checkpoint.getPauseReason());
            resp.setLocationCandidates(checkpoint.getLocationCandidates());
            resp.setSelectionOptions(checkpoint.getSelectionOptions());
            resp.setRecommendationCandidates(checkpoint.getRecommendationCandidates());
            resp.setSelectionPrompt(SelectionPromptDto.from(
                    checkpoint.getPendingInputType(),
                    checkpoint.getSelectionStage(),
                    checkpoint.getSelectedBranchType(),
                    checkpoint.getStartLocationQuery(),
                    checkpoint.getCurrentStepIndex(),
                    null,
                    checkpoint.getCurrentContext(),
                    checkpoint.getWeatherContext()));
            resp.setCurrentContext(checkpoint.getCurrentContext() == null ? Map.of() : checkpoint.getCurrentContext());
            resp.setWeatherContext(checkpoint.getWeatherContext() == null ? Map.of() : checkpoint.getWeatherContext());
            resp.setSelectedOrigin(checkpoint.getSelectedOrigin());
            resp.setSelectedDestination(checkpoint.getSelectedDestination());
            resp.setSessionState(checkpoint.toSessionState());
        } else {
            events.stream()
                    .filter(e -> e.getStepIndex() != null)
                    .reduce((a, b) -> b)
                    .ifPresent(e -> {
                        resp.setCurrentStepIndex(e.getStepIndex());
                        resp.setTotalSteps(e.getTotalSteps());
                    });
        }

        return resp;
    }

    /**
     * 查询任务最近一条执行事件。
     *
     * @param taskUuid 任务唯一标识
     * @return 最近事件，缺失时返回 null
     */
    @Override
    public TaskExecutionEvent getLatestEvent(String taskUuid) {
        return eventMapper.findLatestByTaskUuid(taskUuid);
    }

    /**
     * 解析任务 checkpoint 并执行 schema 迁移。
     *
     * @param task 任务实体
     * @return 当前 checkpoint，缺失或解析失败时返回 null
     */
    private TaskCheckpoint parseCheckpoint(Task task) {
        if (task == null || task.getCheckpointJson() == null || task.getCheckpointJson().isBlank()) {
            return null;
        }
        try {
            TaskCheckpoint checkpoint = jsonUtil.fromJson(task.getCheckpointJson(), TaskCheckpoint.class);
            checkpoint.migrateToCurrentSchema();
            return checkpoint;
        } catch (Exception e) {
            log.warn("[TaskProgressService] Failed to parse checkpoint for task={}: {}", task.getTaskUuid(), e.getMessage());
            return null;
        }
    }
}
