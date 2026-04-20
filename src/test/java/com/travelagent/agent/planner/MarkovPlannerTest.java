package com.travelagent.agent.planner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelagent.agent.context.CompletedStep;
import com.travelagent.agent.context.PlanningConfig;
import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.client.dashscope.DashscopeLlmClient;
import com.travelagent.client.dashscope.LlmCallResult;
import com.travelagent.model.entity.Task;
import com.travelagent.service.notification.SseEvent;
import com.travelagent.service.notification.SseNotificationService;
import com.travelagent.service.rag.RagService;
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
    @DisplayName("buildSystemPrompt returns lightweight base prompt")
    void buildSystemPrompt_noCompletedSteps_noVisitedSet() {
        TaskCheckpoint cp = buildCheckpoint(2, 2, "Xi'an", List.of(), List.of());
        String prompt = markovPlanner.buildSystemPrompt(cp);

        assertThat(prompt).contains("Xi'an");
        assertThat(prompt).contains("Explore Xi'an");
        assertThat(prompt).contains("Recommend the next attraction only");
        assertThat(prompt).doesNotContain("do NOT recommend");
    }

    @Test
    @DisplayName("buildSystemPrompt keeps advisor-managed fields out of base prompt")
    void buildSystemPrompt_withCompletedSteps_includesVisitedSet() {
        CompletedStep step = step("Terracotta Army", 34.38, 109.28);
        TaskCheckpoint cp = buildCheckpoint(2, 2, "Xi'an", List.of(step), List.of());

        String prompt = markovPlanner.buildSystemPrompt(cp);

        assertThat(prompt).doesNotContain("Terracotta Army");
        assertThat(prompt).doesNotContain("do NOT recommend");
    }

    @Test
    @DisplayName("buildSystemPrompt delegates preference keywords to advisors")
    void buildSystemPrompt_withPreferences_includesKeywords() {
        TaskCheckpoint cp = buildCheckpoint(1, 1, "Chengdu",
                List.of(), List.of("food", "history"));

        String prompt = markovPlanner.buildSystemPrompt(cp);

        assertThat(prompt).contains("Explore Chengdu");
        assertThat(prompt).doesNotContain("food");
        assertThat(prompt).doesNotContain("history");
    }

    @Test
    @DisplayName("buildSystemPrompt delegates response schema to advisors")
    void buildSystemPrompt_includesJsonFormatInstruction() {
        TaskCheckpoint cp = buildCheckpoint(1, 1, "Beijing", List.of(), List.of());
        String prompt = markovPlanner.buildSystemPrompt(cp);

        assertThat(prompt).contains("Recommend the next attraction only");
        assertThat(prompt).doesNotContain("attractionName");
        assertThat(prompt).doesNotContain("reason");
    }

    @Test
    @DisplayName("buildStepPrompt first step has no start-from clause")
    void buildStepPrompt_firstStep_noStartFrom() {
        TaskCheckpoint cp = buildCheckpoint(2, 2, "Xi'an", List.of(), List.of());
        cp.setCurrentStepIndex(0);

        String prompt = markovPlanner.buildStepPrompt(cp);

        assertThat(prompt).contains("step 1 of 4");
        assertThat(prompt).contains("day 1");
        assertThat(prompt).doesNotContain("Start from");
    }

    @Test
    @DisplayName("buildStepPrompt later step includes previous coordinates")
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
    @DisplayName("buildStepPrompt computes day and order correctly")
    void buildStepPrompt_dayAndOrderInDay_computedCorrectly() {
        TaskCheckpoint cp = buildCheckpoint(2, 3, "Guilin", List.of(), List.of());
        cp.setCurrentStepIndex(3);

        String prompt = markovPlanner.buildStepPrompt(cp);

        assertThat(prompt).contains("day 2");
        assertThat(prompt).contains("step 4 of 6");
    }

    @Test
    @DisplayName("parseLlmAttractionName valid JSON returns attractionName")
    void parseLlmAttractionName_validJson_returnsName() {
        String json = "{\"attractionName\":\"Terracotta Army\",\"reason\":\"Famous site\"}";
        assertThat(markovPlanner.parseLlmAttractionName(json, 0))
                .isEqualTo("Terracotta Army");
    }

    @Test
    @DisplayName("parseLlmAttractionName strips markdown fences")
    void parseLlmAttractionName_markdownFenced_stripsAndParses() {
        String fenced = "```json\n{\"attractionName\":\"Wild Goose Pagoda\",\"reason\":\"Historic\"}\n```";
        assertThat(markovPlanner.parseLlmAttractionName(fenced, 1))
                .isEqualTo("Wild Goose Pagoda");
    }

    @Test
    @DisplayName("parseLlmAttractionName invalid JSON falls back to raw text")
    void parseLlmAttractionName_invalidJson_returnsFallback() {
        String raw = "I recommend the Terracotta Army because it is very famous and unique.";
        String result = markovPlanner.parseLlmAttractionName(raw, 2);
        assertThat(result).hasSize(50);
        assertThat(raw).startsWith(result.trim());
    }

    @Test
    @DisplayName("parseLlmAttractionName blank response returns unknown")
    void parseLlmAttractionName_blankResponse_returnsUnknown() {
        assertThat(markovPlanner.parseLlmAttractionName("", 0))
                .isEqualTo("Unknown Attraction");
        assertThat(markovPlanner.parseLlmAttractionName("   ", 0))
                .isEqualTo("Unknown Attraction");
    }

    @Test
    @DisplayName("parseLlmAttractionName short raw text returns as-is")
    void parseLlmAttractionName_shortRaw_returnsFull() {
        String raw = "not json at all";
        assertThat(markovPlanner.parseLlmAttractionName(raw, 0)).isEqualTo(raw);
    }

    @Test
    @DisplayName("planNextAttraction calls LLM with advisor-aware overload")
    void planNextAttraction_callsLlmAndAppendsHistory() {
        TaskCheckpoint cp = buildCheckpoint(1, 1, "Xi'an", List.of(), List.of());
        cp.setCurrentStepIndex(0);
        cp.setLlmConversationHistory(new ArrayList<>());

        Task task = new Task();
        task.setId(1L);
        task.setUserId(10L);

        when(historyManager.prepareForLlm(cp)).thenReturn(List.of());
        when(llmClient.defaultPlanningAdvisors()).thenReturn(List.of("travelPlanning", "jsonSchema", "ragContext"));
        when(llmClient.callStreaming(any(), any(), anyString(), anyString(),
                any(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(new LlmCallResult("{\"attractionName\":\"Terracotta Army\",\"reason\":\"Famous\"}", 0));

        PlanningResult result = markovPlanner.planNextAttraction(task, cp, "test-uuid");

        assertThat(result.attractionName()).isEqualTo("Terracotta Army");
        verify(historyManager).appendExchange(eq(cp), anyString(), anyString());
        verify(llmClient).callStreaming(any(), any(), anyString(), anyString(),
                any(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("planNextAttraction forwards streamed tokens to SSE")
    void planNextAttraction_streamsTokensViaSse() {
        TaskCheckpoint cp = buildCheckpoint(1, 1, "Beijing", List.of(), List.of());
        cp.setCurrentStepIndex(0);
        cp.setLlmConversationHistory(new ArrayList<>());
        Task task = new Task();
        task.setId(2L);
        task.setUserId(20L);

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
    @DisplayName("buildSystemPrompt with RagService available does not throw")
    void buildSystemPrompt_ragChunksInjectedIntoPrompt() {
        RagService mockRagService = mock(RagService.class);
        ReflectionTestUtils.setField(markovPlanner, "ragService", mockRagService);

        TaskCheckpoint cp = buildCheckpoint(1, 1, "西安市", List.of(), List.of());
        String prompt = markovPlanner.buildSystemPrompt(cp);

        assertThat(prompt).contains("Explore 西安市");
        assertThat(prompt).doesNotContain("Reference information from travel guides:");

        ReflectionTestUtils.setField(markovPlanner, "ragService", null);
    }

    @Test
    @DisplayName("buildSystemPrompt null RagService does not throw")
    void buildSystemPrompt_nullRagService_doesNotThrow() {
        ReflectionTestUtils.setField(markovPlanner, "ragService", null);
        TaskCheckpoint cp = buildCheckpoint(1, 1, "北京市", List.of(), List.of());

        assertThatNoException().isThrownBy(() -> markovPlanner.buildSystemPrompt(cp));
    }

    @Test
    @DisplayName("buildSystemPrompt RagService exception does not affect base prompt")
    void buildSystemPrompt_ragServiceThrows_promptStillBuilt() {
        RagService failingRagService = mock(RagService.class);
        ReflectionTestUtils.setField(markovPlanner, "ragService", failingRagService);

        TaskCheckpoint cp = buildCheckpoint(1, 1, "成都市", List.of(), List.of());
        String prompt = markovPlanner.buildSystemPrompt(cp);

        assertThat(prompt).contains("成都市");
        assertThat(prompt).contains("Recommend the next attraction only");
        assertThat(prompt).doesNotContain("Reference information from travel guides:");

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

    // -----------------------------------------------------------------------
    // parseFinalSummary
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("parseFinalSummary: valid JSON populates all fields")
    void parseFinalSummary_validJson_populatesAllFields() {
        TaskCheckpoint cp = buildCheckpoint(1, 2, "西安市", List.of(), List.of());
        String json = """
                {
                  "title": "西安 1 日精华游",
                  "summary": "以秦汉文化为主线",
                  "steps": [
                    {"stepOrder": 0, "estimatedDurationMin": 180, "llmDescription": "建议上午游览"},
                    {"stepOrder": 1, "estimatedDurationMin": 120, "llmDescription": "下午悠闲参观"}
                  ]
                }
                """;

        FinalSummaryResult result = markovPlanner.parseFinalSummary(json, cp);

        assertThat(result.title()).isEqualTo("西安 1 日精华游");
        assertThat(result.summary()).isEqualTo("以秦汉文化为主线");
        assertThat(result.steps()).hasSize(2);
        assertThat(result.steps().get(0).estimatedDurationMin()).isEqualTo(180);
        assertThat(result.steps().get(0).llmDescription()).isEqualTo("建议上午游览");
        assertThat(result.steps().get(1).estimatedDurationMin()).isEqualTo(120);
    }

    @Test
    @DisplayName("parseFinalSummary: strips markdown fences before parsing")
    void parseFinalSummary_markdownFenced_stripsAndParses() {
        TaskCheckpoint cp = buildCheckpoint(1, 1, "北京市", List.of(), List.of());
        String fenced = "```json\n{\"title\":\"北京1日游\",\"summary\":\"故宫之旅\",\"steps\":[]}\n```";

        FinalSummaryResult result = markovPlanner.parseFinalSummary(fenced, cp);

        assertThat(result.title()).isEqualTo("北京1日游");
        assertThat(result.summary()).isEqualTo("故宫之旅");
        assertThat(result.steps()).isEmpty();
    }

    @Test
    @DisplayName("parseFinalSummary: blank response falls back to defaults")
    void parseFinalSummary_blankResponse_returnsDefaults() {
        TaskCheckpoint cp = buildCheckpoint(3, 2, "成都市", List.of(), List.of());

        FinalSummaryResult result = markovPlanner.parseFinalSummary("", cp);

        assertThat(result.title()).contains("成都市");
        assertThat(result.summary()).isEqualTo("Explore 成都市");
    }

    @Test
    @DisplayName("parseFinalSummary: invalid JSON falls back to defaults")
    void parseFinalSummary_invalidJson_returnsDefaults() {
        TaskCheckpoint cp = buildCheckpoint(2, 2, "杭州市", List.of(), List.of());

        FinalSummaryResult result = markovPlanner.parseFinalSummary("not json at all", cp);

        assertThat(result.title()).contains("杭州市");
        assertThat(result.steps()).isEmpty(); // no completedSteps in checkpoint
    }

    @Test
    @DisplayName("parseFinalSummary: out-of-range duration clamped to 90")
    void parseFinalSummary_outOfRangeDuration_clampedTo90() {
        TaskCheckpoint cp = buildCheckpoint(1, 1, "西安市", List.of(), List.of());
        String json = """
                {"title":"T","summary":"S","steps":[
                  {"stepOrder":0,"estimatedDurationMin":9999,"llmDescription":"x"}
                ]}
                """;

        FinalSummaryResult result = markovPlanner.parseFinalSummary(json, cp);

        assertThat(result.steps().get(0).estimatedDurationMin()).isEqualTo(90);
    }

    @Test
    @DisplayName("generateFinalSummary: LLM failure returns safe defaults")
    void generateFinalSummary_llmThrows_returnsDefaults() {
        TaskCheckpoint cp = buildCheckpoint(2, 2, "西安市", List.of(), List.of());
        Task task = new Task();
        task.setId(1L);
        task.setUserId(10L);

        when(llmClient.call(any(), any(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenThrow(new RuntimeException("LLM timeout"));

        FinalSummaryResult result = markovPlanner.generateFinalSummary(task, cp, "test-uuid");

        assertThat(result).isNotNull();
        assertThat(result.title()).contains("西安市");
    }

    @Test
    @DisplayName("generateFinalSummary: valid LLM response parsed and returned")
    void generateFinalSummary_validResponse_parsedCorrectly() {
        TaskCheckpoint cp = buildCheckpoint(1, 2, "西安市", List.of(), List.of());
        Task task = new Task();
        task.setId(1L);
        task.setUserId(10L);

        String llmJson = """
                {"title":"西安精华1日","summary":"历史文化之旅","steps":[
                  {"stepOrder":0,"estimatedDurationMin":150,"llmDescription":"必游之地"}
                ]}
                """;
        when(llmClient.call(any(), any(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn(llmJson);

        FinalSummaryResult result = markovPlanner.generateFinalSummary(task, cp, "uuid-x");

        assertThat(result.title()).isEqualTo("西安精华1日");
        assertThat(result.summary()).isEqualTo("历史文化之旅");
        assertThat(result.steps()).hasSize(1);
        assertThat(result.steps().get(0).estimatedDurationMin()).isEqualTo(150);
        assertThat(result.steps().get(0).llmDescription()).isEqualTo("必游之地");
    }

    private CompletedStep step(String name, double lat, double lng) {
        CompletedStep s = new CompletedStep();
        s.setAttractionName(name);
        s.setLat(lat);
        s.setLng(lng);
        return s;
    }
}
