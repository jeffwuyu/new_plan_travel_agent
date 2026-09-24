package com.travelagent.model.dto;

import com.travelagent.agent.requirements.TravelConstraints;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class TravelConstraintParseRequest {

    @NotBlank(message = "message is required")
    @Size(max = 1000, message = "message is too long")
    private String message;

    private TravelConstraints existingConstraints;
}

