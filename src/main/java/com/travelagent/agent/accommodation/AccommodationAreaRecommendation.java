package com.travelagent.agent.accommodation;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class AccommodationAreaRecommendation {

    private String areaName;
    private int commuteScore;
    private int convenienceScore;
    private int budgetScore;
    private int safetyScore;
    private int peopleFitScore;
    private int totalScore;
    private String suitableFor;
    private List<String> reasons = new ArrayList<>();
}
