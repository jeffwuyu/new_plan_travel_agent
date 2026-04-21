package com.travelagent.service.recommendation.impl;

import com.travelagent.model.dto.NearbyPoiRecommendationRequest;
import com.travelagent.model.dto.RecommendedPoiItem;
import com.travelagent.model.entity.Attraction;
import com.travelagent.service.recommendation.RecommendationExplanationService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class RecommendationExplanationServiceImpl implements RecommendationExplanationService {

    @Override
    public List<String> buildExplanations(NearbyPoiRecommendationRequest request,
                                          Attraction attraction,
                                          RecommendedPoiItem item) {
        List<String> explanations = new ArrayList<>();
        if (item.getFeatures() != null && item.getFeatures().getDistanceKm() != null) {
            explanations.add(String.format("距离当前点约 %.1fkm", item.getFeatures().getDistanceKm()));
        }
        if (item.getFeatures() != null && item.getFeatures().getTravelTimeMin() != null) {
            explanations.add(String.format("%s 约 %d 分钟可达",
                    normalizeTravelMode(request.getTravelMode()),
                    item.getFeatures().getTravelTimeMin()));
        }
        if (item.getFeatures() != null && item.getFeatures().getStyleSimilarity() != null
                && item.getFeatures().getStyleSimilarity() > 0.2d) {
            explanations.add(String.format("与当前偏好或已选景点的风格相似度 %.2f", item.getFeatures().getStyleSimilarity()));
        }
        if (item.getFeatures() != null && item.getFeatures().getRouteDeltaKm() != null) {
            explanations.add(String.format("加入当前路线的额外绕路约 %.1fkm", item.getFeatures().getRouteDeltaKm()));
        }
        if (item.getFeatures() != null && Boolean.TRUE.equals(item.getFeatures().getCurrentlyOpen())) {
            explanations.add("当前时段可访问");
        }
        if (explanations.isEmpty()) {
            explanations.add("综合距离、风格和路线连贯性后优先推荐");
        }
        return explanations;
    }

    private String normalizeTravelMode(String travelMode) {
        if ("walking".equalsIgnoreCase(travelMode)) {
            return "步行";
        }
        if ("transit".equalsIgnoreCase(travelMode)) {
            return "公共交通";
        }
        return "驾车";
    }
}
