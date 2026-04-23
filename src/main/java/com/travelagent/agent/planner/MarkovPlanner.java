package com.travelagent.agent.planner;

import com.travelagent.agent.context.CompletedStep;
import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.client.amap.AmapClient;
import com.travelagent.client.dashscope.DashscopeLlmClient;
import com.travelagent.client.dashscope.LlmCallResult;
import com.travelagent.mapper.LlmCallLogMapper;
import com.travelagent.model.dto.LocationCandidateItem;
import com.travelagent.model.dto.NearbyPoiRecommendationRequest;
import com.travelagent.model.dto.NearbyPoiRecommendationResponse;
import com.travelagent.model.entity.Task;
import com.travelagent.service.notification.SseEvent;
import com.travelagent.service.notification.SseNotificationService;
import com.travelagent.service.rag.RagService;
import com.travelagent.service.recommendation.NearbyPoiRecommendationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.travelagent.agent.planner.PlannerUtils.*;

/**
 * 核心规划编排器：决策分支选择、调用 LLM、生成最终摘要。
 * Prompt 构建委托给 {@link PlannerPromptBuilder}，响应解析委托给 {@link PlannerResponseParser}。
 */
@Component
public class MarkovPlanner {

    private static final Logger log = LoggerFactory.getLogger(MarkovPlanner.class);

    @Autowired private DashscopeLlmClient llmClient;
    @Autowired private HistoryManager historyManager;
    @Autowired private SseNotificationService sseNotificationService;
    @Autowired private LlmCallLogMapper llmCallLogMapper;
    @Autowired private PlannerPromptBuilder promptBuilder;
    @Autowired private PlannerResponseParser responseParser;
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
                    promptBuilder.buildBranchSelectionOptions(weatherContext),
                    promptBuilder.buildSelectionContext(cp, request, weatherContext, null),
                    weatherContext
            );
        }

        if ("nearby_poi".equals(request.getSelectedBranchType())) {
            PlanningResult result = tryRecommendationDrivenSelection(cp, request, weatherContext);
            if (result != null) {
                return result;
            }
        }

        if ("route_plan".equals(request.getSelectedBranchType())) {
            PlanningResult result = tryRoutePlanningSelection(task, cp, request, taskUuid, weatherContext);
            if (result != null) {
                return result;
            }
        }

        List<String> ragChunks = fetchRagChunks(cp);
        String llmIdempotencyKey = taskUuid + "-step" + stepIndex + "-llm";
        String userMessage = promptBuilder.buildStepPrompt(cp);

        LlmCallResult llmResult = llmClient.callStreaming(
                task.getId(), task.getUserId(), "planning",
                promptBuilder.buildSystemPrompt(cp),
                historyManager.prepareForLlm(cp),
                userMessage,
                llmIdempotencyKey,
                token -> sseNotificationService.sendEvent(taskUuid, SseEvent.LLM_STREAM, Map.of("token", token)),
                llmClient.defaultPlanningAdvisors(),
                promptBuilder.buildAdvisorContext(cp, ragChunks)
        );

        historyManager.appendExchange(cp, userMessage, llmResult.content());

        String attractionName = responseParser.parseLlmAttractionName(llmResult.content(), stepIndex);
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
        request.setCurrentTime(promptBuilder.resolveCurrentTime(cp));
        request.setSelectedBranchType(cp.getSelectedBranchType());
        request.setVisitedPoiNames(cp.getCompletedSteps() == null
                ? List.of()
                : cp.getCompletedSteps().stream().map(CompletedStep::getAttractionName).toList());
        request.setWeatherContext(cp.getWeatherContext() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(cp.getWeatherContext()));

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
        NearbyPoiRecommendationRequest request = promptBuilder.buildRecommendationRequest(cp, planningRequest, weatherContext);
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
                    candidate.setWeatherSuitability(responseParser.resolveWeatherSuitability(item.getFeatures(), weatherContext));
                    candidate.setExplanations(item.getExplanations() == null ? List.of() : item.getExplanations());
                    candidate.setHighlights(item.getHighlights() == null ? List.of() : item.getHighlights());
                    return candidate;
                })
                .toList();
        Map<String, Object> currentContext = promptBuilder.buildSelectionContext(cp, planningRequest, weatherContext, "nearby_poi");
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
        List<String> ragChunks = fetchRagChunks(cp);
        String idempotencyKey = taskUuid + "-step" + cp.getCurrentStepIndex() + "-route-llm";
        LlmCallResult llmResult = llmClient.callStreaming(
                task.getId(), task.getUserId(), "route_planning",
                promptBuilder.buildRouteCandidateSystemPrompt(cp, request, weatherContext),
                historyManager.prepareForLlm(cp),
                promptBuilder.buildRouteCandidateUserPrompt(cp, request, weatherContext),
                idempotencyKey,
                token -> sseNotificationService.sendEvent(taskUuid, SseEvent.LLM_STREAM, Map.of("token", token)),
                llmClient.defaultPlanningAdvisors(),
                promptBuilder.buildAdvisorContext(cp, ragChunks)
        );
        List<LocationCandidateItem> routeCandidates = responseParser.parseRouteCandidates(llmResult.content(), weatherContext);
        if (routeCandidates.isEmpty()) {
            return null;
        }
        historyManager.appendExchange(cp, "route_plan", llmResult.content());
        int resolvedTokens = resolvePlanningTokens(task.getId(), idempotencyKey, llmResult.totalTokens());
        return PlanningResult.forCandidates(routeCandidates, resolvedTokens,
                "route_candidate_selection", "route_candidate_selection", "route_plan",
                promptBuilder.buildSelectionContext(cp, request, weatherContext, "route_plan"),
                weatherContext);
    }

    public FinalSummaryResult generateFinalSummary(Task task, TaskCheckpoint cp, String taskUuid) {
        String idempotencyKey = taskUuid + "-final-summary";
        try {
            String llmResponse = llmClient.call(
                    task.getId(), task.getUserId(), "final_summary",
                    promptBuilder.buildFinalSummarySystemPrompt(cp),
                    List.of(),
                    promptBuilder.buildFinalSummaryUserMessage(cp),
                    idempotencyKey);
            return responseParser.parseFinalSummary(llmResponse, cp);
        } catch (Exception e) {
            log.warn("[MarkovPlanner] Final summary LLM call failed (using defaults): {}", e.getMessage());
            return responseParser.parseFinalSummary(null, cp);
        }
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

    private List<String> fetchRagChunks(TaskCheckpoint cp) {
        if (ragService == null) {
            return List.of();
        }
        try {
            return ragService.queryChunks(cp.getUserIntent(), cp.getRegion(), 5);
        } catch (Exception e) {
            log.warn("[MarkovPlanner] RAG query failed: {}", e.getMessage());
            return List.of();
        }
    }
}
