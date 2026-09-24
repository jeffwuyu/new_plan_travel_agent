package com.travelagent.service.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.agent.context.PendingToolCall;
import com.travelagent.mapper.TaskMapper;
import com.travelagent.model.entity.Task;
import com.travelagent.model.enums.TaskStatus;
import com.travelagent.service.notification.SseEvent;
import com.travelagent.service.notification.SseNotificationService;
import com.travelagent.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaskLifecycleGovernanceServiceTest {

    @Mock private TaskMapper taskMapper;
    @Mock private TaskProgressService taskProgressService;
    @Mock private SseNotificationService sseNotificationService;

    private TaskLifecycleGovernanceService service;
    private JsonUtil jsonUtil;

    @BeforeEach
    void setUp() {
        jsonUtil = new JsonUtil();
        ReflectionTestUtils.setField(jsonUtil, "objectMapper", new ObjectMapper().findAndRegisterModules());
        service = new TaskLifecycleGovernanceService(taskMapper, taskProgressService, sseNotificationService, jsonUtil);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "awaitingTimeoutHours", 24L);
        ReflectionTestUtils.setField(service, "resumingTimeoutMinutes", 30L);
        ReflectionTestUtils.setField(service, "pausedArchiveDays", 14L);
        ReflectionTestUtils.setField(service, "scanLimit", 100);
    }

    @Test
    void scanAndApplyTimeouts_pausesStaleAwaitingTask() {
        Task task = buildTask(TaskStatus.AWAITING_USER_INPUT);
        TaskCheckpoint checkpoint = checkpoint(task, TaskStatus.AWAITING_USER_INPUT);
        task.setCheckpointJson(jsonUtil.toJson(checkpoint));

        when(taskMapper.findStaleByStatus(eq(TaskStatus.AWAITING_USER_INPUT.getCode()), any(LocalDateTime.class), eq(100)))
                .thenReturn(List.of(task));
        when(taskMapper.findStaleByStatus(eq(TaskStatus.RESUMING.getCode()), any(LocalDateTime.class), eq(100)))
                .thenReturn(List.of());
        when(taskMapper.findStaleByStatus(eq(TaskStatus.PAUSED.getCode()), any(LocalDateTime.class), eq(100)))
                .thenReturn(List.of());
        when(taskMapper.transitionStatusIfCurrent(eq(task.getId()), eq(TaskStatus.AWAITING_USER_INPUT.getCode()),
                eq(TaskStatus.PAUSED.getCode()), anyString(), anyString(), eq(1L))).thenReturn(1);

        Map<String, Object> result = service.scanAndApplyTimeouts("unit_test");

        assertThat(result).containsEntry("pausedAwaiting", 1);
        ArgumentCaptor<String> checkpointCaptor = ArgumentCaptor.forClass(String.class);
        verify(taskMapper).transitionStatusIfCurrent(eq(task.getId()), eq(TaskStatus.AWAITING_USER_INPUT.getCode()),
                eq(TaskStatus.PAUSED.getCode()), checkpointCaptor.capture(), contains("awaiting_user_input_timeout"), eq(1L));
        TaskCheckpoint saved = jsonUtil.fromJson(checkpointCaptor.getValue(), TaskCheckpoint.class);
        assertThat(saved.getCurrentState()).isEqualTo(TaskStatus.PAUSED.getCode());
        assertThat(saved.getPauseReason()).isEqualTo("awaiting_user_input_timeout");
        verify(taskProgressService).recordEvent(eq(task.getTaskUuid()), eq("TASK_WAIT_TIMEOUT"),
                eq(TaskStatus.PAUSED.getCode()), any(), any(), contains("waiting too long"), any());
        verify(sseNotificationService).sendEvent(eq(task.getTaskUuid()), eq(SseEvent.PAUSED), any());
    }

    @Test
    void scanAndApplyTimeouts_archivesStalePausedTask() {
        Task task = buildTask(TaskStatus.PAUSED);
        TaskCheckpoint checkpoint = checkpoint(task, TaskStatus.PAUSED);
        checkpoint.setPendingToolCall(new PendingToolCall("weather", Map.of("city", "Xi'an"), "idem-1"));
        task.setCheckpointJson(jsonUtil.toJson(checkpoint));

        when(taskMapper.findStaleByStatus(eq(TaskStatus.AWAITING_USER_INPUT.getCode()), any(LocalDateTime.class), eq(100)))
                .thenReturn(List.of());
        when(taskMapper.findStaleByStatus(eq(TaskStatus.RESUMING.getCode()), any(LocalDateTime.class), eq(100)))
                .thenReturn(List.of());
        when(taskMapper.findStaleByStatus(eq(TaskStatus.PAUSED.getCode()), any(LocalDateTime.class), eq(100)))
                .thenReturn(List.of(task));
        when(taskMapper.transitionStatusIfCurrent(eq(task.getId()), eq(TaskStatus.PAUSED.getCode()),
                eq(TaskStatus.CANCELLED.getCode()), anyString(), anyString(), eq(1L))).thenReturn(1);

        Map<String, Object> result = service.scanAndApplyTimeouts("unit_test");

        assertThat(result).containsEntry("archivedPaused", 1);
        ArgumentCaptor<String> checkpointCaptor = ArgumentCaptor.forClass(String.class);
        verify(taskMapper).transitionStatusIfCurrent(eq(task.getId()), eq(TaskStatus.PAUSED.getCode()),
                eq(TaskStatus.CANCELLED.getCode()), checkpointCaptor.capture(), contains("paused_archive_timeout"), eq(1L));
        TaskCheckpoint saved = jsonUtil.fromJson(checkpointCaptor.getValue(), TaskCheckpoint.class);
        assertThat(saved.getCurrentState()).isEqualTo(TaskStatus.CANCELLED.getCode());
        assertThat(saved.getPauseReason()).isEqualTo("paused_archive_timeout");
        assertThat(saved.getPendingToolCall()).isNull();
        verify(taskProgressService).recordEvent(eq(task.getTaskUuid()), eq("TASK_AUTO_ARCHIVED"),
                eq(TaskStatus.CANCELLED.getCode()), any(), any(), contains("auto-closed"), any());
        verify(sseNotificationService).completeEmitter(task.getTaskUuid());
    }

    @Test
    void scanAndApplyTimeouts_disabledSkipsMapperCalls() {
        ReflectionTestUtils.setField(service, "enabled", false);

        Map<String, Object> result = service.scanAndApplyTimeouts("unit_test");

        assertThat(result)
                .containsEntry("enabled", false)
                .containsEntry("pausedAwaiting", 0)
                .containsEntry("archivedPaused", 0);
        verifyNoInteractions(taskMapper, taskProgressService, sseNotificationService);
    }

    @Test
    void scanExpiredExecutionLeases_claimsExpiredTaskWithDatabaseFence() {
        Task task = buildTask(TaskStatus.PLANNING);
        task.setId(42L);
        task.setRevision(7L);
        task.setLeaseExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(taskMapper.findRecoverableExpiredLeases(anyList(), any(LocalDateTime.class), eq(0L), eq(10)))
                .thenReturn(List.of(task));
        when(taskMapper.claimRecoveryIfExpired(eq(42L), eq(7L), eq("unit_test"), anyString(), any(LocalDateTime.class)))
                .thenReturn(1);

        TaskLifecycleGovernanceService.RecoveryScanResult result =
                service.scanExpiredExecutionLeases("unit_test", 0L, 10);

        assertThat(result.claimed()).isEqualTo(1);
        assertThat(result.taskUuids()).containsExactly(task.getTaskUuid());
        assertThat(result.nextCursor()).isEqualTo(42L);
        verify(taskMapper).claimRecoveryIfExpired(eq(42L), eq(7L), eq("unit_test"), anyString(), any(LocalDateTime.class));
        verify(taskMapper).findRecoverableExpiredLeases(anyList(), any(LocalDateTime.class), eq(0L), eq(10));
    }

    @Test
    void scanExpiredExecutionLeases_doesNotAdvancePastClaimedTaskOnConcurrentUpdate() {
        Task task = buildTask(TaskStatus.TOOL_CALLING);
        task.setId(9L);
        task.setRevision(2L);
        when(taskMapper.findRecoverableExpiredLeases(anyList(), any(LocalDateTime.class), eq(0L), eq(10)))
                .thenReturn(List.of(task));
        when(taskMapper.claimRecoveryIfExpired(eq(9L), eq(2L), anyString(), anyString(), any(LocalDateTime.class)))
                .thenReturn(0);

        TaskLifecycleGovernanceService.RecoveryScanResult result =
                service.scanExpiredExecutionLeases("race", 0L, 10);

        assertThat(result.claimed()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.nextCursor()).isEqualTo(9L);
    }

    private Task buildTask(TaskStatus status) {
        Task task = new Task();
        task.setId(10L);
        task.setTaskUuid("task-lifecycle-001");
        task.setUserId(7L);
        task.setStatus(status.getCode());
        task.setRevision(1L);
        task.setUpdatedAt(LocalDateTime.now().minusDays(20));
        return task;
    }

    private TaskCheckpoint checkpoint(Task task, TaskStatus status) {
        TaskCheckpoint checkpoint = new TaskCheckpoint();
        checkpoint.setTaskUuid(task.getTaskUuid());
        checkpoint.setCurrentState(status.getCode());
        checkpoint.setCurrentStepIndex(2);
        return checkpoint;
    }
}
