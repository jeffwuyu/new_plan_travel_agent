package com.travelagent.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class AgentFeedbackRequest {

    private String message;
    private String targetVariantType;
}
