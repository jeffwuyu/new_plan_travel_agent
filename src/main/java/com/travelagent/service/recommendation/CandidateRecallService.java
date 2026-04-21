package com.travelagent.service.recommendation;

import com.travelagent.model.dto.NearbyPoiRecommendationRequest;
import com.travelagent.model.entity.Attraction;

import java.util.List;

public interface CandidateRecallService {

    List<Attraction> recallCandidates(NearbyPoiRecommendationRequest request);
}
