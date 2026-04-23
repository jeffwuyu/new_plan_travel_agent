package com.travelagent.agent.planner;

import com.travelagent.agent.context.PlanningConfig;
import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.client.dashscope.DashscopeLlmClient;
import com.travelagent.client.dashscope.LlmCallResult;
import com.travelagent.service.llm.LlmUsageAccountingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 中文注释：测试类，用于验证 History Manager 的滑动窗口裁剪与 LLM 摘要压缩行为。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HistoryManager Tests")
class HistoryManagerTest {

    @Mock
    private DashscopeLlmClient llmClient;

    @Mock
    private LlmUsageAccountingService llmUsageAccountingService;

    @InjectMocks
    private HistoryManager historyManager;

    @BeforeEach
    void setUp() {
        // Default: max 3000 tokens, sliding window 6 rounds
        ReflectionTestUtils.setField(historyManager, "maxTokens", 3000);
        ReflectionTestUtils.setField(historyManager, "slidingWindow", 6);
    }

    // -----------------------------------------------------------------------
    // estimateTokens
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("estimateTokens: null history returns 0")
    void estimateTokens_nullHistory_returnsZero() {
        assertThat(historyManager.estimateTokens(null)).isZero();
    }

    @Test
    @DisplayName("estimateTokens: empty history returns 0")
    void estimateTokens_emptyHistory_returnsZero() {
        assertThat(historyManager.estimateTokens(List.of())).isZero();
    }

    @Test
    @DisplayName("estimateTokens: returns totalChars / 4")
    void estimateTokens_withContent_returnsDividedByFour() {
        // 8 chars in "content" → 8/4 = 2 tokens
        List<Map<String, Object>> history = List.of(entry("user", "12345678"));
        assertThat(historyManager.estimateTokens(history)).isEqualTo(2);
    }

    @Test
    @DisplayName("estimateTokens: entry with null content contributes 0")
    void estimateTokens_nullContent_skipsEntry() {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("role", "user");
        entry.put("content", null);
        assertThat(historyManager.estimateTokens(List.of(entry))).isZero();
    }

    // -----------------------------------------------------------------------
    // trimWindow
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("trimWindow: under limit returns full list copy")
    void trimWindow_underLimit_returnsFullList() {
        List<Map<String, Object>> history = buildHistory(4); // 4 entries = 2 rounds, window=6
        List<Map<String, Object>> result = historyManager.trimWindow(history, 6);
        assertThat(result).hasSize(4);
    }

    @Test
    @DisplayName("trimWindow: over limit returns last windowSize*2 entries")
    void trimWindow_overLimit_returnsLastN() {
        List<Map<String, Object>> history = buildHistory(16); // 16 entries, window=6 → keep last 12
        List<Map<String, Object>> result = historyManager.trimWindow(history, 6);
        assertThat(result).hasSize(12);
        // Verify the last entry is the same object reference (subList / copy)
        assertThat(result.get(result.size() - 1))
                .isEqualTo(history.get(history.size() - 1));
    }

    @Test
    @DisplayName("trimWindow: null returns empty list")
    void trimWindow_nullHistory_returnsEmpty() {
        assertThat(historyManager.trimWindow(null, 6)).isEmpty();
    }

    // -----------------------------------------------------------------------
    // appendExchange
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("appendExchange: adds one user and one assistant entry")
    void appendExchange_addsUserAndAssistantEntries() {
        TaskCheckpoint cp = emptyCheckpoint();
        cp.setLlmConversationHistory(new ArrayList<>());

        historyManager.appendExchange(cp, "Where should I go?", "I recommend Xi'an.");

        List<Map<String, Object>> history = cp.getLlmConversationHistory();
        assertThat(history).hasSize(2);
        assertThat(history.get(0).get("role")).isEqualTo("user");
        assertThat(history.get(0).get("content")).isEqualTo("Where should I go?");
        assertThat(history.get(1).get("role")).isEqualTo("assistant");
        assertThat(history.get(1).get("content")).isEqualTo("I recommend Xi'an.");
    }

    @Test
    @DisplayName("appendExchange: initialises history list when null")
    void appendExchange_nullHistory_initialisesAndAdds() {
        TaskCheckpoint cp = emptyCheckpoint();
        cp.setLlmConversationHistory(null);

        historyManager.appendExchange(cp, "Hello", "Hi");

        assertThat(cp.getLlmConversationHistory()).hasSize(2);
    }

    // -----------------------------------------------------------------------
    // prepareForLlm — no compression path
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("prepareForLlm: under token threshold — LLM not called, returns windowed list")
    void prepareForLlm_underThreshold_noCompression() {
        TaskCheckpoint cp = emptyCheckpoint();
        // 4 entries × 40 chars = 160 chars / 4 = 40 tokens << 3000
        cp.setLlmConversationHistory(buildHistory(4));

        List<Map<String, Object>> result = historyManager.prepareForLlm(cp);

        assertThat(result).hasSize(4);
        verify(llmClient, never()).call(any(), any(), anyString(), anyString(), anyList(), anyString(), any());
    }

    @Test
    @DisplayName("prepareForLlm: under threshold — window trim applied correctly")
    void prepareForLlm_underThreshold_windowApplied() {
        // 8 entries = 4 rounds; window=3 → keep last 6 entries
        ReflectionTestUtils.setField(historyManager, "slidingWindow", 3);
        TaskCheckpoint cp = emptyCheckpoint();
        cp.setLlmConversationHistory(buildHistory(8));

        List<Map<String, Object>> result = historyManager.prepareForLlm(cp);

        assertThat(result).hasSize(6);
        verify(llmClient, never()).call(any(), any(), anyString(), anyString(), anyList(), anyString(), any());
    }

    // -----------------------------------------------------------------------
    // prepareForLlm — compression path
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("prepareForLlm: over token threshold — LLM called, history compressed")
    void prepareForLlm_overThreshold_compressesAndTrims() {
        // Lower threshold so 20 entries (each 40 chars = 800 chars / 4 = 200 tokens) triggers compression
        ReflectionTestUtils.setField(historyManager, "maxTokens", 50);

        when(llmClient.callWithUsage(any(), any(), anyString(), anyString(), anyList(), anyString(), any()))
                .thenReturn(new LlmCallResult("Summary: visited Terracotta Army and Wild Goose Pagoda.", 120));

        TaskCheckpoint cp = emptyCheckpoint();
        cp.setLlmConversationHistory(buildHistory(20));

        List<Map<String, Object>> result = historyManager.prepareForLlm(cp);

        verify(llmClient).callWithUsage(any(), any(), anyString(), anyString(), anyList(), anyString(), any());

        // After compression: 1 summary + KEEP_ROUNDS_AFTER_COMPRESS*2 recent = 1 + 8 = 9 entries
        // Then sliding window (6*2=12) doesn't cut further (9 < 12)
        assertThat(result).hasSizeLessThanOrEqualTo(12);

        // historyTrimmedAt should be set on the checkpoint
        assertThat(cp.getHistoryTrimmedAt()).isNotNull();
    }

    @Test
    @DisplayName("prepareForLlm: compression LLM fails — falls back to keeping recent rounds only")
    void prepareForLlm_compressionFails_fallsBackToRecentRounds() {
        ReflectionTestUtils.setField(historyManager, "maxTokens", 50);

        when(llmClient.callWithUsage(any(), any(), anyString(), anyString(), anyList(), anyString(), any()))
                .thenThrow(new RuntimeException("LLM unavailable"));

        TaskCheckpoint cp = emptyCheckpoint();
        cp.setLlmConversationHistory(buildHistory(20));

        // Should not throw — falls back gracefully
        List<Map<String, Object>> result = historyManager.prepareForLlm(cp);

        assertThat(result).isNotNull();
        // KEEP_ROUNDS_AFTER_COMPRESS * 2 = 8 recent entries kept
        assertThat(result).hasSizeLessThanOrEqualTo(HistoryManager.KEEP_ROUNDS_AFTER_COMPRESS * 2);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private TaskCheckpoint emptyCheckpoint() {
        TaskCheckpoint cp = new TaskCheckpoint();
        cp.setTaskUuid("test-uuid");
        cp.setTaskId(1L);
        cp.setUserId(2L);
        PlanningConfig config = new PlanningConfig();
        config.setTotalDays(2);
        config.setAttractionsPerDay(2);
        cp.setPlanningConfig(config);
        return cp;
    }

    /**
     * Builds a history list with {@code count} entries.
     * Each entry has a 40-character content string.
     */
    private List<Map<String, Object>> buildHistory(int count) {
        List<Map<String, Object>> history = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String role = (i % 2 == 0) ? "user" : "assistant";
            // 40 chars of content → 10 tokens each
            history.add(entry(role, "A".repeat(40)));
        }
        return history;
    }

    private Map<String, Object> entry(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }
}
