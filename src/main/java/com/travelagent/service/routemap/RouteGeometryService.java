package com.travelagent.service.routemap;

import com.fasterxml.jackson.core.type.TypeReference;
import com.travelagent.mapper.PlanMapper;
import com.travelagent.model.entity.PlanStep;
import com.travelagent.util.JsonUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class RouteGeometryService {

    @Autowired
    private PlanMapper planMapper;

    @Autowired
    private JsonUtil jsonUtil;

    public Map<String, Object> buildGeometry(Long planId, int dayNumber) {
        List<PlanStep> steps = planMapper.findStepsByPlanId(planId).stream()
                .filter(s -> s.getDayNumber() != null && s.getDayNumber() == dayNumber)
                .sorted(Comparator.comparing(PlanStep::getStepOrder))
                .toList();
        List<Map<String, Object>> stops = buildStops(steps);
        List<Map<String, Object>> segments = buildSegments(steps);
        Map<String, Object> bounds = buildBounds(stops, segments);
        Map<String, Object> geometry = new LinkedHashMap<>();
        geometry.put("stops", stops);
        geometry.put("segments", segments);
        geometry.put("bounds", bounds);
        return geometry;
    }

    private List<Map<String, Object>> buildStops(List<PlanStep> steps) {
        List<Map<String, Object>> stops = new ArrayList<>();
        int order = 1;
        for (PlanStep step : steps) {
            Map<String, Object> stop = new LinkedHashMap<>();
            stop.put("order", order++);
            stop.put("stepId", step.getId());
            stop.put("name", step.getAttractionName());
            stop.put("lat", number(step.getLatitude()));
            stop.put("lng", number(step.getLongitude()));
            stop.put("plannedStartTime", step.getPlannedStartTime());
            stop.put("plannedEndTime", step.getPlannedEndTime());
            stop.put("introduction", step.getLlmDescription());
            stop.put("selectionReason", step.getLlmDescription());
            stop.put("recommendedVisitRoute", step.getLlmDescription());
            stop.put("suggestedDurationMin", step.getEstimatedDurationMin());
            stops.add(stop);
        }
        return stops;
    }

    private List<Map<String, Object>> buildSegments(List<PlanStep> steps) {
        List<Map<String, Object>> segments = new ArrayList<>();
        for (int i = 1; i < steps.size(); i += 1) {
            PlanStep from = steps.get(i - 1);
            PlanStep to = steps.get(i);
            Map<String, Object> segment = new LinkedHashMap<>();
            segment.put("fromOrder", i);
            segment.put("toOrder", i + 1);
            segment.put("from", from.getAttractionName());
            segment.put("to", to.getAttractionName());
            segment.put("mode", firstNonBlank(to.getTrafficModeFromPrev(), "driving"));
            segment.put("durationMin", to.getTrafficTimeFromPrev());
            segment.put("routeSummary", to.getSelectedRouteSummaryFromPrev());
            segment.put("source", "agent_selected_or_fallback");

            List<Map<String, Object>> polyline = readPolyline(to.getSelectedRouteGeometryJson());
            boolean fallback = polyline.isEmpty();
            if (fallback) {
                polyline = directPolyline(from, to);
            }
            segment.put("polyline", polyline);
            segment.put("fallback", fallback);
            segments.add(segment);
        }
        return segments;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readPolyline(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            Map<String, Object> data = jsonUtil.fromJson(json, new TypeReference<Map<String, Object>>() {});
            Object polyline = data.get("polyline");
            if (polyline instanceof List<?> list && !list.isEmpty()) {
                return (List<Map<String, Object>>) polyline;
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return List.of();
    }

    private List<Map<String, Object>> directPolyline(PlanStep from, PlanStep to) {
        Double fromLng = number(from.getLongitude());
        Double fromLat = number(from.getLatitude());
        Double toLng = number(to.getLongitude());
        Double toLat = number(to.getLatitude());
        if (fromLng == null || fromLat == null || toLng == null || toLat == null) {
            return List.of();
        }
        return List.of(
                Map.of("lng", fromLng, "lat", fromLat),
                Map.of("lng", toLng, "lat", toLat)
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildBounds(List<Map<String, Object>> stops, List<Map<String, Object>> segments) {
        double minLng = Double.POSITIVE_INFINITY;
        double maxLng = Double.NEGATIVE_INFINITY;
        double minLat = Double.POSITIVE_INFINITY;
        double maxLat = Double.NEGATIVE_INFINITY;
        for (Map<String, Object> stop : stops) {
            double[] bounds = include(minLng, maxLng, minLat, maxLat, stop.get("lng"), stop.get("lat"));
            minLng = bounds[0]; maxLng = bounds[1]; minLat = bounds[2]; maxLat = bounds[3];
        }
        for (Map<String, Object> segment : segments) {
            Object polylineObj = segment.get("polyline");
            if (polylineObj instanceof List<?> polyline) {
                for (Object pointObj : polyline) {
                    if (pointObj instanceof Map<?, ?> point) {
                        double[] bounds = include(minLng, maxLng, minLat, maxLat, point.get("lng"), point.get("lat"));
                        minLng = bounds[0]; maxLng = bounds[1]; minLat = bounds[2]; maxLat = bounds[3];
                    }
                }
            }
        }
        if (!Double.isFinite(minLng)) {
            minLng = 0; maxLng = 1; minLat = 0; maxLat = 1;
        }
        double lngPad = Math.max((maxLng - minLng) * 0.12d, 0.01d);
        double latPad = Math.max((maxLat - minLat) * 0.12d, 0.01d);
        return Map.of(
                "minLng", minLng - lngPad,
                "maxLng", maxLng + lngPad,
                "minLat", minLat - latPad,
                "maxLat", maxLat + latPad
        );
    }

    private double[] include(double minLng, double maxLng, double minLat, double maxLat, Object lngObj, Object latObj) {
        Double lng = toDouble(lngObj);
        Double lat = toDouble(latObj);
        if (lng == null || lat == null) {
            return new double[]{minLng, maxLng, minLat, maxLat};
        }
        return new double[]{
                Math.min(minLng, lng),
                Math.max(maxLng, lng),
                Math.min(minLat, lat),
                Math.max(maxLat, lat)
        };
    }

    private Double number(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    private Double toDouble(Object value) {
        if (value == null) return null;
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
