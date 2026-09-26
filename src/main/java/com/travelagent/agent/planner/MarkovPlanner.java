package com.travelagent.agent.planner;

import com.travelagent.agent.context.CompletedStep;
import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.agent.scratchpad.ScratchpadManagementService;
import com.travelagent.agent.tools.WeatherTool;
import com.travelagent.agent.validation.HallucinationDetectionResult;
import com.travelagent.agent.validation.HallucinationDetector;
import com.travelagent.client.dashscope.DashscopeLlmClient;
import com.travelagent.client.dashscope.LlmCallResult;
import com.travelagent.exception.AgentErrorCode;
import com.travelagent.exception.AgentException;
import com.travelagent.mapper.LlmCallLogMapper;
import com.travelagent.model.dto.LocationCandidateItem;
import com.travelagent.model.entity.Task;
import com.travelagent.service.notification.SseEvent;
import com.travelagent.service.notification.SseNotificationService;
import com.travelagent.service.rag.RagAttractionCandidateService;
import com.travelagent.service.task.TaskProgressService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static com.travelagent.agent.planner.PlannerUtils.*;

@Component
public class MarkovPlanner {

    private static final Logger log = LoggerFactory.getLogger(MarkovPlanner.class);

    @Autowired private DashscopeLlmClient llmClient;
    @Autowired private HistoryManager historyManager;
    @Autowired private SseNotificationService sseNotificationService;
    @Autowired private LlmCallLogMapper llmCallLogMapper;
    @Autowired private PlannerPromptBuilder promptBuilder;
    @Autowired private PlannerResponseParser responseParser;
    @Autowired(required = false) private HallucinationDetector hallucinationDetector;
    @Autowired(required = false) private TaskProgressService taskProgressService;
    @Autowired(required = false) private ScratchpadManagementService scratchpadManagementService;
    @Autowired(required = false) private RagAttractionCandidateService ragAttractionCandidateService;
    @Autowired(required = false) private WeatherTool weatherTool;

    public PlanningResult planNextAttraction(Task task, TaskCheckpoint cp, String taskUuid) {
        return planNextAttraction(task, cp, buildPlanRequest(cp), taskUuid);
    }

    public PlanningResult planNextAttraction(Task task, TaskCheckpoint cp,
                                             PlanNextAttractionRequest request,
                                             String taskUuid) {
        Map<String, Object> weatherContext = resolveWeatherContext(cp, request);
        return tryRagCandidateSelection(task, cp, request, taskUuid, weatherContext);
    }

    public PlanNextAttractionRequest buildPlanRequest(TaskCheckpoint cp) {
        PlanNextAttractionRequest request = new PlanNextAttractionRequest();
        if (cp == null || cp.getPlanningConfig() == null) {
            return request;
        }
        request.setRegion(cp.getRegion());
        request.setTravelMode(cp.getPlanningConfig().getTravelMode());
        request.setRemainingTimeBudgetMin(cp.getRemainingTimeBudgetMin());
        request.setCurrentTime(promptBuilder.resolveCurrentTime(cp));
        request.setSelectedBranchType(cp.getSelectedBranchType());
        request.setVisitedPoiNames(cp.getCompletedSteps() == null
                ? List.of()
                : cp.getCompletedSteps().stream().map(CompletedStep::getAttractionName).toList());
        request.setWeatherContext(cp.getWeatherContext() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(cp.getWeatherContext()));

        if (cp.getCompletedSteps() != null && !cp.getCompletedSteps().isEmpty()) {
            CompletedStep last = cp.getCompletedSteps().get(cp.getCompletedSteps().size() - 1);
            request.setCurrentPositionName(last.getAttractionName());
            request.setCurrentLat(last.getLat());
            request.setCurrentLng(last.getLng());
            Map<String, Object> geocode = extractToolResult(last, "geocode");
            if (geocode != null) {
                request.setCurrentAdcode(firstNonBlank(
                        stringValue(geocode.get("adcode")),
                        stringValue(geocode.get("citycode"))
                ));
            }
        } else if (cp.getSelectedOrigin() != null) {
            request.setCurrentPositionName(cp.getSelectedOrigin().getName());
            request.setCurrentLat(cp.getSelectedOrigin().getLatitude());
            request.setCurrentLng(cp.getSelectedOrigin().getLongitude());
            request.setCurrentAdcode(cp.getSelectedOrigin().getAdcode());
        }
        return request;
    }

    private PlanningResult tryRagCandidateSelection(Task task,
                                                    TaskCheckpoint cp,
                                                    PlanNextAttractionRequest request,
                                                    String taskUuid,
                                                    Map<String, Object> weatherContext) {
        Map<String, Object> currentContext = promptBuilder.buildSelectionContext(cp, request, weatherContext, "rag_route");
        currentContext.put("ragRecallTopK", RagAttractionCandidateService.RAG_RECALL_TOP_K);
        currentContext.put("rerankTopK", RagAttractionCandidateService.FINAL_TOP_K);

        if (ragAttractionCandidateService == null) {
            currentContext.put("candidateCount", 0);
            currentContext.put("emptyCandidateMessage", "No RAG candidate service is available.");
            return PlanningResult.forCandidates(List.of(), 0,
                    "route_candidate_selection", "route_candidate_selection", "rag_route",
                    currentContext, weatherContext);
        }

        List<LocationCandidateItem> rankedCandidates =
                ragAttractionCandidateService.buildCandidates(cp, request, weatherContext, taskUuid);
        currentContext.put("candidateCount", rankedCandidates.size());
        if (rankedCandidates.isEmpty()) {
            currentContext.put("emptyCandidateMessage",
                    "No suitable RAG attraction candidates were found for the current request.");
            return PlanningResult.forCandidates(List.of(), 0,
                    "route_candidate_selection", "route_candidate_selection", "rag_route",
                    currentContext, weatherContext);
        }

        Map<String, Object> routeValidationContext = buildRouteValidationContext(cp, request, weatherContext, rankedCandidates);
        String idempotencyKey = taskUuid + "-step" + cp.getCurrentStepIndex() + "-route-llm";
        LlmCallResult llmResult = llmClient.callStreaming(
                task.getId(), task.getUserId(), "route_planning",
                promptBuilder.buildRouteCandidatePrompt(cp, request, routeValidationContext, rankedCandidates),
                historyManager.prepareForLlm(cp),
                promptBuilder.buildRouteCandidateUserPrompt(cp, request, routeValidationContext, rankedCandidates),
                idempotencyKey,
                token -> sseNotificationService.sendEvent(taskUuid, SseEvent.LLM_STREAM, Map.of("token", token)),
                llmClient.defaultPlanningAdvisors(),
                promptBuilder.buildAdvisorContext(cp, List.of())
        );
        List<LocationCandidateItem> routeCandidates = mergeLlmCandidates(
                rankedCandidates, responseParser.parseRouteCandidates(llmResult.content(), routeValidationContext));
        recordPlannerScratchpadThought(cp, llmResult.content());
        historyManager.appendExchange(cp, "rag_route", llmResult.content());
        int resolvedTokens = resolvePlanningTokens(task.getId(), idempotencyKey, llmResult.totalTokens());
        return PlanningResult.forCandidates(routeCandidates, resolvedTokens,
                "route_candidate_selection", "route_candidate_selection", "rag_route",
                currentContext, weatherContext);
    }

    private void recordPlannerScratchpadThought(TaskCheckpoint cp, String planningContent) {
        if (scratchpadManagementService != null && cp != null) {
            scratchpadManagementService.recordThought(
                    cp,
                    cp.getCurrentStepIndex(),
                    "route_candidate_selection",
                    planningContent);
        }
    }

    private Map<String, Object> buildRouteValidationContext(TaskCheckpoint cp,
                                                            PlanNextAttractionRequest request,
                                                            Map<String, Object> weatherContext,
                                                            List<LocationCandidateItem> rankedCandidates) {
        Map<String, Object> context = new LinkedHashMap<>(weatherContext == null ? Map.of() : weatherContext);
        context.put("remainingTimeBudgetMin", cp.getRemainingTimeBudgetMin());
        context.put("allowedRouteIds", rankedCandidates.stream()
                .map(LocationCandidateItem::getCandidateId)
                .filter(value -> value != null && !value.isBlank())
                .toList());
        context.put("allowedAttractionNames", rankedCandidates.stream()
                .flatMap(candidate -> Stream.of(candidate.getName(), candidate.getTargetAttractionName()))
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList());
        context.put("visitedPoiNames", request.getVisitedPoiNames() == null ? List.of() : request.getVisitedPoiNames());
        context.put("currentPositionName", request.getCurrentPositionName());
        context.put("currentAdcode", request.getCurrentAdcode());
        return context;
    }

    private List<LocationCandidateItem> mergeLlmCandidates(List<LocationCandidateItem> rankedCandidates,
                                                           List<LocationCandidateItem> llmCandidates) {
        if (llmCandidates == null || llmCandidates.isEmpty()) {
            return rankedCandidates;
        }
        Map<String, LocationCandidateItem> byId = new LinkedHashMap<>();
        Map<String, LocationCandidateItem> byName = new LinkedHashMap<>();
        for (LocationCandidateItem base : rankedCandidates) {
            if (base.getCandidateId() != null) {
                byId.put(base.getCandidateId(), base);
            }
            if (base.getTargetAttractionName() != null) {
                byName.put(base.getTargetAttractionName().toLowerCase(Locale.ROOT), base);
            }
            if (base.getName() != null) {
                byName.put(base.getName().toLowerCase(Locale.ROOT), base);
            }
        }

        List<LocationCandidateItem> merged = new ArrayList<>();
        for (LocationCandidateItem llm : llmCandidates) {
            LocationCandidateItem base = byId.get(llm.getCandidateId());
            if (base == null && llm.getTargetAttractionName() != null) {
                base = byName.get(llm.getTargetAttractionName().toLowerCase(Locale.ROOT));
            }
            if (base == null && llm.getName() != null) {
                base = byName.get(llm.getName().toLowerCase(Locale.ROOT));
            }
            if (base == null) {
                continue;
            }
            LocationCandidateItem item = copyCandidate(base);
            if (llm.getRouteSummary() != null && !llm.getRouteSummary().isBlank()) {
                item.setRouteSummary(llm.getRouteSummary());
            }
            if (llm.getWeatherSuitability() != null && !llm.getWeatherSuitability().isBlank()) {
                item.setWeatherSuitability(llm.getWeatherSuitability());
            }
            if (llm.getExplanations() != null && !llm.getExplanations().isEmpty()) {
                item.setExplanations(llm.getExplanations());
            }
            if (llm.getHighlights() != null && !llm.getHighlights().isEmpty()) {
                item.setHighlights(llm.getHighlights());
            }
            merged.add(item);
        }
        if (merged.isEmpty()) {
            return rankedCandidates;
        }
        return merged.stream()
                .sorted(Comparator.comparing(LocationCandidateItem::getScore,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    private LocationCandidateItem copyCandidate(LocationCandidateItem base) {
        LocationCandidateItem item = new LocationCandidateItem();
        item.setCandidateId(base.getCandidateId());
        item.setCandidateType("rag_candidate");
        item.setBranchType("rag_route");
        item.setName(base.getName());
        item.setTargetAttractionName(base.getTargetAttractionName());
        item.setRegion(base.getRegion());
        item.setDistrict(base.getDistrict());
        item.setCategory(base.getCategory());
        item.setAddress(base.getAddress());
        item.setLatitude(base.getLatitude());
        item.setLongitude(base.getLongitude());
        item.setAdcode(base.getAdcode());
        item.setSource(base.getSource());
        item.setScore(base.getScore());
        item.setRouteSummary(base.getRouteSummary());
        item.setVisitDurationMin(base.getVisitDurationMin());
        item.setEstimatedTotalDurationMin(base.getEstimatedTotalDurationMin());
        item.setWeatherSuitability(base.getWeatherSuitability());
        item.setExplanations(base.getExplanations() == null ? List.of() : base.getExplanations());
        item.setHighlights(base.getHighlights() == null ? List.of() : base.getHighlights());
        item.setRouteStops(base.getRouteStops() == null ? List.of() : base.getRouteStops());
        return item;
    }

    public FinalSummaryResult generateFinalSummary(Task task, TaskCheckpoint cp, String taskUuid) {
        String idempotencyKey = taskUuid + "-final-summary";
        try {
            int maxRetries = hallucinationDetector == null || !hallucinationDetector.isEnabled()
                    ? 0
                    : hallucinationDetector.maxRetries();
            String retryFeedback = "";
            HallucinationDetectionResult lastDetection = HallucinationDetectionResult.valid();
            for (int attempt = 0; attempt <= maxRetries; attempt++) {
                String userMessage = appendHallucinationRetryFeedback(
                        promptBuilder.buildFinalSummaryUserMessage(cp), retryFeedback);
                String llmResponse = llmClient.call(
                        task.getId(), task.getUserId(), "final_summary",
                        promptBuilder.buildFinalSummaryPrompt(cp),
                        List.of(),
                        userMessage,
                        attempt == 0 ? idempotencyKey : idempotencyKey + "-hdet-retry-" + attempt);
                FinalSummaryResult parsed = responseParser.parseFinalSummary(llmResponse, cp);
                lastDetection = hallucinationDetector == null
                        ? HallucinationDetectionResult.valid()
                        : hallucinationDetector.validateFinalSummary(parsed, cp);
                cp.recordValidatorResult(lastDetection.toMap());
                if (lastDetection.isValid()) {
                    return parsed;
                }
                recordHallucinationDetectionWarning(taskUuid, cp, attempt + 1, maxRetries, lastDetection);
                retryFeedback = lastDetection.getRetryFeedback();
            }
            throw new AgentException(AgentErrorCode.TASK_FAILED_PERMANENT,
                    "Final summary failed hallucination detection after " + maxRetries
                            + " retries: " + summarizeDetection(lastDetection));
        } catch (AgentException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[MarkovPlanner] Final summary LLM call failed (using defaults): {}", e.getMessage());
            return responseParser.parseFinalSummary(null, cp);
        }
    }

    private String appendHallucinationRetryFeedback(String userMessage, String retryFeedback) {
        if (retryFeedback == null || retryFeedback.isBlank()) {
            return userMessage;
        }
        return userMessage + "\n\n## Hallucination detection feedback from previous attempt\n"
                + retryFeedback
                + "\nReturn corrected strict JSON only.";
    }

    private void recordHallucinationDetectionWarning(String taskUuid,
                                                     TaskCheckpoint cp,
                                                     int attempt,
                                                     int maxRetries,
                                                     HallucinationDetectionResult detection) {
        if (taskProgressService == null || taskUuid == null || taskUuid.isBlank()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>(detection.toMap());
        payload.put("attempt", attempt);
        payload.put("maxRetries", maxRetries);
        taskProgressService.recordEvent(taskUuid, "HALLUCINATION_DETECTION_WARNING",
                cp == null ? null : cp.getCurrentState(),
                cp == null ? null : cp.getCurrentStepIndex(),
                cp == null ? null : cp.totalPlannedSteps(),
                "Final summary contains facts unsupported by tool results; retrying LLM generation.",
                payload);
    }

    private String summarizeDetection(HallucinationDetectionResult detection) {
        if (detection == null || detection.getIssues().isEmpty()) {
            return "unknown unsupported claims";
        }
        return detection.getIssues().stream()
                .limit(3)
                .map(issue -> issue.code() + "=" + issue.claimedValue())
                .toList()
                .toString();
    }

    private Map<String, Object> resolveWeatherContext(TaskCheckpoint cp, PlanNextAttractionRequest request) {
        if (request.getWeatherContext() != null && !request.getWeatherContext().isEmpty()) {
            return request.getWeatherContext();
        }
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("source", "unknown");
        String adcode = request.getCurrentAdcode();
        if ((adcode == null || adcode.isBlank()) && cp.getSelectedOrigin() != null) {
            adcode = cp.getSelectedOrigin().getAdcode();
        }
        if (adcode == null || adcode.isBlank() || weatherTool == null) {
            context.put("summary", "Weather unavailable; use normal planning constraints.");
            context.put("constraintHints", List.of());
            return context;
        }
        try {
            String weatherKey = cp.getTaskUuid() + "-planner-weather-" + adcode;
            Map<String, Object> weather = weatherTool.execute(Map.of("adcode", adcode), weatherKey);
            context.putAll(weather);
            context.put("source", "amap");
            context.putAll(buildWeatherConstraintSummary(weather));
        } catch (Exception e) {
            context.put("summary", "Weather lookup failed; use normal planning constraints.");
            context.put("constraintHints", List.of());
        }
        return context;
    }

    private Map<String, Object> buildWeatherConstraintSummary(Map<String, Object> weather) {
        Map<String, Object> summary = new LinkedHashMap<>();
        List<String> hints = new ArrayList<>();
        String weatherText = stringValue(weather.get("weather")).toLowerCase(Locale.ROOT);
        Integer temperature = parseInteger(weather.get("temperature"));
        boolean avoidRain = weatherText.contains("rain") || weatherText.contains("snow") || weatherText.contains("storm")
                || weatherText.contains("雨") || weatherText.contains("雪") || weatherText.contains("暴");
        boolean indoorPreferred = avoidRain;
        boolean shortWalkPreferred = avoidRain;
        if (temperature != null && temperature >= 32) {
            indoorPreferred = true;
            shortWalkPreferred = true;
            hints.add("High temperature; prefer indoor or comfortable attractions");
        }
        if (avoidRain) {
            hints.add("Precipitation; prefer indoor attractions and shorter transfers");
        }
        int windPower = parseInteger(weather.get("windPower")) == null ? 0 : parseInteger(weather.get("windPower"));
        boolean avoidWind = windPower >= 6;
        if (avoidWind) {
            hints.add("Strong wind; reduce long walks and exposed outdoor stops");
            shortWalkPreferred = true;
        }
        if (hints.isEmpty()) {
            hints.add("Weather is stable; outdoor attractions are acceptable");
        }
        summary.put("summary", String.format("%s%s%s",
                stringValue(weather.get("weather")).isBlank() ? "Unknown weather" : stringValue(weather.get("weather")),
                temperature == null ? "" : " " + temperature + "C",
                hints.isEmpty() ? "" : "; " + String.join("; ", hints)));
        summary.put("constraintHints", hints);
        summary.put("indoorPreferred", indoorPreferred);
        summary.put("shortWalkPreferred", shortWalkPreferred);
        summary.put("avoidRain", avoidRain);
        summary.put("avoidWind", avoidWind);
        return summary;
    }

    private int resolvePlanningTokens(Long taskId, String idempotencyKey, int directTokens) {
        if (directTokens > 0) {
            return directTokens;
        }
        if (taskId == null || idempotencyKey == null || idempotencyKey.isBlank()) {
            return 0;
        }
        try {
            Integer fallbackTokens = llmCallLogMapper.findLatestSuccessfulTotalTokens(taskId, idempotencyKey);
            return fallbackTokens != null && fallbackTokens > 0 ? fallbackTokens : 0;
        } catch (Exception e) {
            log.warn("[MarkovPlanner] Failed to resolve token usage for taskId={}, key={}: {}",
                    taskId, idempotencyKey, e.getMessage());
            return 0;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractToolResult(CompletedStep step, String key) {
        if (step == null || step.getToolCallResults() == null) {
            return null;
        }
        Object value = step.getToolCallResults().get(key);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }
}
