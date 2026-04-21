package com.travelagent.service.recommendation.impl;

import com.travelagent.client.amap.AmapClient;
import com.travelagent.mapper.AttractionMapper;
import com.travelagent.mapper.AttractionRecommendationMapper;
import com.travelagent.model.dto.NearbyPoiRecommendationRequest;
import com.travelagent.model.entity.Attraction;
import com.travelagent.service.poi.PoiProfileEnrichmentService;
import com.travelagent.service.recommendation.CandidateRecallService;
import com.travelagent.util.JsonUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class CandidateRecallServiceImpl implements CandidateRecallService {

    @Autowired private AttractionRecommendationMapper attractionRecommendationMapper;
    @Autowired private AttractionMapper attractionMapper;
    @Autowired private AmapClient amapClient;
    @Autowired private PoiProfileEnrichmentService poiProfileEnrichmentService;
    @Autowired private JsonUtil jsonUtil;

    @Override
    public List<Attraction> recallCandidates(NearbyPoiRecommendationRequest request) {
        int desired = Math.max(safeTopK(request) * 4, 20);
        Map<String, Attraction> merged = new LinkedHashMap<>();

        Attraction currentPoi = resolveCurrentPoi(request);
        List<String> currentTags = currentPoi != null ? parseStringList(currentPoi.getTagsJson()) : List.of();

        if (request.getCurrentLat() != null && request.getCurrentLng() != null) {
            for (Attraction attraction : attractionRecommendationMapper.findByBoundingBox(
                    BigDecimal.valueOf(request.getCurrentLat() - 0.05d),
                    BigDecimal.valueOf(request.getCurrentLat() + 0.05d),
                    BigDecimal.valueOf(request.getCurrentLng() - 0.05d),
                    BigDecimal.valueOf(request.getCurrentLng() + 0.05d),
                    desired)) {
                merged.put(keyOf(attraction), attraction);
            }
        }

        if (request.getRegion() != null && !request.getRegion().isBlank()) {
            for (Attraction attraction : attractionRecommendationMapper.findByRegion(request.getRegion(), desired)) {
                merged.putIfAbsent(keyOf(attraction), attraction);
            }
        }

        if (merged.size() < desired / 2 && request.getCurrentLat() != null && request.getCurrentLng() != null) {
            List<Map<String, Object>> remote = amapClient.searchNearbyPois(
                    request.getCurrentLng(), request.getCurrentLat(), 5000,
                    null, null, 1, desired);
            for (Attraction attraction : poiProfileEnrichmentService.saveOrUpdateAll(remote)) {
                merged.putIfAbsent(keyOf(attraction), attraction);
            }
        }

        List<Attraction> all = new ArrayList<>(merged.values());
        if (!currentTags.isEmpty()) {
            List<Attraction> styleMatches = all.stream()
                    .filter(attraction -> hasTagOverlap(parseStringList(attraction.getTagsJson()), currentTags))
                    .collect(Collectors.toList());
            styleMatches.forEach(attraction -> merged.putIfAbsent(keyOf(attraction), attraction));
        }

        return merged.values().stream()
                .filter(attraction -> !isExcluded(attraction, request))
                .collect(Collectors.toList());
    }

    private Attraction resolveCurrentPoi(NearbyPoiRecommendationRequest request) {
        if (request.getCurrentPoiId() != null && !request.getCurrentPoiId().isBlank()) {
            Attraction byId = attractionMapper.findByAmapPoiId(request.getCurrentPoiId());
            if (byId != null) {
                return byId;
            }
        }
        if (request.getCurrentPoiName() != null && request.getRegion() != null) {
            return attractionMapper.findByNameAndRegion(request.getCurrentPoiName(), request.getRegion());
        }
        return null;
    }

    private boolean isExcluded(Attraction attraction, NearbyPoiRecommendationRequest request) {
        if (attraction == null || attraction.getName() == null) {
            return true;
        }
        Set<String> excludedIds = toLowerSet(request.getSelectedPoiIds());
        Set<String> excludedNames = toLowerSet(request.getSelectedPoiNames());
        if (!excludedIds.isEmpty()) {
            if (attraction.getAmapPoiId() != null && excludedIds.contains(attraction.getAmapPoiId().toLowerCase())) {
                return true;
            }
        }
        return excludedNames.contains(attraction.getName().toLowerCase());
    }

    private Set<String> toLowerSet(Collection<String> values) {
        if (values == null) {
            return Set.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
    }

    private boolean hasTagOverlap(List<String> left, List<String> right) {
        Set<String> normalized = left.stream()
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
        for (String value : right) {
            if (normalized.contains(value.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return jsonUtil.fromJson(json, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private String keyOf(Attraction attraction) {
        return attraction.getAmapPoiId() != null && !attraction.getAmapPoiId().isBlank()
                ? attraction.getAmapPoiId()
                : attraction.getName() + "|" + attraction.getRegion();
    }

    private int safeTopK(NearbyPoiRecommendationRequest request) {
        return request.getTopK() == null ? 5 : request.getTopK();
    }
}
