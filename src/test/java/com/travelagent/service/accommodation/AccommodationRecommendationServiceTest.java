package com.travelagent.service.accommodation;

import com.travelagent.agent.context.CompletedStep;
import com.travelagent.agent.context.PlanningConfig;
import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.model.entity.PlanAccommodation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AccommodationRecommendationServiceTest {

    @Test
    void recommend_filtersOutQuotesOverBudget() {
        AccommodationRecommendationService service = new AccommodationRecommendationService(request -> List.of(
                quote("expensive", "hotel", "4.9", 2000, "900", 34.25, 108.95),
                quote("within-budget", "hotel", "4.5", 300, "420", 34.25, 108.95)
        ), "mock");

        AccommodationRecommendationResult result = service.recommend(10L, checkpoint(new BigDecimal("500")));

        assertThat(result.status()).isEqualTo("available");
        assertThat(result.accommodations()).extracting(PlanAccommodation::getProviderHotelId)
                .containsExactly("within-budget");
    }

    @Test
    void recommend_prefersHigherRatingWhenPriceAndDistanceAreComparable() {
        AccommodationRecommendationService service = new AccommodationRecommendationService(request -> List.of(
                quote("lower-rating", "hotel", "4.0", 300, "420", 34.25, 108.95),
                quote("higher-rating", "hotel", "4.8", 300, "420", 34.25, 108.95)
        ), "mock");

        AccommodationRecommendationResult result = service.recommend(10L, checkpoint(new BigDecimal("500")));

        assertThat(result.accommodations()).first()
                .extracting(PlanAccommodation::getProviderHotelId)
                .isEqualTo("higher-rating");
    }

    @Test
    void recommend_prefersCloserQuoteWhenRatingMatches() {
        AccommodationRecommendationService service = new AccommodationRecommendationService(request -> List.of(
                quote("far", "hotel", "4.6", 300, "420", 35.00, 109.50),
                quote("near", "hotel", "4.6", 300, "420", 34.2501, 108.9501)
        ), "mock");

        AccommodationRecommendationResult result = service.recommend(10L, checkpoint(new BigDecimal("500")));

        assertThat(result.accommodations()).first()
                .extracting(PlanAccommodation::getProviderHotelId)
                .isEqualTo("near");
    }

    @Test
    void recommend_returnsUnavailableWhenProviderFailsAndDoesNotInventPrices() {
        AccommodationRecommendationService service = new AccommodationRecommendationService(request -> {
            throw new AccommodationAvailabilityException("OTA_RATE_LIMITED", "limited");
        }, "ctrip");

        AccommodationRecommendationResult result = service.recommend(10L, checkpoint(new BigDecimal("500")));

        assertThat(result.status()).isEqualTo("unavailable");
        assertThat(result.accommodations()).isEmpty();
        assertThat(result.failureReason()).contains("OTA_RATE_LIMITED");
    }

    @Test
    void recommend_returnsNotConfiguredWhenCtripIsSelectedButCredentialsAreMissing() {
        AccommodationRecommendationService service = new AccommodationRecommendationService(request -> {
            throw new AccommodationAvailabilityException("OTA_NOT_CONFIGURED", "missing ctrip credentials");
        }, "ctrip");

        AccommodationRecommendationResult result = service.recommend(10L, checkpoint(new BigDecimal("500")));

        assertThat(result.status()).isEqualTo("not_configured");
        assertThat(result.failureReason()).contains("OTA_NOT_CONFIGURED");
        assertThat(result.accommodations()).isEmpty();
    }

    @Test
    void recommend_returnsDisabledWhenProviderIsDisabled() {
        AccommodationRecommendationService service = new AccommodationRecommendationService(request -> {
            throw new AssertionError("provider should not be called when accommodation is disabled");
        }, "disabled");

        AccommodationRecommendationResult result = service.recommend(10L, checkpoint(new BigDecimal("500")));

        assertThat(result.status()).isEqualTo("disabled");
        assertThat(result.failureReason()).contains("disabled");
        assertThat(result.accommodations()).isEmpty();
    }

    private TaskCheckpoint checkpoint(BigDecimal lodgingBudget) {
        PlanningConfig config = new PlanningConfig();
        config.setTotalDays(2);
        config.setStartTime(LocalDateTime.of(2026, 7, 1, 10, 0));
        config.setEndTime(LocalDateTime.of(2026, 7, 2, 18, 0));
        config.setCityName("西安市");
        config.setAccommodationTypes(List.of("hotel", "inn", "homestay"));
        config.setAdultCount(2);
        config.setRoomCount(1);
        config.setLodgingBudgetPerNightYuan(lodgingBudget);

        CompletedStep step = new CompletedStep();
        step.setDayNumber(1);
        step.setAttractionName("钟楼");
        step.setLat(34.25);
        step.setLng(108.95);

        TaskCheckpoint checkpoint = new TaskCheckpoint();
        checkpoint.setRegion("西安市");
        checkpoint.setCityName("西安市");
        checkpoint.setPlanningConfig(config);
        checkpoint.setCompletedSteps(new ArrayList<>(List.of(step)));
        return checkpoint;
    }

    private AccommodationQuote quote(String id,
                                     String type,
                                     String rating,
                                     int reviews,
                                     String price,
                                     double lat,
                                     double lng) {
        AccommodationQuote quote = new AccommodationQuote();
        quote.setProvider("mock");
        quote.setProviderHotelId(id);
        quote.setName(id);
        quote.setType(type);
        quote.setAddress("test address");
        quote.setRating(new BigDecimal(rating));
        quote.setReviewCount(reviews);
        quote.setPricePerNightYuan(new BigDecimal(price));
        quote.setCurrency("CNY");
        quote.setLatitude(lat);
        quote.setLongitude(lng);
        quote.setAvailable(true);
        quote.setPriceFetchedAt(LocalDateTime.of(2026, 7, 1, 12, 0));
        return quote;
    }
}
