package com.travelagent.agent.scenic;

import com.travelagent.agent.requirements.TravelConstraints;
import org.springframework.stereotype.Service;

@Service
public class ScenicRoutePlanner {

    public ScenicRoute plan(String attractionName, TravelConstraints constraints, String routeType) {
        if (attractionName == null || attractionName.isBlank()) {
            throw new IllegalArgumentException("attractionName is required");
        }
        String resolvedType = resolveType(constraints, routeType);
        ScenicRoute route = new ScenicRoute();
        route.setAttractionName(attractionName);
        route.setRouteType(resolvedType);
        route.setEntranceSuggestion("Use the main entrance unless realtime crowd data recommends a secondary gate.");
        int baseStay = "intensive".equals(resolvedType) ? 35 : "relaxed".equals(resolvedType) ? 25 : 30;
        route.getPoints().add(new ScenicRoutePoint("Entrance orientation point", 10, "Confirm exit and restroom locations first."));
        route.getPoints().add(new ScenicRoutePoint("Core highlight zone", baseStay, "Follow the signed main walking route."));
        route.getPoints().add(new ScenicRoutePoint("Photo viewpoint", 20, "Leave space for crowd control and short rest."));
        if ("intensive".equals(resolvedType)) {
            route.getPoints().add(new ScenicRoutePoint("Extended niche zone", 25, "Add only when queue and weather are acceptable."));
        }
        route.getPhotoSpots().add("Main facade or skyline-facing viewpoint");
        route.getPhotoSpots().add("Less crowded side angle near the exit path");
        route.getCrowdAvoidanceTips().add("Avoid the first hour after opening on weekends and holidays.");
        route.getCrowdAvoidanceTips().add("Reverse the internal order if the core highlight zone is already crowded.");
        route.setEstimatedWalkingMinutes("relaxed".equals(resolvedType) ? 60 : "intensive".equals(resolvedType) ? 110 : 80);
        return route;
    }

    private String resolveType(TravelConstraints constraints, String requested) {
        if (requested != null && !requested.isBlank()) {
            return requested;
        }
        if (constraints != null && constraints.getSpecialGroups() != null) {
            String groups = String.join(",", constraints.getSpecialGroups());
            if (groups.contains("老人") || groups.contains("儿童") || groups.contains("行动不便")) {
                return "relaxed";
            }
        }
        if (constraints != null && constraints.getTravelPace() != null) {
            return constraints.getTravelPace();
        }
        return "normal";
    }
}
