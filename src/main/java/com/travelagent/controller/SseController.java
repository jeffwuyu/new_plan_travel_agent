package com.travelagent.controller;

import com.travelagent.exception.TaskNotFoundException;
import com.travelagent.filter.JwtAuthInterceptor;
import com.travelagent.mapper.TaskMapper;
import com.travelagent.model.dto.TaskExecutionProgressResponse;
import com.travelagent.model.entity.Task;
import com.travelagent.model.entity.TaskExecutionEvent;
import com.travelagent.model.enums.TaskStatus;
import com.travelagent.service.notification.SseEvent;
import com.travelagent.service.notification.SseNotificationService;
import com.travelagent.service.task.TaskProgressService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

/**
 * SSE (Server-Sent Events) endpoint for real-time task progress.
 *
 * <p>Requires a valid JWT (set by {@link JwtAuthInterceptor}). Only the task owner
 * may subscribe; other authenticated users receive 403.
 *
 * <h3>Client usage</h3>
 * <pre>
 *   const es = new EventSource('/api/tasks/{uuid}/stream');
 *   es.addEventListener('STATE_CHANGE', e => console.log(JSON.parse(e.data)));
 *   es.addEventListener('STEP_DONE',    e => console.log(JSON.parse(e.data)));
 *   es.addEventListener('COMPLETED',    e => es.close());
 *   es.addEventListener('ERROR',        e => es.close());
 * </pre>
 *
 * <h3>Terminal task behaviour</h3>
 * If the client connects after the task has already reached a terminal state,
 * a single {@code STATE_CHANGE} event is sent and the emitter is immediately
 * completed, so the client can render the final state without polling.
 */

/**
 * 中文注释：控制器类，负责对外暴露 Sse Controller 相关接口并协调请求处理流程。
 */

@Tag(name = "任务SSE推送", description = "订阅任务实时进展（Server-Sent Events）")
@RestController
@RequestMapping("/api/tasks")
public class SseController {

    @Autowired private SseNotificationService sseNotificationService;
    @Autowired private TaskMapper             taskMapper;
    @Autowired private TaskProgressService    taskProgressService;

    @Operation(summary = "订阅任务实时进展 (SSE)",
               description = "返回 text/event-stream。需要 JWT，仅任务所有者可订阅。")
    @GetMapping(value = "/{taskUuid}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<?> stream(@PathVariable String taskUuid, HttpServletRequest request) {
        Long requestUserId = JwtAuthInterceptor.getUserId(request);

        Task task = taskMapper.findByUuid(taskUuid);
        if (task == null) {
            throw new TaskNotFoundException(taskUuid);
        }

        if (!task.getUserId().equals(requestUserId)) {
            return ResponseEntity.status(403)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":403,\"message\":\"无权访问此任务的事件流\"}");
        }

        SseEmitter emitter = sseNotificationService.createEmitter(taskUuid);

        TaskStatus status = TaskStatus.fromCode(task.getStatus());
        if (status.isTerminal()) {
            // Terminal task: send current state and close immediately
            try {
                emitter.send(SseEmitter.event()
                    .name(SseEvent.STATE_CHANGE.name())
                    .data(Map.of("status", task.getStatus(), "taskUuid", taskUuid),
                          MediaType.APPLICATION_JSON));
                emitter.complete();
            } catch (IOException ignored) {
                // Client disconnected before we could send — nothing to do
            }
        } else {
            // Non-terminal task: send a PROGRESS_SNAPSHOT so the client has current state
            try {
                TaskExecutionEvent latest = taskProgressService.getLatestEvent(taskUuid);
                if (latest != null) {
                    TaskExecutionProgressResponse snapshot = taskProgressService.getProgress(taskUuid, 20);
                    emitter.send(SseEmitter.event()
                        .name(SseEvent.PROGRESS_SNAPSHOT.name())
                        .data(snapshot, MediaType.APPLICATION_JSON));
                }
            } catch (IOException ignored) {
                // Client disconnected before snapshot — no action needed
            }
        }

        return ResponseEntity.ok(emitter);
    }
}
