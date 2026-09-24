package com.travelagent.agent.validation;

import com.travelagent.agent.accommodation.AccommodationAnalysisResult;
import com.travelagent.agent.itinerary.DailyItinerary;
import com.travelagent.agent.itinerary.GeneratedItinerary;
import com.travelagent.agent.requirements.TravelConstraints;
import com.travelagent.agent.scoring.BudgetBreakdown;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.Map;

@Service
public class ItineraryValidator {

    public ValidatorResult validate(GeneratedItinerary itinerary,
                                    TravelConstraints constraints,
                                    BudgetBreakdown budget) {
        return validate(itinerary, constraints, budget, null, Map.of());
    }

    public ValidatorResult validate(GeneratedItinerary itinerary,
                                    TravelConstraints constraints,
                                    BudgetBreakdown budget,
                                    AccommodationAnalysisResult accommodation,
                                    Map<String, Object> realtimeHints) {
        ValidatorResult result = new ValidatorResult();
        if (itinerary == null) {
            result.addIssue("missing_itinerary", "Itinerary is missing.", "Generate a day-by-day itinerary first.");
            return result;
        }
        for (DailyItinerary day : itinerary.getDailyPlans()) {
            int maxStops = "relaxed".equals(itinerary.getPace()) ? 3 : "intensive".equals(itinerary.getPace()) ? 5 : 4;
            if (day.getStops().size() > maxStops) {
                result.addIssue("too_many_stops", "Day " + day.getDayNumber() + " has too many attractions.",
                        "Reduce stops or split them into another day.");
            }
            if (day.getEstimatedWalkingKm() > walkingLimit(constraints)) {
                result.addIssue("walking_too_long", "Day " + day.getDayNumber() + " walking distance is high.",
                        "Use taxi/public transit transfers or reduce internal scenic routes.");
            }
            day.getTransportLegs().stream()
                    .filter(leg -> leg.getEstimatedMinutes() > 70)
                    .forEach(leg -> result.addIssue("route_too_far", "Transport from " + leg.getFrom() + " to " + leg.getTo() + " exceeds 70 minutes.",
                            "Move these attractions to nearby route clusters."));
            if (day.getMealSuggestion() == null || day.getMealSuggestion().isBlank()) {
                result.addIssue("meal_missing", "Day " + day.getDayNumber() + " has no meal suggestion.",
                        "Add lunch and dinner slots near planned stops.");
            }
            day.getStops().stream()
                    .filter(stop -> stop.getStartTime() == null || stop.getEndTime() == null)
                    .forEach(stop -> result.addIssue("opening_time_missing", stop.getName() + " has no planned time window.",
                            "Add a visit window and compare it with realtime opening hours."));
            if (day.getDayNumber() == itinerary.getDays() && endsTooLate(day)) {
                result.addIssue("return_time_risk", "Last day ends too late for a safe return buffer.",
                        "Move the final stop earlier or shorten the last day.");
            }
            if (!matchesTransportPreference(day, constraints)) {
                result.addIssue("transport_preference_mismatch", "Transport mode does not match user preference.",
                        "Switch route legs to the preferred transport mode where possible.");
            }
        }
        if (budget != null && constraints != null && constraints.getBudgetYuan() != null
                && budget.getTotalBudgetYuan().compareTo(constraints.getBudgetYuan()) > 0) {
            result.addIssue("budget_exceeded", "Estimated budget exceeds user budget.",
                    "Compress accommodation, transport or paid attractions.");
        }
        if (constraints != null && constraints.getBookingRequired() != null && constraints.getBookingRequired()
                && itinerary.getAlternatives().isEmpty()) {
            result.addIssue("booking_fallback_missing", "Booking-sensitive plan has no fallback.",
                    "Add alternative attraction or time-slot options.");
        }
        if (realtimeHints != null && Boolean.TRUE.equals(realtimeHints.get("outdoorRisk"))
                && itinerary.getDailyPlans().stream().flatMap(day -> day.getStops().stream()).anyMatch(stop -> !stop.isIndoor())) {
            result.addIssue("weather_fit_risk", "Outdoor stops remain while realtime weather risk is high.",
                    "Move outdoor stops to indoor alternatives or adjust the affected day.");
        }
        if (accommodation != null && accommodation.getRecommendedAreas().isEmpty()) {
            result.addIssue("accommodation_match_missing", "No recommended accommodation area is available.",
                    "Run accommodation area analysis before finalizing the itinerary.");
        }
        result.setValid(result.getIssues().isEmpty());
        itinerary.setValidationStatus(result.isValid() ? "passed" : "needs_replan");
        return result;
    }

    private int walkingLimit(TravelConstraints constraints) {
        if (constraints == null || constraints.getSpecialGroups() == null) {
            return 10;
        }
        String groups = String.join(",", constraints.getSpecialGroups());
        return groups.contains("老人") || groups.contains("儿童") || groups.contains("行动不便") ? 5 : 10;
    }

    private boolean endsTooLate(DailyItinerary day) {
        return day.getStops().stream()
                .map(stop -> parseTime(stop.getEndTime()))
                .filter(time -> time != null)
                .max(LocalTime::compareTo)
                .map(time -> time.isAfter(LocalTime.of(20, 0)))
                .orElse(false);
    }

    private LocalTime parseTime(String value) {
        try {
            return value == null || value.isBlank() ? null : LocalTime.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean matchesTransportPreference(DailyItinerary day, TravelConstraints constraints) {
        if (constraints == null || constraints.getTransportPreference() == null || constraints.getTransportPreference().isEmpty()) {
            return true;
        }
        if (day.getTransportLegs() == null || day.getTransportLegs().isEmpty()) {
            return true;
        }
        String preference = String.join(",", constraints.getTransportPreference());
        return day.getTransportLegs().stream().allMatch(leg -> {
            String mode = leg.getMode() == null ? "" : leg.getMode();
            return preference.contains(mode) || mode.contains(preference)
                    || (preference.contains("公共交通") && (mode.contains("公共交通") || mode.contains("公交") || mode.contains("地铁")))
                    || (preference.contains("打车") && mode.contains("打车"));
        });
    }
}
