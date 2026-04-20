package com.travelagent.agent.planner;

import com.travelagent.advisor.AdvisorContextKeys;
import com.fasterxml.jackson.core.type.TypeReference;
import com.travelagent.agent.context.CompletedStep;
import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.agent.tools.WeatherTool;
import com.travelagent.client.dashscope.DashscopeLlmClient;
import com.travelagent.client.dashscope.LlmCallResult;
import com.travelagent.model.entity.Task;
import com.travelagent.service.notification.SseEvent;
import com.travelagent.service.notification.SseNotificationService;
import com.travelagent.service.rag.RagService;
import com.travelagent.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Markov-chain-inspired planner that selects the next attraction at each step.
 *
 * <p>The "Markov" property: each attraction choice depends only on the current position
 * (coordinates of the last selected attraction) and the <em>visited set</em> — not the
 * full planning history. This is encoded in the system prompt and enforced by listing
 * already-visited attractions so the LLM does not repeat them.
 *
 * <p>Core responsibility: given a {@link TaskCheckpoint} at step N, determine the
 * attraction for step N+1 by calling the LLM and parsing its JSON response.
 *
 * <p>This class owns:
 * <ul>
 *   <li>System-prompt construction (visited-set injection, preference keywords, trip parameters)</li>
 *   <li>Per-step user-message construction (day/order within day, proximity constraint)</li>
 *   <li>LLM call via {@link DashscopeLlmClient} (streaming, with SSE token forwarding)</li>
 *   <li>History management delegation to {@link HistoryManager}</li>
 *   <li>LLM response parsing (JSON → attractionName with fallback)</li>
 * </ul>
 */
@Component
public class MarkovPlanner {

    private static final Logger log = LoggerFactory.getLogger(MarkovPlanner.class);

    @Autowired private DashscopeLlmClient llmClient;
    @Autowired private HistoryManager historyManager;
    @Autowired private SseNotificationService sseNotificationService;
    @Autowired private JsonUtil jsonUtil;
    @Autowired(required = false) private RagService ragService;

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Plans the next attraction for the current step index.
     *
     * <p>Side effects on {@code cp}:
     * <ul>
     *   <li>History may be compressed (if token threshold exceeded)</li>
     *   <li>The user/assistant exchange for this step is appended to history</li>
     * </ul>
     *
     * @param task    the task entity — used for audit log IDs in LLM client
     * @param cp      current checkpoint (mutated: history updated)
     * @param taskUuid task UUID for SSE routing
     * @return the chosen attraction name (never null, falls back to raw LLM text)
     */
    public PlanningResult planNextAttraction(Task task, TaskCheckpoint cp, String taskUuid) {
        int stepIndex = cp.getCurrentStepIndex();
        String systemPrompt = buildSystemPrompt(cp);
        String userMessage = buildStepPrompt(cp);
        String llmIdempotencyKey = taskUuid + "-step" + stepIndex + "-llm";

        // Compress + sliding-window trim before the call
        List<Map<String, Object>> trimmedHistory = historyManager.prepareForLlm(cp);

        List<String> ragChunks = List.of();
        if (ragService != null) {
            try {
                ragChunks = ragService.queryChunks(cp.getUserIntent(), cp.getRegion(), 5);
            } catch (Exception e) {
                log.warn("[MarkovPlanner] RAG query failed, skipping advisor injection: {}", e.getMessage());
            }
        }

        LlmCallResult llmResult = llmClient.callStreaming(
                task.getId(), task.getUserId(), "planning",
                systemPrompt, trimmedHistory, userMessage, llmIdempotencyKey,
                token -> sseNotificationService.sendEvent(
                        taskUuid, SseEvent.LLM_STREAM, Map.of("token", token)),
                llmClient.defaultPlanningAdvisors(),
                buildAdvisorContext(cp, ragChunks)
        );

        // Append raw exchange to full history (before trimming — for future compression)
        historyManager.appendExchange(cp, userMessage, llmResult.content());

        String attractionName = parseLlmAttractionName(llmResult.content(), stepIndex);
        return new PlanningResult(attractionName, llmResult.totalTokens());
    }

    // -----------------------------------------------------------------------
    // Final summary generation
    // -----------------------------------------------------------------------

    /**
     * Calls the LLM once after all steps complete to generate a structured plan summary.
     *
     * <p>Returns a {@link FinalSummaryResult} with:
     * <ul>
     *   <li>A short plan {@code title} (e.g. "西安 3 日历史文化游")</li>
     *   <li>A {@code summary} paragraph describing the overall trip</li>
     *   <li>Per-step {@code llmDescription} and {@code estimatedDurationMin}</li>
     * </ul>
     *
     * <p>Uses non-streaming {@code call()} so no SSE tokens are emitted for this
     * behind-the-scenes wrap-up call.
     *
     * <p>Never throws — on any LLM or parse error the method returns a safe default
     * so {@code persistPlan()} can still complete successfully.
     *
     * @param task     task entity (for audit log IDs)
     * @param cp       completed checkpoint
     * @param taskUuid task UUID (used as idempotency key suffix)
     * @return parsed summary; falls back to safe defaults on failure
     */
    public FinalSummaryResult generateFinalSummary(Task task, TaskCheckpoint cp, String taskUuid) {
        String systemPrompt = buildFinalSummarySystemPrompt(cp);
        String userMessage  = buildFinalSummaryUserMessage(cp);
        String idempotencyKey = taskUuid + "-final-summary";
        try {
            String llmResponse = llmClient.call(
                    task.getId(), task.getUserId(), "final_summary",
                    systemPrompt, List.of(), userMessage, idempotencyKey);
            return parseFinalSummary(llmResponse, cp);
        } catch (Exception e) {
            log.warn("[MarkovPlanner] Final summary LLM call failed (using defaults): {}", e.getMessage());
            return buildDefaultSummary(cp);
        }
    }

    private String buildFinalSummarySystemPrompt(TaskCheckpoint cp) {
        return "You are a travel writer. The user just completed planning a trip to " + cp.getRegion() + ".\n"
                + "User intent: " + cp.getUserIntent() + "\n"
                + "Produce a concise JSON summary of the completed itinerary. "
                + "Respond ONLY with valid JSON matching this schema exactly:\n"
                + "{\n"
                + "  \"title\": \"short trip title\",\n"
                + "  \"summary\": \"one paragraph overview\",\n"
                + "  \"steps\": [\n"
                + "    {\"stepOrder\": 0, \"estimatedDurationMin\": 120, \"llmDescription\": \"visit note\"}\n"
                + "  ]\n"
                + "}\n"
                + "estimatedDurationMin should reflect actual attraction scale (60–240 min). "
                + "llmDescription should be a brief, practical visit tip in Chinese.";
    }

    private String buildFinalSummaryUserMessage(TaskCheckpoint cp) {
        StringBuilder sb = new StringBuilder("Completed attractions:\n");
        for (CompletedStep s : cp.getCompletedSteps()) {
            sb.append(String.format("  Step %d (Day %d): %s",
                    s.getStepIndex(), s.getDayNumber(), s.getAttractionName()));
            if (s.getToolCallResults() != null) {
                Map<?, ?> weather = (Map<?, ?>) s.getToolCallResults().get(WeatherTool.NAME);
                if (weather != null) {
                    sb.append(String.format(", weather: %s %s°C",
                            weather.get("weather"), weather.get("temperature")));
                }
            }
            sb.append("\n");
        }
        sb.append("\nGenerate the JSON summary now.");
        return sb.toString();
    }

    /**
     * Parses the LLM's JSON response for the final summary.
     * Strips markdown fences, extracts fields, applies per-field defaults on missing/invalid data.
     */
    @SuppressWarnings("unchecked")
    FinalSummaryResult parseFinalSummary(String llmResponse, TaskCheckpoint cp) {
        if (llmResponse == null || llmResponse.isBlank()) {
            return buildDefaultSummary(cp);
        }
        try {
            String cleaned = llmResponse.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("(?s)```[a-z]*\\s*", "").replace("```", "").trim();
            }
            Map<String, Object> parsed = jsonUtil.fromJson(cleaned, new TypeReference<>() {});

            String title   = stringOrDefault(parsed.get("title"),
                    cp.getRegion() + " " + cp.getPlanningConfig().getTotalDays() + "日游");
            String summary = stringOrDefault(parsed.get("summary"), cp.getUserIntent());

            List<FinalSummaryResult.StepSummary> stepSummaries = new ArrayList<>();
            Object stepsObj = parsed.get("steps");
            if (stepsObj instanceof List<?> rawList) {
                for (Object item : rawList) {
                    if (item instanceof Map<?, ?> stepMap) {
                        int stepOrder = toInt(stepMap.get("stepOrder"), stepSummaries.size());
                        int duration  = toInt(stepMap.get("estimatedDurationMin"), 90);
                        if (duration < 30 || duration > 480) duration = 90; // sanity clamp
                        String desc   = stringOrDefault(stepMap.get("llmDescription"), "");
                        stepSummaries.add(new FinalSummaryResult.StepSummary(stepOrder, duration, desc));
                    }
                }
            }
            return new FinalSummaryResult(title, summary, stepSummaries);
        } catch (Exception e) {
            log.warn("[MarkovPlanner] Final summary parse failed: {}", e.getMessage());
            return buildDefaultSummary(cp);
        }
    }

    /** Safe default when LLM call or parse fails — mirrors the old hardcoded behaviour. */
    private FinalSummaryResult buildDefaultSummary(TaskCheckpoint cp) {
        String title = cp.getRegion() + " "
                + cp.getPlanningConfig().getTotalDays() + "-Day Trip";
        List<FinalSummaryResult.StepSummary> steps = new ArrayList<>();
        for (CompletedStep s : cp.getCompletedSteps()) {
            steps.add(new FinalSummaryResult.StepSummary(s.getStepIndex(), 90, null));
        }
        return new FinalSummaryResult(title, cp.getUserIntent(), steps);
    }

    private String stringOrDefault(Object value, String defaultVal) {
        if (value == null) return defaultVal;
        String s = value.toString().trim();
        return s.isBlank() ? defaultVal : s;
    }

    private int toInt(Object value, int defaultVal) {
        if (value == null) return defaultVal;
        try { return ((Number) value).intValue(); }
        catch (Exception e) { return defaultVal; }
    }

    // -----------------------------------------------------------------------
    // Prompt builders (package-visible for testing)
    // -----------------------------------------------------------------------

    /**
     * Builds the system prompt injected at the start of every LLM request.
     *
     * <p>Injects:
     * <ul>
     *   <li>Region and trip parameters (days × attractions per day)</li>
     *   <li>User preference keywords</li>
     *   <li>Visited set (already-planned attractions — must not be repeated)</li>
     *   <li>Distance constraint (30 km same-day radius)</li>
     *   <li>Response schema (JSON only)</li>
     * </ul>
     */
    String buildSystemPrompt(TaskCheckpoint cp) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a professional travel planner. Help the user plan an itinerary for ")
                .append(cp.getRegion()).append(".\n");
        sb.append("User intent: ").append(cp.getUserIntent()).append("\n");
        sb.append("Recommend the next attraction only.");
        return sb.toString();
    }

    /**
     * Builds the per-step user message asking for the next attraction.
     *
     * <p>Encodes:
     * <ul>
     *   <li>Which slot within the day (e.g. "attraction 2 for day 1")</li>
     *   <li>Overall progress (step N of M)</li>
     *   <li>Travel mode</li>
     *   <li>Starting coordinates (previous attraction's position, if available)</li>
     * </ul>
     */
    String buildStepPrompt(TaskCheckpoint cp) {
        int stepIndex = cp.getCurrentStepIndex();
        int attractionsPerDay = cp.getPlanningConfig().getAttractionsPerDay();
        int dayNumber = (stepIndex / attractionsPerDay) + 1;
        int orderInDay = (stepIndex % attractionsPerDay) + 1;

        StringBuilder prompt = new StringBuilder();
        prompt.append(String.format(
                "Recommend attraction %d for day %d (overall step %d of %d). ",
                orderInDay, dayNumber, stepIndex + 1, cp.totalPlannedSteps()));
        prompt.append(String.format(
                "The destination region is %s; travel mode is %s.",
                cp.getRegion(), cp.getPlanningConfig().getTravelMode()));

        if (!cp.getCompletedSteps().isEmpty()) {
            var last = cp.getCompletedSteps().get(cp.getCompletedSteps().size() - 1);
            prompt.append(String.format(
                    " Start from '%s' (lat=%.6f, lng=%.6f). Choose the next attraction within 30 km.",
                    last.getAttractionName(), last.getLat(), last.getLng()));
        }

        return prompt.toString();
    }

    private Map<String, Object> buildAdvisorContext(TaskCheckpoint cp, List<String> ragChunks) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put(AdvisorContextKeys.REGION, cp.getRegion());
        context.put(AdvisorContextKeys.USER_INTENT, cp.getUserIntent());
        context.put(AdvisorContextKeys.PLANNING_CONFIG, cp.getPlanningConfig());
        context.put(AdvisorContextKeys.COMPLETED_STEPS,
                cp.getCompletedSteps() != null ? cp.getCompletedSteps() : List.<CompletedStep>of());
        context.put(AdvisorContextKeys.SAME_DAY_RADIUS_KM, 30);
        if (ragChunks != null && !ragChunks.isEmpty()) {
            context.put(AdvisorContextKeys.RAG_CHUNKS, ragChunks);
        }
        context.put(AdvisorContextKeys.RESPONSE_SCHEMA, Map.of(
                "type", "object",
                "required", List.of("attractionName", "reason")
        ));
        return context;
    }

    /**
     * Parses the LLM's JSON response to extract the attraction name.
     *
     * <p>Handles:
     * <ul>
     *   <li>Clean JSON: {@code {"attractionName":"…"}}</li>
     *   <li>Markdown-fenced JSON (code blocks)</li>
     *   <li>Fallback: uses the first 50 characters of the raw response</li>
     * </ul>
     */
    String parseLlmAttractionName(String llmResponse, int stepIndex) {
        if (llmResponse == null || llmResponse.isBlank()) {
            log.warn("[MarkovPlanner] Received blank LLM response at step={}", stepIndex);
            return "Unknown Attraction";
        }
        try {
            String cleaned = llmResponse.trim();
            if (cleaned.startsWith("```")) {
                // Strip markdown code fences: ```json\n{…}\n```
                cleaned = cleaned.replaceAll("(?s)```[a-z]*\\s*", "").replace("```", "").trim();
            }
            Map<String, Object> parsed = jsonUtil.fromJson(cleaned, new TypeReference<>() {});
            Object name = parsed.get("attractionName");
            if (name != null && !name.toString().isBlank()) {
                return name.toString().trim();
            }
        } catch (Exception e) {
            log.warn("[MarkovPlanner] JSON parse failed at step={}: {}", stepIndex, e.getMessage());
        }
        // Last-resort fallback
        return llmResponse.length() > 50 ? llmResponse.substring(0, 50).trim() : llmResponse.trim();
    }
}
