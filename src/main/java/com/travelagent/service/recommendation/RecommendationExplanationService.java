package com.travelagent.service.recommendation;

import com.travelagent.model.dto.NearbyPoiRecommendationRequest;
import com.travelagent.model.dto.RecommendedPoiItem;
import com.travelagent.model.entity.Attraction;

import java.util.List;

public interface RecommendationExplanationService {

    List<String> buildExplanations(NearbyPoiRecommendationRequest request,
                                   Attraction attraction,
                                   RecommendedPoiItem item);
}
