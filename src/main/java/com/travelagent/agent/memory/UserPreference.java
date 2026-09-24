package com.travelagent.agent.memory;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class UserPreference {

    private Long userId;
    private String pacePreference;
    private String hotelPreference;
    private String foodPreference;
    private List<String> avoidConstraints = new ArrayList<>();
    private List<String> historicalDestinations = new ArrayList<>();
    private BigDecimal typicalBudgetYuan;
    private List<String> commonTransportModes = new ArrayList<>();
    private List<String> attractionInterests = new ArrayList<>();
    private String sourceSummary;
    private Instant updatedAt = Instant.now();
}
