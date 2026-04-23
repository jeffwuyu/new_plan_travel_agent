package com.travelagent.agent.planner;

import com.travelagent.agent.context.CompletedStep;
import com.travelagent.agent.context.DailyTimeWindow;
import com.travelagent.agent.context.PlanningConfig;
import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.client.dashscope.DashscopeLlmClient;
import com.travelagent.client.dashscope.LlmCallResult;
import com.travelagent.mapper.LlmCallLogMapper;
import com.travelagent.model.dto.NearbyPoiRecommendationRequest;
import com.travelagent.model.dto.NearbyPoiRecommendationResponse;
import com.travelagent.model.dto.RecommendedPoiItem;
import com.travelagent.model.dto.ResolvedLocation;
import com.travelagent.model.dto.SelectionOptionItem;
import com.travelagent.model.entity.Task;
import com.travelagent.service.notification.SseEvent;
import com.travelagent.service.notification.SseNotificationService;
import com.travelagent.service.recommendation.NearbyPoiRecommendationService;
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
    @Mock private NearbyPoiRecommendationService nearbyPoiRecommendationService;

    @InjectMocks
    private MarkovPlanner markovPlanner;

    @Test
    void planNextAttraction_withoutBranchSelection_returnsSelectionOptions() {
        TaskCheckpoint cp = buildCheckpoint(1, 1, "Xi'an", List.of(), List.of());
        cp.setSelectedBranchType(null);

        List<SelectionOptionItem> options = List.of(new SelectionOptionItem(), new SelectionOptionItem());
        Map<String, Object> selectionContext = new LinkedHashMap<>(Map.of("dayNumber", 1));

        when(promptBuilder.buildBranchSelectionOptions(any())).thenReturn(options);
        when(promptBuilder.buildSelectionContext(eq(cp), any(), any(), eq(null)))
                .thenReturn(selectionContext);

        PlanningResult result = markovPlanner.planNextAttraction(new Task(), cp, "branch-uuid");

        assertThat(result.requiresUserSelection()).isTrue();
        assertThat(result.pendingInputType()).isEqualTo("selection_branch");
        assertThat(result.selectionOptions()).isEqualTo(options);
        assertThat(result.currentContext()).isEqualTo(selectionContext);
        assertThat(result.weatherContext()).containsKeys("summary", "constraintHints", "source");
    }

    @Test
    void planNextAttraction_prefersRecommendationEngine() {
        TaskCheckpoint cp = buildCheckpoint(1, 1, "Hangzhou", List.of(step("West Lake", 30.25, 120.14)), List.of("lake"));
        cp.setCurrentStepIndex(1);
        cp.setSelectedBranchType("nearby_poi");

        NearbyPoiRecommendationRequest request = new NearbyPoiRecommendationRequest();
        NearbyPoiRecommendationResponse response = new NearbyPoiRecommendationResponse();
        RecommendedPoiItem item = new RecommendedPoiItem();
        item.setName("Leifeng Pagoda");
        item.setAmapPoiId("poi-1");
        response.setRecommendations(List.of(item));

        Map<String, Object> selectionContext = new LinkedHashMap<>(Map.of("currentPositionName", "West Lake"));

        when(promptBuilder.buildRecommendationRequest(eq(cp), any(), any())).thenReturn(request);
        when(nearbyPoiRecommendationService.recommend(request)).thenReturn(response);
        when(responseParser.resolveWeatherSuitability(any(), any())).thenReturn("weather-friendly");
        when(promptBuilder.buildSelectionContext(eq(cp), any(), any(), eq("nearby_poi")))
                .thenReturn(selectionContext);

        PlanningResult result = markovPlanner.planNextAttraction(new Task(), cp, "rec-uuid");

        assertThat(result.requiresUserSelection()).isTrue();
        assertThat(result.selectedBranchType()).isEqualTo("nearby_poi");
        assertThat(result.recommendationCandidates()).hasSize(1);
        assertThat(result.recommendationCandidates().get(0).getName()).isEqualTo("Leifeng Pagoda");
        assertThat(result.currentContext()).containsEntry("currentPositionName", "West Lake");
        assertThat(result.currentContext()).containsKey("emptyCandidateMessage");
    }

    @Test
    void planNextAttraction_callsLlmAndAppendsHistory() {
        TaskCheckpoint cp = buildCheckpoint(1, 1, "Xi'an", List.of(), List.of());
        cp.setCurrentStepIndex(0);
        cp.setLlmConversationHistory(new ArrayList<>());

        Task task = new Task();
        task.setId(1L);
        task.setUserId(10L);

        when(historyManager.prepareForLlm(cp)).thenReturn(List.of());
        when(promptBuilder.buildSystemPrompt(cp)).thenReturn("system prompt");
        when(promptBuilder.buildStepPrompt(cp)).thenReturn("user prompt");
        when(promptBuilder.buildAdvisorContext(eq(cp), eq(List.of()))).thenReturn(Map.of("schema", "value"));
        when(llmClient.defaultPlanningAdvisors()).thenReturn(List.of("travelPlanning", "jsonSchema", "ragContext"));
        when(llmClient.callStreaming(any(), any(), anyString(), anyString(),
                any(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(new LlmCallResult("{\"attractionName\":\"Terracotta Army\",\"reason\":\"Famous\"}", 0));
        when(responseParser.parseLlmAttractionName("{\"attractionName\":\"Terracotta Army\",\"reason\":\"Famous\"}", 0))
                .thenReturn("Terracotta Army");

        PlanningResult result = markovPlanner.planNextAttraction(task, cp, "test-uuid");

        assertThat(result.attractionName()).isEqualTo("Terracotta Army");
        verify(historyManager).appendExchange(cp, "user prompt",
                "{\"attractionName\":\"Terracotta Army\",\"reason\":\"Famous\"}");
    }

    @Test
    void planNextAttraction_streamingUsageMissing_usesAuditLogTokens() {
        TaskCheckpoint cp = buildCheckpoint(1, 1, "Xi'an", List.of(), List.of());
        cp.setCurrentStepIndex(0);
        cp.setLlmConversationHistory(new ArrayList<>());

        Task task = new Task();
        task.setId(5L);
        task.setUserId(99L);

        when(historyManager.prepareForLlm(cp)).thenReturn(List.of());
        when(promptBuilder.buildSystemPrompt(cp)).thenReturn("system prompt");
        when(promptBuilder.buildStepPrompt(cp)).thenReturn("user prompt");
        when(promptBuilder.buildAdvisorContext(eq(cp), eq(List.of()))).thenReturn(Map.of());
        when(llmClient.defaultPlanningAdvisors()).thenReturn(List.of("travelPlanning"));
        when(llmClient.callStreaming(any(), any(), anyString(), anyString(),
                any(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(new LlmCallResult("{\"attractionName\":\"Terracotta Army\",\"reason\":\"Famous\"}", 0));
        when(responseParser.parseLlmAttractionName("{\"attractionName\":\"Terracotta Army\",\"reason\":\"Famous\"}", 0))
                .thenReturn("Terracotta Army");
        when(llmCallLogMapper.findLatestSuccessfulTotalTokens(5L, "task-uuid-step0-llm")).thenReturn(321);

        PlanningResult result = markovPlanner.planNextAttraction(task, cp, "task-uuid");

        assertThat(result.totalTokens()).isEqualTo(321);
    }

    @Test
    void planNextAttraction_streamsTokensViaSse() {
        TaskCheckpoint cp = buildCheckpoint(1, 1, "Beijing", List.of(), List.of());
        cp.setCurrentStepIndex(0);
        cp.setLlmConversationHistory(new ArrayList<>());

        Task task = new Task();
        task.setId(2L);
        task.setUserId(20L);

        when(historyManager.prepareForLlm(cp)).thenReturn(List.of());
        when(promptBuilder.buildSystemPrompt(cp)).thenReturn("system prompt");
        when(promptBuilder.buildStepPrompt(cp)).thenReturn("user prompt");
        when(promptBuilder.buildAdvisorContext(eq(cp), eq(List.of()))).thenReturn(Map.of());
        when(llmClient.defaultPlanningAdvisors()).thenReturn(List.of("travelPlanning", "jsonSchema", "ragContext"));
        when(llmClient.callStreaming(any(), any(), anyString(), anyString(),
                any(), anyString(), anyString(), any(), any(), any()))
                .thenAnswer(inv -> {
                    java.util.function.Consumer<String> consumer = inv.getArgument(7);
                    consumer.accept("Forbidden");
                    consumer.accept(" City");
                    return new LlmCallResult("{\"attractionName\":\"Forbidden City\",\"reason\":\"Imperial palace\"}", 0);
                });
        when(responseParser.parseLlmAttractionName("{\"attractionName\":\"Forbidden City\",\"reason\":\"Imperial palace\"}", 0))
                .thenReturn("Forbidden City");

        markovPlanner.planNextAttraction(task, cp, "uuid-2");

        verify(sseNotificationService).sendEvent("uuid-2", SseEvent.LLM_STREAM, Map.of("token", "Forbidden"));
        verify(sseNotificationService).sendEvent("uuid-2", SseEvent.LLM_STREAM, Map.of("token", " City"));
    }

    private TaskCheckpoint buildCheckpoint(int days, int perDay, String region,
                                           List<CompletedStep> steps,
                                           List<String> prefs) {
        TaskCheckpoint cp = new TaskCheckpoint();
        cp.setTaskUuid("test-uuid");
        cp.setTaskId(1L);
        cp.setRegion(region);
        cp.setUserIntent("Explore " + region);
        cp.setStartLocationQuery("Bell Tower");
        cp.setEndLocationQuery("Xi'an North Station");
        cp.setTripStartTime(LocalDateTime.of(2026, 4, 22, 9, 0));
        cp.setTripEndTime(LocalDateTime.of(2026, 4, 23, 18, 0));

        PlanningConfig config = new PlanningConfig();
        config.setTotalDays(days);
        config.setAttractionsPerDay(perDay);
        config.setDynamicTargetSteps(days * perDay);
        config.setTravelMode("driving");
        config.setPreferenceKeywords(prefs);
        config.setStartLocationQuery("Bell Tower");
        config.setEndLocationQuery("Xi'an North Station");
        config.setStartTime(cp.getTripStartTime());
        config.setEndTime(cp.getTripEndTime());
        config.setFullDayStartTime(LocalTime.of(7, 0));
        config.setFullDayEndTime(LocalTime.of(21, 0));
        cp.setPlanningConfig(config);

        cp.setDailyTimeWindows(List.of(
                new DailyTimeWindow(1, LocalDateTime.of(2026, 4, 22, 9, 0), LocalDateTime.of(2026, 4, 22, 21, 0)),
                new DailyTimeWindow(2, LocalDateTime.of(2026, 4, 23, 7, 0), LocalDateTime.of(2026, 4, 23, 18, 0))
        ));
        cp.setRemainingTimeBudgetMin(480);

        ResolvedLocation origin = new ResolvedLocation();
        origin.setName("Bell Tower");
        origin.setLatitude(34.26);
        origin.setLongitude(108.95);
        cp.setSelectedOrigin(origin);

        ResolvedLocation destination = new ResolvedLocation();
        destination.setName("Xi'an North Station");
        destination.setLatitude(34.38);
        destination.setLongitude(108.94);
        cp.setSelectedDestination(destination);

        cp.setCompletedSteps(new ArrayList<>(steps));
        cp.setLlmConversationHistory(new ArrayList<>());
        cp.setCurrentStepIndex(steps.size());
        cp.setSelectedBranchType("manual");
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
}
