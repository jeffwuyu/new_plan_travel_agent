package com.travelagent.service.task.impl;

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

@Service
public class TaskProgressServiceImpl implements TaskProgressService {

    private static final Logger log = LoggerFactory.getLogger(TaskProgressServiceImpl.class);

    @Autowired private TaskExecutionEventMapper eventMapper;
    @Autowired private TaskMapper               taskMapper;
    @Autowired private JsonUtil                 jsonUtil;

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

    @Override
    public TaskExecutionProgressResponse getProgress(String taskUuid, int limit) {
        TaskExecutionProgressResponse resp = new TaskExecutionProgressResponse();
        resp.setTaskUuid(taskUuid);

        Task task = taskMapper.findByUuid(taskUuid);
        if (task != null) {
            resp.setCurrentStatus(task.getStatus());
        }

        int total = eventMapper.countByTaskUuid(taskUuid);
        List<TaskExecutionEvent> events = eventMapper.findByTaskUuid(taskUuid, limit);
        resp.setEvents(events);
        resp.setTotalEventCount(total);

        // Derive currentStepIndex / totalSteps from most recent event that has them
        events.stream()
                .filter(e -> e.getStepIndex() != null)
                .reduce((a, b) -> b)
                .ifPresent(e -> {
                    resp.setCurrentStepIndex(e.getStepIndex());
                    resp.setTotalSteps(e.getTotalSteps());
                });

        return resp;
    }

    @Override
    public TaskExecutionEvent getLatestEvent(String taskUuid) {
        return eventMapper.findLatestByTaskUuid(taskUuid);
    }
}
