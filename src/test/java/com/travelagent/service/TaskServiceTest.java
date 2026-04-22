package com.travelagent.service;

import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.agent.statemachine.AgentStateMachine;
import com.travelagent.client.amap.AmapClient;
import com.travelagent.exception.BusinessException;
import com.travelagent.exception.QuotaExhaustedException;
import com.travelagent.exception.TaskNotFoundException;
import com.travelagent.mapper.TaskMapper;
import com.travelagent.model.dto.CreateTaskRequest;
import com.travelagent.model.dto.LocationCandidateItem;
import com.travelagent.model.dto.TaskResponse;
import com.travelagent.model.entity.Task;
import com.travelagent.model.entity.UserQuotaConfig;
import com.travelagent.model.enums.TaskStatus;
import com.travelagent.service.notification.SseEvent;
import com.travelagent.service.notification.SseNotificationService;
import com.travelagent.service.task.OriginCandidateService;
import com.travelagent.service.task.impl.TaskServiceImpl;
import com.travelagent.service.user.QuotaService;
import com.travelagent.util.JsonUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TaskServiceImpl Tests")
class TaskServiceTest {

    @Mock private TaskMapper taskMapper;
    @Mock private QuotaService quotaService;
    @Mock private AgentStateMachine stateMachine;
    @Mock private SseNotificationService sseNotificationService;
    @Mock private JsonUtil jsonUtil;
    @Mock private OriginCandidateService originCandidateService;
    @Mock private AmapClient amapClient;

    @InjectMocks
    private TaskServiceImpl taskService;

    private static final Long USER_ID = 1L;
    private static final int USER_LEVEL = 1;
    private static final String TASK_UUID = "test-uuid-0001";

    @Test
    void createTask_success_insertAndCheckpointPersisted() {
        when(quotaService.getQuotaConfig(USER_LEVEL)).thenReturn(quotaConfig(2));
        when(taskMapper.countActiveByUserId(USER_ID)).thenReturn(0);
        when(jsonUtil.toJson(any())).thenReturn("{\"schemaVersion\":\"1.0\"}");
        when(originCandidateService.generateCandidates(any(), any())).thenReturn(List.of(candidate()));
        when(amapClient.geocode(eq("Capital Airport"), eq("Beijing")))
                .thenReturn(Map.of("lat", 40.08, "lng", 116.59, "adcode", "110000"));
        doAnswer(inv -> {
            Task t = inv.getArgument(0);
            t.setId(100L);
            return 1;
        }).when(taskMapper).insert(any(Task.class));

        TaskResponse response = taskService.createTask(USER_ID, USER_LEVEL, buildRequest());

        verify(taskMapper).insert(any(Task.class));
        verify(taskMapper).updateCheckpoint(any(Task.class));
        assertThat(response.getStatus()).isEqualTo(TaskStatus.PENDING.getCode());
        assertThat(response.getStartLocationQuery()).isEqualTo("Guomao Hotel");
        assertThat(response.getEndLocationQuery()).isEqualTo("Capital Airport");
        assertThat(response.getDailyTimeWindows()).hasSize(3);
    }

    @Test
    void createTask_usesDefaultFullDayWindowWhenNotProvided() {
        when(quotaService.getQuotaConfig(USER_LEVEL)).thenReturn(quotaConfig(2));
        when(taskMapper.countActiveByUserId(USER_ID)).thenReturn(0);
        when(jsonUtil.toJson(any())).thenReturn("{\"schemaVersion\":\"1.0\"}");
        when(originCandidateService.generateCandidates(any(), any())).thenReturn(List.of(candidate()));
        when(amapClient.geocode(any(), any())).thenReturn(Map.of("lat", 40.08, "lng", 116.59, "adcode", "110000"));
        doAnswer(inv -> {
            Task t = inv.getArgument(0);
            t.setId(100L);
            return 1;
        }).when(taskMapper).insert(any(Task.class));

        TaskResponse response = taskService.createTask(USER_ID, USER_LEVEL, buildRequest());

        assertThat(response.getFullDayStartTime()).isEqualTo(LocalTime.of(7, 0));
        assertThat(response.getFullDayEndTime()).isEqualTo(LocalTime.of(21, 0));
    }

    @Test
    void createTask_usesCustomFullDayWindowWhenProvided() {
        when(quotaService.getQuotaConfig(USER_LEVEL)).thenReturn(quotaConfig(2));
        when(taskMapper.countActiveByUserId(USER_ID)).thenReturn(0);
        when(jsonUtil.toJson(any())).thenReturn("{\"schemaVersion\":\"1.0\"}");
        when(originCandidateService.generateCandidates(any(), any())).thenReturn(List.of(candidate()));
        when(amapClient.geocode(any(), any())).thenReturn(Map.of("lat", 40.08, "lng", 116.59, "adcode", "110000"));
        doAnswer(inv -> {
            Task t = inv.getArgument(0);
            t.setId(100L);
            return 1;
        }).when(taskMapper).insert(any(Task.class));

        CreateTaskRequest request = buildRequest();
        request.setFullDayStartTime(LocalTime.of(8, 30));
        request.setFullDayEndTime(LocalTime.of(20, 0));

        TaskResponse response = taskService.createTask(USER_ID, USER_LEVEL, request);

        assertThat(response.getFullDayStartTime()).isEqualTo(LocalTime.of(8, 30));
        assertThat(response.getFullDayEndTime()).isEqualTo(LocalTime.of(20, 0));
    }

    @Test
    void createTask_rejectsInvalidTimeRange() {
        CreateTaskRequest request = buildRequest();
        request.setEndTime(request.getStartTime().minusHours(1));

        assertThatThrownBy(() -> taskService.createTask(USER_ID, USER_LEVEL, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getHttpStatus()).isEqualTo(400));
    }

    @Test
    void createTask_quotaExhausted_throwsAndNoInsert() {
        doThrow(new QuotaExhaustedException("daily"))
                .when(quotaService).checkDailyQuota(USER_ID, USER_LEVEL);

        assertThatThrownBy(() -> taskService.createTask(USER_ID, USER_LEVEL, buildRequest()))
                .isInstanceOf(QuotaExhaustedException.class);

        verify(taskMapper, never()).insert(any());
    }

    @Test
    void createTask_concurrentLimitReached_throws429() {
        when(quotaService.getQuotaConfig(USER_LEVEL)).thenReturn(quotaConfig(2));
        when(taskMapper.countActiveByUserId(USER_ID)).thenReturn(2);

        assertThatThrownBy(() -> taskService.createTask(USER_ID, USER_LEVEL, buildRequest()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getHttpStatus()).isEqualTo(429));

        verify(taskMapper, never()).insert(any());
    }

    @Test
    void getTask_success_returnsResponse() {
        Task task = pendingTask();
        when(taskMapper.findByUuid(TASK_UUID)).thenReturn(task);
        when(jsonUtil.fromJson(any(), eq(TaskCheckpoint.class))).thenReturn(new TaskCheckpoint());

        TaskResponse response = taskService.getTask(TASK_UUID, USER_ID);

        assertThat(response.getTaskUuid()).isEqualTo(TASK_UUID);
    }

    @Test
    void getTask_notFound_throwsTaskNotFoundException() {
        when(taskMapper.findByUuid(TASK_UUID)).thenReturn(null);

        assertThatThrownBy(() -> taskService.getTask(TASK_UUID, USER_ID))
                .isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    void cancelTask_pendingTask_succeeds() {
        Task task = pendingTask();
        when(taskMapper.findByUuid(TASK_UUID)).thenReturn(task);
        when(stateMachine.transition(TaskStatus.PENDING, com.travelagent.agent.statemachine.AgentEvent.CANCEL))
                .thenReturn(TaskStatus.CANCELLED);

        taskService.cancelTask(TASK_UUID, USER_ID);

        verify(taskMapper).updateStatus(task.getId(), TaskStatus.CANCELLED.getCode());
        verify(sseNotificationService).sendEvent(eq(TASK_UUID), eq(SseEvent.STATE_CHANGE), any());
    }

    private Task pendingTask() {
        Task task = new Task();
        task.setId(1L);
        task.setTaskUuid(TASK_UUID);
        task.setUserId(USER_ID);
        task.setStatus(TaskStatus.PENDING.getCode());
        task.setRegion("Beijing");
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

    private LocationCandidateItem candidate() {
        LocationCandidateItem candidate = new LocationCandidateItem();
        candidate.setCandidateId("origin-1");
        candidate.setName("Guomao Hotel");
        candidate.setLatitude(39.9);
        candidate.setLongitude(116.47);
        return candidate;
    }

    private CreateTaskRequest buildRequest() {
        CreateTaskRequest req = new CreateTaskRequest();
        req.setRegion("Beijing");
        req.setUserIntent("3 day Beijing trip");
        req.setStartLocationQuery("Guomao Hotel");
        req.setEndLocationQuery("Capital Airport");
        req.setStartTime(LocalDateTime.of(2026, 4, 22, 15, 0));
        req.setEndTime(LocalDateTime.of(2026, 4, 24, 18, 0));
        req.setTravelMode("driving");
        return req;
    }
}
