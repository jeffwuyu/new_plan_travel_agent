package com.travelagent.agent.planner;

import com.travelagent.advisor.AdvisorContextKeys;
import com.travelagent.agent.context.CompletedStep;
import com.travelagent.agent.context.DailyTimeWindow;
import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.agent.tools.WeatherTool;
import com.travelagent.model.dto.NearbyPoiRecommendationRequest;
import com.travelagent.model.dto.RoutePoint;
import com.travelagent.model.dto.SelectionOptionItem;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.travelagent.agent.planner.PlannerUtils.*;

/**
 * 负责所有 LLM Prompt 的构建，无副作用，无外部调用。
 */
@Component
public class PlannerPromptBuilder {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    public String buildSystemPrompt(TaskCheckpoint cp) {
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

    public String buildStepPrompt(TaskCheckpoint cp) {
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

    public String buildRouteCandidateSystemPrompt(TaskCheckpoint cp,
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
                      "reasonHighlights": ["历史氛围", "地标打卡", "夜景体验"],
                      "reason": "string",
                      "estimatedTotalDurationMin": 120,
                      "weatherSuitability": "string"
                    }
                  ]
                }
                The field reasonHighlights must contain 2 to 4 short Chinese keywords or phrases.
                reasonHighlights should explain why this attraction or route is worth visiting.
                Focus on attraction value, atmosphere, theme, city identity, photos, culture, food, family appeal, or night vibe.
                Do not use process-style reasons such as 顺路, 天气合适, 路线推荐, 时间预算内.
                """;
    }

    public String buildRouteCandidateUserPrompt(TaskCheckpoint cp,
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
        if (request.getNodePreferencePrompt() != null && !request.getNodePreferencePrompt().isBlank()) {
            sb.append("Current node preference: ").append(request.getNodePreferencePrompt()).append("\n");
        }
        sb.append("User intent: ").append(cp.getUserIntent()).append("\n");
        sb.append("Return route candidates only. reasonHighlights must be concise attraction-value keywords, not route/weather/process phrases.");
        return sb.toString();
    }

    public String buildFinalSummarySystemPrompt(TaskCheckpoint cp) {
        String preferenceKeywords = "";
        if (cp.getPlanningConfig() != null
                && cp.getPlanningConfig().getPreferenceKeywords() != null
                && !cp.getPlanningConfig().getPreferenceKeywords().isEmpty()) {
            preferenceKeywords = String.join(", ", cp.getPlanningConfig().getPreferenceKeywords());
        }
        String travelMode = cp.getPlanningConfig() != null ? cp.getPlanningConfig().getTravelMode() : "";
        return PromptTemplates.buildFinalSummarySystem(
                cp.getRegion(),
                cp.getPlanningConfig() != null ? cp.getPlanningConfig().getTotalDays() : 1,
                cp.getUserIntent(),
                travelMode,
                preferenceKeywords);
    }

    public String buildFinalSummaryUserMessage(TaskCheckpoint cp) {
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

    public Map<String, Object> buildAdvisorContext(TaskCheckpoint cp, List<String> ragChunks) {
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

    public Map<String, Object> buildSelectionContext(TaskCheckpoint cp,
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

    public List<SelectionOptionItem> buildBranchSelectionOptions(Map<String, Object> weatherContext) {
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

    public NearbyPoiRecommendationRequest buildRecommendationRequest(TaskCheckpoint cp,
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

    String resolveCurrentTime(TaskCheckpoint cp) {
        DailyTimeWindow window = cp.getDailyWindow(resolveDayNumber(cp));
        if (window == null || window.getStartTime() == null) {
            return null;
        }
        int offsetWithinTrip = cp.getUsedTimeBudgetMin() == null ? 0 : cp.getUsedTimeBudgetMin();
        return window.getStartTime().plusMinutes(offsetWithinTrip).format(TIME_FORMATTER);
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

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
