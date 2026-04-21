package com.travelagent.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelagent.agent.context.CompletedStep;
import com.travelagent.agent.context.PendingToolCall;
import com.travelagent.agent.context.PlanningConfig;
import com.travelagent.agent.context.RetryState;
import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.agent.planner.MarkovPlanner;
import com.travelagent.agent.planner.PlanningResult;
import com.travelagent.agent.statemachine.AgentEvent;
import com.travelagent.agent.statemachine.AgentStateMachine;
import com.travelagent.agent.tools.GeocodeTool;
import com.travelagent.agent.tools.ToolRegistry;
import com.travelagent.agent.tools.TrafficTimeTool;
import com.travelagent.agent.tools.WeatherTool;
import com.travelagent.exception.QuotaExhaustedException;
import com.travelagent.mapper.PlanMapper;
import com.travelagent.mapper.TaskMapper;
import com.travelagent.mapper.UserMapper;
import com.travelagent.model.entity.Plan;
import com.travelagent.model.entity.Task;
import com.travelagent.model.entity.User;
import com.travelagent.model.enums.TaskStatus;
import com.travelagent.service.agent.impl.AgentServiceImpl;
import com.travelagent.service.notification.SseEvent;
import com.travelagent.service.notification.SseNotificationService;
import com.travelagent.monitoring.TaskMetricsService;
import com.travelagent.service.task.TaskProgressService;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 中文注释：测试类，用于验证 Agent Service Impl Test 相关行为是否符合预期。
 */

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentServiceImpl Tests")
class AgentServiceImplTest {

    @Mock private TaskMapper taskMapper;
    @Mock private AgentStateMachine stateMachine;
    @Mock private SseNotificationService sseNotificationService;
    @Mock private MarkovPlanner markovPlanner;
    @Mock private ToolRegistry toolRegistry;
    @Mock private QuotaService quotaService;
    @Mock private PlanMapper planMapper;
    @Mock private UserMapper userMapper;
    @Mock private TaskProgressService taskProgressService;
    @Mock private TaskMetricsService taskMetricsService;

    @InjectMocks
    private AgentServiceImpl agentService;

    private final JsonUtil jsonUtil = new JsonUtil();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(jsonUtil, "objectMapper", new ObjectMapper().findAndRegisterModules());
        ReflectionTestUtils.setField(agentService, "jsonUtil", jsonUtil);
    }

    @Test
    @DisplayName("executeTask: returns early when task UUID not found")
    void executeTask_taskNotFound_returnsEarly() {
        when(taskMapper.findByUuid("unknown-uuid")).thenReturn(null);

        agentService.executeTask("unknown-uuid");

        verifyNoInteractions(stateMachine, markovPlanner, sseNotificationService);
    }

    @Test
    @DisplayName("executeTask: skips task in non-PENDING/RESUMING status")
    void executeTask_wrongStatus_skipsExecution() {
        Task task = buildTask(TaskStatus.PLANNING, 1L);
        when(taskMapper.findByUuid("uuid")).thenReturn(task);

        agentService.executeTask("uuid");

        verifyNoInteractions(stateMachine, markovPlanner);
    }

    @Test
    @DisplayName("executeTask: transitions PENDING -> PLANNING and sends STATE_CHANGE SSE")
    void executeTask_pending_transitionsToPlanningAndSendsSSE() {
        Task task = buildTask(TaskStatus.PENDING, 1L);
        TaskCheckpoint checkpoint = buildCompletedCheckpoint();
        task.setCheckpointJson(jsonUtil.toJson(checkpoint));
        when(taskMapper.findByUuid("uuid")).thenReturn(task);
        when(stateMachine.transition(TaskStatus.PENDING, AgentEvent.START_PLANNING))
                .thenReturn(TaskStatus.PLANNING);
        mockUserLevel(1L, 1);

        when(stateMachine.transition(TaskStatus.PLANNING, AgentEvent.COMPLETE))
                .thenReturn(TaskStatus.COMPLETED);
        when(planMapper.insertPlan(any())).thenAnswer(inv -> {
            Plan plan = inv.getArgument(0);
            plan.setId(99L);
            return 1;
        });

        agentService.executeTask("uuid");

        verify(stateMachine).transition(TaskStatus.PENDING, AgentEvent.START_PLANNING);
        verify(taskMapper).updateStatus(eq(1L), eq("planning"));

        ArgumentCaptor<Object> sseCaptor = ArgumentCaptor.forClass(Object.class);
        verify(sseNotificationService, atLeastOnce())
                .sendEvent(eq("uuid"), eq(SseEvent.STATE_CHANGE), sseCaptor.capture());
        assertThat(sseCaptor.getAllValues()).isNotEmpty();
    }

    @Test
    @DisplayName("executeTask: quota exhaustion pauses task and saves checkpoint")
    void executeTask_quotaExhausted_pausesTask() {
        Task task = buildTask(TaskStatus.PENDING, 1L);
        TaskCheckpoint checkpoint = buildOneStepCheckpoint();
        task.setCheckpointJson(jsonUtil.toJson(checkpoint));
        when(taskMapper.findByUuid("uuid")).thenReturn(task);
        when(stateMachine.transition(TaskStatus.PENDING, AgentEvent.START_PLANNING))
                .thenReturn(TaskStatus.PLANNING);
        mockUserLevel(1L, 1);

        doThrow(new QuotaExhaustedException("daily"))
                .when(quotaService).checkDailyQuota(1L, 1);

        when(stateMachine.transition(TaskStatus.PLANNING, AgentEvent.QUOTA_EXHAUSTED))
                .thenReturn(TaskStatus.PAUSED);

        agentService.executeTask("uuid");

        verify(taskMapper).updateStatus(eq(1L), eq("paused"));
        verify(sseNotificationService).sendEvent(eq("uuid"), eq(SseEvent.PAUSED), any());
        verify(taskMapper, atLeastOnce()).updateCheckpoint(any());
    }

    @Test
    @DisplayName("executeTask: completes one-step plan end-to-end")
    void executeTask_oneStepPlan_completesSuccessfully() {
        Task task = buildTask(TaskStatus.PENDING, 1L);
        TaskCheckpoint checkpoint = buildOneStepCheckpoint();
        task.setCheckpointJson(jsonUtil.toJson(checkpoint));
        when(taskMapper.findByUuid("uuid")).thenReturn(task);
        when(stateMachine.transition(TaskStatus.PENDING, AgentEvent.START_PLANNING))
                .thenReturn(TaskStatus.PLANNING);
        when(stateMachine.transition(TaskStatus.PLANNING, AgentEvent.START_TOOL_CALL))
                .thenReturn(TaskStatus.TOOL_CALLING);
        when(stateMachine.transition(TaskStatus.TOOL_CALLING, AgentEvent.TOOL_CALL_DONE))
                .thenReturn(TaskStatus.PLANNING);
        when(stateMachine.transition(TaskStatus.PLANNING, AgentEvent.COMPLETE))
                .thenReturn(TaskStatus.COMPLETED);
        mockUserLevel(1L, 1);

        when(markovPlanner.planNextAttraction(any(), any(), anyString()))
                .thenReturn(new PlanningResult("Terracotta Army", 0));

        mockToolRegistry();

        when(planMapper.insertPlan(any())).thenAnswer(inv -> {
            Plan plan = inv.getArgument(0);
            plan.setId(42L);
            return 1;
        });

        agentService.executeTask("uuid");

        verify(sseNotificationService).sendEvent(eq("uuid"), eq(SseEvent.COMPLETED), any());
        verify(sseNotificationService).completeEmitter("uuid");
        verify(sseNotificationService).sendEvent(eq("uuid"), eq(SseEvent.STEP_DONE), any());
    }

    @Test
    @DisplayName("executeTask: replays pending tool call on resume")
    void executeTask_resumeWithPendingToolCall_replaysToolCall() {
        Task task = buildTask(TaskStatus.RESUMING, 1L);
        TaskCheckpoint checkpoint = buildOneStepCheckpoint();
        checkpoint.setPendingToolCall(new PendingToolCall(
                GeocodeTool.NAME,
                Map.of("name", "Terracotta Army", "region", "Xi'an"),
                "uuid-step0-geocode"
        ));
        task.setCheckpointJson(jsonUtil.toJson(checkpoint));

        when(taskMapper.findByUuid("uuid")).thenReturn(task);
        when(stateMachine.transition(TaskStatus.RESUMING, AgentEvent.START_PLANNING))
                .thenReturn(TaskStatus.PLANNING);
        when(stateMachine.transition(TaskStatus.PLANNING, AgentEvent.START_TOOL_CALL))
                .thenReturn(TaskStatus.TOOL_CALLING);
        when(stateMachine.transition(TaskStatus.TOOL_CALLING, AgentEvent.TOOL_CALL_DONE))
                .thenReturn(TaskStatus.PLANNING);
        when(stateMachine.transition(TaskStatus.PLANNING, AgentEvent.COMPLETE))
                .thenReturn(TaskStatus.COMPLETED);
        mockUserLevel(1L, 1);

        when(markovPlanner.planNextAttraction(any(), any(), anyString()))
                .thenReturn(new PlanningResult("Terracotta Army", 0));
        mockToolRegistry();
        when(planMapper.insertPlan(any())).thenAnswer(inv -> {
            Plan plan = inv.getArgument(0);
            plan.setId(1L);
            return 1;
        });

        agentService.executeTask("uuid");

        verify(toolRegistry, atLeastOnce()).getTool(GeocodeTool.NAME);
    }

    private Task buildTask(TaskStatus status, Long id) {
        Task task = new Task();
        task.setId(id);
        task.setTaskUuid("uuid");
        task.setUserId(1L);
        task.setStatus(status.getCode());
        task.setRegion("Xi'an");
        task.setTotalTokensUsed(0);
        return task;
    }

    private TaskCheckpoint buildOneStepCheckpoint() {
        TaskCheckpoint checkpoint = new TaskCheckpoint();
        checkpoint.setTaskId(1L);
        checkpoint.setTaskUuid("uuid");
        checkpoint.setCurrentState(TaskStatus.PENDING.getCode());
        checkpoint.setRegion("Xi'an");
        checkpoint.setUserIntent("One day Xi'an trip");
        PlanningConfig config = new PlanningConfig();
        config.setTotalDays(1);
        config.setAttractionsPerDay(1);
        config.setTravelMode("driving");
        config.setPreferenceKeywords(List.of("history"));
        checkpoint.setPlanningConfig(config);
        checkpoint.setCompletedSteps(new ArrayList<>());
        checkpoint.setLlmConversationHistory(new ArrayList<>());
        checkpoint.setRetryState(new RetryState());
        checkpoint.setCurrentStepIndex(0);
        return checkpoint;
    }

    private TaskCheckpoint buildCompletedCheckpoint() {
        TaskCheckpoint checkpoint = buildOneStepCheckpoint();
        CompletedStep step = new CompletedStep();
        step.setStepIndex(0);
        step.setDayNumber(1);
        step.setAttractionName("Terracotta Army");
        step.setLat(34.38);
        step.setLng(109.28);
        step.setToolCallResults(Map.of());
        checkpoint.getCompletedSteps().add(step);
        checkpoint.setCurrentStepIndex(1);
        return checkpoint;
    }

    private void mockUserLevel(Long userId, int level) {
        User user = new User();
        user.setId(userId);
        user.setUserLevel(level);
        when(userMapper.findById(userId)).thenReturn(user);
    }

    @SuppressWarnings("unchecked")
    private void mockToolRegistry() {
        com.travelagent.agent.tools.AgentTool geocodeTool = mock(com.travelagent.agent.tools.AgentTool.class);
        when(geocodeTool.execute(any(), any()))
                .thenReturn(Map.of("lat", 34.38, "lng", 109.28, "adcode", "610100"));
        when(toolRegistry.getTool(GeocodeTool.NAME)).thenReturn(geocodeTool);

        com.travelagent.agent.tools.AgentTool weatherTool = mock(com.travelagent.agent.tools.AgentTool.class);
        when(weatherTool.execute(any(), any())).thenReturn(Map.of(
                "weather", "Sunny",
                "temperature", "22",
                "windDirection", "North",
                "windPower", "3",
                "humidity", "45"
        ));
        when(toolRegistry.getTool(WeatherTool.NAME)).thenReturn(weatherTool);

    }
}
