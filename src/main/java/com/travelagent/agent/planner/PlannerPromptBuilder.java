package com.travelagent.agent.planner;

import com.travelagent.advisor.AdvisorContextKeys;
import com.travelagent.agent.memory.UserPreferenceMemoryService;
import com.travelagent.agent.context.CompletedStep;
import com.travelagent.agent.context.DailyTimeWindow;
import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.agent.prompt.PromptAssembly;
import com.travelagent.agent.prompt.PromptSectionType;
import com.travelagent.agent.tools.WeatherTool;
import com.travelagent.model.dto.LocationCandidateItem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.travelagent.agent.planner.PlannerUtils.firstNonBlank;

@Component
public class PlannerPromptBuilder {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    private final UserPreferenceMemoryService userPreferenceMemoryService;

    public PlannerPromptBuilder() {
        this(null);
    }

    @Autowired
    public PlannerPromptBuilder(UserPreferenceMemoryService userPreferenceMemoryService) {
        this.userPreferenceMemoryService = userPreferenceMemoryService;
    }

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
            sb.append("Total trip days: ").append(cp.getPlanningConfig().getTotalDays())
                    .append("; keep the itinerary distributed by dayNumber and never compress a multi-day trip into one day.\n");
            sb.append("Remaining planning budget: ")
                    .append(cp.getRemainingTimeBudgetMin())
                    .append(" minutes.\n");
            if (cp.getPlanningConfig().getTotalBudgetYuan() != null) {
                sb.append("Total user budget: CNY ")
                        .append(cp.getPlanningConfig().getTotalBudgetYuan())
                        .append(".\n");
            }
            if (cp.getPlanningConfig().getLodgingBudgetPerNightYuan() != null) {
                sb.append("Lodging budget per night: CNY ")
                        .append(cp.getPlanningConfig().getLodgingBudgetPerNightYuan())
                        .append("; lodging prices must come from OTA availability data, not from the model.\n");
            }
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
        DailyTimeWindow dayWindow = cp.getDailyWindow(dayNumber);
        StringBuilder sb = new StringBuilder();
        sb.append("Recommend attraction ")
                .append(stepIndex + 1)
                .append(" for day ")
                .append(dayNumber)
                .append(" (overall ")
                .append(stepIndex + 1)
                .append("/")
                .append(totalSteps)
                .append(")")
                .append(".\n");
        sb.append("Region: ").append(cp.getRegion()).append(".\n");
        if (cp.getPlanningConfig() != null && cp.getPlanningConfig().getTotalDays() > 1) {
            sb.append("This is a multi-day trip. Choose a stop that fits day ")
                    .append(dayNumber)
                    .append(" and leave future days available for later steps.\n");
        }
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
        if (cp.getSelectedOrigin() != null && (cp.getCompletedSteps() == null || cp.getCompletedSteps().isEmpty())) {
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
        return sb.toString();
    }

    public String buildRouteCandidateSystemPrompt(TaskCheckpoint cp,
                                                  PlanNextAttractionRequest request,
                                                  Map<String, Object> weatherContext) {
        String profile = buildLongTermUserProfile(cp);
        return """
                You are a travel route planner. Return strict JSON only.
                Context priority: current user request and explicit task constraints > short-term session memory > tool/RAG facts > long-term user profile.
                Long-term user profile is low priority and must never override current-session requirements.
                You must choose only from the provided ranked RAG candidates.
                Generate up to 3 next-attraction candidates. Keep each routeId exactly equal to one provided candidateId.
                Each candidate must fit the remaining budget and current weather constraints.
                JSON schema:
                {
                  "routes": [
                    {
                      "routeId": "candidateId-from-input",
                      "title": "string",
                      "targetAttractionName": "string",
                      "stops": ["string"],
                      "reasonHighlights": ["history", "city icon", "photo spot"],
                      "reason": "string",
                      "estimatedTotalDurationMin": 120,
                      "weatherSuitability": "string"
                    }
                  ]
                }
                reasonHighlights must contain 2 to 4 short attraction-value keywords.
                Do not invent attractions outside the candidate list.
                """ + (profile.isBlank() ? "" : "\n" + profile + "\n");
    }

    public PromptAssembly buildRouteCandidatePrompt(TaskCheckpoint cp,
                                                    PlanNextAttractionRequest request,
                                                    Map<String, Object> weatherContext,
                                                    List<LocationCandidateItem> candidates) {
        String profile = buildLongTermUserProfile(cp);
        return PromptAssembly.create()
                .add(PromptSectionType.SYSTEM, "You are a travel route planner. Return strict JSON only.")
                .add(PromptSectionType.POLICY, """
                        Context priority: current user request and explicit task constraints > short-term session memory > tool/RAG facts > long-term user profile.
                        Long-term user profile is low priority and must never override current-session requirements.
                        You must choose only from the provided ranked RAG candidates.
                        Generate up to 3 next-attraction candidates. Keep each routeId exactly equal to one provided candidateId.
                        Each candidate must fit the remaining budget and current weather constraints.
                        Do not invent attractions outside the candidate list.
                        """)
                .add(PromptSectionType.MEMORY, buildRouteMemory(cp, profile))
                .add(PromptSectionType.CURRENT_GOAL, buildRouteCurrentGoal(cp, request))
                .add(PromptSectionType.OBSERVATION, buildRouteObservation(cp, request, weatherContext, candidates))
                .add(PromptSectionType.OUTPUT_FORMAT, """
                        JSON schema:
                        {
                          "routes": [
                            {
                              "routeId": "candidateId-from-input",
                              "title": "string",
                              "targetAttractionName": "string",
                              "stops": ["string"],
                              "reasonHighlights": ["history", "city icon", "photo spot"],
                              "reason": "string",
                              "estimatedTotalDurationMin": 120,
                              "weatherSuitability": "string"
                            }
                          ]
                        }
                        reasonHighlights must contain 2 to 4 short attraction-value keywords.
                        Return candidates only. routeId must match one candidateId above.
                        """);
    }

    public String buildRouteCandidateUserPrompt(TaskCheckpoint cp,
                                                PlanNextAttractionRequest request,
                                                Map<String, Object> weatherContext,
                                                List<LocationCandidateItem> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("Region: ").append(cp.getRegion()).append("\n");
        sb.append("Current position: ").append(firstNonBlank(request.getCurrentPositionName(), "unknown")).append("\n");
        sb.append("Travel mode: ").append(firstNonBlank(request.getTravelMode(), "driving")).append("\n");
        sb.append("Remaining budget: ").append(cp.getRemainingTimeBudgetMin()).append(" minutes\n");
        sb.append("Visited attractions: ").append(request.getVisitedPoiNames()).append("\n");
        sb.append("Destination constraint: ")
                .append(cp.getSelectedDestination() != null ? cp.getSelectedDestination().getName() : "none")
                .append("\n");
        sb.append("Weather summary: ").append(weatherContext.getOrDefault("summary", "none")).append("\n");
        sb.append("Weather constraints: ").append(weatherContext.getOrDefault("constraintHints", List.of())).append("\n");
        if (request.getNodePreferencePrompt() != null && !request.getNodePreferencePrompt().isBlank()) {
            sb.append("Current node preference: ").append(request.getNodePreferencePrompt()).append("\n");
        }
        sb.append("User intent: ").append(cp.getUserIntent()).append("\n");
        sb.append("Ranked RAG candidates:\n");
        for (LocationCandidateItem candidate : candidates == null ? List.<LocationCandidateItem>of() : candidates) {
            sb.append("- candidateId: ").append(candidate.getCandidateId()).append("\n");
            sb.append("  name: ").append(candidate.getName()).append("\n");
            sb.append("  score: ").append(candidate.getScore()).append("\n");
            sb.append("  routeSummary: ").append(candidate.getRouteSummary()).append("\n");
            sb.append("  estimatedTotalDurationMin: ").append(candidate.getEstimatedTotalDurationMin()).append("\n");
            sb.append("  weatherSuitability: ").append(candidate.getWeatherSuitability()).append("\n");
            sb.append("  reasons: ").append(candidate.getExplanations()).append("\n");
        }
        sb.append("Return candidates only. routeId must match one candidateId above.");
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

    public PromptAssembly buildFinalSummaryPrompt(TaskCheckpoint cp) {
        return PromptAssembly.create()
                .add(PromptSectionType.SYSTEM, buildFinalSummarySystemPrompt(cp))
                .add(PromptSectionType.POLICY, """
                        Do not invent phone numbers, addresses, prices, URLs, opening hours, booking status, ticket availability, weather, or traffic times.
                        These facts may be used only when they are present in tool result evidence.
                        If a tool did not return a fact, say that the tool did not return it or that official confirmation is needed; never fill it in from general knowledge.
                        """)
                .add(PromptSectionType.MEMORY, buildCompletedStepsMemory(cp))
                .add(PromptSectionType.CURRENT_GOAL, "Generate the final structured travel summary for the completed itinerary.")
                .add(PromptSectionType.OBSERVATION, buildFinalSummaryUserMessage(cp))
                .add(PromptSectionType.OUTPUT_FORMAT, "Return strict JSON only, matching the schema described above. Do not use markdown fences or commentary.");
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
        String profile = buildLongTermUserProfile(cp);
        if (!profile.isBlank()) {
            context.put(AdvisorContextKeys.LONG_TERM_USER_PROFILE, profile);
        }
        if (ragChunks != null && !ragChunks.isEmpty()) {
            context.put(AdvisorContextKeys.RAG_CHUNKS, ragChunks);
        }
        context.put(AdvisorContextKeys.RESPONSE_SCHEMA, Map.of(
                "type", "object",
                "required", List.of("routes")
        ));
        return context;
    }

    String buildLongTermUserProfile(TaskCheckpoint cp) {
        if (userPreferenceMemoryService == null || cp == null || cp.getUserId() == null) {
            return "";
        }
        return userPreferenceMemoryService.buildPromptProfile(cp.getUserId(), cp.getStructuredConstraints());
    }

    private String buildRouteMemory(TaskCheckpoint cp, String longTermProfile) {
        StringBuilder sb = new StringBuilder();
        if (longTermProfile != null && !longTermProfile.isBlank()) {
            sb.append(longTermProfile.trim());
        }
        String completed = buildCompletedStepsMemory(cp);
        if (!completed.isBlank()) {
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append(completed);
        }
        return sb.toString();
    }

    private String buildCompletedStepsMemory(TaskCheckpoint cp) {
        if (cp == null || cp.getCompletedSteps() == null || cp.getCompletedSteps().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("Completed itinerary steps:\n");
        for (CompletedStep step : cp.getCompletedSteps()) {
            sb.append("- Step ").append(step.getStepIndex())
                    .append(" day ").append(step.getDayNumber())
                    .append(": ").append(step.getAttractionName());
            if (step.getLat() != null && step.getLng() != null) {
                sb.append(" (").append(step.getLat()).append(", ").append(step.getLng()).append(")");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    private String buildRouteCurrentGoal(TaskCheckpoint cp, PlanNextAttractionRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("Region: ").append(cp.getRegion()).append("\n");
        sb.append(buildStepPrompt(cp).trim()).append("\n");
        sb.append("Current position: ").append(firstNonBlank(request.getCurrentPositionName(), "unknown")).append("\n");
        sb.append("Travel mode: ").append(firstNonBlank(request.getTravelMode(), "driving")).append("\n");
        if (request.getNodePreferencePrompt() != null && !request.getNodePreferencePrompt().isBlank()) {
            sb.append("Current node preference: ").append(request.getNodePreferencePrompt()).append("\n");
        }
        sb.append("User intent: ").append(cp.getUserIntent());
        return sb.toString();
    }

    private String buildRouteObservation(TaskCheckpoint cp,
                                         PlanNextAttractionRequest request,
                                         Map<String, Object> weatherContext,
                                         List<LocationCandidateItem> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("Visited attractions: ").append(request.getVisitedPoiNames()).append("\n");
        sb.append("Destination constraint: ")
                .append(cp.getSelectedDestination() != null ? cp.getSelectedDestination().getName() : "none")
                .append("\n");
        sb.append("Weather summary: ").append(weatherContext.getOrDefault("summary", "none")).append("\n");
        sb.append("Weather constraints: ").append(weatherContext.getOrDefault("constraintHints", List.of())).append("\n");
        sb.append("Ranked RAG candidates:\n");
        for (LocationCandidateItem candidate : candidates == null ? List.<LocationCandidateItem>of() : candidates) {
            sb.append("- candidateId: ").append(candidate.getCandidateId()).append("\n");
            sb.append("  name: ").append(candidate.getName()).append("\n");
            sb.append("  score: ").append(candidate.getScore()).append("\n");
            sb.append("  routeSummary: ").append(candidate.getRouteSummary()).append("\n");
            sb.append("  estimatedTotalDurationMin: ").append(candidate.getEstimatedTotalDurationMin()).append("\n");
            sb.append("  weatherSuitability: ").append(candidate.getWeatherSuitability()).append("\n");
            sb.append("  reasons: ").append(candidate.getExplanations()).append("\n");
        }
        return sb.toString().trim();
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

    String resolveCurrentTime(TaskCheckpoint cp) {
        DailyTimeWindow window = cp.getDailyWindow(resolveDayNumber(cp));
        if (window == null || window.getStartTime() == null) {
            return null;
        }
        int offsetWithinDay = resolveOffsetWithinDay(cp, window);
        return window.getStartTime().plusMinutes(offsetWithinDay).format(TIME_FORMATTER);
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

    private int resolveOffsetWithinDay(TaskCheckpoint cp, DailyTimeWindow currentWindow) {
        int remaining = cp.getUsedTimeBudgetMin() == null ? 0 : cp.getUsedTimeBudgetMin();
        if (cp.getDailyTimeWindows() == null || cp.getDailyTimeWindows().isEmpty()) {
            return remaining;
        }
        for (DailyTimeWindow window : cp.getDailyTimeWindows()) {
            if (window.getDayNumber() == currentWindow.getDayNumber()) {
                return Math.max(0, Math.min(remaining, window.availableMinutes()));
            }
            remaining -= window.availableMinutes();
            if (remaining < 0) {
                return 0;
            }
        }
        return Math.max(0, remaining);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
