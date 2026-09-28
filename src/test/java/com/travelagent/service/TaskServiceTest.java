package com.travelagent.service;

import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.agent.context.CompletedStep;
import com.travelagent.agent.context.PendingToolCall;
import com.travelagent.service.agent.AgentService;
import com.travelagent.agent.planner.PlanningResult;
import com.travelagent.agent.planner.TaskExecutionDispatcher;
import com.travelagent.agent.statemachine.AgentEvent;
import com.travelagent.agent.statemachine.AgentStateMachine;
import com.travelagent.client.amap.AmapClient;
import com.travelagent.exception.BusinessException;
import com.travelagent.exception.QuotaExhaustedException;
import com.travelagent.exception.TaskNotFoundException;
import com.travelagent.mapper.TaskMapper;
import com.travelagent.model.dto.ConfirmOriginSelectionRequest;
import com.travelagent.model.dto.CreateTaskRequest;
import com.travelagent.model.dto.LocationCandidateItem;
import com.travelagent.model.dto.NodeChatRequest;
import com.travelagent.model.dto.ResolvedLocation;
import com.travelagent.model.dto.RewindTaskRequest;
import com.travelagent.model.dto.SelectionOptionItem;
import com.travelagent.model.dto.TaskResponse;
import com.travelagent.model.entity.Task;
import com.travelagent.model.entity.UserQuotaConfig;
import com.travelagent.model.enums.TaskStatus;
import com.travelagent.service.notification.SseEvent;
import com.travelagent.service.notification.SseNotificationService;
import com.travelagent.service.task.OriginCandidateService;
import com.travelagent.service.task.TaskProgressService;
import com.travelagent.service.task.impl.TaskInitialCheckpointBuilder;
import com.travelagent.service.task.impl.TaskRewindHandler;
import com.travelagent.service.task.impl.TaskSelectionConfirmationHandler;
import com.travelagent.service.task.impl.TaskServiceImpl;
import com.travelagent.service.user.QuotaService;
import com.travelagent.util.JsonUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
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
    @Mock private AgentService agentService;
    @Mock private TaskProgressService taskProgressService;
    @Mock private TaskExecutionDispatcher taskExecutionDispatcher;

    @InjectMocks
    private TaskServiceImpl taskService;

    private final TaskRewindHandler rewindHandler = new TaskRewindHandler();
    private final TaskInitialCheckpointBuilder initialCheckpointBuilder = new TaskInitialCheckpointBuilder();
    private final TaskSelectionConfirmationHandler selectionConfirmationHandler = new TaskSelectionConfirmationHandler();

    private static final Long USER_ID = 1L;
    private static final int USER_LEVEL = 1;
    private static final String TASK_UUID = "test-uuid-0001";

    @BeforeEach
    void setUp() {
        org.springframework.test.util.ReflectionTestUtils.setField(taskService, "rewindHandler", rewindHandler);
        org.springframework.test.util.ReflectionTestUtils.setField(initialCheckpointBuilder,
                "originCandidateService", originCandidateService);
        org.springframework.test.util.ReflectionTestUtils.setField(initialCheckpointBuilder,
                "amapClient", amapClient);
        org.springframework.test.util.ReflectionTestUtils.setField(taskService,
                "initialCheckpointBuilder", initialCheckpointBuilder);
        org.springframework.test.util.ReflectionTestUtils.setField(taskService,
                "selectionConfirmationHandler", selectionConfirmationHandler);
    }

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

        TaskResponse response = taskService.createTask(USER_ID, USER_LEVEL, buildRequest(), "203.0.113.10");

        ArgumentCaptor<Task> taskCaptor = ArgumentCaptor.forClass(Task.class);
        verify(taskMapper).insert(taskCaptor.capture());
        verify(taskMapper).updateCheckpoint(any(Task.class));
        assertThat(taskCaptor.getValue().getRequestIp()).isEqualTo("203.0.113.10");
        assertThat(response.getStatus()).isEqualTo(TaskStatus.PENDING.getCode());
        assertThat(response.getStartLocationQuery()).isEqualTo("Guomao Hotel");
        assertThat(response.getEndLocationQuery()).isEqualTo("Capital Airport");
        assertThat(response.getDailyTimeWindows()).hasSize(3);
        assertThat(response.getTotalSteps()).isGreaterThan(1).isLessThanOrEqualTo(15);
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

        TaskResponse response = taskService.createTask(USER_ID, USER_LEVEL, buildRequest(), "203.0.113.10");

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

        TaskResponse response = taskService.createTask(USER_ID, USER_LEVEL, request, "203.0.113.10");

        assertThat(response.getFullDayStartTime()).isEqualTo(LocalTime.of(8, 30));
        assertThat(response.getFullDayEndTime()).isEqualTo(LocalTime.of(20, 0));
    }

    @Test
    void createTask_rejectsInvalidTimeRange() {
        CreateTaskRequest request = buildRequest();
        request.setEndTime(request.getStartTime().minusHours(1));

        assertThatThrownBy(() -> taskService.createTask(USER_ID, USER_LEVEL, request, "203.0.113.10"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getHttpStatus()).isEqualTo(400));
    }

    @Test
    void createTask_quotaExhausted_throwsAndNoInsert() {
        doThrow(new QuotaExhaustedException("daily"))
                .when(quotaService).checkDailyQuota(USER_ID, USER_LEVEL);

        assertThatThrownBy(() -> taskService.createTask(USER_ID, USER_LEVEL, buildRequest(), "203.0.113.10"))
                .isInstanceOf(QuotaExhaustedException.class);

        verify(taskMapper, never()).insert(any());
    }

    @Test
    void createTask_concurrentLimitReached_throws429() {
        when(quotaService.getQuotaConfig(USER_LEVEL)).thenReturn(quotaConfig(2));
        when(taskMapper.countActiveByUserId(USER_ID)).thenReturn(2);

        assertThatThrownBy(() -> taskService.createTask(USER_ID, USER_LEVEL, buildRequest(), "203.0.113.10"))
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

    @Test
    void cancelTask_withCheckpoint_marksCheckpointCancelledAndRecordsEvent() {
        Task task = pendingTask();
        TaskCheckpoint checkpoint = new TaskCheckpoint();
        checkpoint.setTaskUuid(TASK_UUID);
        checkpoint.setTaskId(task.getId());
        checkpoint.setCurrentState(TaskStatus.PLANNING.getCode());
        checkpoint.setCurrentStepIndex(1);
        checkpoint.setPlanningConfig(new com.travelagent.agent.context.PlanningConfig());
        checkpoint.getPlanningConfig().setDynamicTargetSteps(3);
        checkpoint.setPendingToolCall(new PendingToolCall("geocode", Map.of("name", "West Lake"), "idem-1"));

        when(taskMapper.findByUuid(TASK_UUID)).thenReturn(task);
        when(jsonUtil.fromJson(task.getCheckpointJson(), TaskCheckpoint.class)).thenReturn(checkpoint);
        when(jsonUtil.toJson(checkpoint)).thenReturn("{\"currentState\":\"cancelled\"}");
        when(stateMachine.transition(TaskStatus.PENDING, AgentEvent.CANCEL)).thenReturn(TaskStatus.CANCELLED);

        taskService.cancelTask(TASK_UUID, USER_ID);

        assertThat(checkpoint.getCurrentState()).isEqualTo(TaskStatus.CANCELLED.getCode());
        assertThat(checkpoint.getPauseReason()).isEqualTo("user_cancelled");
        assertThat(checkpoint.getPendingToolCall()).isNull();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.CANCELLED.getCode());
        verify(taskMapper).updateCheckpoint(task);
        verify(taskProgressService).recordEvent(eq(TASK_UUID), eq("CANCELLED"), eq(TaskStatus.CANCELLED.getCode()),
                eq(1), eq(3), eq("Task cancelled by user"), any());
    }

    @Test
    void confirmOriginSelection_ragCandidateSelection_omitsNullFieldsFromPayload() {
        TaskCheckpoint checkpoint = awaitingCheckpoint("route_candidate_selection");
        checkpoint.setRecommendationCandidates(List.of(recommendationCandidate("poi-1", "West Lake Cafe")));
        Task task = awaitingUserInputTask();
        ConfirmOriginSelectionRequest request = selectionRequest("route_candidate_selection", "poi-1", "West Lake Cafe");

        stubConfirmSelection(task, checkpoint);

        TaskResponse response = taskService.confirmOriginSelection(TASK_UUID, USER_ID, request);

        assertThat(response.getStatus()).isEqualTo(TaskStatus.RESUMING.getCode());
        verify(taskMapper).updateStatus(task.getId(), TaskStatus.RESUMING.getCode());
        verify(taskMapper).updateCheckpoint(task);
        verify(taskExecutionDispatcher).dispatchTask(TASK_UUID, "resume:user_selection_confirmed");

        Map<String, Object> payload = captureSelectionConfirmedPayload();
        assertThat(payload)
                .containsEntry("taskUuid", TASK_UUID)
                .containsEntry("pendingInputType", "route_candidate_selection")
                .containsKey("selectedCandidate")
                .doesNotContainKeys("selectedBranchType", "selectedOrigin");

        LocationCandidateItem selectedCandidate = (LocationCandidateItem) payload.get("selectedCandidate");
        assertThat(selectedCandidate.getCandidateId()).isEqualTo("poi-1");
        assertThat(selectedCandidate.getName()).isEqualTo("West Lake Cafe");
    }

    @Test
    void confirmOriginSelection_routeCandidateSelection_omitsNullFieldsFromPayload() {
        TaskCheckpoint checkpoint = awaitingCheckpoint("route_candidate_selection");
        LocationCandidateItem routeCandidate = recommendationCandidate("route-1", "Scenic Route A");
        routeCandidate.setRouteSummary("Hub -> West Lake -> Station");
        checkpoint.setRecommendationCandidates(List.of(routeCandidate));
        Task task = awaitingUserInputTask();
        ConfirmOriginSelectionRequest request = selectionRequest("route_candidate_selection", "route-1", "Scenic Route A");

        stubConfirmSelection(task, checkpoint);

        TaskResponse response = taskService.confirmOriginSelection(TASK_UUID, USER_ID, request);

        assertThat(response.getStatus()).isEqualTo(TaskStatus.RESUMING.getCode());
        verify(taskExecutionDispatcher).dispatchTask(TASK_UUID, "resume:user_selection_confirmed");

        Map<String, Object> payload = captureSelectionConfirmedPayload();
        assertThat(payload)
                .containsEntry("taskUuid", TASK_UUID)
                .containsEntry("pendingInputType", "route_candidate_selection")
                .containsKey("selectedCandidate")
                .doesNotContainKeys("selectedBranchType", "selectedOrigin");

        LocationCandidateItem selectedCandidate = (LocationCandidateItem) payload.get("selectedCandidate");
        assertThat(selectedCandidate.getRouteSummary()).isEqualTo("Hub -> West Lake -> Station");
    }

    @Test
    void confirmOriginSelection_selectionBranch_onlyIncludesSelectedBranchType() {
        TaskCheckpoint checkpoint = awaitingCheckpoint("selection_branch");
        checkpoint.setSelectionOptions(List.of(selectionOption("branch-1", "rag_route")));
        checkpoint.setCurrentContext(new LinkedHashMap<>());
        Task task = awaitingUserInputTask();
        ConfirmOriginSelectionRequest request = selectionRequest("selection_branch", "branch-1", "Nearby POI");

        stubConfirmSelection(task, checkpoint);

        TaskResponse response = taskService.confirmOriginSelection(TASK_UUID, USER_ID, request);

        assertThat(response.getStatus()).isEqualTo(TaskStatus.RESUMING.getCode());
        verify(taskExecutionDispatcher).dispatchTask(TASK_UUID, "resume:user_selection_confirmed");

        Map<String, Object> payload = captureSelectionConfirmedPayload();
        assertThat(payload)
                .containsEntry("taskUuid", TASK_UUID)
                .containsEntry("pendingInputType", "selection_branch")
                .containsEntry("selectedBranchType", "rag_route")
                .doesNotContainKeys("selectedCandidate", "selectedOrigin");
    }

    @Test
    void confirmOriginSelection_originSelection_onlyIncludesSelectedOrigin() {
        TaskCheckpoint checkpoint = awaitingCheckpoint("origin_selection");
        checkpoint.setLocationCandidates(List.of(originCandidate("origin-1", "杭州东站")));
        Task task = awaitingUserInputTask();
        ConfirmOriginSelectionRequest request = selectionRequest("origin_selection", "origin-1", "杭州东站");

        stubConfirmSelection(task, checkpoint);

        TaskResponse response = taskService.confirmOriginSelection(TASK_UUID, USER_ID, request);

        assertThat(response.getStatus()).isEqualTo(TaskStatus.RESUMING.getCode());
        verify(taskExecutionDispatcher).dispatchTask(TASK_UUID, "resume:user_selection_confirmed");

        Map<String, Object> payload = captureSelectionConfirmedPayload();
        assertThat(payload)
                .containsEntry("taskUuid", TASK_UUID)
                .containsEntry("pendingInputType", "origin_selection")
                .containsKey("selectedOrigin")
                .doesNotContainKeys("selectedCandidate", "selectedBranchType");

        assertThat(payload.get("selectedOrigin")).isNotNull();
    }

    @Test
    void rewindTask_pausedTask_truncatesCheckpointAndResumes() {
        Task task = pendingTask();
        task.setStatus(TaskStatus.PAUSED.getCode());
        TaskCheckpoint checkpoint = rewindCheckpoint();
        when(taskMapper.findByUuid(TASK_UUID)).thenReturn(task);
        when(jsonUtil.fromJson(task.getCheckpointJson(), TaskCheckpoint.class)).thenReturn(checkpoint);
        when(jsonUtil.toJson(any())).thenReturn("{\"schemaVersion\":\"1.0\"}");

        RewindTaskRequest request = new RewindTaskRequest();
        request.setTargetStepIndex(0);

        TaskResponse response = taskService.rewindTask(TASK_UUID, USER_ID, request);

        assertThat(response.getStatus()).isEqualTo(TaskStatus.RESUMING.getCode());
        assertThat(checkpoint.getCompletedSteps()).hasSize(1);
        assertThat(checkpoint.getCurrentStepIndex()).isEqualTo(1);
        assertThat(checkpoint.getPendingInputType()).isNull();
        verify(taskMapper).updateCheckpoint(task);
        verify(taskMapper).updateStatus(task.getId(), TaskStatus.RESUMING.getCode());
        verify(sseNotificationService).sendEvent(eq(TASK_UUID), eq(SseEvent.REWIND), any());
        verify(taskExecutionDispatcher).dispatchTask(TASK_UUID, "resume:rewind");
    }

    @Test
    void resumeTask_pausedTask_dispatchesExecution() {
        Task task = pendingTask();
        task.setStatus(TaskStatus.PAUSED.getCode());
        TaskCheckpoint checkpoint = awaitingCheckpoint("route_candidate_selection");
        checkpoint.setCurrentState(TaskStatus.PAUSED.getCode());
        when(taskMapper.findByUuid(TASK_UUID)).thenReturn(task, task);
        when(jsonUtil.fromJson(task.getCheckpointJson(), TaskCheckpoint.class)).thenReturn(checkpoint);
        when(jsonUtil.toJson(any())).thenReturn("{\"schemaVersion\":\"1.0\"}");
        when(stateMachine.transition(TaskStatus.PAUSED, AgentEvent.RESUME)).thenReturn(TaskStatus.RESUMING);

        TaskResponse response = taskService.resumeTask(TASK_UUID, USER_ID);

        assertThat(response.getStatus()).isEqualTo(TaskStatus.RESUMING.getCode());
        verify(taskMapper).updateStatus(task.getId(), TaskStatus.RESUMING.getCode());
        verify(taskExecutionDispatcher).dispatchTask(TASK_UUID, "resume:manual_resume");
    }

    @Test
    void confirmPendingToolReplay_marksApprovedAndDispatchesResume() {
        Task task = pendingTask();
        task.setStatus(TaskStatus.PAUSED.getCode());
        TaskCheckpoint checkpoint = pendingToolCheckpoint();
        when(taskMapper.findByUuid(TASK_UUID)).thenReturn(task);
        when(jsonUtil.fromJson(task.getCheckpointJson(), TaskCheckpoint.class)).thenReturn(checkpoint);
        when(jsonUtil.toJson(any())).thenReturn("{\"pendingToolCall\":{\"manualReplayApproved\":true}}");

        TaskResponse response = taskService.confirmPendingToolReplay(TASK_UUID, USER_ID);

        assertThat(response.getStatus()).isEqualTo(TaskStatus.RESUMING.getCode());
        assertThat(checkpoint.getPendingToolCall()).isNotNull();
        assertThat(checkpoint.getPendingToolCall().isManualReplayApproved()).isTrue();
        assertThat(checkpoint.getPauseReason()).isNull();
        assertThat(checkpoint.getCurrentState()).isEqualTo(TaskStatus.RESUMING.getCode());
        assertThat(response.getPendingToolName()).isEqualTo("booking_query");
        assertThat(response.getPendingToolReplayApproved()).isTrue();
        verify(taskMapper).updateCheckpoint(task);
        verify(taskMapper).updateStatus(task.getId(), TaskStatus.RESUMING.getCode());
        verify(taskProgressService).recordEvent(eq(TASK_UUID), eq("PENDING_TOOL_REPLAY_CONFIRMED"),
                eq(TaskStatus.RESUMING.getCode()), eq(1), eq(3),
                eq("Pending tool replay confirmed: booking_query"), any());
        verify(sseNotificationService).sendEvent(eq(TASK_UUID), eq(SseEvent.STATE_CHANGE), any());
        verify(taskExecutionDispatcher).dispatchTask(TASK_UUID, "resume:pending_tool_confirmed");
    }

    @Test
    void skipPendingToolReplay_clearsPendingToolAndDispatchesResume() {
        Task task = pendingTask();
        task.setStatus(TaskStatus.PAUSED.getCode());
        TaskCheckpoint checkpoint = pendingToolCheckpoint();
        when(taskMapper.findByUuid(TASK_UUID)).thenReturn(task);
        when(jsonUtil.fromJson(task.getCheckpointJson(), TaskCheckpoint.class)).thenReturn(checkpoint);
        when(jsonUtil.toJson(any())).thenReturn("{\"pendingToolCall\":null}");

        TaskResponse response = taskService.skipPendingToolReplay(TASK_UUID, USER_ID);

        assertThat(response.getStatus()).isEqualTo(TaskStatus.RESUMING.getCode());
        assertThat(checkpoint.getPendingToolCall()).isNull();
        assertThat(checkpoint.getPauseReason()).isEqualTo("pending_tool_skipped");
        assertThat(response.getPendingToolName()).isNull();
        verify(taskMapper).updateCheckpoint(task);
        verify(taskMapper).updateStatus(task.getId(), TaskStatus.RESUMING.getCode());
        verify(taskProgressService).recordEvent(eq(TASK_UUID), eq("PENDING_TOOL_SKIPPED"),
                eq(TaskStatus.RESUMING.getCode()), eq(1), eq(3),
                eq("Pending tool skipped: booking_query"), any());
        verify(taskExecutionDispatcher).dispatchTask(TASK_UUID, "resume:pending_tool_skipped");
    }

    @Test
    void skipPendingToolReplay_withoutPendingToolRejected() {
        Task task = pendingTask();
        task.setStatus(TaskStatus.PAUSED.getCode());
        TaskCheckpoint checkpoint = awaitingCheckpoint(null);
        when(taskMapper.findByUuid(TASK_UUID)).thenReturn(task);
        when(jsonUtil.fromJson(task.getCheckpointJson(), TaskCheckpoint.class)).thenReturn(checkpoint);

        assertThatThrownBy(() -> taskService.skipPendingToolReplay(TASK_UUID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("no pending tool call");
    }

    @Test
    void rewindTask_planningTask_rejected() {
        Task task = pendingTask();
        task.setStatus(TaskStatus.PLANNING.getCode());
        when(taskMapper.findByUuid(TASK_UUID)).thenReturn(task);

        RewindTaskRequest request = new RewindTaskRequest();
        request.setTargetStepIndex(0);

        assertThatThrownBy(() -> taskService.rewindTask(TASK_UUID, USER_ID, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("only paused or awaiting_user_input");
    }

    @Test
    void refreshNodeSelection_updatesCandidatesWithoutCompletingStep() {
        Task task = awaitingUserInputTask();
        TaskCheckpoint checkpoint = awaitingCheckpoint("route_candidate_selection");
        checkpoint.setTaskId(task.getId());
        checkpoint.setUserId(task.getUserId());
        checkpoint.setCurrentStepIndex(1);
        checkpoint.setCompletedSteps(new java.util.ArrayList<>(List.of(completedStep(0, "West Lake"))));
        checkpoint.setSelectedBranchType("rag_route");
        when(taskMapper.findByUuid(TASK_UUID)).thenReturn(task);
        when(jsonUtil.fromJson(task.getCheckpointJson(), TaskCheckpoint.class)).thenReturn(checkpoint);
        when(jsonUtil.toJson(any())).thenReturn("{\"schemaVersion\":\"1.0\"}");

        LocationCandidateItem refreshed = recommendationCandidate("poi-2", "Indoor Museum");
        refreshed.setBranchType("rag_route");
        when(agentService.refreshNodeCandidates(eq(task), eq(checkpoint), eq(TASK_UUID)))
                .thenReturn(PlanningResult.forCandidates(
                        List.of(refreshed),
                        88,
                        "route_candidate_selection",
                        "route_candidate_selection",
                        "rag_route",
                        Map.of("userPreferencePrompt", "室内 少走路"),
                        Map.of()));

        NodeChatRequest request = new NodeChatRequest();
        request.setPendingInputType("route_candidate_selection");
        request.setSelectionStage("route_candidate_selection");
        request.setMessage("室内 少走路");

        TaskResponse response = taskService.refreshNodeSelection(TASK_UUID, USER_ID, request);

        assertThat(response.getStatus()).isEqualTo(TaskStatus.AWAITING_USER_INPUT.getCode());
        assertThat(checkpoint.getCompletedSteps()).hasSize(1);
        assertThat(checkpoint.getRecommendationCandidates()).hasSize(1);
        assertThat(checkpoint.getCurrentContext()).containsEntry("userPreferencePrompt", "室内 少走路");
        verify(sseNotificationService).sendEvent(eq(TASK_UUID), eq(SseEvent.USER_SELECTION_REQUIRED), any());
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

    private Task awaitingUserInputTask() {
        Task task = pendingTask();
        task.setStatus(TaskStatus.AWAITING_USER_INPUT.getCode());
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

    private LocationCandidateItem originCandidate(String candidateId, String name) {
        LocationCandidateItem candidate = new LocationCandidateItem();
        candidate.setCandidateId(candidateId);
        candidate.setName(name);
        candidate.setRegion("Hangzhou");
        candidate.setDistrict("Shangcheng");
        candidate.setAddress("Station Road 1");
        candidate.setLatitude(30.245);
        candidate.setLongitude(120.182);
        candidate.setSource("manual");
        return candidate;
    }

    private LocationCandidateItem recommendationCandidate(String candidateId, String name) {
        LocationCandidateItem candidate = originCandidate(candidateId, name);
        candidate.setCandidateType("poi");
        candidate.setCategory("餐饮服务");
        candidate.setScore(0.69);
        return candidate;
    }

    private SelectionOptionItem selectionOption(String optionId, String branchType) {
        SelectionOptionItem option = new SelectionOptionItem();
        option.setOptionId(optionId);
        option.setLabel("Nearby POI");
        option.setDescription("Use nearby recommendation");
        option.setBranchType(branchType);
        return option;
    }

    private ConfirmOriginSelectionRequest selectionRequest(String pendingInputType, String candidateId, String name) {
        ConfirmOriginSelectionRequest request = new ConfirmOriginSelectionRequest();
        request.setPendingInputType(pendingInputType);
        request.setSelectionStage(pendingInputType);
        request.setSelectedCandidateId(candidateId);
        request.setSelectedCandidateName(name);
        request.setSelectedLat(30.245);
        request.setSelectedLng(120.182);
        return request;
    }

    private TaskCheckpoint awaitingCheckpoint(String pendingInputType) {
        TaskCheckpoint checkpoint = new TaskCheckpoint();
        checkpoint.setTaskUuid(TASK_UUID);
        checkpoint.setTaskId(1L);
        checkpoint.setUserId(USER_ID);
        checkpoint.setPendingInputType(pendingInputType);
        checkpoint.setSelectionStage(pendingInputType);
        checkpoint.setCurrentState(TaskStatus.AWAITING_USER_INPUT.getCode());
        checkpoint.setCurrentContext(new LinkedHashMap<>());
        checkpoint.setWeatherContext(new LinkedHashMap<>());
        checkpoint.setPlanningConfig(new com.travelagent.agent.context.PlanningConfig());
        checkpoint.getPlanningConfig().setTotalDays(1);
        checkpoint.getPlanningConfig().setDynamicTargetSteps(3);
        return checkpoint;
    }

    private TaskCheckpoint pendingToolCheckpoint() {
        TaskCheckpoint checkpoint = awaitingCheckpoint(null);
        checkpoint.setCurrentState(TaskStatus.PAUSED.getCode());
        checkpoint.setPauseReason("pending_tool_replay_requires_confirmation");
        checkpoint.setCurrentStepIndex(1);
        checkpoint.getPlanningConfig().setDynamicTargetSteps(3);
        checkpoint.setPendingToolCall(new PendingToolCall("booking_query", Map.of("hotel", "West Lake"), "idem-booking"));
        return checkpoint;
    }

    private TaskCheckpoint rewindCheckpoint() {
        TaskCheckpoint checkpoint = awaitingCheckpoint("route_candidate_selection");
        checkpoint.setCurrentState(TaskStatus.PAUSED.getCode());
        checkpoint.setDailyTimeWindows(List.of(
                new com.travelagent.agent.context.DailyTimeWindow(
                        1,
                        LocalDateTime.of(2026, 4, 22, 9, 0),
                        LocalDateTime.of(2026, 4, 22, 21, 0)
                )
        ));
        checkpoint.setSelectedDestination(new ResolvedLocation());
        checkpoint.getSelectedDestination().setLatitude(30.2);
        checkpoint.getSelectedDestination().setLongitude(120.2);
        checkpoint.getSelectedDestination().setName("Station");
        checkpoint.setCompletedSteps(new java.util.ArrayList<>(List.of(
                completedStep(0, "West Lake"),
                completedStep(1, "Lingyin Temple")
        )));
        checkpoint.setCurrentStepIndex(2);
        checkpoint.setUsedTimeBudgetMin(260);
        checkpoint.setProjectedReturnToDestinationMin(30);
        checkpoint.setPendingInputType("route_candidate_selection");
        checkpoint.setSelectionOptions(List.of(selectionOption("branch-1", "rag_route")));
        checkpoint.setRecommendationCandidates(List.of(recommendationCandidate("poi-1", "Cafe")));
        checkpoint.setLlmConversationHistory(new java.util.ArrayList<>(List.of(Map.of("role", "user", "content", "old"))));
        return checkpoint;
    }

    private CompletedStep completedStep(int stepIndex, String name) {
        CompletedStep step = new CompletedStep();
        step.setStepIndex(stepIndex);
        step.setDayNumber(1);
        step.setAttractionName(name);
        step.setTrafficTimeFromPrevMin(20);
        step.setEstimatedVisitDurationMin(90);
        step.setTravelTimeToDestinationMin(25);
        return step;
    }

    private void stubConfirmSelection(Task task, TaskCheckpoint checkpoint) {
        when(taskMapper.findByUuid(TASK_UUID)).thenReturn(task, task);
        when(jsonUtil.fromJson(task.getCheckpointJson(), TaskCheckpoint.class)).thenReturn(checkpoint);
        when(jsonUtil.toJson(any())).thenReturn("{\"schemaVersion\":\"1.0\"}");
        when(stateMachine.transition(TaskStatus.AWAITING_USER_INPUT, AgentEvent.USER_INPUT_RECEIVED))
                .thenReturn(TaskStatus.RESUMING);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> captureSelectionConfirmedPayload() {
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(sseNotificationService).sendEvent(eq(TASK_UUID), eq(SseEvent.USER_SELECTION_CONFIRMED), payloadCaptor.capture());
        return payloadCaptor.getValue();
    }

    private CreateTaskRequest buildRequest() {
        CreateTaskRequest req = new CreateTaskRequest();
        req.setRegion("Beijing");
        req.setUserIntent("3 day Beijing trip");
        req.setStartLocationQuery("Guomao Hotel");
        req.setEndLocationQuery("Capital Airport");
        req.setStartTime(LocalDateTime.of(2026, 4, 22, 15, 0));
        req.setEndTime(LocalDateTime.of(2026, 4, 24, 18, 0));
        req.setTotalBudgetYuan(new BigDecimal("3000"));
        req.setLodgingBudgetPerNightYuan(new BigDecimal("500"));
        req.setAccommodationTypes(List.of("hotel", "inn", "homestay"));
        req.setAdultCount(2);
        req.setRoomCount(1);
        req.setTravelMode("driving");
        return req;
    }
}
