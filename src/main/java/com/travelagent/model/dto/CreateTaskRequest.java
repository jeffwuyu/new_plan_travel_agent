package com.travelagent.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.math.BigDecimal;
import java.util.List;

@Data
public class CreateTaskRequest {

    @NotBlank(message = "destination region is required")
    @Size(max = 128, message = "destination region is too long")
    private String region;

    @Size(max = 64, message = "province name is too long")
    private String provinceName;

    @Size(max = 64, message = "city name is too long")
    private String cityName;

    @Size(max = 64, message = "district name is too long")
    private String districtName;

    @Size(max = 16, message = "adcode is too long")
    private String adcode;

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

    private List<String> preferenceKeywords;

    private String travelMode = "driving";

    @NotNull(message = "total budget is required")
    private BigDecimal totalBudgetYuan;

    @NotNull(message = "lodging budget per night is required")
    private BigDecimal lodgingBudgetPerNightYuan;

    private List<String> accommodationTypes;

    private Integer adultCount = 2;

    private Integer roomCount = 1;
}
