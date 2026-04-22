package com.travelagent.agent.planner;

import com.fasterxml.jackson.core.type.TypeReference;
import com.travelagent.advisor.AdvisorContextKeys;
import com.travelagent.agent.context.CompletedStep;
import com.travelagent.agent.context.DailyTimeWindow;
import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.agent.tools.WeatherTool;
import com.travelagent.client.amap.AmapClient;
import com.travelagent.client.dashscope.DashscopeLlmClient;
import com.travelagent.client.dashscope.LlmCallResult;
import com.travelagent.mapper.LlmCallLogMapper;
import com.travelagent.model.dto.NearbyPoiRecommendationRequest;
import com.travelagent.model.dto.NearbyPoiRecommendationResponse;
import com.travelagent.model.dto.LocationCandidateItem;
import com.travelagent.model.dto.RoutePoint;
import com.travelagent.model.dto.SelectionOptionItem;
import com.travelagent.model.entity.Task;
import com.travelagent.service.notification.SseEvent;
import com.travelagent.service.notification.SseNotificationService;
import com.travelagent.service.rag.RagService;
import com.travelagent.service.recommendation.NearbyPoiRecommendationService;
import com.travelagent.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class MarkovPlanner {

    private static final Logger log = LoggerFactory.getLogger(MarkovPlanner.class);
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    @Autowired private DashscopeLlmClient llmClient;
    @Autowired private HistoryManager historyManager;
    @Autowired private SseNotificationService sseNotificationService;
    @Autowired private JsonUtil jsonUtil;
    @Autowired private LlmCallLogMapper llmCallLogMapper;
    @Autowired(required = false) private RagService ragService;
    @Autowired(required = false) private NearbyPoiRecommendationService nearbyPoiRecommendationService;
    @Autowired(required = false) private AmapClient amapClient;

    public PlanningResult planNextAttraction(Task task, TaskCheckpoint cp, String taskUuid) {
        return planNextAttraction(task, cp, buildPlanRequest(cp), taskUuid);
    }

    public PlanningResult planNextAttraction(Task task, TaskCheckpoint cp,
                                             PlanNextAttractionRequest request,
                                             String taskUuid) {
        int stepIndex = cp.getCurrentStepIndex();
        Map<String, Object> weatherContext = resolveWeatherContext(cp, request);

        if (request.getSelectedBranchType() == null || request.getSelectedBranchType().isBlank()) {
            return PlanningResult.forBranchSelection(
                    buildBranchSelectionOptions(weatherContext),
                    buildSelectionContext(cp, request, weatherContext, null),
                    weatherContext
            );
        }

        if ("nearby_poi".equals(request.getSelectedBranchType())) {
            PlanningResult recommendationDriven = tryRecommendationDrivenSelection(cp, request, weatherContext);
            if (recommendationDriven != null) {
                return recommendationDriven;
            }
        }

        if ("route_plan".equals(request.getSelectedBranchType())) {
            PlanningResult routePlanning = tryRoutePlanningSelection(task, cp, request, taskUuid, weatherContext);
            if (routePlanning != null) {
                return routePlanning;
            }
        }

        String systemPrompt = buildSystemPrompt(cp);
        String userMessage = buildStepPrompt(cp);
        String llmIdempotencyKey = taskUuid + "-step" + stepIndex + "-llm";

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

        historyManager.appendExchange(cp, userMessage, llmResult.content());

        String attractionName = parseLlmAttractionName(llmResult.content(), stepIndex);
        int resolvedTokens = resolvePlanningTokens(task.getId(), llmIdempotencyKey, llmResult.totalTokens());
        return PlanningResult.forAttraction(attractionName, resolvedTokens);
    }

    public PlanNextAttractionRequest buildPlanRequest(TaskCheckpoint cp) {
        PlanNextAttractionRequest request = new PlanNextAttractionRequest();
        if (cp == null || cp.getPlanningConfig() == null) {
            return request;
        }
        request.setRegion(cp.getRegion());
        request.setTravelMode(cp.getPlanningConfig().getTravelMode());
        request.setRemainingTimeBudgetMin(cp.getRemainingTimeBudgetMin());
        request.setCurrentTime(resolveCurrentTime(cp));
        request.setSelectedBranchType(cp.getSelectedBranchType());
        request.setVisitedPoiNames(cp.getCompletedSteps() == null
                ? List.of()
                : cp.getCompletedSteps().stream().map(CompletedStep::getAttractionName).toList());
        request.setWeatherContext(cp.getWeatherContext() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(cp.getWeatherContext()));

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

    private PlanningResult tryRecommendationDrivenSelection(TaskCheckpoint cp,
                                                            PlanNextAttractionRequest planningRequest,
                                                            Map<String, Object> weatherContext) {
        if (nearbyPoiRecommendationService == null) {
            return null;
        }
        NearbyPoiRecommendationRequest request = buildRecommendationRequest(cp, planningRequest, weatherContext);
        if (request == null) {
            return null;
        }
        NearbyPoiRecommendationResponse response = nearbyPoiRecommendationService.recommend(request);
        if (response == null || response.getRecommendations() == null || response.getRecommendations().isEmpty()) {
            return null;
        }
        List<LocationCandidateItem> candidates = response.getRecommendations().stream()
                .map(item -> {
                    LocationCandidateItem candidate = new LocationCandidateItem();
                    candidate.setCandidateId(firstNonBlank(item.getAmapPoiId(), item.getPoiId(), item.getName()));
                    candidate.setName(item.getName());
                    candidate.setRegion(item.getRegion());
                    candidate.setDistrict(item.getDistrict());
                    candidate.setCategory(item.getCategory());
                    candidate.setAddress(item.getAddress());
                    candidate.setLatitude(item.getLatitude());
                    candidate.setLongitude(item.getLongitude());
                    candidate.setSource(item.getSource());
                    candidate.setScore(item.getScore());
                    candidate.setRouteSummary(item.getRouteSummary());
                    candidate.setVisitDurationMin(item.getVisitDurationMin());
                    candidate.setCandidateType("poi_candidate");
                    candidate.setBranchType("nearby_poi");
                    candidate.setTargetAttractionName(item.getName());
                    candidate.setEstimatedTotalDurationMin(item.getVisitDurationMin());
                    candidate.setWeatherSuitability(resolveWeatherSuitability(item.getFeatures(), weatherContext));
                    candidate.setExplanations(item.getExplanations() == null ? List.of() : item.getExplanations());
                    candidate.setHighlights(item.getHighlights() == null ? List.of() : item.getHighlights());
                    return candidate;
                })
                .toList();
        Map<String, Object> currentContext = buildSelectionContext(cp, planningRequest, weatherContext, "nearby_poi");
        currentContext.put("emptyCandidateMessage", "暂无符合当前天气和时间预算的附近 POI，可尝试切换路线规划。");
        return PlanningResult.forCandidates(candidates, 0,
                "poi_candidate_selection", "poi_candidate_selection", "nearby_poi",
                currentContext, weatherContext);
    }

    private PlanningResult tryRoutePlanningSelection(Task task,
                                                     TaskCheckpoint cp,
                                                     PlanNextAttractionRequest request,
                                                     String taskUuid,
                                                     Map<String, Object> weatherContext) {
        List<String> ragChunks = List.of();
        if (ragService != null) {
            try {
                ragChunks = ragService.queryChunks(cp.getUserIntent(), cp.getRegion(), 5);
            } catch (Exception e) {
                log.warn("[MarkovPlanner] Route planning RAG query failed: {}", e.getMessage());
            }
        }

        String idempotencyKey = taskUuid + "-step" + cp.getCurrentStepIndex() + "-route-llm";
        LlmCallResult llmResult = llmClient.callStreaming(
                task.getId(), task.getUserId(), "route_planning",
                buildRouteCandidateSystemPrompt(cp, request, weatherContext),
                historyManager.prepareForLlm(cp),
                buildRouteCandidateUserPrompt(cp, request, weatherContext),
                idempotencyKey,
                token -> sseNotificationService.sendEvent(taskUuid, SseEvent.LLM_STREAM, Map.of("token", token)),
                llmClient.defaultPlanningAdvisors(),
                buildAdvisorContext(cp, ragChunks)
        );
        List<LocationCandidateItem> routeCandidates = parseRouteCandidates(llmResult.content(), weatherContext);
        if (routeCandidates.isEmpty()) {
            return null;
        }
        historyManager.appendExchange(cp, "route_plan", llmResult.content());
        int resolvedTokens = resolvePlanningTokens(task.getId(), idempotencyKey, llmResult.totalTokens());
        return PlanningResult.forCandidates(routeCandidates, resolvedTokens,
                "route_candidate_selection", "route_candidate_selection", "route_plan",
                buildSelectionContext(cp, request, weatherContext, "route_plan"),
                weatherContext);
    }

    public FinalSummaryResult generateFinalSummary(Task task, TaskCheckpoint cp, String taskUuid) {
        String systemPrompt = buildFinalSummarySystemPrompt(cp);
        String userMessage = buildFinalSummaryUserMessage(cp);
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
        String preferenceKeywords = "";
        if (cp.getPlanningConfig() != null
                && cp.getPlanningConfig().getPreferenceKeywords() != null
                && !cp.getPlanningConfig().getPreferenceKeywords().isEmpty()) {
            preferenceKeywords = String.join(", ", cp.getPlanningConfig().getPreferenceKeywords());
        }
        String travelMode = cp.getPlanningConfig() != null
                ? cp.getPlanningConfig().getTravelMode() : "";

        return PromptTemplates.buildFinalSummarySystem(
                cp.getRegion(),
                cp.getPlanningConfig() != null ? cp.getPlanningConfig().getTotalDays() : 1,
                cp.getUserIntent(),
                travelMode,
                preferenceKeywords);
    }

    private String buildFinalSummaryUserMessage(TaskCheckpoint cp) {
        StringBuilder sb = new StringBuilder(PromptTemplates.FINAL_SUMMARY_USER_PREFIX);
        for (CompletedStep s : cp.getCompletedSteps()) {
            sb.append(String.format("  Step %d (day %d): %s",
                    s.getStepIndex(), s.getDayNumber(), s.getAttractionName()));
            if (s.getPlannedStartTime() != null && s.getPlannedEndTime() != null) {
                sb.append(String.format(" [%s-%s]",
                        s.getPlannedStartTime().format(TIME_FORMATTER),
                        s.getPlannedEndTime().format(TIME_FORMATTER)));
            }
            if (s.getToolCallResults() != null) {
                Map<?, ?> weather = (Map<?, ?>) s.getToolCallResults().get(WeatherTool.NAME);
                if (weather != null) {
                    sb.append(String.format(", weather: %s %sC",
                            weather.get("weather"), weather.get("temperature")));
                }
            }
            sb.append("\n");
        }
        if (cp.getSelectedDestination() != null && cp.getSelectedDestination().getName() != null) {
            sb.append("\nTrip ends near: ").append(cp.getSelectedDestination().getName());
        }
        if (cp.getPlanningConfig() != null) {
            sb.append("\nTravel mode: ").append(nullToEmpty(cp.getPlanningConfig().getTravelMode()));
        }
        sb.append(PromptTemplates.FINAL_SUMMARY_USER_SUFFIX);
        return sb.toString();
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    NearbyPoiRecommendationRequest buildRecommendationRequest(TaskCheckpoint cp,
                                                              PlanNextAttractionRequest planningRequest,
                                                              Map<String, Object> weatherContext) {
        if (cp == null || cp.getPlanningConfig() == null) {
            return null;
        }
        NearbyPoiRecommendationRequest request = new NearbyPoiRecommendationRequest();
        request.setRegion(cp.getRegion());
        request.setTravelMode(cp.getPlanningConfig().getTravelMode());
        request.setPreferredTags(cp.getPlanningConfig().getPreferenceKeywords());
        request.setTopK(5);
        request.setCurrentTime(resolveCurrentTime(cp));
        request.setQueryType(cp.getCompletedSteps() == null || cp.getCompletedSteps().isEmpty()
                ? "nearby" : "itinerary_fill");
        request.setWeatherCondition(stringValue(weatherContext.get("weather")));
        request.setTemperature(parseInteger(weatherContext.get("temperature")));
        request.setIndoorPreferred(booleanValue(weatherContext.get("indoorPreferred")));
        request.setShortWalkPreferred(booleanValue(weatherContext.get("shortWalkPreferred")));
        request.setAvoidRain(booleanValue(weatherContext.get("avoidRain")));
        request.setAvoidWind(booleanValue(weatherContext.get("avoidWind")));
        request.setWeatherSummary(stringValue(weatherContext.get("summary")));

        if (planningRequest != null && planningRequest.getCurrentPositionName() != null) {
            request.setCurrentPoiName(planningRequest.getCurrentPositionName());
            request.setCurrentLat(planningRequest.getCurrentLat());
            request.setCurrentLng(planningRequest.getCurrentLng());
        }
        if (cp.getCompletedSteps() != null && !cp.getCompletedSteps().isEmpty()) {
            request.setSelectedPoiNames(cp.getCompletedSteps().stream()
                    .map(CompletedStep::getAttractionName)
                    .toList());
            request.setRoutePoints(cp.getCompletedSteps().stream()
                    .filter(step -> step.getLat() != null && step.getLng() != null)
                    .map(step -> new RoutePoint(step.getLat(), step.getLng(), step.getAttractionName()))
                    .toList());
        }
        return request;
    }

    @SuppressWarnings("unchecked")
    FinalSummaryResult parseFinalSummary(String llmResponse, TaskCheckpoint cp) {
        if (llmResponse == null || llmResponse.isBlank()) {
            return buildDefaultSummary(cp);
        }
        try {
            String cleaned = LlmResponseSanitizer.sanitize(llmResponse);
            Map<String, Object> parsed = jsonUtil.fromJson(cleaned, new TypeReference<>() {});

            String title = stringOrDefault(parsed.get("title"),
                    cp.getRegion() + " " + cp.getPlanningConfig().getTotalDays() + " Day Trip");
            String summary = stringOrDefault(parsed.get("summary"), cp.getUserIntent());

            List<FinalSummaryResult.StepSummary> stepSummaries = new ArrayList<>();
            Object stepsObj = parsed.get("steps");
            if (stepsObj instanceof List<?> rawList) {
                for (Object item : rawList) {
                    if (item instanceof Map<?, ?> stepMap) {
                        int stepOrder = toInt(stepMap.get("stepOrder"), stepSummaries.size());
                        int duration = toInt(stepMap.get("estimatedDurationMin"), 90);
                        if (duration < 45 || duration > 360) {
                            duration = 90;
                        }
                        String desc = stringOrDefault(stepMap.get("llmDescription"), "");
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

    private FinalSummaryResult buildDefaultSummary(TaskCheckpoint cp) {
        String title = cp.getRegion() + " "
                + cp.getPlanningConfig().getTotalDays() + "-Day Trip";
        List<FinalSummaryResult.StepSummary> steps = new ArrayList<>();
        for (CompletedStep s : cp.getCompletedSteps()) {
            steps.add(new FinalSummaryResult.StepSummary(
                    s.getStepIndex(),
                    s.getEstimatedVisitDurationMin() == null ? 90 : s.getEstimatedVisitDurationMin(),
                    null
            ));
        }
        return new FinalSummaryResult(title, cp.getUserIntent(), steps);
    }

    private String stringOrDefault(Object value, String defaultVal) {
        if (value == null) {
            return defaultVal;
        }
        String s = value.toString().trim();
        return s.isBlank() ? defaultVal : s;
    }

    private int toInt(Object value, int defaultVal) {
        if (value == null) {
            return defaultVal;
        }
        try {
            return ((Number) value).intValue();
        } catch (Exception e) {
            return defaultVal;
        }
    }

    String buildSystemPrompt(TaskCheckpoint cp) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a professional travel planner. Help the user plan an itinerary for ")
                .append(cp.getRegion()).append(".\n");
        sb.append("User intent: ").append(cp.getUserIntent()).append("\n");
        sb.append("Trip window: ")
                .append(cp.getTripStartTime() != null ? cp.getTripStartTime().format(DATE_TIME_FORMATTER) : "")
                .append(" -> ")
                .append(cp.getTripEndTime() != null ? cp.getTripEndTime().format(DATE_TIME_FORMATTER) : "")
                .append("\n");
        if (cp.getPlanningConfig() != null) {
            sb.append("Travel mode: ").append(cp.getPlanningConfig().getTravelMode()).append("\n");
            sb.append("Full-day default window: ")
                    .append(cp.getPlanningConfig().resolveFullDayStartTime())
                    .append("-")
                    .append(cp.getPlanningConfig().resolveFullDayEndTime())
                    .append("\n");
            sb.append("Remaining planning budget: ")
                    .append(cp.getRemainingTimeBudgetMin())
                    .append(" minutes.\n");
            sb.append("Reserve at least ")
                    .append(cp.getPlanningConfig().getDestinationBufferMin())
                    .append(" minutes buffer before trip end.\n");
        }
        if (cp.getSelectedDestination() != null && cp.getSelectedDestination().getName() != null) {
            sb.append("Soft destination constraint: keep the final attraction near ")
                    .append(cp.getSelectedDestination().getName())
                    .append(" and account for travel time to reach it.\n");
        }
        sb.append("Recommend the next attraction only.");
        return sb.toString();
    }

    String buildStepPrompt(TaskCheckpoint cp) {
        int stepIndex = cp.getCurrentStepIndex();
        int totalSteps = cp.totalPlannedSteps();
        int dayNumber = resolveDayNumber(cp);
        long orderInDay = cp.getCompletedSteps() == null ? 1L
                : cp.getCompletedSteps().stream().filter(step -> step.getDayNumber() == dayNumber).count() + 1;
        DailyTimeWindow dayWindow = cp.getDailyWindow(dayNumber);
        String travelMode = nullToEmpty(cp.getPlanningConfig().getTravelMode());
        StringBuilder sb = new StringBuilder();
        sb.append("Recommend attraction ")
                .append(orderInDay)
                .append(" for day ")
                .append(dayNumber)
                .append(" (overall ")
                .append(stepIndex + 1)
                .append("/")
                .append(totalSteps)
                .append(").\n");
        sb.append("Region: ").append(cp.getRegion())
                .append(", travel mode: ").append(travelMode).append(".\n");
        if (dayWindow != null) {
            sb.append("Today's time window: ")
                    .append(dayWindow.getStartTime().format(DATE_TIME_FORMATTER))
                    .append(" -> ")
                    .append(dayWindow.getEndTime().format(DATE_TIME_FORMATTER))
                    .append(".\n");
        }
        sb.append("Remaining total planning budget: ")
                .append(cp.getRemainingTimeBudgetMin())
                .append(" minutes.\n");
        if (cp.getSelectedOrigin() != null && cp.getCompletedSteps().isEmpty()) {
            sb.append("Start from ")
                    .append(cp.getSelectedOrigin().getName())
                    .append(" (")
                    .append(cp.getSelectedOrigin().getLatitude())
                    .append(", ")
                    .append(cp.getSelectedOrigin().getLongitude())
                    .append(").\n");
        } else if (cp.getCompletedSteps() != null && !cp.getCompletedSteps().isEmpty()) {
            CompletedStep last = cp.getCompletedSteps().get(cp.getCompletedSteps().size() - 1);
            sb.append("Current route position: ")
                    .append(last.getAttractionName())
                    .append(" (")
                    .append(last.getLat())
                    .append(", ")
                    .append(last.getLng())
                    .append(").\n");
        }
        if (cp.getSelectedDestination() != null && cp.getSelectedDestination().getName() != null) {
            sb.append("Trip should eventually end near ")
                    .append(cp.getSelectedDestination().getName())
                    .append(".");
            if (cp.getProjectedReturnToDestinationMin() != null && cp.getProjectedReturnToDestinationMin() > 0) {
                sb.append(" Current estimated transfer to destination: ")
                        .append(cp.getProjectedReturnToDestinationMin())
                        .append(" minutes.");
            }
            sb.append("\n");
        }
        sb.append("Prefer attractions that fit the remaining time instead of forcing only three scenes.");
        return sb.toString();
    }

    private int resolveDayNumber(TaskCheckpoint cp) {
        int remaining = cp.getUsedTimeBudgetMin() == null ? 0 : cp.getUsedTimeBudgetMin();
        if (cp.getDailyTimeWindows() == null || cp.getDailyTimeWindows().isEmpty()) {
            return 1;
        }
        for (DailyTimeWindow window : cp.getDailyTimeWindows()) {
            int minutes = window.availableMinutes();
            if (remaining < minutes) {
                return window.getDayNumber();
            }
            remaining -= minutes;
        }
        return cp.getDailyTimeWindows().get(cp.getDailyTimeWindows().size() - 1).getDayNumber();
    }

    private String resolveCurrentTime(TaskCheckpoint cp) {
        DailyTimeWindow window = cp.getDailyWindow(resolveDayNumber(cp));
        if (window == null || window.getStartTime() == null) {
            return null;
        }
        int offsetWithinTrip = cp.getUsedTimeBudgetMin() == null ? 0 : cp.getUsedTimeBudgetMin();
        return window.getStartTime().plusMinutes(offsetWithinTrip).format(TIME_FORMATTER);
    }

    private Map<String, Object> buildAdvisorContext(TaskCheckpoint cp, List<String> ragChunks) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put(AdvisorContextKeys.REGION, cp.getRegion());
        context.put(AdvisorContextKeys.USER_INTENT, cp.getUserIntent());
        context.put(AdvisorContextKeys.PLANNING_CONFIG, cp.getPlanningConfig());
        context.put(AdvisorContextKeys.COMPLETED_STEPS,
                cp.getCompletedSteps() != null ? cp.getCompletedSteps() : List.<CompletedStep>of());
        context.put(AdvisorContextKeys.SAME_DAY_RADIUS_KM, 30);
        context.put(AdvisorContextKeys.CURRENT_DAY_NUMBER, resolveDayNumber(cp));
        context.put("remainingTimeBudgetMin", cp.getRemainingTimeBudgetMin());
        context.put("destinationName", cp.getSelectedDestination() != null ? cp.getSelectedDestination().getName() : null);
        if (ragChunks != null && !ragChunks.isEmpty()) {
            context.put(AdvisorContextKeys.RAG_CHUNKS, ragChunks);
        }
        context.put(AdvisorContextKeys.RESPONSE_SCHEMA, Map.of(
                "type", "object",
                "required", List.of("attractionName", "reason")
        ));
        return context;
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
            log.warn("[MarkovPlanner] Failed to resolve token usage from audit log for taskId={}, key={}: {}",
                    taskId, idempotencyKey, e.getMessage());
            return 0;
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private List<SelectionOptionItem> buildBranchSelectionOptions(Map<String, Object> weatherContext) {
        SelectionOptionItem nearby = new SelectionOptionItem();
        nearby.setOptionId("nearby_poi");
        nearby.setBranchType("nearby_poi");
        nearby.setLabel("附近 POI 推荐");
        nearby.setDescription(booleanValue(weatherContext.get("shortWalkPreferred"))
                ? "按当前天气优先推荐更近、更省步行的景点。"
                : "基于当前位置、偏好和天气筛选附近可去景点。");

        SelectionOptionItem route = new SelectionOptionItem();
        route.setOptionId("route_plan");
        route.setBranchType("route_plan");
        route.setLabel("路线规划");
        route.setDescription("结合 LLM 和 RAG 生成多条顺路路线候选，再选择下一站。");

        return List.of(nearby, route);
    }

    private Map<String, Object> buildSelectionContext(TaskCheckpoint cp,
                                                      PlanNextAttractionRequest request,
                                                      Map<String, Object> weatherContext,
                                                      String branchType) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("currentPositionName", request.getCurrentPositionName());
        context.put("currentLat", request.getCurrentLat());
        context.put("currentLng", request.getCurrentLng());
        context.put("visitedPoiNames", request.getVisitedPoiNames());
        context.put("remainingTimeBudgetMin", cp.getRemainingTimeBudgetMin());
        context.put("travelMode", request.getTravelMode());
        context.put("dayNumber", resolveDayNumber(cp));
        context.put("destinationName", cp.getSelectedDestination() != null ? cp.getSelectedDestination().getName() : null);
        context.put("selectedBranchType", branchType);
        context.put("weatherSummary", weatherContext.getOrDefault("summary", ""));
        context.put("weatherConstraints", weatherContext.getOrDefault("constraintHints", List.of()));
        return context;
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
        if (adcode == null || adcode.isBlank() || amapClient == null) {
            context.put("summary", "暂未获取到天气，默认按常规条件推荐。");
            context.put("constraintHints", List.of());
            return context;
        }
        try {
            Map<String, Object> weather = amapClient.getWeather(adcode);
            context.putAll(weather);
            context.put("source", "amap");
            context.putAll(buildWeatherConstraintSummary(weather));
        } catch (Exception e) {
            context.put("summary", "天气获取失败，默认按常规条件推荐。");
            context.put("constraintHints", List.of());
        }
        return context;
    }

    private Map<String, Object> buildWeatherConstraintSummary(Map<String, Object> weather) {
        Map<String, Object> summary = new LinkedHashMap<>();
        List<String> hints = new ArrayList<>();
        String weatherText = stringValue(weather.get("weather")).toLowerCase(Locale.ROOT);
        Integer temperature = parseInteger(weather.get("temperature"));
        boolean avoidRain = weatherText.contains("雨") || weatherText.contains("snow") || weatherText.contains("storm");
        boolean indoorPreferred = avoidRain;
        boolean shortWalkPreferred = avoidRain;
        if (temperature != null && temperature >= 32) {
            indoorPreferred = true;
            shortWalkPreferred = true;
            hints.add("高温，优先室内/更舒适的景点");
        }
        if (avoidRain) {
            hints.add("降水天气，优先室内/避雨路线");
        }
        int windPower = parseInteger(weather.get("windPower")) == null ? 0 : parseInteger(weather.get("windPower"));
        boolean avoidWind = windPower >= 6;
        if (avoidWind) {
            hints.add("风力较大，减少长距离步行和空旷点位");
            shortWalkPreferred = true;
        }
        if (hints.isEmpty()) {
            hints.add("天气平稳，可正常安排户外景点");
        }
        summary.put("summary", String.format("%s%s%s",
                stringValue(weather.get("weather")).isBlank() ? "未知天气" : stringValue(weather.get("weather")),
                temperature == null ? "" : " " + temperature + "C",
                hints.isEmpty() ? "" : "，" + String.join("；", hints)));
        summary.put("constraintHints", hints);
        summary.put("indoorPreferred", indoorPreferred);
        summary.put("shortWalkPreferred", shortWalkPreferred);
        summary.put("avoidRain", avoidRain);
        summary.put("avoidWind", avoidWind);
        return summary;
    }

    private String buildRouteCandidateSystemPrompt(TaskCheckpoint cp,
                                                   PlanNextAttractionRequest request,
                                                   Map<String, Object> weatherContext) {
        return """
                You are a travel route planner. Return strict JSON only.
                Generate 3 route candidates for the next leg of the trip.
                Each route must fit the remaining budget and weather constraints.
                JSON schema:
                {
                  "routes": [
                    {
                      "routeId": "route-1",
                      "title": "string",
                      "targetAttractionName": "string",
                      "stops": ["string"],
                      "reason": "string",
                      "estimatedTotalDurationMin": 120,
                      "weatherSuitability": "string"
                    }
                  ]
                }
                """;
    }

    private String buildRouteCandidateUserPrompt(TaskCheckpoint cp,
                                                 PlanNextAttractionRequest request,
                                                 Map<String, Object> weatherContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("Region: ").append(cp.getRegion()).append("\n");
        sb.append("Current position: ").append(firstNonBlank(request.getCurrentPositionName(), "unknown")).append("\n");
        sb.append("Travel mode: ").append(firstNonBlank(request.getTravelMode(), "driving")).append("\n");
        sb.append("Remaining budget: ").append(cp.getRemainingTimeBudgetMin()).append(" minutes\n");
        sb.append("Visited POIs: ").append(request.getVisitedPoiNames()).append("\n");
        sb.append("Destination constraint: ")
                .append(cp.getSelectedDestination() != null ? cp.getSelectedDestination().getName() : "none")
                .append("\n");
        sb.append("Weather summary: ").append(weatherContext.getOrDefault("summary", "none")).append("\n");
        sb.append("Weather constraints: ").append(weatherContext.getOrDefault("constraintHints", List.of())).append("\n");
        sb.append("User intent: ").append(cp.getUserIntent()).append("\n");
        sb.append("Return route candidates only.");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private List<LocationCandidateItem> parseRouteCandidates(String llmResponse, Map<String, Object> weatherContext) {
        if (llmResponse == null || llmResponse.isBlank()) {
            return List.of();
        }
        try {
            String cleaned = LlmResponseSanitizer.sanitize(llmResponse);
            Map<String, Object> parsed = jsonUtil.fromJson(cleaned, new TypeReference<>() {});
            Object routesObj = parsed.get("routes");
            if (!(routesObj instanceof List<?> rawRoutes)) {
                return List.of();
            }
            List<LocationCandidateItem> candidates = new ArrayList<>();
            int index = 0;
            for (Object routeObj : rawRoutes) {
                if (!(routeObj instanceof Map<?, ?> route)) {
                    continue;
                }
                LocationCandidateItem candidate = new LocationCandidateItem();
                candidate.setCandidateId(firstNonBlank(stringValue(route.get("routeId")), "route-" + (++index)));
                candidate.setCandidateType("route_candidate");
                candidate.setBranchType("route_plan");
                candidate.setName(firstNonBlank(stringValue(route.get("title")), stringValue(route.get("targetAttractionName"))));
                candidate.setTargetAttractionName(firstNonBlank(stringValue(route.get("targetAttractionName")), candidate.getName()));
                candidate.setEstimatedTotalDurationMin(parseInteger(route.get("estimatedTotalDurationMin")));
                candidate.setWeatherSuitability(firstNonBlank(stringValue(route.get("weatherSuitability")),
                        stringValue(weatherContext.get("summary"))));
                candidate.setRouteStops(toStringList(route.get("stops")));
                candidate.setRouteSummary(String.join(" -> ", candidate.getRouteStops()));
                candidate.setHighlights(List.of("路线规划候选", "结合顺路关系和天气约束生成"));
                candidate.setExplanations(List.of(firstNonBlank(stringValue(route.get("reason")), "综合天气、顺路关系与时间预算生成")));
                candidates.add(candidate);
            }
            return candidates;
        } catch (Exception e) {
            log.warn("[MarkovPlanner] Failed to parse route candidates: {}", e.getMessage());
            return List.of();
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

    private String resolveWeatherSuitability(com.travelagent.model.dto.RecommendationFeatureBreakdown features,
                                             Map<String, Object> weatherContext) {
        if (features != null && Boolean.TRUE.equals(features.getWeatherFriendly())) {
            return "天气适配较好";
        }
        return firstNonBlank(stringValue(weatherContext.get("summary")), "常规适配");
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private Integer parseInteger(Object value) {
        if (value == null) {
            return null;
        }
        try {
            String text = String.valueOf(value).replaceAll("[^0-9-]", "");
            if (text.isBlank()) {
                return null;
            }
            return Integer.parseInt(text);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean booleanValue(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private List<String> toStringList(Object value) {
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        return raw.stream().map(String::valueOf).filter(v -> !v.isBlank()).toList();
    }

    String parseLlmAttractionName(String llmResponse, int stepIndex) {
        if (llmResponse == null || llmResponse.isBlank()) {
            log.warn("[MarkovPlanner] Received blank LLM response at step={}", stepIndex);
            return "Unknown Attraction";
        }
        try {
            String cleaned = LlmResponseSanitizer.sanitize(llmResponse);
            Map<String, Object> parsed = jsonUtil.fromJson(cleaned, new TypeReference<>() {});
            Object name = parsed.get("attractionName");
            if (name != null && !name.toString().isBlank()) {
                return name.toString().trim();
            }
        } catch (Exception e) {
            log.warn("[MarkovPlanner] JSON parse failed at step={}: {}", stepIndex, e.getMessage());
        }
        return llmResponse.length() > 50 ? llmResponse.substring(0, 50).trim() : llmResponse.trim();
    }
}
