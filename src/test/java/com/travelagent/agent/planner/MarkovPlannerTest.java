package com.travelagent.agent.planner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelagent.agent.context.CompletedStep;
import com.travelagent.agent.context.DailyTimeWindow;
import com.travelagent.agent.context.PlanningConfig;
import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.client.dashscope.DashscopeLlmClient;
import com.travelagent.client.dashscope.LlmCallResult;
import com.travelagent.mapper.LlmCallLogMapper;
import com.travelagent.model.dto.NearbyPoiRecommendationResponse;
import com.travelagent.model.dto.RecommendedPoiItem;
import com.travelagent.model.dto.ResolvedLocation;
import com.travelagent.model.entity.Task;
import com.travelagent.service.notification.SseEvent;
import com.travelagent.service.notification.SseNotificationService;
import com.travelagent.service.rag.RagService;
import com.travelagent.service.recommendation.NearbyPoiRecommendationService;
import com.travelagent.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MarkovPlanner Tests")
class MarkovPlannerTest {

    @Mock private DashscopeLlmClient llmClient;
    @Mock private HistoryManager historyManager;
    @Mock private SseNotificationService sseNotificationService;
    @Mock private LlmCallLogMapper llmCallLogMapper;
    @Mock private NearbyPoiRecommendationService nearbyPoiRecommendationService;

    @InjectMocks
    private MarkovPlanner markovPlanner;

    private final JsonUtil jsonUtil = new JsonUtil();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(jsonUtil, "objectMapper",
                new ObjectMapper().findAndRegisterModules());
        ReflectionTestUtils.setField(markovPlanner, "jsonUtil", jsonUtil);
    }

    @Test
    void buildSystemPrompt_includesTimeBudgetAndDestinationConstraint() {
        TaskCheckpoint cp = buildCheckpoint(2, 3, "Xi'an", List.of(), List.of("history"));

        String prompt = markovPlanner.buildSystemPrompt(cp);

        assertThat(prompt).contains("Xi'an");
        assertThat(prompt).contains("Trip window");
        assertThat(prompt).contains("Remaining planning budget");
        assertThat(prompt).contains("Soft destination constraint");
    }

    @Test
    void buildStepPrompt_firstStep_mentionsStartAndRemainingBudget() {
        TaskCheckpoint cp = buildCheckpoint(2, 3, "Xi'an", List.of(), List.of());
        cp.setCurrentStepIndex(0);

        String prompt = markovPlanner.buildStepPrompt(cp);

        assertThat(prompt).contains("overall 1/6");
        assertThat(prompt).contains("Start from Bell Tower");
        assertThat(prompt).contains("Remaining total planning budget");
    }

    @Test
    void buildStepPrompt_laterStep_includesPreviousCoordinatesAndDestinationReserve() {
        CompletedStep prev = step("Wild Goose Pagoda", 34.22, 108.96);
        TaskCheckpoint cp = buildCheckpoint(1, 4, "Xi'an", List.of(prev), List.of());
        cp.setCurrentStepIndex(1);
        cp.setProjectedReturnToDestinationMin(38);

        String prompt = markovPlanner.buildStepPrompt(cp);

        assertThat(prompt).contains("Wild Goose Pagoda");
        assertThat(prompt).contains("34.22");
        assertThat(prompt).contains("38 minutes");
    }

    @Test
    void parseLlmAttractionName_validJson_returnsName() {
        String json = "{\"attractionName\":\"Terracotta Army\",\"reason\":\"Famous site\"}";
        assertThat(markovPlanner.parseLlmAttractionName(json, 0))
                .isEqualTo("Terracotta Army");
    }

    @Test
    void planNextAttraction_prefersRecommendationEngine() {
        TaskCheckpoint cp = buildCheckpoint(1, 1, "Hangzhou", List.of(step("West Lake", 30.25, 120.14)), List.of("lake"));
        cp.setCurrentStepIndex(1);

        NearbyPoiRecommendationResponse response = new NearbyPoiRecommendationResponse();
        RecommendedPoiItem item = new RecommendedPoiItem();
        item.setName("Leifeng Pagoda");
        response.setRecommendations(List.of(item));

        when(nearbyPoiRecommendationService.recommend(any())).thenReturn(response);

        PlanningResult result = markovPlanner.planNextAttraction(new Task(), cp, "rec-uuid");

        assertThat(result.attractionName()).isEqualTo("Leifeng Pagoda");
    }

    @Test
    void planNextAttraction_callsLlmAndAppendsHistory() {
        TaskCheckpoint cp = buildCheckpoint(1, 1, "Xi'an", List.of(), List.of());
        cp.setCurrentStepIndex(0);
        cp.setLlmConversationHistory(new ArrayList<>());

        Task task = new Task();
        task.setId(1L);
        task.setUserId(10L);

        when(nearbyPoiRecommendationService.recommend(any())).thenReturn(new NearbyPoiRecommendationResponse());
        when(historyManager.prepareForLlm(cp)).thenReturn(List.of());
        when(llmClient.defaultPlanningAdvisors()).thenReturn(List.of("travelPlanning", "jsonSchema", "ragContext"));
        when(llmClient.callStreaming(any(), any(), anyString(), anyString(),
                any(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(new LlmCallResult("{\"attractionName\":\"Terracotta Army\",\"reason\":\"Famous\"}", 0));

        PlanningResult result = markovPlanner.planNextAttraction(task, cp, "test-uuid");

        assertThat(result.attractionName()).isEqualTo("Terracotta Army");
        verify(historyManager).appendExchange(eq(cp), anyString(), anyString());
    }

    @Test
    void planNextAttraction_streamingUsageMissing_usesAuditLogTokens() {
        TaskCheckpoint cp = buildCheckpoint(1, 1, "Xi'an", List.of(), List.of());
        cp.setCurrentStepIndex(0);
        cp.setLlmConversationHistory(new ArrayList<>());

        Task task = new Task();
        task.setId(5L);
        task.setUserId(99L);

        when(nearbyPoiRecommendationService.recommend(any())).thenReturn(new NearbyPoiRecommendationResponse());
        when(historyManager.prepareForLlm(cp)).thenReturn(List.of());
        when(llmClient.defaultPlanningAdvisors()).thenReturn(List.of("travelPlanning"));
        when(llmClient.callStreaming(any(), any(), anyString(), anyString(),
                any(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(new LlmCallResult("{\"attractionName\":\"Terracotta Army\",\"reason\":\"Famous\"}", 0));
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

        when(nearbyPoiRecommendationService.recommend(any())).thenReturn(new NearbyPoiRecommendationResponse());
        when(historyManager.prepareForLlm(cp)).thenReturn(List.of());
        when(llmClient.defaultPlanningAdvisors()).thenReturn(List.of("travelPlanning", "jsonSchema", "ragContext"));
        when(llmClient.callStreaming(any(), any(), anyString(), anyString(),
                any(), anyString(), anyString(), any(), any(), any()))
                .thenAnswer(inv -> {
                    java.util.function.Consumer<String> consumer = inv.getArgument(7);
                    consumer.accept("Forbidden");
                    consumer.accept(" City");
                    return new LlmCallResult("{\"attractionName\":\"Forbidden City\",\"reason\":\"Imperial palace\"}", 0);
                });

        markovPlanner.planNextAttraction(task, cp, "uuid-2");

        verify(sseNotificationService).sendEvent("uuid-2", SseEvent.LLM_STREAM, Map.of("token", "Forbidden"));
        verify(sseNotificationService).sendEvent("uuid-2", SseEvent.LLM_STREAM, Map.of("token", " City"));
    }

    @Test
    void buildSystemPrompt_ragServiceDoesNotBreakPrompt() {
        RagService mockRagService = mock(RagService.class);
        ReflectionTestUtils.setField(markovPlanner, "ragService", mockRagService);

        TaskCheckpoint cp = buildCheckpoint(1, 1, "Xi'an", List.of(), List.of());

        assertThatNoException().isThrownBy(() -> markovPlanner.buildSystemPrompt(cp));

        ReflectionTestUtils.setField(markovPlanner, "ragService", null);
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
