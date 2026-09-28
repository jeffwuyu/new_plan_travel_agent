package com.travelagent.agent.planner;

import com.travelagent.agent.context.CompletedStep;
import com.travelagent.agent.context.DailyTimeWindow;
import com.travelagent.agent.context.PlanningConfig;
import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.agent.prompt.PromptAssembly;
import com.travelagent.agent.validation.HallucinationDetectionProperties;
import com.travelagent.agent.validation.HallucinationDetector;
import com.travelagent.client.dashscope.DashscopeLlmClient;
import com.travelagent.client.dashscope.LlmCallResult;
import com.travelagent.mapper.LlmCallLogMapper;
import com.travelagent.model.dto.LocationCandidateItem;
import com.travelagent.model.dto.ResolvedLocation;
import com.travelagent.model.entity.Task;
import com.travelagent.service.notification.SseEvent;
import com.travelagent.service.notification.SseNotificationService;
import com.travelagent.service.rag.RagAttractionCandidateService;
import com.travelagent.service.task.TaskProgressService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MarkovPlanner Tests")
class MarkovPlannerTest {

    @Mock private DashscopeLlmClient llmClient;
    @Mock private HistoryManager historyManager;
    @Mock private SseNotificationService sseNotificationService;
    @Mock private LlmCallLogMapper llmCallLogMapper;
    @Mock private PlannerPromptBuilder promptBuilder;
    @Mock private PlannerResponseParser responseParser;
    @Mock private RagAttractionCandidateService ragAttractionCandidateService;
    @Mock private TaskProgressService taskProgressService;

    @InjectMocks
    private MarkovPlanner markovPlanner;

    @Test
    void planNextAttraction_withoutBranchSelection_returnsRagCandidates() {
        TaskCheckpoint cp = buildCheckpoint("Hangzhou", List.of(step("West Lake", 30.25, 120.14)));
        cp.setSelectedBranchType(null);
        Task task = task();
        LocationCandidateItem base = candidate("rag-1", "Lingyin Temple", 0.91);
        LocationCandidateItem llm = candidate("rag-1", "Lingyin Temple", null);
        llm.setRouteSummary("West Lake -> Lingyin Temple");
        llm.setExplanations(List.of("RAG matched Buddhist culture"));

        when(promptBuilder.buildSelectionContext(eq(cp), any(), any(), eq("rag_route")))
                .thenReturn(new LinkedHashMap<>(Map.of("currentPositionName", "West Lake")));
        when(ragAttractionCandidateService.buildCandidates(eq(cp), any(), any(), eq("task-uuid")))
                .thenReturn(List.of(base));
        when(historyManager.prepareForLlm(cp)).thenReturn(List.of());
        when(promptBuilder.buildRouteCandidatePrompt(eq(cp), any(), any(), eq(List.of(base))))
                .thenReturn(PromptAssembly.legacy("system", "user"));
        when(promptBuilder.buildRouteCandidateUserPrompt(eq(cp), any(), any(), eq(List.of(base)))).thenReturn("user");
        when(promptBuilder.buildAdvisorContext(eq(cp), eq(List.of()))).thenReturn(Map.of());
        when(llmClient.defaultPlanningAdvisors()).thenReturn(List.of("travelPlanning"));
        when(llmClient.callStreaming(any(), any(), anyString(), any(PromptAssembly.class),
                any(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(new LlmCallResult("{\"routes\":[]}", 12));
        when(responseParser.parseRouteCandidates(eq("{\"routes\":[]}"), any())).thenReturn(List.of(llm));

        PlanningResult result = markovPlanner.planNextAttraction(task, cp, "task-uuid");

        assertThat(result.requiresUserSelection()).isTrue();
        assertThat(result.pendingInputType()).isEqualTo("route_candidate_selection");
        assertThat(result.selectedBranchType()).isEqualTo("rag_route");
        assertThat(result.selectionOptions()).isEmpty();
        assertThat(result.recommendationCandidates()).hasSize(1);
        assertThat(result.recommendationCandidates().get(0).getCandidateType()).isEqualTo("rag_candidate");
        assertThat(result.recommendationCandidates().get(0).getBranchType()).isEqualTo("rag_route");
        assertThat(result.recommendationCandidates().get(0).getRouteSummary()).isEqualTo("West Lake -> Lingyin Temple");
        verify(historyManager).appendExchange(cp, "rag_route", "{\"routes\":[]}");
    }

    @Test
    void planNextAttraction_emptyRagCandidates_waitsWithEmptyMessageAndSkipsLlm() {
        TaskCheckpoint cp = buildCheckpoint("Xi'an", List.of());
        Task task = task();
        Map<String, Object> selectionContext = new LinkedHashMap<>();
        when(promptBuilder.buildSelectionContext(eq(cp), any(), any(), eq("rag_route"))).thenReturn(selectionContext);
        when(ragAttractionCandidateService.buildCandidates(eq(cp), any(), any(), eq("task-uuid")))
                .thenReturn(List.of());

        PlanningResult result = markovPlanner.planNextAttraction(task, cp, "task-uuid");

        assertThat(result.requiresUserSelection()).isTrue();
        assertThat(result.recommendationCandidates()).isEmpty();
        assertThat(result.currentContext()).containsKey("emptyCandidateMessage");
        verify(llmClient, never()).callStreaming(any(), any(), anyString(), any(PromptAssembly.class),
                any(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void planNextAttraction_streamsTokensViaSse() {
        TaskCheckpoint cp = buildCheckpoint("Beijing", List.of());
        Task task = task();
        LocationCandidateItem base = candidate("rag-1", "Forbidden City", 0.9);

        when(promptBuilder.buildSelectionContext(eq(cp), any(), any(), eq("rag_route")))
                .thenReturn(new LinkedHashMap<>());
        when(ragAttractionCandidateService.buildCandidates(eq(cp), any(), any(), eq("uuid-2")))
                .thenReturn(List.of(base));
        when(historyManager.prepareForLlm(cp)).thenReturn(List.of());
        when(promptBuilder.buildRouteCandidatePrompt(eq(cp), any(), any(), eq(List.of(base))))
                .thenReturn(PromptAssembly.legacy("system", "user"));
        when(promptBuilder.buildRouteCandidateUserPrompt(eq(cp), any(), any(), eq(List.of(base)))).thenReturn("user");
        when(promptBuilder.buildAdvisorContext(eq(cp), eq(List.of()))).thenReturn(Map.of());
        when(llmClient.defaultPlanningAdvisors()).thenReturn(List.of("travelPlanning"));
        when(llmClient.callStreaming(any(), any(), anyString(), any(PromptAssembly.class),
                any(), anyString(), anyString(), any(), any(), any()))
                .thenAnswer(inv -> {
                    java.util.function.Consumer<String> consumer = inv.getArgument(7);
                    consumer.accept("Forbidden");
                    consumer.accept(" City");
                    return new LlmCallResult("{\"routes\":[]}", 0);
                });
        when(responseParser.parseRouteCandidates(anyString(), any())).thenReturn(List.of());

        markovPlanner.planNextAttraction(task, cp, "uuid-2");

        verify(sseNotificationService).sendEvent("uuid-2", SseEvent.LLM_STREAM, Map.of("token", "Forbidden"));
        verify(sseNotificationService).sendEvent("uuid-2", SseEvent.LLM_STREAM, Map.of("token", " City"));
    }

    @Test
    void generateFinalSummary_retriesWithHallucinationFeedback() {
        injectDetector(new HallucinationDetector(new HallucinationDetectionProperties()));
        TaskCheckpoint cp = buildCheckpoint("Hangzhou", List.of(stepWithPhoneEvidence()));
        Task task = task();
        task.setTaskUuid("summary-task");
        FinalSummaryResult hallucinated = new FinalSummaryResult("杭州1日精华游", "湖景与城市文化串联。",
                List.of(new FinalSummaryResult.StepSummary(0, 120, "酒店电话 13800138000，建议提前联系。")));
        FinalSummaryResult grounded = new FinalSummaryResult("杭州1日精华游", "湖景与城市文化串联。",
                List.of(new FinalSummaryResult.StepSummary(0, 120, "工具未返回酒店电话，出行前请以官方渠道确认为准。")));

        when(promptBuilder.buildFinalSummaryPrompt(cp)).thenReturn(PromptAssembly.legacy("system", "user"));
        when(promptBuilder.buildFinalSummaryUserMessage(cp)).thenReturn("final summary input");
        when(llmClient.call(eq(1L), eq(10L), eq("final_summary"), any(PromptAssembly.class),
                eq(List.of()), anyString(), anyString()))
                .thenReturn("bad-json", "good-json");
        when(responseParser.parseFinalSummary("bad-json", cp)).thenReturn(hallucinated);
        when(responseParser.parseFinalSummary("good-json", cp)).thenReturn(grounded);

        FinalSummaryResult result = markovPlanner.generateFinalSummary(task, cp, "summary-task");

        assertThat(result).isEqualTo(grounded);
        assertThat(cp.getValidatorResults()).hasSize(2);
        verify(taskProgressService).recordEvent(eq("summary-task"), eq("HALLUCINATION_DETECTION_WARNING"),
                any(), any(), any(), anyString(), any());
        verify(llmClient).call(eq(1L), eq(10L), eq("final_summary"), any(PromptAssembly.class),
                eq(List.of()), contains("13800138000"), eq("summary-task-final-summary-hdet-retry-1"));
    }

    private Task task() {
        Task task = new Task();
        task.setId(1L);
        task.setUserId(10L);
        return task;
    }

    private TaskCheckpoint buildCheckpoint(String region, List<CompletedStep> steps) {
        TaskCheckpoint cp = new TaskCheckpoint();
        cp.setTaskUuid("test-uuid");
        cp.setTaskId(1L);
        cp.setRegion(region);
        cp.setUserIntent("Explore " + region);
        cp.setTripStartTime(LocalDateTime.of(2026, 4, 22, 9, 0));
        cp.setTripEndTime(LocalDateTime.of(2026, 4, 22, 21, 0));

        PlanningConfig config = new PlanningConfig();
        config.setTotalDays(1);
        config.setAttractionsPerDay(3);
        config.setDynamicTargetSteps(3);
        config.setTravelMode("driving");
        config.setDefaultVisitDurationMin(120);
        config.setFullDayStartTime(LocalTime.of(9, 0));
        config.setFullDayEndTime(LocalTime.of(21, 0));
        cp.setPlanningConfig(config);
        cp.setDailyTimeWindows(List.of(new DailyTimeWindow(1,
                LocalDateTime.of(2026, 4, 22, 9, 0),
                LocalDateTime.of(2026, 4, 22, 21, 0))));
        cp.setRemainingTimeBudgetMin(480);
        cp.setCompletedSteps(new ArrayList<>(steps));
        cp.setCurrentStepIndex(steps.size());

        ResolvedLocation origin = new ResolvedLocation();
        origin.setName("Hotel");
        origin.setLatitude(30.25);
        origin.setLongitude(120.14);
        cp.setSelectedOrigin(origin);
        return cp;
    }

    private CompletedStep step(String name, double lat, double lng) {
        CompletedStep s = new CompletedStep();
        s.setAttractionName(name);
        s.setLat(lat);
        s.setLng(lng);
        s.setDayNumber(1);
        return s;
    }

    private CompletedStep stepWithPhoneEvidence() {
        CompletedStep s = step("West Lake", 30.25, 120.14);
        s.setStepIndex(0);
        s.setToolCallResults(Map.of("booking_query", Map.of("hotelName", "Lake Hotel")));
        return s;
    }

    private void injectDetector(HallucinationDetector detector) {
        org.springframework.test.util.ReflectionTestUtils.setField(markovPlanner, "hallucinationDetector", detector);
        org.springframework.test.util.ReflectionTestUtils.setField(markovPlanner, "taskProgressService", taskProgressService);
    }

    private LocationCandidateItem candidate(String id, String name, Double score) {
        LocationCandidateItem candidate = new LocationCandidateItem();
        candidate.setCandidateId(id);
        candidate.setCandidateType("rag_candidate");
        candidate.setBranchType("rag_route");
        candidate.setName(name);
        candidate.setTargetAttractionName(name);
        candidate.setScore(score);
        candidate.setRouteSummary("current -> " + name);
        candidate.setEstimatedTotalDurationMin(150);
        return candidate;
    }
}
