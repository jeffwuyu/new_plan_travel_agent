package com.travelagent.agent.planner;

import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.client.dashscope.DashscopeLlmClient;
import com.travelagent.client.dashscope.LlmCallResult;
import com.travelagent.service.llm.LlmUsageAccountingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Manages LLM conversation history with sliding-window trimming and optional
 * summary compression when the history grows too large.
 */
@Component
public class HistoryManager {

    private static final Logger log = LoggerFactory.getLogger(HistoryManager.class);

    static final int KEEP_ROUNDS_AFTER_COMPRESS = 4;
    private static final int CHARS_PER_TOKEN = 4;

    @Value("${agent.history.sliding-window:6}")
    private int slidingWindow;

    @Value("${agent.history.max-tokens:3000}")
    private int maxTokens;

    @Autowired
    private DashscopeLlmClient llmClient;

    @Autowired
    private LlmUsageAccountingService llmUsageAccountingService;

    public List<Map<String, Object>> prepareForLlm(TaskCheckpoint checkpoint) {
        List<Map<String, Object>> history = checkpoint.getLlmConversationHistory();
        if (history == null) {
            history = new ArrayList<>();
            checkpoint.setLlmConversationHistory(history);
        }

        if (estimateTokens(history) > maxTokens) {
            int sizeBefore = history.size();
            history = compress(checkpoint, history);
            checkpoint.setLlmConversationHistory(history);
            checkpoint.setHistoryTrimmedAt(sizeBefore);
            log.info("[HistoryManager] Compressed history for task={} ({} -> {} entries)",
                    checkpoint.getTaskUuid(), sizeBefore, history.size());
        }

        return trimWindow(history, slidingWindow);
    }

    public void appendExchange(TaskCheckpoint checkpoint, String userMessage, String assistantResponse) {
        List<Map<String, Object>> history = checkpoint.getLlmConversationHistory();
        if (history == null) {
            history = new ArrayList<>();
            checkpoint.setLlmConversationHistory(history);
        }

        Map<String, Object> userEntry = new LinkedHashMap<>();
        userEntry.put("role", "user");
        userEntry.put("content", userMessage);

        Map<String, Object> assistantEntry = new LinkedHashMap<>();
        assistantEntry.put("role", "assistant");
        assistantEntry.put("content", assistantResponse);

        history.add(userEntry);
        history.add(assistantEntry);
    }

    public int estimateTokens(List<Map<String, Object>> history) {
        if (history == null || history.isEmpty()) {
            return 0;
        }
        int totalChars = history.stream()
                .mapToInt(m -> {
                    Object content = m.get("content");
                    return content != null ? content.toString().length() : 0;
                })
                .sum();
        return totalChars / CHARS_PER_TOKEN;
    }

    public List<Map<String, Object>> trimWindow(List<Map<String, Object>> history, int windowSize) {
        if (history == null) {
            return new ArrayList<>();
        }
        int maxEntries = windowSize * 2;
        if (history.size() <= maxEntries) {
            return new ArrayList<>(history);
        }
        return new ArrayList<>(history.subList(history.size() - maxEntries, history.size()));
    }

    private List<Map<String, Object>> compress(TaskCheckpoint checkpoint,
                                               List<Map<String, Object>> history) {
        int keepCount = KEEP_ROUNDS_AFTER_COMPRESS * 2;
        if (history.size() <= keepCount) {
            return history;
        }

        List<Map<String, Object>> toSummarize = history.subList(0, history.size() - keepCount);
        List<Map<String, Object>> recent = new ArrayList<>(
                history.subList(history.size() - keepCount, history.size()));

        String oldHistoryText = toSummarize.stream()
                .map(m -> m.getOrDefault("role", "").toString().toUpperCase()
                        + ": " + m.getOrDefault("content", "").toString())
                .collect(Collectors.joining("\n"));

        String compressKey = (checkpoint.getTaskUuid() != null ? checkpoint.getTaskUuid() : "unknown")
                + "-history-compress-" + history.size();

        try {
            String summaryPrompt =
                    "Summarize the following travel planning conversation in a concise paragraph " +
                    "(max 200 words). Preserve key decisions, selected attractions, and constraints:\n\n"
                    + oldHistoryText;

            LlmCallResult summaryResult = llmClient.callWithUsage(
                    checkpoint.getTaskId(),
                    checkpoint.getUserId(),
                    "history_compress",
                    "You are a concise conversation summarizer for a travel planning assistant. " +
                            "Produce a single paragraph summary only - no headings, no bullet points.",
                    List.of(),
                    summaryPrompt,
                    compressKey
            );
            llmUsageAccountingService.recordUsageLenient(
                    checkpoint.getTaskId(),
                    checkpoint.getUserId(),
                    summaryResult.totalTokens()
            );

            Map<String, Object> summaryEntry = new LinkedHashMap<>();
            summaryEntry.put("role", "system");
            summaryEntry.put("content", "[Prior conversation summary] " + summaryResult.content().trim());

            List<Map<String, Object>> compressed = new ArrayList<>();
            compressed.add(summaryEntry);
            compressed.addAll(recent);
            return compressed;
        } catch (Exception e) {
            log.warn("[HistoryManager] Summary compression failed for task={}, dropping old messages: {}",
                    checkpoint.getTaskUuid(), e.getMessage());
            return recent;
        }
    }
}
