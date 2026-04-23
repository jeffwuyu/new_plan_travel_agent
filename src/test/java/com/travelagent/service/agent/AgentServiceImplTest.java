package com.travelagent.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelagent.agent.context.DailyTimeWindow;
import com.travelagent.agent.context.PendingToolCall;
import com.travelagent.agent.context.PlanningConfig;
import com.travelagent.agent.context.RetryState;
import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.agent.planner.MarkovPlanner;
import com.travelagent.agent.planner.PlanNextAttractionRequest;
import com.travelagent.agent.planner.PlanningResult;
import com.travelagent.agent.statemachine.AgentEvent;
import com.travelagent.agent.statemachine.AgentStateMachine;
import com.travelagent.agent.tools.GeocodeTool;
import com.travelagent.agent.tools.ToolRegistry;
import com.travelagent.agent.tools.TrafficTimeTool;
import com.travelagent.agent.tools.WeatherTool;
import com.travelagent.client.amap.AmapClient;
import com.travelagent.config.DatabaseSchemaGuard;
import com.travelagent.exception.AgentErrorCode;
import com.travelagent.exception.AgentException;
import com.travelagent.exception.QuotaExhaustedException;
import com.travelagent.mapper.PlanMapper;
import com.travelagent.mapper.TaskMapper;
import com.travelagent.mapper.UserMapper;
import com.travelagent.model.dto.LocationCandidateItem;
import com.travelagent.model.dto.ResolvedLocation;
import com.travelagent.model.entity.Plan;
import com.travelagent.model.entity.Task;
import com.travelagent.model.entity.User;
import com.travelagent.model.enums.TaskStatus;
import com.travelagent.monitoring.TaskMetricsService;
import com.travelagent.service.agent.impl.AgentCheckpointHelper;
import com.travelagent.service.agent.impl.AgentServiceImpl;
import com.travelagent.service.agent.impl.AgentToolExecutor;
import com.travelagent.service.llm.LlmUsageAccountingService;
import com.travelagent.service.notification.SseEvent;
import com.travelagent.service.notification.SseNotificationService;
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

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
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
    @Mock private DatabaseSchemaGuard schemaGuard;
    @Mock private AmapClient amapClient;
    @Mock private LlmUsageAccountingService llmUsageAccountingService;

    @InjectMocks private AgentServiceImpl agentService;

    private final JsonUtil jsonUtil = new JsonUtil();
    private AgentToolExecutor toolExecutor;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(jsonUtil, "objectMapper", new ObjectMapper().findAndRegisterModules());

        AgentCheckpointHelper checkpointHelper = new AgentCheckpointHelper();
        ReflectionTestUtils.setField(checkpointHelper, "taskMapper", taskMapper);
        ReflectionTestUtils.setField(checkpointHelper, "userMapper", userMapper);
        ReflectionTestUtils.setField(checkpointHelper, "jsonUtil", jsonUtil);
        ReflectionTestUtils.setField(checkpointHelper, "amapClient", amapClient);

        toolExecutor = new AgentToolExecutor();
        ReflectionTestUtils.setField(toolExecutor, "toolRegistry", toolRegistry);
        ReflectionTestUtils.setField(toolExecutor, "checkpointHelper", checkpointHelper);
        ReflectionTestUtils.setField(toolExecutor, "taskProgressService", taskProgressService);
        ReflectionTestUtils.setField(toolExecutor, "taskMetricsService", taskMetricsService);
        ReflectionTestUtils.setField(toolExecutor, "jsonUtil", jsonUtil);

        ReflectionTestUtils.setField(agentService, "checkpointHelper", checkpointHelper);
        ReflectionTestUtils.setField(agentService, "toolExecutor", toolExecutor);
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
        checkpoint.getPlanningConfig().setDynamicTargetSteps(1);
        task.setCheckpointJson(jsonUtil.toJson(checkpoint));

        when(taskMapper.findByUuid("uuid")).thenReturn(task);
        mockStandardTransitions(TaskStatus.PENDING);
        mockUserLevel(1L, 1);
        when(markovPlanner.buildPlanRequest(any())).thenReturn(buildPlanningRequest("manual"));
        when(markovPlanner.planNextAttraction(any(), any(), any(), anyString()))
                .thenReturn(PlanningResult.forAttraction("Terracotta Army", 0));
        when(amapClient.getTravelDuration(any(Double.class), any(Double.class), any(Double.class), any(Double.class), anyString()))
                .thenReturn(Map.of("durationMin", 25));
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
    void executeTask_passesTravelModeToTrafficTool() {
        Task task = buildTask(TaskStatus.PENDING);
        TaskCheckpoint checkpoint = buildCheckpoint(true);
        checkpoint.getPlanningConfig().setTravelMode("walking");
        checkpoint.getPlanningConfig().setDynamicTargetSteps(1);
        task.setCheckpointJson(jsonUtil.toJson(checkpoint));

        when(taskMapper.findByUuid("uuid")).thenReturn(task);
        mockStandardTransitions(TaskStatus.PENDING);
        mockUserLevel(1L, 1);
        PlanNextAttractionRequest planningRequest = buildPlanningRequest("manual");
        planningRequest.setTravelMode("walking");
        when(markovPlanner.buildPlanRequest(any())).thenReturn(planningRequest);
        when(markovPlanner.planNextAttraction(any(), any(), any(), anyString()))
                .thenReturn(PlanningResult.forAttraction("Terracotta Army", 0));
        when(amapClient.getTravelDuration(any(Double.class), any(Double.class), any(Double.class), any(Double.class), anyString()))
                .thenReturn(Map.of("durationMin", 25));
        var geocodeTool = successGeocodeTool();
        var weatherTool = successWeatherTool();
        var trafficTool = successTrafficTool();
        when(toolRegistry.getTool(GeocodeTool.NAME)).thenReturn(geocodeTool);
        when(toolRegistry.getTool(WeatherTool.NAME)).thenReturn(weatherTool);
        when(toolRegistry.getTool(TrafficTimeTool.NAME)).thenReturn(trafficTool);
        when(markovPlanner.generateFinalSummary(any(), any(), anyString())).thenReturn(null);
        when(planMapper.insertPlan(any())).thenAnswer(invocation -> {
            Plan plan = invocation.getArgument(0);
            plan.setId(42L);
            return 1;
        });

        agentService.executeTask("uuid");

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(trafficTool).execute(captor.capture(), anyString());
        assertThat(captor.getValue().get("travelMode")).isEqualTo("walking");
    }

    @Test
    void executeTask_resumeWithPendingToolCall_replaysToolCall() {
        Task task = buildTask(TaskStatus.RESUMING);
        TaskCheckpoint checkpoint = buildCheckpoint(true);
        checkpoint.getPlanningConfig().setDynamicTargetSteps(1);
        checkpoint.setPendingToolCall(new PendingToolCall(
                GeocodeTool.NAME,
                Map.of("name", "Terracotta Army", "region", "Xi'an"),
                "uuid-step0-geocode"
        ));
        task.setCheckpointJson(jsonUtil.toJson(checkpoint));

        when(taskMapper.findByUuid("uuid")).thenReturn(task);
        mockStandardTransitions(TaskStatus.RESUMING);
        mockUserLevel(1L, 1);
        when(markovPlanner.buildPlanRequest(any())).thenReturn(buildPlanningRequest("manual"));
        when(markovPlanner.planNextAttraction(any(), any(), any(), anyString()))
                .thenReturn(PlanningResult.forAttraction("Terracotta Army", 0));
        when(amapClient.getTravelDuration(any(Double.class), any(Double.class), any(Double.class), any(Double.class), anyString()))
                .thenReturn(Map.of("durationMin", 20));
        mockToolRegistry();
        when(planMapper.insertPlan(any())).thenAnswer(invocation -> {
            Plan plan = invocation.getArgument(0);
            plan.setId(1L);
            return 1;
        });

        agentService.executeTask("uuid");

        verify(toolRegistry, atLeastOnce()).getTool(GeocodeTool.NAME);
    }

    @Test
    void executeTask_amapRateLimit_retriesAndEventuallyCompletes() {
        Task task = buildTask(TaskStatus.PENDING);
        TaskCheckpoint checkpoint = buildCheckpoint(true);
        checkpoint.getPlanningConfig().setDynamicTargetSteps(1);
        task.setCheckpointJson(jsonUtil.toJson(checkpoint));

        when(taskMapper.findByUuid("uuid")).thenReturn(task, task, task);
        mockStandardTransitions(TaskStatus.PENDING);
        mockStandardTransitions(TaskStatus.RESUMING);
        mockUserLevel(1L, 1);
        when(markovPlanner.buildPlanRequest(any())).thenReturn(buildPlanningRequest("manual"));
        when(markovPlanner.planNextAttraction(any(), any(), any(), anyString()))
                .thenReturn(PlanningResult.forAttraction("Terracotta Army", 0));
        when(planMapper.insertPlan(any())).thenAnswer(invocation -> {
            Plan plan = invocation.getArgument(0);
            plan.setId(7L);
            return 1;
        });
        when(amapClient.getTravelDuration(any(Double.class), any(Double.class), any(Double.class), any(Double.class), anyString()))
                .thenReturn(Map.of("durationMin", 22));
        when(markovPlanner.generateFinalSummary(any(), any(), anyString())).thenReturn(null);

        var weatherTool = successWeatherTool();
        var trafficTool = successTrafficTool();
        when(toolRegistry.getTool(WeatherTool.NAME)).thenReturn(weatherTool);
        when(toolRegistry.getTool(TrafficTimeTool.NAME)).thenReturn(trafficTool);

        AtomicInteger attempts = new AtomicInteger();
        var geocodeTool = mock(com.travelagent.agent.tools.AgentTool.class);
        when(geocodeTool.execute(any(), any())).thenAnswer(invocation -> {
            if (attempts.getAndIncrement() == 0) {
                throw new AgentException(AgentErrorCode.TOOL_AMAP_RATE_LIMIT, "rate limited");
            }
            return Map.of("lat", 34.38, "lng", 109.28, "adcode", "610100");
        });
        when(toolRegistry.getTool(GeocodeTool.NAME)).thenReturn(geocodeTool);

        agentService.executeTask("uuid");

        verify(taskProgressService, atLeastOnce()).recordEvent(eq("uuid"), eq("RETRY"), any(), any(), any(), anyString(), any());
        verify(sseNotificationService, atLeastOnce()).sendEvent(eq("uuid"), eq(SseEvent.RETRY), any());
        verify(sseNotificationService).sendEvent(eq("uuid"), eq(SseEvent.COMPLETED), any());
    }

    @Test
    void executeTask_withAttractionCandidates_entersAwaitingUserInput() {
        Task task = buildTask(TaskStatus.PENDING);
        TaskCheckpoint checkpoint = buildCheckpoint(true);
        checkpoint.getPlanningConfig().setDynamicTargetSteps(1);
        task.setCheckpointJson(jsonUtil.toJson(checkpoint));

        LocationCandidateItem candidate = new LocationCandidateItem();
        candidate.setCandidateId("poi-1");
        candidate.setName("Terracotta Army");

        when(taskMapper.findByUuid("uuid")).thenReturn(task);
        when(stateMachine.transition(TaskStatus.PENDING, AgentEvent.START_PLANNING)).thenReturn(TaskStatus.PLANNING);
        when(stateMachine.transition(TaskStatus.PLANNING, AgentEvent.USER_INPUT_REQUIRED)).thenReturn(TaskStatus.AWAITING_USER_INPUT);
        mockUserLevel(1L, 1);
        when(markovPlanner.buildPlanRequest(any())).thenReturn(buildPlanningRequest("nearby_poi"));
        when(markovPlanner.planNextAttraction(any(), any(), any(), anyString()))
                .thenReturn(PlanningResult.forCandidates(
                        List.of(candidate),
                        0,
                        "poi_candidate_selection",
                        "poi_candidate_selection",
                        "nearby_poi",
                        Map.of(),
                        Map.of()));

        agentService.executeTask("uuid");

        verify(sseNotificationService).sendEvent(eq("uuid"), eq(SseEvent.USER_SELECTION_REQUIRED), any());
        verify(toolRegistry, never()).getTool(GeocodeTool.NAME);
    }

    @Test
    void buildTrafficIdempotencyKey_isStableAndSensitiveToArguments() {
        String firstKey = ReflectionTestUtils.invokeMethod(
                toolExecutor, "buildToolIdempotencyKey", "uuid", 0, TrafficTimeTool.NAME, Map.of(
                        "originLng", 108.95,
                        "originLat", 34.26,
                        "destLng", 109.28,
                        "destLat", 34.38,
                        "travelMode", "walking"
                ));
        String secondKey = ReflectionTestUtils.invokeMethod(
                toolExecutor, "buildToolIdempotencyKey", "uuid", 0, TrafficTimeTool.NAME, Map.of(
                        "originLng", 108.95,
                        "originLat", 34.26,
                        "destLng", 109.28,
                        "destLat", 34.38,
                        "travelMode", "walking"
                ));
        String changedKey = ReflectionTestUtils.invokeMethod(
                toolExecutor, "buildToolIdempotencyKey", "uuid", 0, TrafficTimeTool.NAME, Map.of(
                        "originLng", 108.95,
                        "originLat", 34.26,
                        "destLng", 109.28,
                        "destLat", 34.38,
                        "travelMode", "driving"
                ));

        assertThat(firstKey).isEqualTo(secondKey);
        assertThat(firstKey).isNotEqualTo(changedKey);
    }

    private void mockStandardTransitions(TaskStatus initialStatus) {
        lenient().when(stateMachine.transition(initialStatus, AgentEvent.START_PLANNING)).thenReturn(TaskStatus.PLANNING);
        lenient().when(stateMachine.transition(TaskStatus.PLANNING, AgentEvent.START_TOOL_CALL)).thenReturn(TaskStatus.TOOL_CALLING);
        lenient().when(stateMachine.transition(TaskStatus.TOOL_CALLING, AgentEvent.TOOL_CALL_DONE)).thenReturn(TaskStatus.PLANNING);
        lenient().when(stateMachine.transition(TaskStatus.PLANNING, AgentEvent.COMPLETE)).thenReturn(TaskStatus.COMPLETED);
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
        checkpoint.setStartLocationQuery("Bell Tower");
        checkpoint.setEndLocationQuery("Xi'an North Station");
        checkpoint.setTripStartTime(LocalDateTime.of(2026, 4, 22, 9, 0));
        checkpoint.setTripEndTime(LocalDateTime.of(2026, 4, 22, 21, 0));

        PlanningConfig config = new PlanningConfig();
        config.setTotalDays(1);
        config.setAttractionsPerDay(1);
        config.setDynamicTargetSteps(1);
        config.setPreferenceKeywords(List.of("history"));
        config.setTravelMode("driving");
        config.setStartLocationQuery("Bell Tower");
        config.setEndLocationQuery("Xi'an North Station");
        config.setStartTime(checkpoint.getTripStartTime());
        config.setEndTime(checkpoint.getTripEndTime());
        config.setFullDayStartTime(LocalTime.of(7, 0));
        config.setFullDayEndTime(LocalTime.of(21, 0));
        checkpoint.setPlanningConfig(config);
        checkpoint.setDailyTimeWindows(List.of(
                new DailyTimeWindow(1, checkpoint.getTripStartTime(), checkpoint.getTripEndTime())
        ));
        checkpoint.setRemainingTimeBudgetMin(360);
        checkpoint.setCompletedSteps(new ArrayList<>());
        checkpoint.setLlmConversationHistory(new ArrayList<>());
        checkpoint.setRetryState(new RetryState());
        checkpoint.setCurrentStepIndex(0);
        checkpoint.setOriginConfirmed(originConfirmed);

        ResolvedLocation destination = new ResolvedLocation();
        destination.setName("Xi'an North Station");
        destination.setLatitude(34.38);
        destination.setLongitude(108.94);
        checkpoint.setSelectedDestination(destination);

        if (originConfirmed) {
            ResolvedLocation selectedOrigin = new ResolvedLocation();
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

    private PlanNextAttractionRequest buildPlanningRequest(String selectedBranchType) {
        PlanNextAttractionRequest request = new PlanNextAttractionRequest();
        request.setRegion("Xi'an");
        request.setCurrentPositionName("Bell Tower");
        request.setCurrentLat(34.26);
        request.setCurrentLng(108.95);
        request.setCurrentAdcode("610100");
        request.setTravelMode("driving");
        request.setRemainingTimeBudgetMin(360);
        request.setSelectedBranchType(selectedBranchType);
        return request;
    }

    private void mockUserLevel(Long userId, int level) {
        User user = new User();
        user.setId(userId);
        user.setUserLevel(level);
        when(userMapper.findById(userId)).thenReturn(user);
    }

    private void mockToolRegistry() {
        var geocodeTool = successGeocodeTool();
        var weatherTool = successWeatherTool();
        var trafficTool = successTrafficTool();
        when(toolRegistry.getTool(GeocodeTool.NAME)).thenReturn(geocodeTool);
        when(toolRegistry.getTool(WeatherTool.NAME)).thenReturn(weatherTool);
        when(toolRegistry.getTool(TrafficTimeTool.NAME)).thenReturn(trafficTool);
        when(markovPlanner.generateFinalSummary(any(), any(), anyString())).thenReturn(null);
    }

    private com.travelagent.agent.tools.AgentTool successGeocodeTool() {
        var tool = mock(com.travelagent.agent.tools.AgentTool.class);
        when(tool.execute(any(), any()))
                .thenReturn(Map.of("lat", 34.38, "lng", 109.28, "adcode", "610100"));
        return tool;
    }

    private com.travelagent.agent.tools.AgentTool successWeatherTool() {
        var tool = mock(com.travelagent.agent.tools.AgentTool.class);
        when(tool.execute(any(), any())).thenReturn(Map.of(
                "weather", "Sunny",
                "temperature", "22",
                "windDirection", "North",
                "windPower", "3",
                "humidity", "45"
        ));
        return tool;
    }

    private com.travelagent.agent.tools.AgentTool successTrafficTool() {
        var tool = mock(com.travelagent.agent.tools.AgentTool.class);
        when(tool.execute(any(), any())).thenReturn(Map.of("durationMin", 25));
        return tool;
    }
}
