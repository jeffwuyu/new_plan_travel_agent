package com.travelagent.service.accommodation;

import com.travelagent.agent.context.CompletedStep;
import com.travelagent.agent.context.PlanningConfig;
import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.model.entity.PlanAccommodation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

@Service
public class AccommodationRecommendationService {

    private static final int MAX_RECOMMENDATIONS_PER_NIGHT = 3;

    private final AccommodationProvider accommodationProvider;
    private final String providerName;

    public AccommodationRecommendationService(AccommodationProvider accommodationProvider,
                                              @Value("${accommodation.provider:mock}") String providerName) {
        this.accommodationProvider = accommodationProvider;
        this.providerName = providerName == null ? "mock" : providerName.trim().toLowerCase();
    }

    public AccommodationRecommendationResult recommend(Long planId, TaskCheckpoint checkpoint) {
        if ("disabled".equals(providerName) || "none".equals(providerName) || "off".equals(providerName)) {
            return AccommodationRecommendationResult.disabled("Accommodation recommendation is disabled by configuration");
        }
        if (checkpoint == null || checkpoint.getPlanningConfig() == null) {
            return AccommodationRecommendationResult.unavailable("planning config is missing");
        }
        PlanningConfig config = checkpoint.getPlanningConfig();
        int nightCount = Math.max(0, config.getTotalDays() - 1);
        if (nightCount == 0) {
            return AccommodationRecommendationResult.unavailable("single-day trip does not require accommodation");
        }
        if (config.getLodgingBudgetPerNightYuan() == null
                || config.getLodgingBudgetPerNightYuan().compareTo(BigDecimal.ZERO) <= 0) {
            return AccommodationRecommendationResult.unavailable("lodging budget per night is missing");
        }

        try {
            List<PlanAccommodation> accommodations = new java.util.ArrayList<>();
            for (int night = 1; night <= nightCount; night++) {
                final int nightNumber = night;
                Anchor anchor = resolveAnchor(checkpoint, night);
                AccommodationSearchRequest request = buildSearchRequest(checkpoint, config, night, anchor);
                List<AccommodationQuote> quotes = accommodationProvider.searchAvailability(request);
                List<PlanAccommodation> nightly = quotes.stream()
                        .filter(quote -> quote.getPricePerNightYuan() != null)
                        .filter(quote -> quote.getPricePerNightYuan().compareTo(config.getLodgingBudgetPerNightYuan()) <= 0)
                        .filter(quote -> matchesType(quote, config.getAccommodationTypes()))
                        .map(quote -> toAccommodation(planId, nightNumber, request, quote, anchor, config.getLodgingBudgetPerNightYuan()))
                        .sorted(Comparator.comparing(PlanAccommodation::getScore,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                        .limit(MAX_RECOMMENDATIONS_PER_NIGHT)
                        .toList();
                accommodations.addAll(nightly);
            }
            if (accommodations.isEmpty()) {
                return AccommodationRecommendationResult.unavailable("OTA returned no available accommodation within budget");
            }
            return AccommodationRecommendationResult.available(accommodations);
        } catch (AccommodationAvailabilityException e) {
            if ("OTA_NOT_CONFIGURED".equals(e.getCode())) {
                return AccommodationRecommendationResult.notConfigured(e.getCode() + ": " + e.getMessage());
            }
            return AccommodationRecommendationResult.unavailable(e.getCode() + ": " + e.getMessage());
        } catch (Exception e) {
            return AccommodationRecommendationResult.unavailable("OTA_UNKNOWN_ERROR: " + e.getMessage());
        }
    }

    private AccommodationSearchRequest buildSearchRequest(TaskCheckpoint checkpoint,
                                                          PlanningConfig config,
                                                          int night,
                                                          Anchor anchor) {
        LocalDate checkIn = config.getStartTime().toLocalDate().plusDays(night - 1L);
        AccommodationSearchRequest request = new AccommodationSearchRequest();
        request.setProvinceName(config.getProvinceName());
        request.setCityName(firstNonBlank(config.getCityName(), checkpoint.getCityName(), checkpoint.getRegion()));
        request.setDistrictName(config.getDistrictName());
        request.setAdcode(config.getAdcode());
        request.setRegion(checkpoint.getRegion());
        request.setCheckInDate(checkIn);
        request.setCheckOutDate(checkIn.plusDays(1));
        request.setAdultCount(config.getAdultCount() == null ? 2 : config.getAdultCount());
        request.setRoomCount(config.getRoomCount() == null ? 1 : config.getRoomCount());
        request.setAccommodationTypes(config.getAccommodationTypes());
        request.setLodgingBudgetPerNightYuan(config.getLodgingBudgetPerNightYuan());
        request.setAnchorLat(anchor.lat());
        request.setAnchorLng(anchor.lng());
        return request;
    }

    private PlanAccommodation toAccommodation(Long planId,
                                              int night,
                                              AccommodationSearchRequest request,
                                              AccommodationQuote quote,
                                              Anchor anchor,
                                              BigDecimal budget) {
        Integer distanceMeters = distanceMeters(anchor.lat(), anchor.lng(), quote.getLatitude(), quote.getLongitude());
        BigDecimal score = score(quote, distanceMeters, budget);
        PlanAccommodation accommodation = new PlanAccommodation();
        accommodation.setPlanId(planId);
        accommodation.setNightNumber(night);
        accommodation.setCheckInDate(request.getCheckInDate());
        accommodation.setCheckOutDate(request.getCheckOutDate());
        accommodation.setProvider(quote.getProvider());
        accommodation.setProviderHotelId(quote.getProviderHotelId());
        accommodation.setName(quote.getName());
        accommodation.setType(quote.getType());
        accommodation.setAddress(quote.getAddress());
        accommodation.setLatitude(quote.getLatitude() == null ? null : BigDecimal.valueOf(quote.getLatitude()));
        accommodation.setLongitude(quote.getLongitude() == null ? null : BigDecimal.valueOf(quote.getLongitude()));
        accommodation.setRating(quote.getRating());
        accommodation.setReviewCount(quote.getReviewCount());
        accommodation.setPricePerNightYuan(quote.getPricePerNightYuan());
        accommodation.setCurrency(firstNonBlank(quote.getCurrency(), "CNY"));
        accommodation.setDistanceMeters(distanceMeters);
        accommodation.setScore(score);
        accommodation.setReason(buildReason(night, quote, distanceMeters, budget));
        accommodation.setPriceFetchedAt(quote.getPriceFetchedAt());
        return accommodation;
    }

    private BigDecimal score(AccommodationQuote quote, Integer distanceMeters, BigDecimal budget) {
        double ratingScore = quote.getRating() == null ? 0.45d : Math.min(1d, quote.getRating().doubleValue() / 5d);
        double reviewScore = quote.getReviewCount() == null ? 0.2d : Math.min(1d, Math.log10(quote.getReviewCount() + 1) / 3d);
        double distanceScore = distanceMeters == null ? 0.3d : Math.max(0d, 1d - Math.min(distanceMeters, 10000) / 10000d);
        double priceScore = 1d - Math.min(1d, quote.getPricePerNightYuan().doubleValue() / Math.max(1d, budget.doubleValue()));
        double value = ratingScore * 0.35d + reviewScore * 0.15d + distanceScore * 0.3d + priceScore * 0.2d;
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
    }

    private String buildReason(int night, AccommodationQuote quote, Integer distanceMeters, BigDecimal budget) {
        String distanceText = distanceMeters == null ? "距离信息暂缺" : "距第 " + night + " 天路线约 " + formatKm(distanceMeters) + "km";
        String ratingText = quote.getRating() == null ? "评分暂缺" : "评分 " + quote.getRating();
        return String.format("%s，%s，每晚 %s 元，低于预算 %s 元；价格来自 %s OTA，获取时间 %s。",
                distanceText,
                ratingText,
                quote.getPricePerNightYuan(),
                budget,
                quote.getProvider(),
                quote.getPriceFetchedAt() == null ? "未知" : quote.getPriceFetchedAt());
    }

    private String formatKm(Integer meters) {
        return BigDecimal.valueOf(meters / 1000.0d).setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    private boolean matchesType(AccommodationQuote quote, List<String> requestedTypes) {
        if (requestedTypes == null || requestedTypes.isEmpty() || quote.getType() == null) {
            return true;
        }
        String type = quote.getType().toLowerCase();
        return requestedTypes.stream().anyMatch(requested -> {
            String normalized = requested.toLowerCase();
            return type.contains(normalized)
                    || ("hotel".equals(normalized) && (type.contains("酒店") || type.contains("宾馆")))
                    || ("inn".equals(normalized) && (type.contains("旅馆") || type.contains("客栈")))
                    || ("homestay".equals(normalized) && type.contains("民宿"));
        });
    }

    private Anchor resolveAnchor(TaskCheckpoint checkpoint, int night) {
        CompletedStep lastOfDay = null;
        CompletedStep firstNextDay = null;
        if (checkpoint.getCompletedSteps() != null) {
            for (CompletedStep step : checkpoint.getCompletedSteps()) {
                if (step.getDayNumber() == night) {
                    lastOfDay = step;
                }
                if (firstNextDay == null && step.getDayNumber() == night + 1) {
                    firstNextDay = step;
                }
            }
        }
        if (lastOfDay != null && lastOfDay.getLat() != null && lastOfDay.getLng() != null) {
            return new Anchor(lastOfDay.getLat(), lastOfDay.getLng());
        }
        if (firstNextDay != null && firstNextDay.getLat() != null && firstNextDay.getLng() != null) {
            return new Anchor(firstNextDay.getLat(), firstNextDay.getLng());
        }
        return new Anchor(null, null);
    }

    private Integer distanceMeters(Double originLat, Double originLng, Double destLat, Double destLng) {
        if (originLat == null || originLng == null || destLat == null || destLng == null) {
            return null;
        }
        double earthRadius = 6371000d;
        double dLat = Math.toRadians(destLat - originLat);
        double dLng = Math.toRadians(destLng - originLng);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(originLat)) * Math.cos(Math.toRadians(destLat))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return (int) Math.round(earthRadius * c);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private record Anchor(Double lat, Double lng) {}
}
