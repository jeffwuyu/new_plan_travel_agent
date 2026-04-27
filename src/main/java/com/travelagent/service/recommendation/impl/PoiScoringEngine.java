package com.travelagent.service.recommendation.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.travelagent.client.amap.AmapClient;
import com.travelagent.model.dto.NearbyPoiRecommendationRequest;
import com.travelagent.model.dto.RecommendationFeatureBreakdown;
import com.travelagent.model.dto.RoutePoint;
import com.travelagent.model.entity.Attraction;
import com.travelagent.util.JsonUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Computes per-dimension feature scores for POI ranking.
 * All score values are in [0, 1]. No side effects beyond AmapClient calls.
 */
@Component
public class PoiScoringEngine {

    private static final double GEO_TAU_KM = 2.0d;
    private static final double ROUTE_GAMMA_KM = 3.0d;
    private static final double TIME_LAMBDA_MIN = 20.0d;

    @Autowired private JsonUtil jsonUtil;
    @Autowired(required = false) private AmapClient amapClient;

    /**
     * 构建featurebreakdown。
     * @param request 请求参数
     * @param candidate 候选项
     * @return 返回处理结果。
     */
    public RecommendationFeatureBreakdown buildFeatureBreakdown(NearbyPoiRecommendationRequest request,
                                                                Attraction candidate) {
        RecommendationFeatureBreakdown features = new RecommendationFeatureBreakdown();

        Double distanceKm = queryDistanceKm(request, candidate);
        if (request.getCurrentLat() != null && request.getCurrentLng() != null
                && candidate.getLatitude() != null && candidate.getLongitude() != null) {
            if (distanceKm == null) {
                distanceKm = haversineKm(
                        request.getCurrentLat(), request.getCurrentLng(),
                        candidate.getLatitude().doubleValue(), candidate.getLongitude().doubleValue());
            }
            features.setDistanceKm(round(distanceKm));
            features.setGeoScore(round(Math.exp(-distanceKm / GEO_TAU_KM)));
        } else {
            features.setGeoScore(0.5d);
        }

        Integer travelTimeMin = estimateTravelTimeMin(request, candidate, distanceKm);
        features.setTravelTimeMin(travelTimeMin);
        features.setTimeScore(round(Math.exp(-(travelTimeMin == null ? 20.0d : travelTimeMin) / TIME_LAMBDA_MIN)));

        double styleSimilarity = computeStyleSimilarity(request, candidate);
        features.setStyleSimilarity(round(styleSimilarity));
        features.setStyleScore(round(styleSimilarity));

        Double routeDeltaKm = computeRouteDeltaKm(request, candidate, distanceKm);
        features.setRouteDeltaKm(routeDeltaKm != null ? round(routeDeltaKm) : null);
        features.setRouteScore(round(Math.exp(-(routeDeltaKm == null ? 1.0d : routeDeltaKm) / ROUTE_GAMMA_KM)));

        boolean currentlyOpen = isCurrentlyOpen(request, candidate);
        features.setCurrentlyOpen(currentlyOpen);
        features.setConstraintScore(round(computeConstraintScore(request, candidate, currentlyOpen)));

        double weatherScore = computeWeatherScore(request, candidate);
        features.setWeatherScore(round(weatherScore));
        features.setWeatherFriendly(weatherScore >= 0.6d);

        return features;
    }

    /**
     * 处理weightsFor。
     * @param queryType q ue ry Ty pe 参数
     * @return 返回处理结果。
     */
    public double[] weightsFor(String queryType) {
        if ("similar_style".equalsIgnoreCase(queryType)) {
            return new double[]{0.15d, 0.20d, 0.45d, 0.15d, 0.05d};
        }
        if ("itinerary_fill".equalsIgnoreCase(queryType)) {
            return new double[]{0.10d, 0.30d, 0.20d, 0.30d, 0.10d};
        }
        return new double[]{0.30d, 0.35d, 0.15d, 0.15d, 0.05d};
    }

    List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return jsonUtil.fromJson(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 处理queryDistanceKm。
     * @param request 请求参数
     * @param candidate 候选项
     * @return 返回处理结果。
     */
    private Double queryDistanceKm(NearbyPoiRecommendationRequest request, Attraction candidate) {
        if (amapClient == null
                || request.getCurrentLat() == null || request.getCurrentLng() == null
                || candidate.getLatitude() == null || candidate.getLongitude() == null) {
            return null;
        }
        try {
            Map<String, Object> result = amapClient.getDistance(
                    request.getCurrentLng(), request.getCurrentLat(),
                    candidate.getLongitude().doubleValue(), candidate.getLatitude().doubleValue());
            Object distanceKm = result.get("distanceKm");
            if (distanceKm instanceof Number number) {
                return number.doubleValue();
            }
            Object distanceMeters = result.get("distanceMeters");
            if (distanceMeters instanceof Number number) {
                return number.doubleValue() / 1000.0d;
            }
        } catch (Exception ignored) {
            // Fall back to haversine distance.
        }
        return null;
    }

    /**
     * 处理estimateTravelTimeMin。
     * @param request 请求参数
     * @param candidate 候选项
     * @param distanceKm d is ta nc eK m 参数
     * @return 返回处理结果。
     */
    private Integer estimateTravelTimeMin(NearbyPoiRecommendationRequest request, Attraction candidate, Double distanceKm) {
        if (amapClient != null
                && request.getCurrentLat() != null && request.getCurrentLng() != null
                && candidate.getLatitude() != null && candidate.getLongitude() != null) {
            try {
                Map<String, Object> result = amapClient.getTravelDuration(
                        request.getCurrentLng(), request.getCurrentLat(),
                        candidate.getLongitude().doubleValue(), candidate.getLatitude().doubleValue(),
                        request.getTravelMode());
                Object durationMin = result.get("durationMin");
                if (durationMin instanceof Number number) {
                    return number.intValue();
                }
            } catch (Exception ignored) {
                // Fall through to heuristic ETA.
            }
        }
        if (distanceKm == null) {
            return null;
        }
        double speedKmh;
        if ("walking".equalsIgnoreCase(request.getTravelMode())) {
            speedKmh = 4.5d;
        } else if ("transit".equalsIgnoreCase(request.getTravelMode())) {
            speedKmh = 18.0d;
        } else {
            speedKmh = 28.0d;
        }
        return (int) Math.ceil(distanceKm / speedKmh * 60.0d);
    }

    /**
     * 处理computeStyleSimilarity。
     * @param request 请求参数
     * @param candidate 候选项
     * @return 返回处理结果。
     */
    private double computeStyleSimilarity(NearbyPoiRecommendationRequest request, Attraction candidate) {
        List<String> candidateTags = parseStringList(candidate.getTagsJson());
        Set<String> targetTags = new HashSet<>();
        if (request.getPreferredTags() != null) {
            request.getPreferredTags().stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(value -> value.toLowerCase(Locale.ROOT))
                    .forEach(targetTags::add);
        }
        if (request.getCurrentPoiName() != null && !request.getCurrentPoiName().isBlank()) {
            targetTags.add(request.getCurrentPoiName().toLowerCase(Locale.ROOT));
        }
        if (targetTags.isEmpty() && candidate.getCategory() != null && request.getRegion() != null) {
            return 0.4d;
        }

        Set<String> candidateTagSet = new HashSet<>();
        candidateTags.stream()
                .filter(tag -> tag != null && !tag.isBlank())
                .map(tag -> tag.toLowerCase(Locale.ROOT))
                .forEach(candidateTagSet::add);
        if (candidate.getCategory() != null && targetTags.contains(candidate.getCategory().toLowerCase(Locale.ROOT))) {
            candidateTagSet.add(candidate.getCategory().toLowerCase(Locale.ROOT));
        }

        if (targetTags.isEmpty() || candidateTagSet.isEmpty()) {
            return 0.0d;
        }

        Set<String> union = new HashSet<>(targetTags);
        union.addAll(candidateTagSet);
        Set<String> intersection = new HashSet<>(targetTags);
        intersection.retainAll(candidateTagSet);
        return (double) intersection.size() / union.size();
    }

    /**
     * 处理computeRouteDeltaKm。
     * @param request 请求参数
     * @param candidate 候选项
     * @param distanceKm d is ta nc eK m 参数
     * @return 返回处理结果。
     */
    private Double computeRouteDeltaKm(NearbyPoiRecommendationRequest request, Attraction candidate, Double distanceKm) {
        if (candidate.getLatitude() == null || candidate.getLongitude() == null) {
            return null;
        }
        List<double[]> routePoints = new ArrayList<>();
        if (request.getRoutePoints() != null) {
            for (RoutePoint rp : request.getRoutePoints()) {
                if (rp.getLat() != null && rp.getLng() != null) {
                    routePoints.add(new double[]{rp.getLat(), rp.getLng()});
                }
            }
        }
        if (routePoints.isEmpty() && request.getCurrentLat() != null && request.getCurrentLng() != null) {
            routePoints.add(new double[]{request.getCurrentLat(), request.getCurrentLng()});
        }
        if (routePoints.size() < 2) {
            return distanceKm;
        }
        double bestDelta = Double.MAX_VALUE;
        double candidateLat = candidate.getLatitude().doubleValue();
        double candidateLng = candidate.getLongitude().doubleValue();
        for (int i = 0; i < routePoints.size() - 1; i++) {
            double[] a = routePoints.get(i);
            double[] b = routePoints.get(i + 1);
            double delta = haversineKm(a[0], a[1], candidateLat, candidateLng)
                    + haversineKm(candidateLat, candidateLng, b[0], b[1])
                    - haversineKm(a[0], a[1], b[0], b[1]);
            bestDelta = Math.min(bestDelta, delta);
        }
        return bestDelta == Double.MAX_VALUE ? distanceKm : bestDelta;
    }

    /**
     * 判断currentlyopen。
     * @param request 请求参数
     * @param candidate 候选项
     * @return 是否满足当前条件。
     */
    private boolean isCurrentlyOpen(NearbyPoiRecommendationRequest request, Attraction candidate) {
        if (candidate.getOpenHoursJson() == null || candidate.getOpenHoursJson().isBlank()) {
            return true;
        }
        try {
            Map<String, List<String>> openHours = jsonUtil.fromJson(
                    candidate.getOpenHoursJson(), new TypeReference<Map<String, List<String>>>() {});
            if (request.getDayOfWeek() == null || request.getCurrentTime() == null) {
                return true;
            }
            List<String> ranges = openHours.get(request.getDayOfWeek().toLowerCase(Locale.ROOT));
            if (ranges == null || ranges.isEmpty()) {
                return true;
            }
            for (String range : ranges) {
                String[] parts = range.split("-");
                if (parts.length == 2
                        && request.getCurrentTime().compareTo(parts[0]) >= 0
                        && request.getCurrentTime().compareTo(parts[1]) <= 0) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * 处理computeConstraintScore。
     * @param request 请求参数
     * @param candidate 候选项
     * @param currentlyOpen c ur re nt ly Op en 参数
     * @return 返回处理结果。
     */
    private double computeConstraintScore(NearbyPoiRecommendationRequest request,
                                          Attraction candidate, boolean currentlyOpen) {
        if (!currentlyOpen) {
            return 0.0d;
        }
        double score = 0.5d;
        if (candidate.getPriceLevel() != null && request.getBudgetLevel() != null) {
            score += candidate.getPriceLevel() <= request.getBudgetLevel() ? 0.2d : -0.25d;
        }
        List<String> suitableFor = parseStringList(candidate.getSuitableForJson());
        if (request.getCompanions() != null && !request.getCompanions().isEmpty() && !suitableFor.isEmpty()) {
            boolean matched = request.getCompanions().stream()
                    .anyMatch(companion -> suitableFor.stream()
                            .map(v -> v.toLowerCase(Locale.ROOT))
                            .anyMatch(v -> v.contains(companion.toLowerCase(Locale.ROOT))));
            score += matched ? 0.15d : -0.10d;
        }
        if (Boolean.TRUE.equals(candidate.getReservationRequired())) {
            score -= 0.05d;
        }
        return Math.max(0.0d, Math.min(1.0d, score));
    }

    /**
     * 处理computeWeatherScore。
     * @param request 请求参数
     * @param candidate 候选项
     * @return 返回处理结果。
     */
    private double computeWeatherScore(NearbyPoiRecommendationRequest request, Attraction candidate) {
        List<String> tags = parseStringList(candidate.getTagsJson());
        String category = candidate.getCategory() == null ? "" : candidate.getCategory().toLowerCase(Locale.ROOT);
        boolean indoorLike = category.contains("museum") || category.contains("mall") || category.contains("art")
                || containsAny(tags, "室内", "博物馆", "美术馆", "商场", "展馆");
        boolean outdoorLike = category.contains("park") || category.contains("mountain")
                || containsAny(tags, "户外", "公园", "徒步", "山", "湖");

        double score = 0.5d;
        if (Boolean.TRUE.equals(request.getIndoorPreferred())) {
            score += indoorLike ? 0.35d : -0.20d;
        }
        if (Boolean.TRUE.equals(request.getAvoidRain())) {
            score += indoorLike ? 0.15d : (outdoorLike ? -0.25d : -0.05d);
        }
        if (Boolean.TRUE.equals(request.getShortWalkPreferred())
                && request.getCurrentLat() != null && request.getCurrentLng() != null
                && candidate.getLatitude() != null && candidate.getLongitude() != null) {
            double distanceKm = haversineKm(
                    request.getCurrentLat(), request.getCurrentLng(),
                    candidate.getLatitude().doubleValue(), candidate.getLongitude().doubleValue());
            score += distanceKm <= 2.0d ? 0.15d : (distanceKm >= 5.0d ? -0.10d : 0.0d);
        }
        if (Boolean.TRUE.equals(request.getAvoidWind()) && outdoorLike) {
            score -= 0.15d;
        }
        return Math.max(0.0d, Math.min(1.0d, score));
    }

    /**
     * 判断containsAny。
     * @param values v al ue s 参数
     * @param targets t ar ge ts 参数
     * @return 是否满足当前条件。
     */
    private boolean containsAny(List<String> values, String... targets) {
        if (values == null || values.isEmpty() || targets == null) {
            return false;
        }
        Set<String> normalized = new HashSet<>();
        values.stream().filter(v -> v != null && !v.isBlank())
                .map(v -> v.toLowerCase(Locale.ROOT))
                .forEach(normalized::add);
        for (String target : targets) {
            if (target != null && normalized.contains(target.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 处理haversineKm。
     * @param lat1 l at1 参数
     * @param lng1 l ng1 参数
     * @param lat2 l at2 参数
     * @param lng2 l ng2 参数
     * @return 返回处理结果。
     */
    private double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 6371.0d * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /**
     * 处理round。
     * @param value 键值
     * @return 返回处理结果。
     */
    private double round(double value) {
        return Math.round(value * 1000.0d) / 1000.0d;
    }

    double safe(Double value) {
        return value == null ? 0.0d : value;
    }
}
