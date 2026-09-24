package com.travelagent.agent.tools;

import com.travelagent.aop.IdempotentTool;
import com.travelagent.client.amap.AmapClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class AmapMapTool implements AgentTool {

    public static final String NAME = "amap_map";

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int DEFAULT_NEARBY_RADIUS = 1200;
    private static final String TRANSIT_TYPES = "150500|150700";
    private static final String AMENITY_TYPES = "050000|060000|100000";

    @Autowired
    private AmapClient amapClient;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getSource() {
        return "amap:map";
    }

    @Override
    @IdempotentTool(ttl = "24h")
    public Map<String, Object> execute(Map<String, Object> arguments, String idempotencyKey) {
        String city = stringArg(arguments, "city", stringArg(arguments, "destination", ""));
        String travelMode = stringArg(arguments, "travelMode", "driving");
        int nearbyRadius = intArg(arguments, "nearbyRadius", DEFAULT_NEARBY_RADIUS);
        List<String> poiKeywords = stringListArg(arguments, "poiKeywords");
        if (poiKeywords.isEmpty()) {
            poiKeywords = stringListArg(arguments, "attractions");
        }

        List<Map<String, Object>> poiResults = searchPois(poiKeywords, city);
        List<Map<String, Object>> routeMatrix = buildRouteMatrix(poiResults, travelMode);
        List<Map<String, Object>> accommodationCommutes =
                buildAccommodationCommutes(mapListArg(arguments, "accommodations"), poiResults, travelMode);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", getSource());
        result.put("queryTime", Instant.now().toString());
        result.put("city", city);
        result.put("travelMode", travelMode);
        result.put("poiResults", poiResults);
        result.put("routeMatrix", routeMatrix);
        result.put("routeEfficiency", analyzeRouteEfficiency(routeMatrix));
        result.put("accommodationCommutes", accommodationCommutes);
        result.put("recommendedAccommodation", recommendAccommodation(accommodationCommutes));
        result.put("nearbyTransit", searchNearby(poiResults, nearbyRadius, "地铁 公交", TRANSIT_TYPES));
        result.put("nearbyAmenities", searchNearby(poiResults, nearbyRadius, "餐饮 商业", AMENITY_TYPES));
        result.put("validatorHints", buildValidatorHints(routeMatrix, accommodationCommutes));
        return result;
    }

    private List<Map<String, Object>> searchPois(List<String> keywords, String city) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (String keyword : keywords) {
            List<Map<String, Object>> pois = amapClient.searchPois(keyword, city, "", DEFAULT_PAGE, DEFAULT_PAGE_SIZE);
            Map<String, Object> best = pois.isEmpty()
                    ? fallbackGeocode(keyword, city)
                    : new LinkedHashMap<>(pois.get(0));
            best.put("keyword", keyword);
            results.add(best);
        }
        return results;
    }

    private Map<String, Object> fallbackGeocode(String keyword, String city) {
        Map<String, Object> geocode = new LinkedHashMap<>(amapClient.geocode(keyword, city));
        geocode.put("name", keyword);
        geocode.put("address", "");
        geocode.put("type", "");
        geocode.put("location", geocode.get("lng") + "," + geocode.get("lat"));
        geocode.put("fallback", "geocode");
        return geocode;
    }

    private List<Map<String, Object>> buildRouteMatrix(List<Map<String, Object>> pois, String travelMode) {
        List<Map<String, Object>> matrix = new ArrayList<>();
        for (int i = 0; i < pois.size(); i++) {
            for (int j = i + 1; j < pois.size(); j++) {
                Map<String, Object> origin = pois.get(i);
                Map<String, Object> destination = pois.get(j);
                if (!hasCoordinates(origin) || !hasCoordinates(destination)) {
                    continue;
                }
                Map<String, Object> route = new LinkedHashMap<>(amapClient.getTravelDuration(
                        doubleValue(origin.get("lng")),
                        doubleValue(origin.get("lat")),
                        doubleValue(destination.get("lng")),
                        doubleValue(destination.get("lat")),
                        travelMode));
                route.put("origin", origin.get("name"));
                route.put("destination", destination.get("name"));
                route.put("straightDistanceKm", haversineKm(origin, destination));
                route.put("onTheWay", isOnTheWay(route));
                matrix.add(route);
            }
        }
        return matrix;
    }

    private List<Map<String, Object>> buildAccommodationCommutes(List<Map<String, Object>> accommodations,
                                                                 List<Map<String, Object>> pois,
                                                                 String travelMode) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> accommodation : accommodations) {
            if (!hasCoordinates(accommodation)) {
                continue;
            }
            List<Map<String, Object>> commutes = new ArrayList<>();
            for (Map<String, Object> poi : pois) {
                if (!hasCoordinates(poi)) {
                    continue;
                }
                Map<String, Object> route = new LinkedHashMap<>(amapClient.getTravelDuration(
                        doubleValue(accommodation.get("lng")),
                        doubleValue(accommodation.get("lat")),
                        doubleValue(poi.get("lng")),
                        doubleValue(poi.get("lat")),
                        travelMode));
                route.put("destination", poi.get("name"));
                commutes.add(route);
            }
            double averageDuration = commutes.stream()
                    .mapToInt(item -> intValue(item.get("durationMin")))
                    .average()
                    .orElse(0.0d);
            int maxDuration = commutes.stream()
                    .mapToInt(item -> intValue(item.get("durationMin")))
                    .max()
                    .orElse(0);
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("name", stringArg(accommodation, "name", ""));
            summary.put("lat", accommodation.get("lat"));
            summary.put("lng", accommodation.get("lng"));
            summary.put("averageDurationMin", round(averageDuration));
            summary.put("maxDurationMin", maxDuration);
            summary.put("commuteScore", commuteScore(averageDuration, maxDuration));
            summary.put("commutes", commutes);
            results.add(summary);
        }
        results.sort(Comparator.comparingDouble(item -> -doubleValue(item.get("commuteScore"))));
        return results;
    }

    private Map<String, Object> recommendAccommodation(List<Map<String, Object>> accommodationCommutes) {
        if (accommodationCommutes.isEmpty()) {
            return Map.of();
        }
        return accommodationCommutes.get(0);
    }

    private List<Map<String, Object>> searchNearby(List<Map<String, Object>> pois,
                                                   int nearbyRadius,
                                                   String keywords,
                                                   String types) {
        List<Map<String, Object>> nearbyResults = new ArrayList<>();
        for (Map<String, Object> poi : pois) {
            if (!hasCoordinates(poi)) {
                continue;
            }
            List<Map<String, Object>> nearby = amapClient.searchNearbyPois(
                    doubleValue(poi.get("lng")),
                    doubleValue(poi.get("lat")),
                    nearbyRadius,
                    keywords,
                    types,
                    DEFAULT_PAGE,
                    5);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("poi", poi.get("name"));
            item.put("radiusMeters", nearbyRadius);
            item.put("results", nearby);
            nearbyResults.add(item);
        }
        return nearbyResults;
    }

    private Map<String, Object> analyzeRouteEfficiency(List<Map<String, Object>> routeMatrix) {
        long onTheWayCount = routeMatrix.stream()
                .filter(item -> Boolean.TRUE.equals(item.get("onTheWay")))
                .count();
        double averageDuration = routeMatrix.stream()
                .mapToInt(item -> intValue(item.get("durationMin")))
                .average()
                .orElse(0.0d);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("routeCount", routeMatrix.size());
        summary.put("onTheWayCount", onTheWayCount);
        summary.put("averageDurationMin", round(averageDuration));
        summary.put("allPairsConvenient", routeMatrix.stream().allMatch(this::isOnTheWay));
        return summary;
    }

    private List<String> buildValidatorHints(List<Map<String, Object>> routeMatrix,
                                             List<Map<String, Object>> accommodationCommutes) {
        List<String> hints = new ArrayList<>();
        boolean routeTooFar = routeMatrix.stream().anyMatch(route -> intValue(route.get("durationMin")) > 60);
        if (routeTooFar) {
            hints.add("route_time_over_60_min");
        }
        boolean walkHeavy = routeMatrix.stream().anyMatch(route ->
                "walking".equals(route.get("routeMode")) && intValue(route.get("distanceMeters")) > 3000);
        if (walkHeavy) {
            hints.add("walking_distance_over_3km");
        }
        boolean poorAccommodation = accommodationCommutes.stream()
                .anyMatch(item -> doubleValue(item.get("commuteScore")) < 60.0d);
        if (poorAccommodation) {
            hints.add("accommodation_commute_score_low");
        }
        return hints;
    }

    private boolean isOnTheWay(Map<String, Object> route) {
        return intValue(route.get("durationMin")) <= 45
                && intValue(route.get("distanceMeters")) <= 20_000;
    }

    private double commuteScore(double averageDuration, int maxDuration) {
        double score = 100.0d - averageDuration - Math.max(0, maxDuration - 45) * 0.8d;
        return Math.max(0.0d, round(score));
    }

    private double haversineKm(Map<String, Object> origin, Map<String, Object> destination) {
        double lat1 = Math.toRadians(doubleValue(origin.get("lat")));
        double lat2 = Math.toRadians(doubleValue(destination.get("lat")));
        double dLat = lat2 - lat1;
        double dLng = Math.toRadians(doubleValue(destination.get("lng")) - doubleValue(origin.get("lng")));
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return round(6371.0d * c);
    }

    private boolean hasCoordinates(Map<String, Object> item) {
        return item.get("lat") instanceof Number && item.get("lng") instanceof Number;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mapListArg(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (!(value instanceof List<?> items)) {
            return List.of();
        }
        List<Map<String, Object>> maps = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof Map<?, ?> map) {
                maps.add(new LinkedHashMap<>((Map<String, Object>) map));
            }
        }
        return maps;
    }

    private List<String> stringListArg(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value instanceof String text && !text.isBlank()) {
            return List.of(text);
        }
        if (!(value instanceof List<?> items)) {
            return List.of();
        }
        return items.stream()
                .map(String::valueOf)
                .filter(text -> !text.isBlank())
                .toList();
    }

    private String stringArg(Map<String, Object> arguments, String key, String fallback) {
        Object value = arguments.get(key);
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    private int intArg(Map<String, Object> arguments, String key, int fallback) {
        Object value = arguments.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return (int) Math.round(Double.parseDouble(value.toString()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return 0.0d;
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return 0.0d;
        }
    }

    private double round(double value) {
        return Math.round(value * 10.0d) / 10.0d;
    }
}
