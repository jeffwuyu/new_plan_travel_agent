package com.travelagent.agent.context;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DailyTimeWindow {

    private int dayNumber;
    private LocalDateTime startTime;
    private LocalDateTime endTime;

    public int availableMinutes() {
        if (startTime == null || endTime == null || !endTime.isAfter(startTime)) {
            return 0;
        }
        return (int) Duration.between(startTime, endTime).toMinutes();
    }
}
