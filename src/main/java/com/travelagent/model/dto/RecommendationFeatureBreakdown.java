package com.travelagent.model.dto;

import lombok.Data;

@Data
public class RecommendationFeatureBreakdown {

    private Double distanceKm;

    private Integer travelTimeMin;

    private Double geoScore;

    private Double timeScore;

    private Double styleSimilarity;

    private Double styleScore;

    private Double routeDeltaKm;

    private Double routeScore;

    private Double constraintScore;

    private Double weatherScore;

    private Boolean weatherFriendly;

    private Boolean currentlyOpen;
}
