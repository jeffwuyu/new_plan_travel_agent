package com.travelagent.agent.itinerary;

import com.travelagent.agent.memory.UserPreference;
import com.travelagent.agent.requirements.TravelConstraints;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@NoArgsConstructor
public class ItineraryGenerationInput {

    private TravelConstraints constraints;
    private UserPreference preference;
    private Map<String, Object> toolResults = new LinkedHashMap<>();
    private Map<String, Object> ragContext = new LinkedHashMap<>();
    private Map<String, Object> validatorConstraints = new LinkedHashMap<>();
}
