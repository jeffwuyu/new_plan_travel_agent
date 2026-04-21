package com.travelagent.model.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class NearbyPoiRecommendationResponse {

    private String queryType;

    private Integer totalCandidates;

    private List<RecommendedPoiItem> recommendations = new ArrayList<>();
}
