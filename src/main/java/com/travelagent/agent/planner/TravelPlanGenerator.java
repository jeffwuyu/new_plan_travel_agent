package com.travelagent.agent.planner;

import com.travelagent.agent.requirements.TravelConstraints;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class TravelPlanGenerator {

    private static final String WEATHER_TASK = "PT01";
    private static final String MAP_TASK = "PT02";
    private static final String WEB_TASK = "PT03";
    private static final String RAG_TASK = "PT04";
    private static final String BOOKING_TASK = "PT05";
    private static final String HOTEL_TASK = "PT06";
    private static final String BUDGET_TASK = "PT07";
    private static final String ITINERARY_TASK = "PT08";
    private static final String VALIDATOR_TASK = "PT09";

    public TravelPlan generate(TravelConstraints constraints) {
        TravelConstraints readyConstraints = requireReadyForPlanning(constraints);

        TravelPlan plan = new TravelPlan();
        plan.setGoal(buildGoal(readyConstraints));
        plan.setConstraints(readyConstraints);
        plan.setTasks(List.of(
                task(WEATHER_TASK, PlanTaskType.WEATHER_QUERY, PlannerToolType.WEATHER,
                        weatherInput(readyConstraints),
                        List.of(),
                        List.of("returns weather summary", "includes query time or source", "flags extreme weather risk")),
                task(MAP_TASK, PlanTaskType.MAP_QUERY, PlannerToolType.AMAP,
                        mapInput(readyConstraints),
                        List.of(),
                        List.of("resolves destination and POIs", "returns route or distance hints", "supports transport analysis")),
                task(WEB_TASK, PlanTaskType.WEB_REALTIME_QUERY, PlannerToolType.WEB_SEARCH,
                        webInput(readyConstraints),
                        List.of(),
                        List.of("returns high-confidence source links", "covers opening hours or policy changes")),
                task(RAG_TASK, PlanTaskType.RAG_RETRIEVAL, PlannerToolType.RAG,
                        ragInput(readyConstraints),
                        List.of(),
                        List.of("recalls curated travel knowledge", "keeps static knowledge separate from realtime sources")),
                task(BOOKING_TASK, PlanTaskType.BOOKING_QUERY, PlannerToolType.BOOKING_QUERY,
                        bookingInput(readyConstraints),
                        List.of(WEB_TASK),
                        List.of("identifies booking requirements", "does not submit orders or payment", "returns official entry when available")),
                task(HOTEL_TASK, PlanTaskType.ACCOMMODATION_ANALYSIS, PlannerToolType.HOTEL_ANALYSIS,
                        baseInput(readyConstraints, "destination", "hotelPreference", "budgetYuan", "peopleCount", "specialGroups"),
                        List.of(MAP_TASK, RAG_TASK),
                        List.of("recommends accommodation areas", "explains commute and budget tradeoffs")),
                task(BUDGET_TASK, PlanTaskType.BUDGET_ESTIMATION, PlannerToolType.BUDGET,
                        baseInput(readyConstraints, "budgetYuan", "peopleCount", "days", "transportPreference", "hotelPreference"),
                        List.of(MAP_TASK, WEB_TASK, BOOKING_TASK, HOTEL_TASK),
                        List.of("splits transport, hotel, tickets, food and contingency", "flags over-budget risks")),
                task(ITINERARY_TASK, PlanTaskType.ITINERARY_GENERATION, PlannerToolType.ITINERARY,
                        baseInput(readyConstraints, "destination", "days", "travelPace", "avoid", "specialGroups"),
                        List.of(WEATHER_TASK, MAP_TASK, WEB_TASK, RAG_TASK, BOOKING_TASK, HOTEL_TASK, BUDGET_TASK),
                        List.of("generates day-by-day itinerary", "uses tool results and constraints", "keeps alternatives when needed")),
                task(VALIDATOR_TASK, PlanTaskType.VALIDATOR_CHECK, PlannerToolType.VALIDATOR,
                        baseInput(readyConstraints, "destination", "budgetYuan", "peopleCount", "travelPace", "specialGroups"),
                        List.of(ITINERARY_TASK, BUDGET_TASK, MAP_TASK, WEATHER_TASK, WEB_TASK, BOOKING_TASK),
                        List.of("validates budget, route time, weather and people suitability", "returns issues for re-plan"))
        ));
        return plan;
    }

    public TravelPlan regenerate(TravelPlan previousPlan, TravelConstraints revisedConstraints, String changeReason) {
        if (previousPlan == null) {
            throw new IllegalArgumentException("previousPlan is required");
        }
        TravelPlan next = generate(revisedConstraints);
        next.setPlanId(previousPlan.getPlanId());
        next.setVersion(previousPlan.getVersion() + 1);
        next.setChangeReason(isBlank(changeReason) ? "constraints or validator issue changed" : changeReason);
        next.setChangeHistory(new ArrayList<>(previousPlan.getChangeHistory()));
        next.getChangeHistory().add(new TravelPlanChangeRecord(
                previousPlan.getVersion(),
                next.getVersion(),
                next.getChangeReason(),
                revisedConstraints == null ? List.of() : revisedConstraints.getUpdatedFields()));
        return next;
    }

    private TravelConstraints requireReadyForPlanning(TravelConstraints constraints) {
        if (constraints == null) {
            throw new IllegalArgumentException("constraints is required");
        }
        TravelConstraints refreshed = constraints.refreshMissingFields();
        if (refreshed.isMustAsk()) {
            throw new IllegalArgumentException("required travel constraints are missing: " + refreshed.getMissingFields());
        }
        return refreshed;
    }

    private TravelPlanTask task(String taskId,
                                PlanTaskType taskType,
                                PlannerToolType toolType,
                                Map<String, Object> input,
                                List<String> dependencies,
                                List<String> successCriteria) {
        return new TravelPlanTask(taskId, taskType, toolType, input, dependencies, successCriteria);
    }

    private String buildGoal(TravelConstraints constraints) {
        String destination = constraints.getDestination();
        int days = constraints.getDays() == null ? resolveDaysFromDateRange(constraints) : constraints.getDays();
        return destination + " " + days + " day travel plan";
    }

    private int resolveDaysFromDateRange(TravelConstraints constraints) {
        LocalDate start = constraints.getStartDate();
        LocalDate end = constraints.getEndDate();
        if (start == null || end == null || end.isBefore(start)) {
            return 1;
        }
        return (int) (end.toEpochDay() - start.toEpochDay()) + 1;
    }

    private Map<String, Object> baseInput(TravelConstraints constraints, String... fields) {
        Map<String, Object> input = new LinkedHashMap<>();
        for (String field : fields) {
            Object value = switch (field) {
                case "rawText" -> constraints.getRawText();
                case "departure" -> constraints.getDeparture();
                case "destination" -> constraints.getDestination();
                case "startDate" -> constraints.getStartDate();
                case "endDate" -> constraints.getEndDate();
                case "days" -> constraints.getDays();
                case "budgetYuan" -> constraints.getBudgetYuan();
                case "peopleCount" -> constraints.getPeopleCount();
                case "transportPreference" -> constraints.getTransportPreference();
                case "hotelPreference" -> constraints.getHotelPreference();
                case "attractionPreference" -> constraints.getAttractionPreference();
                case "foodPreference" -> constraints.getFoodPreference();
                case "travelPace" -> constraints.getTravelPace();
                case "specialGroups" -> constraints.getSpecialGroups();
                case "bookingRequired" -> constraints.getBookingRequired();
                case "avoid" -> constraints.getAvoid();
                default -> null;
            };
            if (value != null) {
                input.put(field, value);
            }
        }
        return input;
    }

    private Map<String, Object> weatherInput(TravelConstraints constraints) {
        Map<String, Object> input = baseInput(constraints, "destination", "startDate", "endDate", "days");
        input.putIfAbsent("city", constraints.getDestination());
        if (constraints.getStartDate() != null) {
            input.put("date", constraints.getStartDate().toString());
        }
        return input;
    }

    private Map<String, Object> mapInput(TravelConstraints constraints) {
        Map<String, Object> input = baseInput(constraints, "departure", "destination", "transportPreference", "attractionPreference");
        input.putIfAbsent("city", constraints.getDestination());
        List<String> keywords = new ArrayList<>();
        if (constraints.getAttractionPreference() != null) {
            keywords.addAll(constraints.getAttractionPreference());
        }
        if (keywords.isEmpty() && constraints.getDestination() != null) {
            keywords.add(constraints.getDestination());
        }
        input.put("poiKeywords", keywords);
        input.put("attractions", keywords);
        input.put("travelMode", resolveTravelMode(constraints));
        return input;
    }

    private Map<String, Object> webInput(TravelConstraints constraints) {
        Map<String, Object> input = baseInput(constraints, "destination", "attractionPreference", "bookingRequired");
        input.putIfAbsent("city", constraints.getDestination());
        input.putIfAbsent("infoType", "开放时间 门票 预约规则 限流政策 临时闭园");
        input.putIfAbsent("query", buildQueryText(constraints, "官方 最新 开放时间 门票 预约"));
        return input;
    }

    private Map<String, Object> ragInput(TravelConstraints constraints) {
        Map<String, Object> input = baseInput(constraints, "destination", "attractionPreference", "foodPreference", "travelPace", "specialGroups");
        input.putIfAbsent("region", constraints.getDestination());
        input.putIfAbsent("query", buildQueryText(constraints, "旅游 景点 文化 美食 行程建议"));
        return input;
    }

    private Map<String, Object> bookingInput(TravelConstraints constraints) {
        Map<String, Object> input = baseInput(constraints, "destination", "startDate", "endDate", "bookingRequired", "attractionPreference");
        input.putIfAbsent("city", constraints.getDestination());
        input.putIfAbsent("date", constraints.getStartDate() == null ? null : constraints.getStartDate().toString());
        input.putIfAbsent("query", buildQueryText(constraints, "官方 预约 门票 票价 购票 入口"));
        return input;
    }

    private String resolveTravelMode(TravelConstraints constraints) {
        List<String> preferences = constraints.getTransportPreference();
        if (preferences == null || preferences.isEmpty()) {
            return "driving";
        }
        String joined = String.join(",", preferences);
        if (joined.contains("公共交通") || joined.contains("公交") || joined.contains("地铁")) {
            return "transit";
        }
        if (joined.contains("步行")) {
            return "walking";
        }
        return "driving";
    }

    private String buildQueryText(TravelConstraints constraints, String suffix) {
        List<String> parts = new ArrayList<>();
        if (constraints.getDestination() != null) {
            parts.add(constraints.getDestination());
        }
        if (constraints.getAttractionPreference() != null) {
            parts.addAll(constraints.getAttractionPreference());
        }
        if (constraints.getFoodPreference() != null) {
            parts.add(constraints.getFoodPreference());
        }
        parts.add(suffix);
        return String.join(" ", parts).trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
