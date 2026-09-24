package com.travelagent.agent.itinerary;

import com.travelagent.agent.memory.UserPreference;
import com.travelagent.agent.requirements.TravelConstraints;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

@Service
public class ItineraryGenerator {

    public GeneratedItinerary generate(ItineraryGenerationInput input) {
        if (input == null || input.getConstraints() == null) {
            throw new IllegalArgumentException("constraints are required");
        }
        TravelConstraints constraints = input.getConstraints();
        int days = constraints.getDays() == null ? 1 : Math.max(1, constraints.getDays());
        String pace = resolvePace(constraints, input.getPreference());
        List<String> attractions = resolveAttractions(constraints);
        BigDecimal dailyBudget = dailyBudget(constraints, days);

        GeneratedItinerary itinerary = new GeneratedItinerary();
        itinerary.setDestination(constraints.getDestination());
        itinerary.setDays(days);
        itinerary.setPace(pace);
        itinerary.setEstimatedTotalBudgetYuan(dailyBudget.multiply(BigDecimal.valueOf(days)));

        for (int day = 1; day <= days; day++) {
            itinerary.getDailyPlans().add(buildDay(day, constraints, pace, attractions, dailyBudget));
        }
        itinerary.getKeyReasons().add("Plan uses destination, budget, pace, people count and attraction preferences as primary constraints.");
        itinerary.getKeyReasons().add("Weather, booking, map and RAG results can override stop order through validator or re-plan constraints.");
        itinerary.getAlternatives().add("If realtime opening hours or booking status conflicts, replace the affected stop with a same-city indoor or lower-intensity attraction.");
        itinerary.getAlternatives().add("If budget exceeds the limit, reduce paid attractions first, then compress accommodation and transport costs.");
        return itinerary;
    }

    private DailyItinerary buildDay(int day,
                                    TravelConstraints constraints,
                                    String pace,
                                    List<String> attractions,
                                    BigDecimal dailyBudget) {
        DailyItinerary daily = new DailyItinerary();
        daily.setDayNumber(day);
        daily.setTitle("Day " + day + " in " + constraints.getDestination());
        daily.setEstimatedBudgetYuan(dailyBudget);
        daily.setEstimatedWalkingKm("intensive".equals(pace) ? 9 : "relaxed".equals(pace) ? 4 : 6);
        daily.setMealSuggestion(resolveMeal(constraints));
        daily.setNote("Confirm realtime opening hours, booking slots and weather before departure.");

        int stopCount = "intensive".equals(pace) ? 4 : "relaxed".equals(pace) ? 2 : 3;
        for (int i = 0; i < stopCount; i++) {
            String name = attractions.get((day + i - 1) % attractions.size());
            String start = switch (i) {
                case 0 -> "09:30";
                case 1 -> "13:30";
                case 2 -> "16:00";
                default -> "19:00";
            };
            String end = switch (i) {
                case 0 -> "11:30";
                case 1 -> "15:00";
                case 2 -> "17:30";
                default -> "20:30";
            };
            boolean indoor = name.contains("museum") || name.contains("博物馆") || name.contains("室内");
            daily.getStops().add(new ItineraryStop(name, themeFor(constraints), start, end,
                    "relaxed".equals(pace) ? 120 : 90, indoor,
                    "Matches user interests and keeps daily density aligned with " + pace + " pace."));
            if (i > 0) {
                ItineraryStop previous = daily.getStops().get(i - 1);
                daily.getTransportLegs().add(new TransportLeg(previous.getName(), name,
                        resolveTransportMode(constraints), "relaxed".equals(pace) ? 25 : 35, "planner:map-or-default"));
            }
        }
        return daily;
    }

    private List<String> resolveAttractions(TravelConstraints constraints) {
        List<String> values = new ArrayList<>();
        if (constraints.getAttractionPreference() != null) {
            for (String preference : constraints.getAttractionPreference()) {
                if (preference != null && !preference.isBlank()) {
                    values.add(constraints.getDestination() + " " + preference + " route");
                }
            }
        }
        if (values.isEmpty()) {
            values.add(constraints.getDestination() + " core attraction");
            values.add(constraints.getDestination() + " local culture area");
            values.add(constraints.getDestination() + " food street");
        }
        return values;
    }

    private BigDecimal dailyBudget(TravelConstraints constraints, int days) {
        if (constraints.getBudgetYuan() == null) {
            return BigDecimal.valueOf(500);
        }
        return constraints.getBudgetYuan()
                .divide(BigDecimal.valueOf(days), 0, RoundingMode.DOWN)
                .max(BigDecimal.valueOf(100));
    }

    private String resolvePace(TravelConstraints constraints, UserPreference preference) {
        if (constraints.getTravelPace() != null && !constraints.getTravelPace().isBlank()) {
            return constraints.getTravelPace();
        }
        if (preference != null && preference.getPacePreference() != null && !preference.getPacePreference().isBlank()) {
            return preference.getPacePreference();
        }
        return "normal";
    }

    private String resolveMeal(TravelConstraints constraints) {
        if (constraints.getFoodPreference() != null && !constraints.getFoodPreference().isBlank()) {
            return "Choose " + constraints.getFoodPreference() + " and keep one flexible meal slot.";
        }
        return "Choose local food near the main route and keep lunch close to the second stop.";
    }

    private String resolveTransportMode(TravelConstraints constraints) {
        if (constraints.getTransportPreference() == null || constraints.getTransportPreference().isEmpty()) {
            return "public transit or taxi";
        }
        return String.join("/", constraints.getTransportPreference());
    }

    private String themeFor(TravelConstraints constraints) {
        if (constraints.getAttractionPreference() == null || constraints.getAttractionPreference().isEmpty()) {
            return "city highlights";
        }
        return String.join(",", constraints.getAttractionPreference());
    }
}
