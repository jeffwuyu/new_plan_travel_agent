package com.travelagent.agent.planner;

import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.agent.prompt.PromptAssembly;
import com.travelagent.agent.prompt.PromptSectionType;
import com.travelagent.client.dashscope.DashscopeLlmClient;
import com.travelagent.client.dashscope.LlmCallResult;
import com.travelagent.service.llm.LlmUsageAccountingService;
import com.travelagent.agent.context.CompletedStep;
import com.travelagent.agent.requirements.MissingField;
import com.travelagent.agent.requirements.TravelConstraints;
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

    /**
     * 处理prepareForLlm。
     * @param checkpoint 任务检查点数据
     * @return 返回处理后的列表结果。
     */
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

    /**
     * 处理appendExchange。
     * @param checkpoint 任务检查点数据
     * @param userMessage u se rM es sa ge 参数
     * @param assistantResponse a ss is ta nt Re sp on se 参数
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
     * 处理estimateTokens。
     * @param history h is to ry 参数
     * @return 返回处理结果。
     */
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

    /**
     * 处理trimWindow。
     * @param history h is to ry 参数
     * @param windowSize w in do wS iz e 参数
     * @return 返回处理后的列表结果。
     */
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

    /**
     * 处理compress。
     * @param checkpoint 任务检查点数据
     * @param history h is to ry 参数
     * @return 返回处理后的列表结果。
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
            String summaryPrompt = "Compress the travel planning conversation into short-term session memory.";
            PromptAssembly prompt = PromptAssembly.create()
                    .add(PromptSectionType.SYSTEM, "You are a lossless short-term memory compressor for a travel planning assistant.")
                    .add(PromptSectionType.POLICY, "Preserve the current task target so planning can continue after old messages are removed. Return strict JSON only, with no markdown fences or commentary.")
                    .add(PromptSectionType.CURRENT_GOAL, "Current task context:\n" + buildCheckpointContext(checkpoint))
                    .add(PromptSectionType.CONVERSATION, oldHistoryText)
                    .add(PromptSectionType.OUTPUT_FORMAT, """
                            Use this exact shape:
                            {"goal":"string","hardConstraints":["string"],"confirmedPreferences":["string"],"selectedAttractions":["string"],"rejectedOrAvoid":["string"],"openQuestions":["string"],"latestUserIntent":"string"}.
                            """);

            LlmCallResult summaryResult = llmClient.callWithUsage(
                    checkpoint.getTaskId(),
                    checkpoint.getUserId(),
                    "history_compress",
                    prompt,
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
            summaryEntry.put("content", "[Short-term session memory] " + normalizeStructuredSummary(summaryResult.content(), checkpoint));

            List<Map<String, Object>> compressed = new ArrayList<>();
            compressed.add(summaryEntry);
            compressed.addAll(recent);
            return compressed;
        } catch (Exception e) {
            log.warn("[HistoryManager] Summary compression failed for task={}, using local structured fallback: {}",
                    checkpoint.getTaskUuid(), e.getMessage());
            Map<String, Object> summaryEntry = new LinkedHashMap<>();
            summaryEntry.put("role", "system");
            summaryEntry.put("content", "[Short-term session memory] " + buildLocalStructuredSummary(checkpoint));
            List<Map<String, Object>> compressed = new ArrayList<>();
            compressed.add(summaryEntry);
            compressed.addAll(recent);
            return compressed;
        }
    }

    private String normalizeStructuredSummary(String content, TaskCheckpoint checkpoint) {
        if (content == null || content.isBlank()) {
            return buildLocalStructuredSummary(checkpoint);
        }
        String trimmed = content.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed;
        }
        return buildLocalStructuredSummary(checkpoint) + "\nmodelSummary=" + quote(trimmed);
    }

    private String buildCheckpointContext(TaskCheckpoint checkpoint) {
        if (checkpoint == null) {
            return "{}";
        }
        return buildLocalStructuredSummary(checkpoint);
    }

    private String buildLocalStructuredSummary(TaskCheckpoint checkpoint) {
        TravelConstraints constraints = checkpoint == null ? null : checkpoint.getStructuredConstraints();
        List<String> hardConstraints = new ArrayList<>();
        List<String> confirmedPreferences = new ArrayList<>();
        List<String> rejectedOrAvoid = new ArrayList<>();
        List<String> openQuestions = new ArrayList<>();

        if (constraints != null) {
            add(hardConstraints, "destination=" + constraints.getDestination());
            add(hardConstraints, "departure=" + constraints.getDeparture());
            add(hardConstraints, constraints.getDays() == null ? null : "days=" + constraints.getDays());
            add(hardConstraints, constraints.getBudgetYuan() == null ? null : "budgetCny=" + constraints.getBudgetYuan());
            add(hardConstraints, constraints.getPeopleCount() == null ? null : "peopleCount=" + constraints.getPeopleCount());
            add(confirmedPreferences, constraints.getTravelPace() == null ? null : "pace=" + constraints.getTravelPace());
            add(confirmedPreferences, constraints.getHotelPreference() == null ? null : "hotel=" + constraints.getHotelPreference());
            add(confirmedPreferences, constraints.getFoodPreference() == null ? null : "food=" + constraints.getFoodPreference());
            addAll(confirmedPreferences, constraints.getAttractionPreference());
            addAll(confirmedPreferences, constraints.getTransportPreference());
            addAll(confirmedPreferences, constraints.getSpecialGroups());
            addAll(rejectedOrAvoid, constraints.getAvoid());
            if (constraints.getMissingFields() != null) {
                for (MissingField field : constraints.getMissingFields()) {
                    if (field != null) {
                        add(openQuestions, field.getName());
                    }
                }
            }
        }

        String goal = checkpoint == null ? "" : firstNonBlank(
                checkpoint.getUserIntent(),
                checkpoint.getRegion() == null ? null : "Plan trip for " + checkpoint.getRegion());
        List<String> selectedAttractions = checkpoint == null || checkpoint.getCompletedSteps() == null
                ? List.of()
                : checkpoint.getCompletedSteps().stream()
                .map(CompletedStep::getAttractionName)
                .filter(this::notBlank)
                .toList();

        return "{"
                + "\"goal\":" + quote(goal) + ","
                + "\"hardConstraints\":" + quoteArray(hardConstraints) + ","
                + "\"confirmedPreferences\":" + quoteArray(confirmedPreferences) + ","
                + "\"selectedAttractions\":" + quoteArray(selectedAttractions) + ","
                + "\"rejectedOrAvoid\":" + quoteArray(rejectedOrAvoid) + ","
                + "\"openQuestions\":" + quoteArray(openQuestions) + ","
                + "\"latestUserIntent\":" + quote(goal)
                + "}";
    }

    private void addAll(List<String> target, List<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            add(target, value);
        }
    }

    private void add(List<String> target, String value) {
        if (notBlank(value) && !target.contains(value.trim())) {
            target.add(value.trim());
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (notBlank(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private String quoteArray(List<String> values) {
        return values == null ? "[]" : values.stream()
                .filter(this::notBlank)
                .map(this::quote)
                .collect(Collectors.joining(",", "[", "]"));
    }

    private String quote(String value) {
        if (value == null) {
            return "\"\"";
        }
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", " ")
                .replace("\n", " ")
                + "\"";
    }

    private boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
