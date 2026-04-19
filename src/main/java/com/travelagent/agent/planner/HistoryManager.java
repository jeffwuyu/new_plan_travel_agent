package com.travelagent.agent.planner;

import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.client.dashscope.DashscopeLlmClient;
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
 * Manages LLM conversation history: sliding-window trimming and LLM-based summary compression.
 *
 * <p>Two-stage strategy applied before every LLM call (via {@link #prepareForLlm}):
 * <ol>
 *   <li><b>Compression</b> — If the estimated token count of the full history exceeds
 *       {@code agent.history.max-tokens} (default 3000), all messages older than the
 *       most recent {@code KEEP_ROUNDS_AFTER_COMPRESS} rounds are compressed into a
 *       single LLM-generated summary inserted as a {@code system} message.
 *       The {@code checkpoint.historyTrimmedAt} field is set to the pre-compression
 *       history size as a marker.</li>
 *   <li><b>Sliding-window trim</b> — The list is capped at
 *       {@code slidingWindow * 2} entries (two per round: user + assistant)
 *       before being returned to the caller. This ensures the LLM context window
 *       is never exceeded even without compression.</li>
 * </ol>
 */
@Component
public class HistoryManager {

    private static final Logger log = LoggerFactory.getLogger(HistoryManager.class);

    /** Number of recent rounds kept verbatim after compression (each round = 2 entries). */
    static final int KEEP_ROUNDS_AFTER_COMPRESS = 4;

    /** Rough chars-per-token ratio used for token estimation. */
    private static final int CHARS_PER_TOKEN = 4;

    @Value("${agent.history.sliding-window:6}")
    private int slidingWindow;

    @Value("${agent.history.max-tokens:3000}")
    private int maxTokens;

    @Autowired
    private DashscopeLlmClient llmClient;

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Prepares the conversation history for the next LLM call.
     *
     * <p>If the current history is too long, this method compresses it in-place on the
     * {@code checkpoint} (updating {@code llmConversationHistory} and
     * {@code historyTrimmedAt}). It then returns a sliding-window view that is safe
     * to pass directly to the LLM client.
     *
     * @param checkpoint the live task checkpoint — may be mutated if compression occurs
     * @return history list ready for the LLM (never null, never exceeds window)
     */
    public List<Map<String, Object>> prepareForLlm(TaskCheckpoint checkpoint) {
        List<Map<String, Object>> history = checkpoint.getLlmConversationHistory();
        if (history == null) {
            history = new ArrayList<>();
            checkpoint.setLlmConversationHistory(history);
        }

        // Step 1: LLM-based compression when token estimate exceeds threshold
        if (estimateTokens(history) > maxTokens) {
            int sizeBefore = history.size();
            history = compress(checkpoint, history);
            checkpoint.setLlmConversationHistory(history);
            checkpoint.setHistoryTrimmedAt(sizeBefore);
            log.info("[HistoryManager] Compressed history for task={} ({} → {} entries)",
                    checkpoint.getTaskUuid(), sizeBefore, history.size());
        }

        // Step 2: Sliding-window trim
        return trimWindow(history, slidingWindow);
    }

    /**
     * Appends a user/assistant exchange to the checkpoint's conversation history.
     *
     * <p>Called after every successful LLM planning step.
     */
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

    /**
     * Estimates the number of LLM tokens in the given history.
     * Uses the heuristic: {@code totalCharacters / CHARS_PER_TOKEN} (~4 chars ≈ 1 token).
     */
    public int estimateTokens(List<Map<String, Object>> history) {
        if (history == null || history.isEmpty()) return 0;
        int totalChars = history.stream()
                .mapToInt(m -> {
                    Object content = m.get("content");
                    return content != null ? content.toString().length() : 0;
                })
                .sum();
        return totalChars / CHARS_PER_TOKEN;
    }

    /**
     * Returns a new list capped to the {@code windowSize} most recent user/assistant rounds.
     * Each round occupies 2 entries, so the cap is {@code windowSize * 2} entries.
     */
    public List<Map<String, Object>> trimWindow(List<Map<String, Object>> history, int windowSize) {
        if (history == null) return new ArrayList<>();
        int maxEntries = windowSize * 2;
        if (history.size() <= maxEntries) {
            return new ArrayList<>(history);
        }
        return new ArrayList<>(history.subList(history.size() - maxEntries, history.size()));
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Compresses old history via LLM summarization.
     *
     * <p>Keeps the {@code KEEP_ROUNDS_AFTER_COMPRESS} most recent rounds verbatim.
     * Everything before that is summarized into a single {@code system} message that
     * is prepended to the recent rounds.
     *
     * <p>If the LLM call fails, the method falls back silently — it simply drops the
     * old messages without a summary. The planning loop must not be blocked by a
     * compression failure.
     */
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

            String summary = llmClient.call(
                    checkpoint.getTaskId(), null, "history_compress",
                    "You are a concise conversation summarizer for a travel planning assistant. " +
                    "Produce a single paragraph summary only — no headings, no bullet points.",
                    List.of(), summaryPrompt, compressKey
            );

            Map<String, Object> summaryEntry = new LinkedHashMap<>();
            summaryEntry.put("role", "system");
            summaryEntry.put("content", "[Prior conversation summary] " + summary.trim());

            List<Map<String, Object>> compressed = new ArrayList<>();
            compressed.add(summaryEntry);
            compressed.addAll(recent);
            return compressed;

        } catch (Exception e) {
            log.warn("[HistoryManager] Summary compression failed for task={}, dropping old messages: {}",
                    checkpoint.getTaskUuid(), e.getMessage());
            // Fallback: discard old messages, keep only recent rounds
            return recent;
        }
    }
}
