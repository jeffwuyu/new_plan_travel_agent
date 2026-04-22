package com.travelagent.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class LocationCandidateItem {

    private String candidateId;
    private String candidateType;
    private String branchType;
    private String name;
    private String targetAttractionName;
    private String region;
    private String district;
    private String category;
    private String address;
    private Double latitude;
    private Double longitude;
    private String adcode;
    private String source;
    private Double score;
    private String routeSummary;
    private Integer visitDurationMin;
    private Integer estimatedTotalDurationMin;
    private String weatherSuitability;
    private List<String> explanations = new ArrayList<>();
    private List<String> highlights = new ArrayList<>();
    private List<String> routeStops = new ArrayList<>();
}
