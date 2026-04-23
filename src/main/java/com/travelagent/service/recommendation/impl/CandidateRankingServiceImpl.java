package com.travelagent.service.recommendation.impl;

import com.travelagent.model.dto.NearbyPoiRecommendationRequest;
import com.travelagent.model.dto.RecommendationFeatureBreakdown;
import com.travelagent.model.dto.RecommendedPoiItem;
import com.travelagent.model.entity.Attraction;
import com.travelagent.service.recommendation.CandidateRankingService;
import com.travelagent.service.recommendation.RecommendationExplanationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class CandidateRankingServiceImpl implements CandidateRankingService {

    @Autowired private PoiScoringEngine scoringEngine;
    @Autowired private RecommendationExplanationService explanationService;

    @Override
    public List<RecommendedPoiItem> rankCandidates(NearbyPoiRecommendationRequest request, List<Attraction> candidates) {
        List<RecommendedPoiItem> ranked = new ArrayList<>();
        for (Attraction candidate : candidates) {
            RecommendationFeatureBreakdown features = scoringEngine.buildFeatureBreakdown(request, candidate);
            if (features.getConstraintScore() != null && features.getConstraintScore() <= 0.0d) {
                continue;
            }

            double[] weights = scoringEngine.weightsFor(request.getQueryType());
            double score = weights[0] * scoringEngine.safe(features.getGeoScore())
                    + weights[1] * scoringEngine.safe(features.getTimeScore())
                    + weights[2] * scoringEngine.safe(features.getStyleScore())
                    + weights[3] * scoringEngine.safe(features.getRouteScore())
                    + weights[4] * scoringEngine.safe(features.getConstraintScore())
                    + 0.15d * scoringEngine.safe(features.getWeatherScore());

            RecommendedPoiItem item = new RecommendedPoiItem();
            item.setPoiId(candidate.getAmapPoiId() != null ? candidate.getAmapPoiId() : String.valueOf(candidate.getId()));
            item.setAmapPoiId(candidate.getAmapPoiId());
            item.setName(candidate.getName());
            item.setRegion(candidate.getRegion());
            item.setDistrict(candidate.getDistrict());
            item.setCategory(candidate.getCategory());
            item.setAddress(candidate.getAddress());
            item.setLatitude(candidate.getLatitude() != null ? candidate.getLatitude().doubleValue() : null);
            item.setLongitude(candidate.getLongitude() != null ? candidate.getLongitude().doubleValue() : null);
            item.setSource(candidate.getSource());
            item.setScore(round(score));
            item.setVisitDurationMin(candidate.getVisitDurationMin());
            item.setFeatures(features);
            item.setRouteSummary(buildRouteSummary(request, features));
            item.setHighlights(buildHighlights(candidate));
            item.setExplanations(explanationService.buildExplanations(request, candidate, item));
            ranked.add(item);
        }

        ranked.sort(Comparator.comparing(RecommendedPoiItem::getScore, Comparator.nullsLast(Comparator.reverseOrder())));
        int topK = request.getTopK() == null ? 5 : request.getTopK();
        return ranked.size() > topK ? ranked.subList(0, topK) : ranked;
    }

    private String buildRouteSummary(NearbyPoiRecommendationRequest request, RecommendationFeatureBreakdown features) {
        List<String> segments = new ArrayList<>();
        if (features.getTravelTimeMin() != null) {
            segments.add(String.format("从当前点位前往约 %d 分钟", features.getTravelTimeMin()));
        } else if (features.getDistanceKm() != null) {
            segments.add(String.format("距离当前点位约 %.1fkm", features.getDistanceKm()));
        }
        if (features.getRouteDeltaKm() != null) {
            segments.add(String.format("加入当前路线额外绕路约 %.1fkm", features.getRouteDeltaKm()));
        }
        if (features.getDistanceKm() != null && features.getTravelTimeMin() != null) {
            segments.add(String.format("预计%s可达", normalizeTravelMode(request.getTravelMode())));
        }
        return String.join("，", segments);
    }

    private List<String> buildHighlights(Attraction candidate) {
        List<String> highlights = new ArrayList<>();
        appendShortValues(highlights, scoringEngine.parseStringList(candidate.getTagsJson()), 3);
        appendShortValues(highlights, scoringEngine.parseStringList(candidate.getBestVisitTimeJson()), 1);
        appendShortValues(highlights, scoringEngine.parseStringList(candidate.getSuitableForJson()), 1);
        appendDescription(highlights, candidate.getDescription());
        return highlights.size() > 5 ? highlights.subList(0, 5) : highlights;
    }

    private void appendShortValues(List<String> target, List<String> values, int maxAppend) {
        if (values == null || values.isEmpty() || maxAppend <= 0) {
            return;
        }
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String trimmed = value.trim();
            if (trimmed.isEmpty() || target.contains(trimmed)) {
                continue;
            }
            target.add(trimmed);
            if (--maxAppend == 0) {
                return;
            }
        }
    }

    private void appendDescription(List<String> target, String description) {
        if (description == null) {
            return;
        }
        String trimmed = description.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        if (trimmed.length() > 48) {
            trimmed = trimmed.substring(0, 48).trim() + "...";
        }
        if (!target.contains(trimmed)) {
            target.add(trimmed);
        }
    }

    private String normalizeTravelMode(String travelMode) {
        if ("walking".equalsIgnoreCase(travelMode)) {
            return "步行";
        }
        if ("transit".equalsIgnoreCase(travelMode)) {
            return "公交/地铁";
        }
        return "驾车";
    }

    private double round(double value) {
        return Math.round(value * 1000.0d) / 1000.0d;
    }
}
