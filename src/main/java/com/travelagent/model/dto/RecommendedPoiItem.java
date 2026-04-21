package com.travelagent.model.dto;

import lombok.Data;

import java.util.List;

@Data
public class RecommendedPoiItem {

    private String poiId;

    private String amapPoiId;

    private String name;

    private String region;

    private String category;

    private Double latitude;

    private Double longitude;

    private Double score;

    private List<String> explanations;

    private RecommendationFeatureBreakdown features;
}
