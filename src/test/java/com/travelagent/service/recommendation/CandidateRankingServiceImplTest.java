package com.travelagent.service.recommendation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelagent.client.amap.AmapClient;
import com.travelagent.model.dto.NearbyPoiRecommendationRequest;
import com.travelagent.model.dto.RecommendedPoiItem;
import com.travelagent.model.dto.RoutePoint;
import com.travelagent.model.entity.Attraction;
import com.travelagent.service.recommendation.impl.CandidateRankingServiceImpl;
import com.travelagent.service.recommendation.impl.RecommendationExplanationServiceImpl;
import com.travelagent.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
@DisplayName("CandidateRankingServiceImpl Tests")
class CandidateRankingServiceImplTest {

    @Mock
    private AmapClient amapClient;

    private CandidateRankingServiceImpl rankingService;

    @BeforeEach
    void setUp() {
        rankingService = new CandidateRankingServiceImpl();
        JsonUtil jsonUtil = new JsonUtil();
        ReflectionTestUtils.setField(jsonUtil, "objectMapper", new ObjectMapper().findAndRegisterModules());
        ReflectionTestUtils.setField(rankingService, "amapClient", amapClient);
        ReflectionTestUtils.setField(rankingService, "jsonUtil", jsonUtil);
        ReflectionTestUtils.setField(rankingService, "explanationService", new RecommendationExplanationServiceImpl());

        lenient().when(amapClient.getTravelDuration(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyString()))
                .thenReturn(Map.of("durationMin", 10));
        lenient().when(amapClient.getDistance(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenAnswer(invocation -> {
                    double destLng = invocation.getArgument(2);
                    if (Math.abs(destLng - 120.001d) < 0.0001d) {
                        return Map.of("distanceMeters", 300, "distanceKm", 0.3d);
                    }
                    if (Math.abs(destLng - 120.040d) < 0.0001d) {
                        return Map.of("distanceMeters", 5600, "distanceKm", 5.6d);
                    }
                    return Map.of("distanceMeters", 1200, "distanceKm", 1.2d);
                });
    }

    @Test
    @DisplayName("nearby mode prefers nearer candidate with shorter ETA")
    void rankCandidates_prefersNearerCandidate() {
        NearbyPoiRecommendationRequest request = baseRequest();
        Attraction near = attraction("near", 30.001, 120.001, List.of("湖景", "散步"), true, 1);
        Attraction far = attraction("far", 30.040, 120.040, List.of("湖景", "散步"), true, 1);

        List<RecommendedPoiItem> ranked = rankingService.rankCandidates(request, List.of(far, near));

        assertThat(ranked).hasSize(2);
        assertThat(ranked.get(0).getName()).isEqualTo("near");
        assertThat(ranked.get(0).getFeatures().getDistanceKm())
                .isLessThan(ranked.get(1).getFeatures().getDistanceKm());
    }

    @Test
    @DisplayName("itinerary_fill favors lower route delta when route points exist")
    void rankCandidates_prefersLowerRouteDelta() {
        NearbyPoiRecommendationRequest request = baseRequest();
        request.setQueryType("itinerary_fill");
        request.setRoutePoints(List.of(
                new RoutePoint(30.000, 120.000, "A"),
                new RoutePoint(30.010, 120.010, "B")
        ));

        Attraction onRoute = attraction("on-route", 30.005, 120.005, List.of("湖景"), true, 1);
        Attraction detour = attraction("detour", 30.050, 120.060, List.of("湖景"), true, 1);

        List<RecommendedPoiItem> ranked = rankingService.rankCandidates(request, List.of(detour, onRoute));

        assertThat(ranked.get(0).getName()).isEqualTo("on-route");
        assertThat(ranked.get(0).getFeatures().getRouteDeltaKm())
                .isLessThan(ranked.get(1).getFeatures().getRouteDeltaKm());
    }

    @Test
    @DisplayName("closed attraction is filtered out by constraint score")
    void rankCandidates_filtersClosedAttraction() {
        NearbyPoiRecommendationRequest request = baseRequest();
        request.setDayOfWeek("mon");
        request.setCurrentTime("22:00");

        Attraction open = attraction("open", 30.001, 120.001, List.of("湖景"), true, 1);
        open.setOpenHoursJson("{\"mon\":[\"08:00-23:00\"]}");
        Attraction closed = attraction("closed", 30.002, 120.002, List.of("湖景"), true, 1);
        closed.setOpenHoursJson("{\"mon\":[\"08:00-18:00\"]}");

        List<RecommendedPoiItem> ranked = rankingService.rankCandidates(request, List.of(open, closed));

        assertThat(ranked).extracting(RecommendedPoiItem::getName).containsExactly("open");
        assertThat(ranked.get(0).getFeatures().getCurrentlyOpen()).isTrue();
    }

    @Test
    @DisplayName("response item contains score explanations and feature breakdown")
    void rankCandidates_returnsExplanationAndFeatures() {
        NearbyPoiRecommendationRequest request = baseRequest();
        Attraction attraction = attraction("sample", 30.001, 120.001, List.of("湖景", "散步"), true, 1);
        attraction.setDescription("适合边走边拍照，也适合轻松停留。");
        attraction.setBestVisitTimeJson("[\"傍晚景色更好\"]");
        attraction.setSuitableForJson("[\"亲子\", \"慢节奏游玩\"]");
        attraction.setVisitDurationMin(90);

        List<RecommendedPoiItem> ranked = rankingService.rankCandidates(request, List.of(attraction));

        assertThat(ranked).hasSize(1);
        RecommendedPoiItem item = ranked.get(0);
        assertThat(item.getScore()).isNotNull();
        assertThat(item.getExplanations()).isNotEmpty();
        assertThat(item.getFeatures()).isNotNull();
        assertThat(item.getFeatures().getGeoScore()).isNotNull();
        assertThat(item.getFeatures().getStyleSimilarity()).isNotNull();
        assertThat(item.getRouteSummary()).isNotBlank();
        assertThat(item.getHighlights()).isNotEmpty();
        assertThat(item.getVisitDurationMin()).isEqualTo(90);
    }

    @Test
    @DisplayName("walking request uses walking ETA and distance API")
    void rankCandidates_usesWalkingEtaAndDistanceApi() {
        NearbyPoiRecommendationRequest request = baseRequest();
        Attraction attraction = attraction("walkable", 30.001, 120.001, List.of("湖景"), true, 1);

        rankingService.rankCandidates(request, List.of(attraction));

        verify(amapClient).getTravelDuration(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyString());
        verify(amapClient).getDistance(anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    @DisplayName("distance falls back to haversine when distance API fails")
    void rankCandidates_distanceFallbacksToHaversine() {
        NearbyPoiRecommendationRequest request = baseRequest();
        Attraction attraction = attraction("fallback", 30.001, 120.001, List.of("湖景"), true, 1);
        org.mockito.Mockito.when(amapClient.getDistance(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenThrow(new RuntimeException("distance unavailable"));

        List<RecommendedPoiItem> ranked = rankingService.rankCandidates(request, List.of(attraction));

        assertThat(ranked.get(0).getFeatures().getDistanceKm()).isNotNull();
    }

    private NearbyPoiRecommendationRequest baseRequest() {
        NearbyPoiRecommendationRequest request = new NearbyPoiRecommendationRequest();
        request.setRegion("杭州");
        request.setCurrentLat(30.000);
        request.setCurrentLng(120.000);
        request.setPreferredTags(List.of("湖景", "散步"));
        request.setTravelMode("walking");
        request.setQueryType("nearby");
        request.setTopK(5);
        return request;
    }

    private Attraction attraction(String name, double lat, double lng, List<String> tags, boolean walkable, int priceLevel) {
        Attraction attraction = new Attraction();
        attraction.setName(name);
        attraction.setRegion("杭州");
        attraction.setLatitude(BigDecimal.valueOf(lat));
        attraction.setLongitude(BigDecimal.valueOf(lng));
        attraction.setTagsJson("[\"" + String.join("\",\"", tags) + "\"]");
        attraction.setTransportAccessJson("{\"walkable\":" + walkable + "}");
        attraction.setPriceLevel(priceLevel);
        attraction.setCategory(tags.get(0));
        return attraction;
    }
}
