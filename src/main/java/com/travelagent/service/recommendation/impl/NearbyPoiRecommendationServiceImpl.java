package com.travelagent.service.recommendation.impl;

import com.travelagent.model.dto.NearbyPoiRecommendationRequest;
import com.travelagent.model.dto.NearbyPoiRecommendationResponse;
import com.travelagent.model.dto.RecommendedPoiItem;
import com.travelagent.model.entity.Attraction;
import com.travelagent.service.recommendation.CandidateRankingService;
import com.travelagent.service.recommendation.CandidateRecallService;
import com.travelagent.service.recommendation.NearbyPoiRecommendationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class NearbyPoiRecommendationServiceImpl implements NearbyPoiRecommendationService {

    @Autowired private CandidateRecallService candidateRecallService;
    @Autowired private CandidateRankingService candidateRankingService;

    /**
     * 处理recommend。
     * @param request 请求参数
     * @return 返回处理结果。
     */
    @Override
    public NearbyPoiRecommendationResponse recommend(NearbyPoiRecommendationRequest request) {
        NearbyPoiRecommendationResponse response = new NearbyPoiRecommendationResponse();
        response.setQueryType(request.getQueryType());

        List<Attraction> candidates = candidateRecallService.recallCandidates(request);
        response.setTotalCandidates(candidates.size());

        List<RecommendedPoiItem> ranked = candidateRankingService.rankCandidates(request, candidates);
        response.setRecommendations(ranked);
        return response;
    }
}
