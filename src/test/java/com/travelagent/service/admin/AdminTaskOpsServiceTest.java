package com.travelagent.service.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.agent.planner.TaskExecutionDispatcher;
import com.travelagent.exception.BusinessException;
import com.travelagent.mapper.TaskMapper;
import com.travelagent.model.entity.Task;
import com.travelagent.model.enums.TaskStatus;
import com.travelagent.service.notification.SseEvent;
import com.travelagent.service.notification.SseNotificationService;
import com.travelagent.service.task.RedisTaskLockService;
import com.travelagent.service.task.TaskDispatchQueueService;
import com.travelagent.service.task.TaskLifecycleGovernanceService;
import com.travelagent.service.task.TaskProgressService;
import com.travelagent.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminTaskOpsServiceTest {

    @Mock private TaskMapper taskMapper;
    @Mock private TaskProgressService taskProgressService;
    @Mock private SseNotificationService sseNotificationService;
    @Mock private RedisTaskLockService redisTaskLockService;
    @Mock private TaskDispatchQueueService taskDispatchQueueService;
    @Mock private TaskExecutionDispatcher taskExecutionDispatcher;
    @Mock private TaskLifecycleGovernanceService taskLifecycleGovernanceService;

    private AdminTaskOpsService service;
    private JsonUtil jsonUtil;

    @BeforeEach
    void setUp() {
        jsonUtil = new JsonUtil();
        ReflectionTestUtils.setField(jsonUtil, "objectMapper", new ObjectMapper().findAndRegisterModules());
        service = new AdminTaskOpsService(taskMapper, taskProgressService, sseNotificationService, jsonUtil,
                redisTaskLockService, taskDispatchQueueService, taskExecutionDispatcher, taskLifecycleGovernanceService);
    }

    @Test
    void redispatchTask_failedTask_resetsToResumingAndDispatches() {
        Task task = buildTask(TaskStatus.FAILED);
        task.setErrorMessage("Task exceeded max recovery attempts");
        TaskCheckpoint checkpoint = new TaskCheckpoint();
        checkpoint.setCurrentState(TaskStatus.FAILED.getCode());
        checkpoint.setPauseReason("recovery_exhausted");
        checkpoint.setTaskUuid(task.getTaskUuid());
        task.setCheckpointJson(jsonUtil.toJson(checkpoint));

        when(taskMapper.findByUuid(task.getTaskUuid())).thenReturn(task);
        when(taskExecutionDispatcher.dispatchTask(task.getTaskUuid(), "admin_redispatch")).thenReturn(true);

        Map<String, Object> result = service.redispatchTask(task.getTaskUuid(), 99L, "manual fix");

        assertThat(result)
                .containsEntry("taskUuid", task.getTaskUuid())
                .containsEntry("status", TaskStatus.RESUMING.getCode())
                .containsEntry("previousStatus", TaskStatus.FAILED.getCode())
                .containsEntry("reason", "manual fix")
                .containsEntry("dispatched", true);

        ArgumentCaptor<String> checkpointCaptor = ArgumentCaptor.forClass(String.class);
        verify(taskMapper).resetForAdminRedispatch(eq(task.getId()), eq(TaskStatus.RESUMING.getCode()),
                checkpointCaptor.capture());
        TaskCheckpoint saved = jsonUtil.fromJson(checkpointCaptor.getValue(), TaskCheckpoint.class);
        assertThat(saved.getCurrentState()).isEqualTo(TaskStatus.RESUMING.getCode());
        assertThat(saved.getPauseReason()).isNull();

        verify(taskProgressService).recordEvent(eq(task.getTaskUuid()), eq("ADMIN_REDISPATCH"),
                eq(TaskStatus.RESUMING.getCode()), any(), any(), eq("Task redispatched by admin"), any());
        verify(sseNotificationService).sendEvent(eq(task.getTaskUuid()), eq(SseEvent.STATE_CHANGE), any());
        verify(taskExecutionDispatcher).dispatchTask(task.getTaskUuid(), "admin_redispatch");
    }

    @Test
    void redispatchTask_planningTask_isRejected() {
        Task task = buildTask(TaskStatus.PLANNING);
        when(taskMapper.findByUuid(task.getTaskUuid())).thenReturn(task);

        assertThatThrownBy(() -> service.redispatchTask(task.getTaskUuid(), 99L, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("only failed or paused tasks can be redispatched");

        verify(taskMapper, never()).resetForAdminRedispatch(anyLong(), anyString(), any());
        verify(taskExecutionDispatcher, never()).dispatchTask(anyString(), anyString());
    }

    @Test
    void redispatchTask_dispatcherFails_recordsFailureEvent() {
        Task task = buildTask(TaskStatus.PAUSED);
        when(taskMapper.findByUuid(task.getTaskUuid())).thenReturn(task);
        when(taskExecutionDispatcher.dispatchTask(task.getTaskUuid(), "admin_redispatch"))
                .thenThrow(new IllegalStateException("queue unavailable"));

        assertThatThrownBy(() -> service.redispatchTask(task.getTaskUuid(), 99L, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("queue unavailable");

        verify(taskProgressService).recordEvent(eq(task.getTaskUuid()), eq("ADMIN_REDISPATCH_FAILED"),
                eq(TaskStatus.RESUMING.getCode()), any(), any(),
                contains("queue unavailable"), any());
    }

    @Test
    void runLifecycleScan_delegatesToGovernanceService() {
        when(taskLifecycleGovernanceService.scanAndApplyTimeouts("admin_manual")).thenReturn(Map.of(
                "enabled", true,
                "pausedAwaiting", 1,
                "archivedPaused", 2
        ));

        Map<String, Object> result = service.runLifecycleScan("admin_manual");

        assertThat(result)
                .containsEntry("enabled", true)
                .containsEntry("pausedAwaiting", 1)
                .containsEntry("archivedPaused", 2);
        verify(taskLifecycleGovernanceService).scanAndApplyTimeouts("admin_manual");
    }

    private Task buildTask(TaskStatus status) {
        Task task = new Task();
        task.setId(1L);
        task.setTaskUuid("task-uuid-001");
        task.setUserId(2L);
        task.setStatus(status.getCode());
        task.setRegion("Xi'an");
        return task;
    }
}
