package com.travelagent.service.accommodation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@ConditionalOnProperty(prefix = "accommodation", name = "provider", havingValue = "mock")
public class MockAccommodationProvider implements AccommodationProvider {

    @Override
    public List<AccommodationQuote> searchAvailability(AccommodationSearchRequest request) {
        List<AccommodationQuote> quotes = new ArrayList<>();
        quotes.add(quote("mock-1", request.getCityName() + " 城心酒店", "hotel", "城市中心路 1 号",
                request.getAnchorLat(), request.getAnchorLng(), "4.8", 880, "428"));
        quotes.add(quote("mock-2", request.getCityName() + " 旅馆", "inn", "老街 18 号",
                offset(request.getAnchorLat(), 0.01), offset(request.getAnchorLng(), 0.01), "4.2", 160, "268"));
        quotes.add(quote("mock-3", request.getCityName() + " 民宿", "homestay", "巷口 6 号",
                offset(request.getAnchorLat(), 0.02), offset(request.getAnchorLng(), 0.02), "4.6", 92, "358"));
        return quotes;
    }

    private AccommodationQuote quote(String id, String name, String type, String address,
                                     Double lat, Double lng, String rating, int reviews, String price) {
        AccommodationQuote quote = new AccommodationQuote();
        quote.setProvider("mock");
        quote.setProviderHotelId(id);
        quote.setName(name);
        quote.setType(type);
        quote.setAddress(address);
        quote.setLatitude(lat);
        quote.setLongitude(lng);
        quote.setRating(new BigDecimal(rating));
        quote.setReviewCount(reviews);
        quote.setPricePerNightYuan(new BigDecimal(price));
        quote.setCurrency("CNY");
        quote.setAvailable(true);
        quote.setCancellationPolicy("mock refundable policy");
        quote.setPriceFetchedAt(LocalDateTime.now());
        return quote;
    }

    private Double offset(Double value, double delta) {
        return value == null ? null : value + delta;
    }
}
