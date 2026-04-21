package com.travelagent.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class CreateTaskRequest {

    @NotBlank(message = "destination region is required")
    @Size(max = 128, message = "destination region is too long")
    private String region;

    @NotBlank(message = "user intent is required")
    @Size(max = 500, message = "user intent is too long")
    private String userIntent;

    @NotBlank(message = "current location query is required")
    @Size(max = 128, message = "current location query is too long")
    private String currentLocationQuery;

    @Min(value = 1, message = "trip days must be at least 1")
    @Max(value = 14, message = "trip days must be at most 14")
    private int totalDays = 1;

    @Min(value = 1, message = "attractions per day must be at least 1")
    @Max(value = 6, message = "attractions per day must be at most 6")
    private int attractionsPerDay = 3;

    private List<String> preferenceKeywords;

    private String travelMode = "driving";
}
