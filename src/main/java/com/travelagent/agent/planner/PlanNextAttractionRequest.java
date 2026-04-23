package com.travelagent.agent.planner;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class PlanNextAttractionRequest {

    private String region;
    private String currentPositionName;
    private Double currentLat;
    private Double currentLng;
    private String currentAdcode;
    private List<String> visitedPoiNames = new ArrayList<>();
    private Integer remainingTimeBudgetMin;
    private String travelMode;
    private String currentTime;
    private String selectedBranchType;
    private String nodePreferencePrompt;
    private Map<String, Object> weatherContext = new LinkedHashMap<>();
}
