package com.travelagent.agent.itinerary;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class GeneratedItinerary {

    private String destination;
    private int days;
    private String pace;
    private List<DailyItinerary> dailyPlans = new ArrayList<>();
    private List<String> keyReasons = new ArrayList<>();
    private List<String> alternatives = new ArrayList<>();
    private BigDecimal estimatedTotalBudgetYuan;
    private String validationStatus = "unchecked";
}
