package com.travelagent.agent.context;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CompletedStep {

    private int stepIndex;
    private int dayNumber;
    private String attractionName;
    private Double lat;
    private Double lng;
    private Integer trafficTimeFromPrevMin;
    private Integer estimatedVisitDurationMin;
    private Integer travelTimeToDestinationMin;
    private LocalDateTime plannedStartTime;
    private LocalDateTime plannedEndTime;
    private Map<String, Object> toolCallResults;

    public CompletedStep(int stepIndex, int dayNumber, String attractionName,
                         Double lat, Double lng, Map<String, Object> toolCallResults) {
        this.stepIndex = stepIndex;
        this.dayNumber = dayNumber;
        this.attractionName = attractionName;
        this.lat = lat;
        this.lng = lng;
        this.toolCallResults = toolCallResults;
    }
}
