package com.travelagent.service.rag;

import com.travelagent.agent.context.CompletedStep;
import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.agent.planner.PlanNextAttractionRequest;
import com.travelagent.agent.tools.GeocodeTool;
import com.travelagent.agent.tools.TrafficTimeTool;
import com.travelagent.model.dto.LocationCandidateItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class RagAttractionCandidateService {

    private static final Logger log = LoggerFactory.getLogger(RagAttractionCandidateService.class);
    public static final int RAG_RECALL_TOP_K = 20;
    public static final int FINAL_TOP_K = 3;

    private static final Pattern LABELED_NAME = Pattern.compile(
            "^(?:attraction|spot|place|name|poi|destination|recommended attraction|recommended destination|\\u63a8\\u8350\\u666f\\u70b9|\\u666f\\u70b9\\u540d\\u79f0|\\u63a8\\u8350\\u76ee\\u7684\\u5730|\\u666f\\u70b9|\\u5730\\u70b9|\\u540d\\u79f0|\\u76ee\\u7684\\u5730)[:\\uff1a\\s]+(.+)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LIST_PREFIX = Pattern.compile("^(?:[-*#>]\\s*|\\d+[.、)\\s]+)(.{2,80})$");
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("[\\r\\n.;；。!?！？]+");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^\\|?\\s*:?-{2,}:?\\s*(?:\\|\\s*:?-{2,}:?\\s*)+\\|?$");
    private static final Pattern TRAILING_TEXT = Pattern.compile("\\s*(?:[,，:：;；\\-\\u2014\\u2013|/]|[（(【\\[]).*$");

    @Autowired(required = false) private RagService ragService;
    @Autowired(required = false) private GeocodeTool geocodeTool;
    @Autowired(required = false) private TrafficTimeTool trafficTimeTool;

    public List<LocationCandidateItem> buildCandidates(TaskCheckpoint cp,
                                                       PlanNextAttractionRequest request,
                                                       Map<String, Object> weatherContext,
                                                       String taskUuid) {
        if (cp == null || ragService == null) {
            return List.of();
        }
        List<String> chunks = ragService.queryChunks(buildQuery(cp, request, weatherContext),
                cp.getRegion(), RAG_RECALL_TOP_K);
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }

        List<RagNameCandidate> names = extractNames(chunks, cp);
        if (names.isEmpty()) {
            return List.of();
        }

        List<ScoredCandidate> scored = new ArrayList<>();
        int rank = 0;
        for (RagNameCandidate nameCandidate : names) {
            LocationCandidateItem item = geocodeCandidate(cp, request, weatherContext,
                    taskUuid, nameCandidate, rank++);
            if (item == null) {
                continue;
            }
            Integer totalDuration = item.getEstimatedTotalDurationMin();
            Integer remaining = request != null ? request.getRemainingTimeBudgetMin() : cp.getRemainingTimeBudgetMin();
            if (remaining != null && remaining > 0 && totalDuration != null && totalDuration > remaining) {
                continue;
            }
            scored.add(new ScoredCandidate(item, item.getScore() == null ? 0.0d : item.getScore()));
        }

        return scored.stream()
                .sorted(Comparator.comparing(ScoredCandidate::score).reversed())
                .limit(FINAL_TOP_K)
                .map(ScoredCandidate::item)
                .toList();
    }

    private String buildQuery(TaskCheckpoint cp,
                              PlanNextAttractionRequest request,
                              Map<String, Object> weatherContext) {
        List<String> parts = new ArrayList<>();
        parts.add(cp.getUserIntent());
        parts.add(cp.getRegion());
        if (cp.getPlanningConfig() != null && cp.getPlanningConfig().getPreferenceKeywords() != null) {
            parts.add(String.join(" ", cp.getPlanningConfig().getPreferenceKeywords()));
        }
        if (request != null) {
            parts.add(request.getNodePreferencePrompt());
            parts.add(request.getCurrentPositionName());
            parts.add(String.join(" ", request.getVisitedPoiNames() == null ? List.of() : request.getVisitedPoiNames()));
        }
        if (weatherContext != null) {
            parts.add(String.valueOf(weatherContext.getOrDefault("summary", "")));
            parts.add(String.valueOf(weatherContext.getOrDefault("constraintHints", "")));
        }
        return parts.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .reduce((a, b) -> a + " " + b)
                .orElse(cp.getUserIntent());
    }

    private List<RagNameCandidate> extractNames(List<String> chunks, TaskCheckpoint cp) {
        Set<String> visited = new LinkedHashSet<>();
        if (cp.getCompletedSteps() != null) {
            for (CompletedStep step : cp.getCompletedSteps()) {
                if (step.getAttractionName() != null) {
                    visited.add(normalizeKey(step.getAttractionName()));
                }
            }
        }

        Map<String, RagNameCandidate> deduped = new LinkedHashMap<>();
        for (int chunkIndex = 0; chunkIndex < chunks.size(); chunkIndex++) {
            String chunk = chunks.get(chunkIndex);
            for (String fragment : SENTENCE_SPLIT.split(chunk == null ? "" : chunk)) {
                String name = extractName(fragment);
                if (name == null || isNoiseName(name)) {
                    continue;
                }
                String key = normalizeKey(name);
                if (visited.contains(key) || deduped.containsKey(key)) {
                    continue;
                }
                deduped.put(key, new RagNameCandidate(name, chunk, chunkIndex));
                if (deduped.size() >= RAG_RECALL_TOP_K) {
                    return List.copyOf(deduped.values());
                }
            }
        }
        return List.copyOf(deduped.values());
    }

    private String extractName(String fragment) {
        if (fragment == null) {
            return null;
        }
        String value = fragment.trim();
        if (value.isBlank()) {
            return null;
        }
        if (value.startsWith("#")) {
            return null;
        }
        Matcher labeled = LABELED_NAME.matcher(value);
        if (labeled.find()) {
            return cleanName(labeled.group(1));
        }
        String tableName = extractNameFromTableRow(value);
        if (tableName != null) {
            return tableName;
        }
        Matcher listItem = LIST_PREFIX.matcher(value);
        if (listItem.find()) {
            return cleanName(listItem.group(1));
        }
        String fallback = cleanName(value);
        if (fallback != null && fallback.length() <= 30 && !fallback.contains(" ")) {
            return fallback;
        }
        return null;
    }

    private String cleanName(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value
                .replaceAll("^[`*_#>\\-\\s]+", "")
                .replaceAll("[`*_]+$", "")
                .replaceAll("^\\|+", "")
                .replaceAll("\\|+$", "")
                .trim();
        Matcher labeled = LABELED_NAME.matcher(cleaned);
        if (labeled.find()) {
            cleaned = labeled.group(1).trim();
        }
        cleaned = cleaned.replaceAll("^\\*\\*(.+?)\\*\\*.*$", "$1").trim();
        cleaned = TRAILING_TEXT.matcher(cleaned).replaceFirst("").trim();
        if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length() > 1) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        return cleaned.length() > 40 ? cleaned.substring(0, 40).trim() : cleaned;
    }

    private String extractNameFromTableRow(String value) {
        if (!value.startsWith("|") || TABLE_SEPARATOR.matcher(value).matches()) {
            return null;
        }
        String[] cells = value.split("\\|");
        for (String cell : cells) {
            String candidate = cleanName(cell);
            if (candidate != null && !candidate.isBlank() && !isNoiseName(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean isNoiseName(String name) {
        if (name == null || name.length() < 2 || name.length() > 40) {
            return true;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("weather")
                || lower.contains("route")
                || lower.contains("budget")
                || lower.contains("recommend")
                || lower.contains("travel mode")
                || lower.equals("attraction")
                || lower.equals("name")
                || lower.equals("description")
                || lower.equals("highlight")
                || lower.equals("景点")
                || lower.equals("名称")
                || lower.equals("特色")
                || lower.equals("说明")
                || lower.equals("推荐")
                || lower.equals("建议")
                || lower.matches("\\d+");
    }

    private LocationCandidateItem geocodeCandidate(TaskCheckpoint cp,
                                                   PlanNextAttractionRequest request,
                                                   Map<String, Object> weatherContext,
                                                   String taskUuid,
                                                   RagNameCandidate nameCandidate,
                                                   int rank) {
        if (geocodeTool == null) {
            return null;
        }
        try {
            Map<String, Object> geocodeArgs = Map.of("name", nameCandidate.name(), "region", cp.getRegion());
            String geocodeKey = buildKey(taskUuid, cp.getCurrentStepIndex(), "rag-geocode", nameCandidate.name());
            Map<String, Object> geocode = geocodeTool.execute(geocodeArgs, geocodeKey);
            Double lat = numberToDouble(geocode.get("lat"));
            Double lng = numberToDouble(geocode.get("lng"));
            if (lat == null || lng == null) {
                return null;
            }

            Double distanceKm = computeDistanceKm(request, lat, lng);
            Integer travelTimeMin = estimateTravelTime(cp, request, taskUuid, nameCandidate.name(), lat, lng, distanceKm);
            int visitDuration = cp.getPlanningConfig() != null
                    ? cp.getPlanningConfig().getDefaultVisitDurationMin()
                    : 120;
            int totalDuration = visitDuration + (travelTimeMin == null ? 0 : travelTimeMin);
            double score = score(rank, distanceKm, travelTimeMin, weatherContext, nameCandidate.chunk());

            LocationCandidateItem item = new LocationCandidateItem();
            item.setCandidateId("rag-" + rank + "-" + Math.abs(nameCandidate.name().hashCode()));
            item.setCandidateType("rag_candidate");
            item.setBranchType("rag_route");
            item.setName(nameCandidate.name());
            item.setTargetAttractionName(nameCandidate.name());
            item.setRegion(cp.getRegion());
            item.setLatitude(lat);
            item.setLongitude(lng);
            item.setAdcode(String.valueOf(geocode.getOrDefault("adcode", "")));
            item.setSource("rag");
            item.setScore(round(score));
            item.setVisitDurationMin(visitDuration);
            item.setEstimatedTotalDurationMin(totalDuration);
            item.setRouteSummary(buildRouteSummary(request, nameCandidate.name(), distanceKm, travelTimeMin));
            item.setWeatherSuitability(buildWeatherSuitability(weatherContext, nameCandidate.chunk()));
            item.setExplanations(buildExplanations(nameCandidate, distanceKm, travelTimeMin));
            item.setHighlights(buildHighlights(nameCandidate.chunk(), nameCandidate.name()));
            return item;
        } catch (Exception e) {
            log.warn("[RagAttractionCandidateService] candidate={} skipped: {}",
                    nameCandidate.name(), e.getMessage());
            return null;
        }
    }

    private Integer estimateTravelTime(TaskCheckpoint cp,
                                       PlanNextAttractionRequest request,
                                       String taskUuid,
                                       String name,
                                       Double lat,
                                       Double lng,
                                       Double distanceKm) {
        if (request == null || request.getCurrentLat() == null || request.getCurrentLng() == null) {
            return fallbackTravelMinutes(distanceKm, request);
        }
        if (trafficTimeTool == null) {
            return fallbackTravelMinutes(distanceKm, request);
        }
        try {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("originLng", request.getCurrentLng());
            args.put("originLat", request.getCurrentLat());
            args.put("destLng", lng);
            args.put("destLat", lat);
            args.put("travelMode", request.getTravelMode() == null ? "driving" : request.getTravelMode());
            String key = buildKey(taskUuid, cp.getCurrentStepIndex(), "rag-traffic", name);
            Map<String, Object> traffic = trafficTimeTool.execute(args, key);
            Object duration = traffic.get("durationMin");
            return duration instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(duration));
        } catch (Exception e) {
            log.warn("[RagAttractionCandidateService] traffic fallback for {}: {}", name, e.getMessage());
            return fallbackTravelMinutes(distanceKm, request);
        }
    }

    private Integer fallbackTravelMinutes(Double distanceKm, PlanNextAttractionRequest request) {
        if (distanceKm == null) {
            return null;
        }
        String mode = request == null || request.getTravelMode() == null
                ? "driving"
                : request.getTravelMode().toLowerCase(Locale.ROOT);
        double kmPerHour = switch (mode) {
            case "walking" -> 4.5d;
            case "bicycling" -> 12.0d;
            case "transit" -> 18.0d;
            default -> 28.0d;
        };
        return Math.max(1, (int) Math.ceil(distanceKm / kmPerHour * 60.0d));
    }

    private Double computeDistanceKm(PlanNextAttractionRequest request, Double lat, Double lng) {
        if (request == null || request.getCurrentLat() == null || request.getCurrentLng() == null) {
            return null;
        }
        double lat1 = Math.toRadians(request.getCurrentLat());
        double lat2 = Math.toRadians(lat);
        double dLat = lat2 - lat1;
        double dLng = Math.toRadians(lng - request.getCurrentLng());
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2)
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return 6371.0d * c;
    }

    private double score(int rank, Double distanceKm, Integer travelTimeMin,
                         Map<String, Object> weatherContext, String chunk) {
        double ragScore = 1.0d / (rank + 1);
        double distanceScore = distanceKm == null ? 0.6d : Math.exp(-distanceKm / 8.0d);
        double timeScore = travelTimeMin == null ? 0.6d : Math.exp(-travelTimeMin / 45.0d);
        double weatherScore = weatherScore(weatherContext, chunk);
        return 0.20d * ragScore + 0.35d * distanceScore + 0.30d * timeScore + 0.15d * weatherScore;
    }

    private double weatherScore(Map<String, Object> weatherContext, String chunk) {
        if (weatherContext == null || weatherContext.isEmpty()) {
            return 0.7d;
        }
        boolean indoorPreferred = Boolean.TRUE.equals(weatherContext.get("indoorPreferred"))
                || Boolean.TRUE.equals(weatherContext.get("avoidRain"));
        if (!indoorPreferred) {
            return 0.8d;
        }
        String text = chunk == null ? "" : chunk.toLowerCase(Locale.ROOT);
        if (text.contains("indoor") || text.contains("museum") || text.contains("gallery")
                || text.contains("室内") || text.contains("博物馆") || text.contains("展览")) {
            return 1.0d;
        }
        if (text.contains("park") || text.contains("mountain") || text.contains("lake")
                || text.contains("户外") || text.contains("公园") || text.contains("山") || text.contains("湖")) {
            return 0.45d;
        }
        return 0.7d;
    }

    private String buildWeatherSuitability(Map<String, Object> weatherContext, String chunk) {
        double score = weatherScore(weatherContext, chunk);
        String summary = weatherContext == null ? "" : String.valueOf(weatherContext.getOrDefault("summary", ""));
        if (score >= 0.9d) {
            return "Weather-friendly: " + summary;
        }
        if (score <= 0.5d) {
            return "Use caution under current weather: " + summary;
        }
        return summary.isBlank() ? "Standard weather fit" : summary;
    }

    private String buildRouteSummary(PlanNextAttractionRequest request, String name,
                                     Double distanceKm, Integer travelTimeMin) {
        String origin = request == null || request.getCurrentPositionName() == null
                ? "current location"
                : request.getCurrentPositionName();
        List<String> details = new ArrayList<>();
        details.add(origin + " -> " + name);
        if (distanceKm != null) {
            details.add(String.format(Locale.ROOT, "%.1f km", distanceKm));
        }
        if (travelTimeMin != null) {
            details.add(travelTimeMin + " min");
        }
        return String.join(", ", details);
    }

    private List<String> buildExplanations(RagNameCandidate candidate,
                                           Double distanceKm,
                                           Integer travelTimeMin) {
        List<String> values = new ArrayList<>();
        values.add("Matched user intent from RAG context");
        if (distanceKm != null) {
            values.add(String.format(Locale.ROOT, "Physical distance about %.1f km", distanceKm));
        }
        if (travelTimeMin != null) {
            values.add("Estimated transfer about " + travelTimeMin + " min");
        }
        return values;
    }

    private List<String> buildHighlights(String chunk, String name) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        values.add(name);
        if (chunk != null) {
            for (String part : chunk.split("[,，、;；。\\s]+")) {
                String cleaned = cleanName(part);
                if (cleaned != null && cleaned.length() >= 2 && cleaned.length() <= 12 && !isNoiseName(cleaned)) {
                    values.add(cleaned);
                }
                if (values.size() >= 4) {
                    break;
                }
            }
        }
        return List.copyOf(values);
    }

    private Double numberToDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String normalizeKey(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private String buildKey(String taskUuid, int stepIndex, String scope, String name) {
        return (taskUuid == null ? "task" : taskUuid)
                + "-step" + stepIndex
                + "-" + scope
                + "-" + Math.abs((name == null ? "" : name).hashCode());
    }

    private double round(double value) {
        return Math.round(value * 1000.0d) / 1000.0d;
    }

    private record RagNameCandidate(String name, String chunk, int chunkRank) {}
    private record ScoredCandidate(LocationCandidateItem item, double score) {}
}
