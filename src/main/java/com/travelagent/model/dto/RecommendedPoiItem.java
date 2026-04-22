package com.travelagent.model.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class RecommendedPoiItem {

    private String poiId;

    private String amapPoiId;

    private String name;

    private String region;

    private String district;

    private String category;

    private String address;

    private Double latitude;

    private Double longitude;

    private String source;

    private Double score;

    private String routeSummary;

    private Integer visitDurationMin;

    private List<String> explanations = new ArrayList<>();

    private List<String> highlights = new ArrayList<>();

    private RecommendationFeatureBreakdown features;
}
