package com.travelagent.agent.scenic;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ScenicRoutePoint {

    private String name;
    private int stayMinutes;
    private String walkingHint;
}
