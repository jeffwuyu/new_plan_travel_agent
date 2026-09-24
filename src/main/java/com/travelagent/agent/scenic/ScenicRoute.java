package com.travelagent.agent.scenic;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class ScenicRoute {

    private String attractionName;
    private String routeType;
    private String entranceSuggestion;
    private List<ScenicRoutePoint> points = new ArrayList<>();
    private List<String> photoSpots = new ArrayList<>();
    private List<String> crowdAvoidanceTips = new ArrayList<>();
    private int estimatedWalkingMinutes;
}
