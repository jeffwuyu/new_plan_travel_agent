package com.travelagent.service.recommendation;

import com.travelagent.model.dto.NearbyPoiRecommendationRequest;
import com.travelagent.model.dto.RecommendedPoiItem;
import com.travelagent.model.entity.Attraction;

import java.util.List;

public interface CandidateRankingService {

    List<RecommendedPoiItem> rankCandidates(NearbyPoiRecommendationRequest request, List<Attraction> candidates);
}
