package com.travelagent.agent.scoring;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ItineraryScores {

    private int intensityScore;
    private int routeEfficiencyScore;
    private int accommodationMatchScore;
    private int weatherFitScore;
    private int peopleFitScore;
}
