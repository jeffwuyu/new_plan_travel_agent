package com.travelagent.service;

import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.agent.statemachine.AgentStateMachine;
import com.travelagent.exception.BusinessException;
import com.travelagent.exception.QuotaExhaustedException;
import com.travelagent.exception.TaskNotFoundException;
import com.travelagent.mapper.TaskMapper;
import com.travelagent.model.dto.CreateTaskRequest;
import com.travelagent.model.dto.TaskResponse;
import com.travelagent.model.entity.Task;
import com.travelagent.model.entity.UserQuotaConfig;
import com.travelagent.model.enums.TaskStatus;
import com.travelagent.service.notification.SseEvent;
import com.travelagent.service.notification.SseNotificationService;
import com.travelagent.service.task.impl.TaskServiceImpl;
import com.travelagent.service.user.QuotaService;
import com.travelagent.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TaskServiceImpl using Mockito (no Spring context).
 */

/**
 * 中文注释：测试类，用于验证 Task Service Test 相关行为是否符合预期。
 */

@ExtendWith(MockitoExtension.class)
@DisplayName("TaskServiceImpl Tests")
class TaskServiceTest {

    @Mock private TaskMapper             taskMapper;
    @Mock private QuotaService           quotaService;
    @Mock private AgentStateMachine      stateMachine;
    @Mock private SseNotificationService sseNotificationService;
    @Mock private JsonUtil               jsonUtil;

    @InjectMocks
    private TaskServiceImpl taskService;

    private static final Long   USER_ID    = 1L;
    private static final int    USER_LEVEL = 1;
    private static final String TASK_UUID  = "test-uuid-0001";

    // -----------------------------------------------------------------------
    // createTask
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("createTask: 正常创建 → 执行两次写入，返回 PENDING 状态")
    void createTask_success_insertAndCheckpointPersisted() {
        UserQuotaConfig config = quotaConfig(2);
        when(quotaService.getQuotaConfig(USER_LEVEL)).thenReturn(config);
        when(taskMapper.countActiveByUserId(USER_ID)).thenReturn(0);
        when(jsonUtil.toJson(any())).thenReturn("{\"schemaVersion\":\"1.0\"}");

        // Simulate useGeneratedKeys: set id after insert
        doAnswer(inv -> {
            Task t = inv.getArgument(0);
            t.setId(100L);
            return 1;
        }).when(taskMapper).insert(any(Task.class));

        TaskResponse response = taskService.createTask(USER_ID, USER_LEVEL, buildRequest());

        // insert + updateCheckpoint both called once
        verify(taskMapper, times(1)).insert(any(Task.class));
        verify(taskMapper, times(1)).updateCheckpoint(any(Task.class));

        assertThat(response.getStatus()).isEqualTo(TaskStatus.PENDING.getCode());
        assertThat(response.getRegion()).isEqualTo("北京市");
    }

    @Test
    @DisplayName("createTask: 每日配额耗尽 → 抛 QuotaExhaustedException，不写入 DB")
    void createTask_quotaExhausted_throwsAndNoInsert() {
        doThrow(new QuotaExhaustedException("daily"))
            .when(quotaService).checkDailyQuota(USER_ID, USER_LEVEL);

        assertThatThrownBy(() -> taskService.createTask(USER_ID, USER_LEVEL, buildRequest()))
            .isInstanceOf(QuotaExhaustedException.class);

        verify(taskMapper, never()).insert(any());
    }

    @Test
    @DisplayName("createTask: 并发任务数已达上限 → 抛 BusinessException 429")
    void createTask_concurrentLimitReached_throws429() {
        UserQuotaConfig config = quotaConfig(2);
        when(quotaService.getQuotaConfig(USER_LEVEL)).thenReturn(config);
        when(taskMapper.countActiveByUserId(USER_ID)).thenReturn(2); // at limit

        assertThatThrownBy(() -> taskService.createTask(USER_ID, USER_LEVEL, buildRequest()))
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> assertThat(((BusinessException) ex).getHttpStatus()).isEqualTo(429));

        verify(taskMapper, never()).insert(any());
    }

    // -----------------------------------------------------------------------
    // getTask
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("getTask: UUID 存在 + 所有者匹配 → 返回 TaskResponse")
    void getTask_success_returnsResponse() {
        Task task = pendingTask();
        when(taskMapper.findByUuid(TASK_UUID)).thenReturn(task);
        when(jsonUtil.fromJson(anyString(), eq(TaskCheckpoint.class))).thenReturn(new TaskCheckpoint());

        TaskResponse response = taskService.getTask(TASK_UUID, USER_ID);

        assertThat(response.getTaskUuid()).isEqualTo(TASK_UUID);
    }

    @Test
    @DisplayName("getTask: UUID 不存在 → 抛 TaskNotFoundException")
    void getTask_notFound_throwsTaskNotFoundException() {
        when(taskMapper.findByUuid(TASK_UUID)).thenReturn(null);

        assertThatThrownBy(() -> taskService.getTask(TASK_UUID, USER_ID))
            .isInstanceOf(TaskNotFoundException.class)
            .satisfies(ex -> assertThat(((BusinessException) ex).getHttpStatus()).isEqualTo(404));
    }

    @Test
    @DisplayName("getTask: 不是所有者 → 抛 BusinessException 403")
    void getTask_wrongOwner_throwsForbidden() {
        Task task = pendingTask();
        when(taskMapper.findByUuid(TASK_UUID)).thenReturn(task);

        assertThatThrownBy(() -> taskService.getTask(TASK_UUID, 999L))
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> assertThat(((BusinessException) ex).getHttpStatus()).isEqualTo(403));
    }

    // -----------------------------------------------------------------------
    // listTasks
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("listTasks: 返回该用户所有任务列表")
    void listTasks_returnsUserTasks() {
        Task t1 = pendingTask();
        Task t2 = pendingTask();
        t2.setTaskUuid("uuid-2");
        when(taskMapper.findByUserId(USER_ID)).thenReturn(List.of(t1, t2));

        List<TaskResponse> list = taskService.listTasks(USER_ID);

        assertThat(list).hasSize(2);
    }

    @Test
    @DisplayName("listTasks: 无任务 → 返回空列表")
    void listTasks_noTasks_returnsEmpty() {
        when(taskMapper.findByUserId(USER_ID)).thenReturn(List.of());

        List<TaskResponse> list = taskService.listTasks(USER_ID);

        assertThat(list).isEmpty();
    }

    // -----------------------------------------------------------------------
    // cancelTask
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("cancelTask: PENDING 任务 → 成功取消，推送 SSE，完成 emitter")
    void cancelTask_pendingTask_succeeds() {
        Task task = pendingTask();
        when(taskMapper.findByUuid(TASK_UUID)).thenReturn(task);
        when(stateMachine.transition(TaskStatus.PENDING, com.travelagent.agent.statemachine.AgentEvent.CANCEL))
            .thenReturn(TaskStatus.CANCELLED);

        taskService.cancelTask(TASK_UUID, USER_ID);

        verify(taskMapper).updateStatus(task.getId(), TaskStatus.CANCELLED.getCode());
        verify(sseNotificationService).sendEvent(eq(TASK_UUID), eq(SseEvent.STATE_CHANGE), any());
        verify(sseNotificationService).completeEmitter(TASK_UUID);
    }

    @Test
    @DisplayName("cancelTask: 任务已为终态(COMPLETED) → 抛 BusinessException 400")
    void cancelTask_alreadyCompleted_throws400() {
        Task task = pendingTask();
        task.setStatus(TaskStatus.COMPLETED.getCode());
        when(taskMapper.findByUuid(TASK_UUID)).thenReturn(task);

        assertThatThrownBy(() -> taskService.cancelTask(TASK_UUID, USER_ID))
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> assertThat(((BusinessException) ex).getHttpStatus()).isEqualTo(400));

        verify(taskMapper, never()).updateStatus(anyLong(), anyString());
    }

    @Test
    @DisplayName("cancelTask: 任务为 FAILED → 抛 BusinessException 400")
    void cancelTask_alreadyFailed_throws400() {
        Task task = pendingTask();
        task.setStatus(TaskStatus.FAILED.getCode());
        when(taskMapper.findByUuid(TASK_UUID)).thenReturn(task);

        assertThatThrownBy(() -> taskService.cancelTask(TASK_UUID, USER_ID))
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> assertThat(((BusinessException) ex).getHttpStatus()).isEqualTo(400));
    }

    @Test
    @DisplayName("cancelTask: 不是所有者 → 抛 BusinessException 403")
    void cancelTask_wrongOwner_throws403() {
        Task task = pendingTask();
        when(taskMapper.findByUuid(TASK_UUID)).thenReturn(task);

        assertThatThrownBy(() -> taskService.cancelTask(TASK_UUID, 999L))
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> assertThat(((BusinessException) ex).getHttpStatus()).isEqualTo(403));
    }

    // -----------------------------------------------------------------------
    // resumeTask
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("resumeTask: PAUSED 任务 → 转为 RESUMING，返回更新后的响应")
    void resumeTask_pausedTask_transitionsToResuming() {
        Task task = pendingTask();
        task.setStatus(TaskStatus.PAUSED.getCode());
        task.setCheckpointJson("{\"schemaVersion\":\"1.0\"}");

        Task updated = pendingTask();
        updated.setStatus(TaskStatus.RESUMING.getCode());
        updated.setCheckpointJson("{\"schemaVersion\":\"1.0\"}");

        when(taskMapper.findByUuid(TASK_UUID)).thenReturn(task).thenReturn(updated);
        when(stateMachine.transition(TaskStatus.PAUSED, com.travelagent.agent.statemachine.AgentEvent.RESUME))
            .thenReturn(TaskStatus.RESUMING);
        when(jsonUtil.fromJson(anyString(), eq(TaskCheckpoint.class))).thenReturn(new TaskCheckpoint());

        TaskResponse response = taskService.resumeTask(TASK_UUID, USER_ID);

        verify(taskMapper).updateStatus(task.getId(), TaskStatus.RESUMING.getCode());
        assertThat(response.getStatus()).isEqualTo(TaskStatus.RESUMING.getCode());
    }

    @Test
    @DisplayName("resumeTask: 非 PAUSED 状态(PLANNING) → 抛 BusinessException 400")
    void resumeTask_notPaused_throws400() {
        Task task = pendingTask();
        task.setStatus(TaskStatus.PLANNING.getCode()); // not resumable
        when(taskMapper.findByUuid(TASK_UUID)).thenReturn(task);

        assertThatThrownBy(() -> taskService.resumeTask(TASK_UUID, USER_ID))
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> assertThat(((BusinessException) ex).getHttpStatus()).isEqualTo(400));
    }

    @Test
    @DisplayName("resumeTask: UUID 不存在 → 抛 TaskNotFoundException 404")
    void resumeTask_notFound_throws404() {
        when(taskMapper.findByUuid(TASK_UUID)).thenReturn(null);

        assertThatThrownBy(() -> taskService.resumeTask(TASK_UUID, USER_ID))
            .isInstanceOf(TaskNotFoundException.class);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private Task pendingTask() {
        Task task = new Task();
        task.setId(1L);
        task.setTaskUuid(TASK_UUID);
        task.setUserId(USER_ID);
        task.setStatus(TaskStatus.PENDING.getCode());
        task.setRegion("北京市");
        task.setSchemaVersion("1.0");
        task.setTotalTokensUsed(0);
        task.setCheckpointJson("{\"schemaVersion\":\"1.0\"}");
        return task;
    }

    private UserQuotaConfig quotaConfig(int maxConcurrent) {
        UserQuotaConfig config = new UserQuotaConfig();
        config.setUserLevel(USER_LEVEL);
        config.setDailyTokenLimit(10000);
        config.setMonthlyTokenLimit(100000);
        config.setMaxConcurrentTasks(maxConcurrent);
        config.setMaxPlanSteps(15);
        return config;
    }

    private CreateTaskRequest buildRequest() {
        CreateTaskRequest req = new CreateTaskRequest();
        req.setRegion("北京市");
        req.setUserIntent("3天北京游");
        req.setTotalDays(3);
        req.setAttractionsPerDay(3);
        req.setTravelMode("driving");
        return req;
    }
}
