package com.travelagent.agent.context;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PlanningConfig {

    public static final LocalTime DEFAULT_FULL_DAY_START = LocalTime.of(7, 0);
    public static final LocalTime DEFAULT_FULL_DAY_END = LocalTime.of(21, 0);

    private int totalDays = 1;
    private int attractionsPerDay = 3;
    private int dynamicTargetSteps = 3;
    private List<String> preferenceKeywords;
    private String travelMode = "driving";
    private String startLocationQuery;
    private String endLocationQuery;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalTime fullDayStartTime;
    private LocalTime fullDayEndTime;
    private int defaultVisitDurationMin = 120;
    private int destinationBufferMin = 30;
    private int minContinueBudgetMin = 90;

    public PlanningConfig(int totalDays, int attractionsPerDay, List<String> preferenceKeywords, String travelMode) {
        this.totalDays = totalDays;
        this.attractionsPerDay = attractionsPerDay;
        this.dynamicTargetSteps = Math.max(1, totalDays * attractionsPerDay);
        this.preferenceKeywords = preferenceKeywords;
        this.travelMode = travelMode;
    }

    public int totalSteps() {
        return dynamicTargetSteps > 0 ? dynamicTargetSteps : totalDays * attractionsPerDay;
    }

    public LocalTime resolveFullDayStartTime() {
        return fullDayStartTime != null ? fullDayStartTime : DEFAULT_FULL_DAY_START;
    }

    public LocalTime resolveFullDayEndTime() {
        return fullDayEndTime != null ? fullDayEndTime : DEFAULT_FULL_DAY_END;
    }

    public boolean hasCustomFullDayWindow() {
        return fullDayStartTime != null && fullDayEndTime != null;
    }
}
