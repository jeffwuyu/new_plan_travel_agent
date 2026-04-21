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
import com.travelagent.model.dto.LocationCandidateItem;
import com.travelagent.model.dto.SelectedOrigin;
import com.travelagent.model.entity.Plan;
import com.travelagent.model.entity.Task;
import com.travelagent.model.entity.User;
import com.travelagent.model.enums.TaskStatus;
import com.travelagent.monitoring.TaskMetricsService;
import com.travelagent.service.agent.impl.AgentServiceImpl;
import com.travelagent.service.notification.SseEvent;
import com.travelagent.service.notification.SseNotificationService;
import com.travelagent.service.task.TaskProgressService;
import com.travelagent.service.user.QuotaService;
import com.travelagent.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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

    @InjectMocks private AgentServiceImpl agentService;

    private final JsonUtil jsonUtil = new JsonUtil();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(jsonUtil, "objectMapper", new ObjectMapper().findAndRegisterModules());
        ReflectionTestUtils.setField(agentService, "jsonUtil", jsonUtil);
    }

    @Test
    void executeTask_taskNotFound_returnsEarly() {
        when(taskMapper.findByUuid("unknown")).thenReturn(null);
        agentService.executeTask("unknown");
        verifyNoInteractions(stateMachine, markovPlanner, sseNotificationService);
    }

    @Test
    void executeTask_withoutOriginSelection_entersAwaitingState() {
        Task task = buildTask(TaskStatus.PENDING);
        TaskCheckpoint checkpoint = buildCheckpoint(false);
        task.setCheckpointJson(jsonUtil.toJson(checkpoint));

        when(taskMapper.findByUuid("uuid")).thenReturn(task);
        when(stateMachine.transition(TaskStatus.PENDING, AgentEvent.START_PLANNING)).thenReturn(TaskStatus.PLANNING);
        when(stateMachine.transition(TaskStatus.PLANNING, AgentEvent.USER_INPUT_REQUIRED)).thenReturn(TaskStatus.AWAITING_USER_INPUT);
        mockUserLevel(1L, 1);

        agentService.executeTask("uuid");

        verify(sseNotificationService).sendEvent(eq("uuid"), eq(SseEvent.USER_SELECTION_REQUIRED), any());
        verify(taskMapper, atLeastOnce()).updateCheckpoint(any());
    }

    @Test
    void executeTask_quotaExhausted_pausesTask() {
        Task task = buildTask(TaskStatus.PENDING);
        TaskCheckpoint checkpoint = buildCheckpoint(true);
        task.setCheckpointJson(jsonUtil.toJson(checkpoint));

        when(taskMapper.findByUuid("uuid")).thenReturn(task);
        when(stateMachine.transition(TaskStatus.PENDING, AgentEvent.START_PLANNING)).thenReturn(TaskStatus.PLANNING);
        when(stateMachine.transition(TaskStatus.PLANNING, AgentEvent.QUOTA_EXHAUSTED)).thenReturn(TaskStatus.PAUSED);
        mockUserLevel(1L, 1);
        doThrow(new QuotaExhaustedException("daily")).when(quotaService).checkDailyQuota(1L, 1);

        agentService.executeTask("uuid");

        verify(taskMapper).updateStatus(1L, "paused");
        verify(sseNotificationService).sendEvent(eq("uuid"), eq(SseEvent.PAUSED), any());
    }

    @Test
    void executeTask_oneStepPlan_completesSuccessfully() {
        Task task = buildTask(TaskStatus.PENDING);
        TaskCheckpoint checkpoint = buildCheckpoint(true);
        task.setCheckpointJson(jsonUtil.toJson(checkpoint));

        when(taskMapper.findByUuid("uuid")).thenReturn(task);
        when(stateMachine.transition(TaskStatus.PENDING, AgentEvent.START_PLANNING)).thenReturn(TaskStatus.PLANNING);
        when(stateMachine.transition(TaskStatus.PLANNING, AgentEvent.START_TOOL_CALL)).thenReturn(TaskStatus.TOOL_CALLING);
        when(stateMachine.transition(TaskStatus.TOOL_CALLING, AgentEvent.TOOL_CALL_DONE)).thenReturn(TaskStatus.PLANNING);
        when(stateMachine.transition(TaskStatus.PLANNING, AgentEvent.COMPLETE)).thenReturn(TaskStatus.COMPLETED);
        mockUserLevel(1L, 1);

        when(markovPlanner.planNextAttraction(any(), any(), anyString()))
                .thenReturn(new PlanningResult("Terracotta Army", 0));
        mockToolRegistry();
        when(planMapper.insertPlan(any())).thenAnswer(invocation -> {
            Plan plan = invocation.getArgument(0);
            plan.setId(42L);
            return 1;
        });

        agentService.executeTask("uuid");

        verify(sseNotificationService).sendEvent(eq("uuid"), eq(SseEvent.COMPLETED), any());
        verify(sseNotificationService, never()).sendEvent(eq("uuid"), eq(SseEvent.ERROR), any());
    }

    @Test
    void executeTask_resumeWithPendingToolCall_replaysToolCall() {
        Task task = buildTask(TaskStatus.RESUMING);
        TaskCheckpoint checkpoint = buildCheckpoint(true);
        checkpoint.setPendingToolCall(new PendingToolCall(
                GeocodeTool.NAME,
                Map.of("name", "Terracotta Army", "region", "Xi'an"),
                "uuid-step0-geocode"
        ));
        task.setCheckpointJson(jsonUtil.toJson(checkpoint));

        when(taskMapper.findByUuid("uuid")).thenReturn(task);
        when(stateMachine.transition(TaskStatus.RESUMING, AgentEvent.START_PLANNING)).thenReturn(TaskStatus.PLANNING);
        when(stateMachine.transition(TaskStatus.PLANNING, AgentEvent.START_TOOL_CALL)).thenReturn(TaskStatus.TOOL_CALLING);
        when(stateMachine.transition(TaskStatus.TOOL_CALLING, AgentEvent.TOOL_CALL_DONE)).thenReturn(TaskStatus.PLANNING);
        when(stateMachine.transition(TaskStatus.PLANNING, AgentEvent.COMPLETE)).thenReturn(TaskStatus.COMPLETED);
        mockUserLevel(1L, 1);
        when(markovPlanner.planNextAttraction(any(), any(), anyString()))
                .thenReturn(new PlanningResult("Terracotta Army", 0));
        mockToolRegistry();
        when(planMapper.insertPlan(any())).thenAnswer(invocation -> {
            Plan plan = invocation.getArgument(0);
            plan.setId(1L);
            return 1;
        });

        agentService.executeTask("uuid");

        verify(toolRegistry, atLeastOnce()).getTool(GeocodeTool.NAME);
    }

    private Task buildTask(TaskStatus status) {
        Task task = new Task();
        task.setId(1L);
        task.setTaskUuid("uuid");
        task.setUserId(1L);
        task.setStatus(status.getCode());
        task.setRegion("Xi'an");
        task.setTotalTokensUsed(0);
        return task;
    }

    private TaskCheckpoint buildCheckpoint(boolean originConfirmed) {
        TaskCheckpoint checkpoint = new TaskCheckpoint();
        checkpoint.setTaskId(1L);
        checkpoint.setTaskUuid("uuid");
        checkpoint.setCurrentState(TaskStatus.PENDING.getCode());
        checkpoint.setRegion("Xi'an");
        checkpoint.setUserIntent("One day Xi'an trip");
        checkpoint.setCurrentLocationQuery("Bell Tower");
        checkpoint.setPlanningConfig(new PlanningConfig(1, 1, List.of("history"), "driving"));
        checkpoint.setCompletedSteps(new ArrayList<>());
        checkpoint.setLlmConversationHistory(new ArrayList<>());
        checkpoint.setRetryState(new RetryState());
        checkpoint.setCurrentStepIndex(0);
        checkpoint.setOriginConfirmed(originConfirmed);
        if (originConfirmed) {
            SelectedOrigin selectedOrigin = new SelectedOrigin();
            selectedOrigin.setCandidateId("origin-1");
            selectedOrigin.setName("Bell Tower");
            selectedOrigin.setLatitude(34.26);
            selectedOrigin.setLongitude(108.95);
            checkpoint.setSelectedOrigin(selectedOrigin);
        } else {
            LocationCandidateItem candidate = new LocationCandidateItem();
            candidate.setCandidateId("origin-1");
            candidate.setName("Bell Tower");
            candidate.setLatitude(34.26);
            candidate.setLongitude(108.95);
            checkpoint.setLocationCandidates(List.of(candidate));
        }
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

        com.travelagent.agent.tools.AgentTool trafficTool = mock(com.travelagent.agent.tools.AgentTool.class);
        when(trafficTool.execute(any(), any())).thenReturn(Map.of("durationMin", 25));
        when(toolRegistry.getTool(TrafficTimeTool.NAME)).thenReturn(trafficTool);

        when(markovPlanner.generateFinalSummary(any(), any(), anyString())).thenReturn(null);
    }
}
