package com.travelagent.model.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class RewindTaskRequest {

    @NotNull(message = "target step index is required")
    @Min(value = 0, message = "target step index must be >= 0")
    private Integer targetStepIndex;
}
