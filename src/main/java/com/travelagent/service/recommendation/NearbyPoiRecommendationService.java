package com.travelagent.service.recommendation;

import com.travelagent.model.dto.NearbyPoiRecommendationRequest;
import com.travelagent.model.dto.NearbyPoiRecommendationResponse;

public interface NearbyPoiRecommendationService {

    NearbyPoiRecommendationResponse recommend(NearbyPoiRecommendationRequest request);
}
