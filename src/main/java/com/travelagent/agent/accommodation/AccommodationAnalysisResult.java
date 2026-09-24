package com.travelagent.agent.accommodation;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class AccommodationAnalysisResult {

    private String destination;
    private List<AccommodationAreaRecommendation> recommendedAreas = new ArrayList<>();
    private List<AccommodationAreaRecommendation> notRecommendedAreas = new ArrayList<>();
    private String source = "accommodation:rules";
}
