package com.travelagent.agent.itinerary;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class DailyItinerary {

    private int dayNumber;
    private String title;
    private List<ItineraryStop> stops = new ArrayList<>();
    private List<TransportLeg> transportLegs = new ArrayList<>();
    private String mealSuggestion;
    private String note;
    private BigDecimal estimatedBudgetYuan;
    private int estimatedWalkingKm;
}
