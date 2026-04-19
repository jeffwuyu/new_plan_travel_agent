package com.travelagent.agent.planner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelagent.agent.context.CompletedStep;
import com.travelagent.agent.context.PlanningConfig;
import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.client.dashscope.DashscopeLlmClient;
import com.travelagent.model.entity.Task;
import com.travelagent.service.notification.SseEvent;
import com.travelagent.service.notification.SseNotificationService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 中文注释：测试类，用于验证 Markov Planner 的 Prompt 构建、LLM 响应解析与历史管理委托行为。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MarkovPlanner Tests")
class MarkovPlannerTest {

    @Mock private DashscopeLlmClient llmClient;
    @Mock private HistoryManager historyManager;
    @Mock private SseNotificationService sseNotificationService;

    @InjectMocks
    private MarkovPlanner markovPlanner;

    private final JsonUtil jsonUtil = new JsonUtil();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(jsonUtil, "objectMapper",
                new ObjectMapper().findAndRegisterModules());
        ReflectionTestUtils.setField(markovPlanner, "jsonUtil", jsonUtil);
    }

    // -----------------------------------------------------------------------
    // buildSystemPrompt
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("buildSystemPrompt: no completed steps — visited-set section absent")
    void buildSystemPrompt_noCompletedSteps_noVisitedSet() {
        TaskCheckpoint cp = buildCheckpoint(2, 2, "Xi'an", List.of(), List.of());
        String prompt = markovPlanner.buildSystemPrompt(cp);

        assertThat(prompt).contains("Xi'an");
        assertThat(prompt).contains("totalDays=2");
        assertThat(prompt).contains("attractionsPerDay=2");
        assertThat(prompt).doesNotContain("do NOT recommend");
    }

    @Test
    @DisplayName("buildSystemPrompt: with completed steps — visited-set injected")
    void buildSystemPrompt_withCompletedSteps_includesVisitedSet() {
        CompletedStep step = step("Terracotta Army", 34.38, 109.28);
        TaskCheckpoint cp = buildCheckpoint(2, 2, "Xi'an", List.of(step), List.of());

        String prompt = markovPlanner.buildSystemPrompt(cp);

        assertThat(prompt).contains("Terracotta Army");
        assertThat(prompt).contains("do NOT recommend");
    }

    @Test
    @DisplayName("buildSystemPrompt: with preference keywords — keywords injected")
    void buildSystemPrompt_withPreferences_includesKeywords() {
        TaskCheckpoint cp = buildCheckpoint(1, 1, "Chengdu",
                List.of(), List.of("food", "history"));

        String prompt = markovPlanner.buildSystemPrompt(cp);

        assertThat(prompt).contains("food");
        assertThat(prompt).contains("history");
        assertThat(prompt).containsPattern("(?i)preference");
    }

    @Test
    @DisplayName("buildSystemPrompt: response format instructions present")
    void buildSystemPrompt_includesJsonFormatInstruction() {
        TaskCheckpoint cp = buildCheckpoint(1, 1, "Beijing", List.of(), List.of());
        String prompt = markovPlanner.buildSystemPrompt(cp);

        assertThat(prompt).contains("attractionName");
        assertThat(prompt).contains("reason");
    }

    // -----------------------------------------------------------------------
    // buildStepPrompt
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("buildStepPrompt: first step (index=0) — no 'start from' clause")
    void buildStepPrompt_firstStep_noStartFrom() {
        TaskCheckpoint cp = buildCheckpoint(2, 2, "Xi'an", List.of(), List.of());
        cp.setCurrentStepIndex(0);

        String prompt = markovPlanner.buildStepPrompt(cp);

        assertThat(prompt).contains("step 1 of 4");
        assertThat(prompt).contains("day 1");
        assertThat(prompt).doesNotContain("Start from");
    }

    @Test
    @DisplayName("buildStepPrompt: later step — includes last attraction coordinates")
    void buildStepPrompt_laterStep_includesLastAttraction() {
        CompletedStep prev = step("Wild Goose Pagoda", 34.22, 108.96);
        TaskCheckpoint cp = buildCheckpoint(2, 2, "Xi'an", List.of(prev), List.of());
        cp.setCurrentStepIndex(1);

        String prompt = markovPlanner.buildStepPrompt(cp);

        assertThat(prompt).contains("Wild Goose Pagoda");
        assertThat(prompt).contains("34.220000");
        assertThat(prompt).contains("108.960000");
        assertThat(prompt).contains("30 km");
    }

    @Test
    @DisplayName("buildStepPrompt: day and order-in-day numbers are computed correctly")
    void buildStepPrompt_dayAndOrderInDay_computedCorrectly() {
        TaskCheckpoint cp = buildCheckpoint(2, 3, "Guilin", List.of(), List.of());
        // stepIndex=3 → day 2, attraction 1 of 3
        cp.setCurrentStepIndex(3);

        String prompt = markovPlanner.buildStepPrompt(cp);

        assertThat(prompt).contains("day 2");
        assertThat(prompt).contains("step 4 of 6");
    }

    // -----------------------------------------------------------------------
    // parseLlmAttractionName
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("parseLlmAttractionName: valid JSON returns attractionName field")
    void parseLlmAttractionName_validJson_returnsName() {
        String json = "{\"attractionName\":\"Terracotta Army\",\"reason\":\"Famous site\"}";
        assertThat(markovPlanner.parseLlmAttractionName(json, 0))
                .isEqualTo("Terracotta Army");
    }

    @Test
    @DisplayName("parseLlmAttractionName: markdown-fenced JSON is stripped and parsed")
    void parseLlmAttractionName_markdownFenced_stripsAndParses() {
        String fenced = "```json\n{\"attractionName\":\"Wild Goose Pagoda\",\"reason\":\"Historic\"}\n```";
        assertThat(markovPlanner.parseLlmAttractionName(fenced, 1))
                .isEqualTo("Wild Goose Pagoda");
    }

    @Test
    @DisplayName("parseLlmAttractionName: invalid JSON falls back to truncated raw text")
    void parseLlmAttractionName_invalidJson_returnsFallback() {
        String raw = "I recommend the Terracotta Army because it is very famous and unique.";
        String result = markovPlanner.parseLlmAttractionName(raw, 2);
        assertThat(result).hasSize(50);
        assertThat(raw).startsWith(result.trim());
    }

    @Test
    @DisplayName("parseLlmAttractionName: blank response returns 'Unknown Attraction'")
    void parseLlmAttractionName_blankResponse_returnsUnknown() {
        assertThat(markovPlanner.parseLlmAttractionName("", 0))
                .isEqualTo("Unknown Attraction");
        assertThat(markovPlanner.parseLlmAttractionName("   ", 0))
                .isEqualTo("Unknown Attraction");
    }

    @Test
    @DisplayName("parseLlmAttractionName: short raw text returned as-is (under 50 chars)")
    void parseLlmAttractionName_shortRaw_returnsFull() {
        String raw = "not json at all";
        assertThat(markovPlanner.parseLlmAttractionName(raw, 0)).isEqualTo(raw);
    }

    // -----------------------------------------------------------------------
    // planNextAttraction
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("planNextAttraction: calls LLM, appends exchange, returns parsed name")
    void planNextAttraction_callsLlmAndAppendsHistory() {
        TaskCheckpoint cp = buildCheckpoint(1, 1, "Xi'an", List.of(), List.of());
        cp.setCurrentStepIndex(0);
        cp.setLlmConversationHistory(new ArrayList<>());

        Task task = new Task();
        task.setId(1L);
        task.setUserId(10L);

        when(historyManager.prepareForLlm(cp)).thenReturn(List.of());
        when(llmClient.callStreaming(any(), any(), anyString(), anyString(),
                any(), anyString(), anyString(), any()))
                .thenReturn("{\"attractionName\":\"Terracotta Army\",\"reason\":\"Famous\"}");

        String result = markovPlanner.planNextAttraction(task, cp, "test-uuid");

        assertThat(result).isEqualTo("Terracotta Army");

        // History append must be delegated to HistoryManager
        verify(historyManager).appendExchange(eq(cp), anyString(), anyString());

        // SSE LLM_STREAM events should be wired (tokenConsumer passed to LLM client)
        verify(llmClient).callStreaming(any(), any(), anyString(), anyString(),
                any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("planNextAttraction: SSE LLM_STREAM events are forwarded")
    void planNextAttraction_streamsTokensViaSse() {
        TaskCheckpoint cp = buildCheckpoint(1, 1, "Beijing", List.of(), List.of());
        cp.setCurrentStepIndex(0);
        cp.setLlmConversationHistory(new ArrayList<>());
        Task task = new Task();
        task.setId(2L);
        task.setUserId(20L);

        when(historyManager.prepareForLlm(cp)).thenReturn(List.of());
        when(llmClient.callStreaming(any(), any(), anyString(), anyString(),
                any(), anyString(), anyString(), any()))
                .thenAnswer(inv -> {
                    // Simulate token streaming
                    java.util.function.Consumer<String> consumer = inv.getArgument(7);
                    consumer.accept("Forbidden");
                    consumer.accept(" City");
                    return "{\"attractionName\":\"Forbidden City\",\"reason\":\"Imperial palace\"}";
                });

        markovPlanner.planNextAttraction(task, cp, "uuid-2");

        // Two token events should have been sent
        verify(sseNotificationService).sendEvent("uuid-2", SseEvent.LLM_STREAM, Map.of("token", "Forbidden"));
        verify(sseNotificationService).sendEvent("uuid-2", SseEvent.LLM_STREAM, Map.of("token", " City"));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private TaskCheckpoint buildCheckpoint(int days, int perDay, String region,
                                           List<CompletedStep> steps,
                                           List<String> prefs) {
        TaskCheckpoint cp = new TaskCheckpoint();
        cp.setTaskUuid("test-uuid");
        cp.setTaskId(1L);
        cp.setRegion(region);
        cp.setUserIntent("Explore " + region);

        PlanningConfig config = new PlanningConfig();
        config.setTotalDays(days);
        config.setAttractionsPerDay(perDay);
        config.setTravelMode("driving");
        config.setPreferenceKeywords(prefs);
        cp.setPlanningConfig(config);

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
        return s;
    }
}
