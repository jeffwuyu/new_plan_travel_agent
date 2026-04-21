package com.travelagent.controller;

import com.travelagent.exception.GlobalExceptionHandler;
import com.travelagent.filter.JwtAuthInterceptor;
import com.travelagent.model.dto.NearbyPoiRecommendationResponse;
import com.travelagent.model.dto.RecommendationFeatureBreakdown;
import com.travelagent.model.dto.RecommendedPoiItem;
import com.travelagent.service.recommendation.NearbyPoiRecommendationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("PoiRecommendationController Tests")
class PoiRecommendationControllerTest {

    @Mock
    private NearbyPoiRecommendationService nearbyPoiRecommendationService;

    @InjectMocks
    private PoiRecommendationController poiRecommendationController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(poiRecommendationController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("POST /api/recommendations/nearby returns score explanations and features")
    void recommendNearby_success() throws Exception {
        NearbyPoiRecommendationResponse response = new NearbyPoiRecommendationResponse();
        response.setQueryType("nearby");
        response.setTotalCandidates(8);

        RecommendedPoiItem item = new RecommendedPoiItem();
        item.setPoiId("poi-1");
        item.setName("西湖");
        item.setScore(0.932);
        item.setExplanations(List.of("距离当前点约 1.2km", "步行约 18 分钟可达", "与当前偏好风格相近"));
        RecommendationFeatureBreakdown features = new RecommendationFeatureBreakdown();
        features.setDistanceKm(1.2);
        features.setTravelTimeMin(18);
        features.setStyleSimilarity(0.81);
        item.setFeatures(features);
        response.setRecommendations(List.of(item));

        try (MockedStatic<JwtAuthInterceptor> mocked = mockStatic(JwtAuthInterceptor.class)) {
            mocked.when(() -> JwtAuthInterceptor.getUserId(any())).thenReturn(1L);
            when(nearbyPoiRecommendationService.recommend(any())).thenReturn(response);

            mockMvc.perform(post("/api/recommendations/nearby")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "region":"杭州",
                                      "currentLat":30.25,
                                      "currentLng":120.14,
                                      "preferredTags":["湖景","散步"],
                                      "travelMode":"walking",
                                      "topK":5
                                    }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.queryType").value("nearby"))
                    .andExpect(jsonPath("$.data.recommendations[0].score").value(0.932))
                    .andExpect(jsonPath("$.data.recommendations[0].explanations.length()").value(3))
                    .andExpect(jsonPath("$.data.recommendations[0].features.distanceKm").value(1.2))
                    .andExpect(jsonPath("$.data.recommendations[0].features.travelTimeMin").value(18))
                    .andExpect(jsonPath("$.data.recommendations[0].features.styleSimilarity").value(0.81));
        }
    }
}
