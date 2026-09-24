package com.travelagent.agent.trace;

import com.travelagent.agent.planner.TravelPlan;
import com.travelagent.agent.requirements.TravelConstraints;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class TraceSnapshot {

    private String traceId;
    private String userInput;
    private TravelConstraints structuredConstraints;
    private TravelPlan plan;
    private String finalOutput;
    private List<TraceEvent> events = new ArrayList<>();
    private String observabilityExtension = "OpenTelemetry/Spring Boot Actuator can consume these events later.";
}
