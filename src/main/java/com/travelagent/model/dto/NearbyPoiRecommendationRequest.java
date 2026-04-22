package com.travelagent.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.util.List;

@Data
public class NearbyPoiRecommendationRequest {

    private String region;

    private Double currentLat;

    private Double currentLng;

    private String currentPoiId;

    private String currentPoiName;

    private List<String> selectedPoiIds;

    private List<String> selectedPoiNames;

    private List<RoutePoint> routePoints;

    private List<String> preferredTags;

    private List<String> avoidTags;

    private List<String> companions;

    private Integer budgetLevel;

    private String pace;

    private String travelMode = "driving";

    private String currentTime;

    private String dayOfWeek;

    private String weatherCondition;

    private Integer temperature;

    private Boolean indoorPreferred;

    private Boolean shortWalkPreferred;

    private Boolean avoidRain;

    private Boolean avoidWind;

    private String weatherSummary;

    /**
     * nearby | similar_style | itinerary_fill
     */
    private String queryType = "nearby";

    @Min(1)
    @Max(20)
    private Integer topK = 5;
}
