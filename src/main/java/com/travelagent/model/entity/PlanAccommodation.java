package com.travelagent.model.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
public class PlanAccommodation {

    private Long id;
    private Long planId;
    private Integer nightNumber;
    private LocalDate checkInDate;
    private LocalDate checkOutDate;
    private String provider;
    private String providerHotelId;
    private String name;
    private String type;
    private String address;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private BigDecimal rating;
    private Integer reviewCount;
    private BigDecimal pricePerNightYuan;
    private String currency;
    private Integer distanceMeters;
    private BigDecimal score;
    private String reason;
    private LocalDateTime priceFetchedAt;
    private Boolean priceStale;
    private Integer priceAgeHours;
    private LocalDateTime createdAt;
}
