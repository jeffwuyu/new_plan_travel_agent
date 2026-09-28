package com.travelagent.service.accommodation;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
public class AccommodationQuote {

    private String provider;
    private String providerHotelId;
    private String name;
    private String type;
    private String address;
    private Double latitude;
    private Double longitude;
    private BigDecimal rating;
    private Integer reviewCount;
    private BigDecimal pricePerNightYuan;
    private String currency = "CNY";
    private Boolean available = true;
    private String cancellationPolicy;
    private LocalDateTime priceFetchedAt;
}
