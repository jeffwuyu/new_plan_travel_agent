package com.travelagent.service.accommodation;

import com.travelagent.model.entity.PlanAccommodation;

import java.util.List;

public record AccommodationRecommendationResult(
        String status,
        String failureReason,
        List<PlanAccommodation> accommodations
) {

    public static AccommodationRecommendationResult available(List<PlanAccommodation> accommodations) {
        return new AccommodationRecommendationResult("available", null, accommodations);
    }

    public static AccommodationRecommendationResult unavailable(String reason) {
        return new AccommodationRecommendationResult("unavailable", reason, List.of());
    }

    public static AccommodationRecommendationResult disabled(String reason) {
        return new AccommodationRecommendationResult("disabled", reason, List.of());
    }

    public static AccommodationRecommendationResult notConfigured(String reason) {
        return new AccommodationRecommendationResult("not_configured", reason, List.of());
    }
}
