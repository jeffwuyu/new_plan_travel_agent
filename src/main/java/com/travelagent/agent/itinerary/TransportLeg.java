package com.travelagent.agent.itinerary;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TransportLeg {

    private String from;
    private String to;
    private String mode;
    private int estimatedMinutes;
    private String source;
}
