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

    /**
     * 初始化CompletedStep 实例。
     * @param stepIndex s te pI nd ex 参数
     * @param dayNumber d ay Nu mb er 参数
     * @param attractionName 景点名称
     * @param lat 纬度
     * @param lng 经度
     * @param toolCallResults t oo lC al lR es ul ts 参数
     */
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
