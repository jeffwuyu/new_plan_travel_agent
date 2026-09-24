package com.travelagent.model.dto;

import com.travelagent.agent.requirements.TravelConstraints;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class AgentSessionRequest {

    private String message;
    private TravelConstraints constraints;
}
