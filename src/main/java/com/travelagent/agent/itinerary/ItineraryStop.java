package com.travelagent.agent.itinerary;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ItineraryStop {

    private String name;
    private String theme;
    private String startTime;
    private String endTime;
    private int suggestedStayMinutes;
    private boolean indoor;
    private String planningReason;
}
