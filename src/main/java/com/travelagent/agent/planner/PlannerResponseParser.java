package com.travelagent.agent.planner;

import com.fasterxml.jackson.core.type.TypeReference;
import com.travelagent.agent.context.CompletedStep;
import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.model.dto.LocationCandidateItem;
import com.travelagent.model.dto.RecommendationFeatureBreakdown;
import com.travelagent.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.travelagent.agent.planner.PlannerUtils.*;

/**
 * 负责解析 LLM 响应：景点名称、路线候选、最终行程摘要。
 */
@Component
public class PlannerResponseParser {

    private static final Logger log = LoggerFactory.getLogger(PlannerResponseParser.class);

    @Autowired private JsonUtil jsonUtil;

    public String parseLlmAttractionName(String llmResponse, int stepIndex) {
        if (llmResponse == null || llmResponse.isBlank()) {
            log.warn("[PlannerResponseParser] Received blank LLM response at step={}", stepIndex);
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
            log.warn("[PlannerResponseParser] JSON parse failed at step={}: {}", stepIndex, e.getMessage());
        }
        return llmResponse.length() > 50 ? llmResponse.substring(0, 50).trim() : llmResponse.trim();
    }

    @SuppressWarnings("unchecked")
    public List<LocationCandidateItem> parseRouteCandidates(String llmResponse, Map<String, Object> weatherContext) {
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
                candidate.setHighlights(resolveRouteHighlights(route, candidate));
                candidate.setExplanations(List.of(firstNonBlank(
                        stringValue(route.get("reason")), "Generated from attraction value and visit appeal")));
                candidates.add(candidate);
            }
            return candidates;
        } catch (Exception e) {
            log.warn("[PlannerResponseParser] Failed to parse route candidates: {}", e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    public FinalSummaryResult parseFinalSummary(String llmResponse, TaskCheckpoint cp) {
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
            log.warn("[PlannerResponseParser] Final summary parse failed: {}", e.getMessage());
            return buildDefaultSummary(cp);
        }
    }

    public String resolveWeatherSuitability(RecommendationFeatureBreakdown features,
                                             Map<String, Object> weatherContext) {
        if (features != null && Boolean.TRUE.equals(features.getWeatherFriendly())) {
            return "天气适配较好";
        }
        return firstNonBlank(stringValue(weatherContext.get("summary")), "常规适配");
    }

    private FinalSummaryResult buildDefaultSummary(TaskCheckpoint cp) {
        String title = cp.getRegion() + " " + cp.getPlanningConfig().getTotalDays() + "-Day Trip";
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

    private List<String> resolveRouteHighlights(Map<?, ?> route, LocationCandidateItem candidate) {
        List<String> llmHighlights = sanitizeRouteHighlights(toStringList(route.get("reasonHighlights")));
        if (!llmHighlights.isEmpty()) {
            return llmHighlights;
        }
        return buildFallbackRouteHighlights(route, candidate);
    }

    private List<String> buildFallbackRouteHighlights(Map<?, ?> route, LocationCandidateItem candidate) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        values.addAll(extractHighlightFragments(stringValue(route.get("title"))));
        values.addAll(extractHighlightFragments(stringValue(route.get("targetAttractionName"))));
        candidate.getRouteStops().forEach(stop -> values.addAll(extractHighlightFragments(stop)));
        values.addAll(extractHighlightFragments(stringValue(route.get("reason"))));
        List<String> sanitized = sanitizeRouteHighlights(new ArrayList<>(values));
        return sanitized.isEmpty() ? List.of("city icon", "visit experience", "cultural feel") : sanitized;
    }

    private List<String> sanitizeRouteHighlights(List<String> rawHighlights) {
        if (rawHighlights == null || rawHighlights.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String raw : rawHighlights) {
            if (raw == null) {
                continue;
            }
            String value = raw.trim()
                    .replace('\uFF0C', ' ')
                    .replace('\u3001', ' ')
                    .replace('\uFF1B', ' ');
            value = value.replaceAll("\\s+", " ").trim();
            if (value.isBlank() || value.length() > 12 || containsRouteProcessTerms(value)) {
                continue;
            }
            normalized.add(value);
            if (normalized.size() == 4) {
                break;
            }
        }
        return List.copyOf(normalized);
    }

    private List<String> extractHighlightFragments(String source) {
        if (source == null || source.isBlank()) {
            return List.of();
        }
        String[] parts = source.split("[,\uFF0C\u3001\uFF1B;|/\\-\u2192>\\s]+");
        List<String> fragments = new ArrayList<>();
        for (String part : parts) {
            String normalized = part.trim();
            if (normalized.isBlank() || normalized.length() > 12) {
                continue;
            }
            fragments.add(normalized);
        }
        return fragments;
    }

    private boolean containsRouteProcessTerms(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("顺路") || normalized.contains("天气") || normalized.contains("路线")
                || normalized.contains("预算") || normalized.contains("约束") || normalized.contains("推荐")
                || normalized.contains("时间") || normalized.contains("travel") || normalized.contains("route")
                || normalized.contains("weather") || normalized.contains("budget");
    }

    private static String stringOrDefault(Object value, String defaultVal) {
        if (value == null) {
            return defaultVal;
        }
        String s = value.toString().trim();
        return s.isBlank() ? defaultVal : s;
    }

    private static int toInt(Object value, int defaultVal) {
        if (value == null) {
            return defaultVal;
        }
        try {
            return ((Number) value).intValue();
        } catch (Exception e) {
            return defaultVal;
        }
    }
}
