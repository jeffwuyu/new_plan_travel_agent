package com.travelagent.service.accommodation;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
public class AccommodationSearchRequest {

    private String provinceName;
    private String cityName;
    private String districtName;
    private String adcode;
    private String region;
    private LocalDate checkInDate;
    private LocalDate checkOutDate;
    private Integer adultCount;
    private Integer roomCount;
    private List<String> accommodationTypes;
    private BigDecimal lodgingBudgetPerNightYuan;
    private Double anchorLat;
    private Double anchorLng;
}
