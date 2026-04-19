package com.travelagent.agent.planner;

import com.fasterxml.jackson.core.type.TypeReference;
import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.client.dashscope.DashscopeLlmClient;
import com.travelagent.model.entity.Task;
import com.travelagent.service.notification.SseEvent;
import com.travelagent.service.notification.SseNotificationService;
import com.travelagent.service.rag.RagService;
import com.travelagent.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
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
    public String planNextAttraction(Task task, TaskCheckpoint cp, String taskUuid) {
        int stepIndex = cp.getCurrentStepIndex();
        String systemPrompt = buildSystemPrompt(cp);
        String userMessage = buildStepPrompt(cp);
        String llmIdempotencyKey = taskUuid + "-step" + stepIndex + "-llm";

        // Compress + sliding-window trim before the call
        List<Map<String, Object>> trimmedHistory = historyManager.prepareForLlm(cp);

        String llmResponse = llmClient.callStreaming(
                task.getId(), task.getUserId(), "planning",
                systemPrompt, trimmedHistory, userMessage, llmIdempotencyKey,
                token -> sseNotificationService.sendEvent(
                        taskUuid, SseEvent.LLM_STREAM, Map.of("token", token))
        );

        // Append raw exchange to full history (before trimming — for future compression)
        historyManager.appendExchange(cp, userMessage, llmResponse);

        return parseLlmAttractionName(llmResponse, stepIndex);
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
        sb.append("Trip parameters: totalDays=")
                .append(cp.getPlanningConfig().getTotalDays())
                .append(", attractionsPerDay=")
                .append(cp.getPlanningConfig().getAttractionsPerDay()).append("\n");

        if (cp.getPlanningConfig().getPreferenceKeywords() != null
                && !cp.getPlanningConfig().getPreferenceKeywords().isEmpty()) {
            sb.append("User preferences: ")
                    .append(String.join(", ", cp.getPlanningConfig().getPreferenceKeywords()))
                    .append("\n");
        }

        if (!cp.getCompletedSteps().isEmpty()) {
            sb.append("Already planned attractions (do NOT recommend any of these): ");
            cp.getCompletedSteps().forEach(s ->
                    sb.append(s.getAttractionName()).append("; "));
            sb.setLength(sb.length() - 2); // trim trailing "; "
            sb.append("\n");
        }

        sb.append("User intent: ").append(cp.getUserIntent()).append("\n");

        if (ragService != null) {
            try {
                List<String> chunks = ragService.queryChunks(cp.getUserIntent(), cp.getRegion(), 5);
                if (!chunks.isEmpty()) {
                    sb.append("Reference information from travel guides:\n");
                    chunks.forEach(c -> sb.append("- ").append(c).append("\n"));
                }
            } catch (Exception e) {
                log.warn("[MarkovPlanner] RAG query failed, skipping injection: {}", e.getMessage());
            }
        }

        sb.append("Distance constraint: attractions within the same day must be within 30 km of each other.\n");
        sb.append("\nReply ONLY in valid JSON using exactly this shape:\n");
        sb.append("{\"attractionName\": \"<attraction name>\", \"reason\": \"<brief recommendation reason>\"}");
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
