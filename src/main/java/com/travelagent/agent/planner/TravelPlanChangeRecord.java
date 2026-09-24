package com.travelagent.agent.planner;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class TravelPlanChangeRecord {

    private int fromVersion;
    private int toVersion;
    private String reason;
    private List<String> changedFields = new ArrayList<>();
    private Instant changedAt = Instant.now();

    public TravelPlanChangeRecord(int fromVersion, int toVersion, String reason, List<String> changedFields) {
        this.fromVersion = fromVersion;
        this.toVersion = toVersion;
        this.reason = reason;
        this.changedFields = changedFields == null ? new ArrayList<>() : new ArrayList<>(changedFields);
        this.changedAt = Instant.now();
    }
}
