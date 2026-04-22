package com.travelagent.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Data
public class CreateTaskRequest {

    @NotBlank(message = "destination region is required")
    @Size(max = 128, message = "destination region is too long")
    private String region;

    @NotBlank(message = "user intent is required")
    @Size(max = 500, message = "user intent is too long")
    private String userIntent;

    @NotBlank(message = "start location query is required")
    @Size(max = 128, message = "start location query is too long")
    private String startLocationQuery;

    @NotBlank(message = "end location query is required")
    @Size(max = 128, message = "end location query is too long")
    private String endLocationQuery;

    @NotNull(message = "start time is required")
    private LocalDateTime startTime;

    @NotNull(message = "end time is required")
    private LocalDateTime endTime;

    private LocalTime fullDayStartTime;

    private LocalTime fullDayEndTime;

    @Min(value = 1, message = "attractions per day must be at least 1")
    @Max(value = 12, message = "attractions per day must be at most 12")
    private int attractionsPerDay = 3;

    private List<String> preferenceKeywords;

    private String travelMode = "driving";
}
