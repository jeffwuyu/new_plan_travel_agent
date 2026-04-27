package com.travelagent.service.task.impl;

import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.mapper.TaskExecutionEventMapper;
import com.travelagent.mapper.TaskMapper;
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
     * 处理recordEvent。
     * @param taskUuid 任务唯一标识
     * @param eventType e ve nt Ty pe 参数
     * @param status 状态值
     * @param stepIndex s te pI nd ex 参数
     * @param totalSteps t ot al St ep s 参数
     * @param message 提示信息
     * @param detailsPayload d et ai ls Pa yl oa d 参数
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
     * 获取progress。
     * @param taskUuid 任务唯一标识
     * @param limit 返回数量上限
     * @return 返回处理结果。
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
            resp.setCurrentContext(checkpoint.getCurrentContext() == null ? Map.of() : checkpoint.getCurrentContext());
            resp.setWeatherContext(checkpoint.getWeatherContext() == null ? Map.of() : checkpoint.getWeatherContext());
            resp.setSelectedOrigin(checkpoint.getSelectedOrigin());
            resp.setSelectedDestination(checkpoint.getSelectedDestination());
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
     * 获取latestevent。
     * @param taskUuid 任务唯一标识
     * @return 返回处理结果。
     */
    @Override
    public TaskExecutionEvent getLatestEvent(String taskUuid) {
        return eventMapper.findLatestByTaskUuid(taskUuid);
    }

    /**
     * 解析checkpoint。
     * @param task 任务实体
     * @return 返回处理结果。
     */
    private TaskCheckpoint parseCheckpoint(Task task) {
        if (task == null || task.getCheckpointJson() == null || task.getCheckpointJson().isBlank()) {
            return null;
        }
        try {
            return jsonUtil.fromJson(task.getCheckpointJson(), TaskCheckpoint.class);
        } catch (Exception e) {
            log.warn("[TaskProgressService] Failed to parse checkpoint for task={}: {}", task.getTaskUuid(), e.getMessage());
            return null;
        }
    }
}
