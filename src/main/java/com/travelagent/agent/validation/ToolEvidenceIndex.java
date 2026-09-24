package com.travelagent.agent.validation;

import com.travelagent.agent.context.CompletedStep;
import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.agent.tools.GeocodeTool;
import com.travelagent.agent.tools.ToolResultValidator;
import com.travelagent.agent.tools.TrafficTimeTool;
import com.travelagent.agent.tools.WeatherTool;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ToolEvidenceIndex {

    public enum ClaimType {
        PHONE, URL, PRICE, ADDRESS, TIME, WEATHER, TRAFFIC, BOOKING
    }

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    private final Map<ClaimType, StringBuilder> evidenceByType = new EnumMap<>(ClaimType.class);
    private final StringBuilder allToolEvidence = new StringBuilder();

    public static ToolEvidenceIndex from(TaskCheckpoint checkpoint) {
        ToolEvidenceIndex index = new ToolEvidenceIndex();
        index.collectSystemEvidence(checkpoint);
        if (checkpoint == null) {
            return index;
        }
        for (CompletedStep step : checkpoint.getCompletedSteps() == null
                ? List.<CompletedStep>of()
                : checkpoint.getCompletedSteps()) {
            index.collectCompletedStep(step);
        }
        if (checkpoint.getToolResults() != null) {
            checkpoint.getToolResults().forEach((toolName, result) -> index.collectToolResult(toolName, result));
        }
        return index;
    }

    public boolean supports(ClaimType type, String claim) {
        if (claim == null || claim.isBlank()) {
            return true;
        }
        String normalizedClaim = normalize(claim);
        String typedCorpus = normalize(evidenceByType.getOrDefault(type, new StringBuilder()).toString());
        String allCorpus = normalize(allToolEvidence.toString());
        if (typedCorpus.contains(normalizedClaim) || allCorpus.contains(normalizedClaim)) {
            return true;
        }
        String claimDigits = digitsOnly(claim);
        if (!claimDigits.isBlank() && (type == ClaimType.PHONE || type == ClaimType.PRICE
                || type == ClaimType.TIME || type == ClaimType.TRAFFIC)) {
            String typedDigits = digitsOnly(typedCorpus);
            return typedDigits.contains(claimDigits);
        }
        return false;
    }

    private void collectSystemEvidence(TaskCheckpoint checkpoint) {
        if (checkpoint == null || checkpoint.getCompletedSteps() == null) {
            return;
        }
        for (CompletedStep step : checkpoint.getCompletedSteps()) {
            if (step == null) {
                continue;
            }
            addSystem(ClaimType.TIME, step.getPlannedStartTime() == null ? null : step.getPlannedStartTime().format(TIME_FORMATTER));
            addSystem(ClaimType.TIME, step.getPlannedEndTime() == null ? null : step.getPlannedEndTime().format(TIME_FORMATTER));
            if (step.getPlannedStartTime() != null && step.getPlannedEndTime() != null) {
                addSystem(ClaimType.TIME, step.getPlannedStartTime().format(TIME_FORMATTER)
                        + "-" + step.getPlannedEndTime().format(TIME_FORMATTER));
            }
            addSystem(ClaimType.TIME, step.getEstimatedVisitDurationMin());
            addSystem(ClaimType.TRAFFIC, step.getTrafficTimeFromPrevMin());
            addSystem(ClaimType.TRAFFIC, step.getTravelTimeToDestinationMin());
        }
    }

    private void collectCompletedStep(CompletedStep step) {
        if (step == null || step.getToolCallResults() == null) {
            return;
        }
        step.getToolCallResults().forEach(this::collectToolResult);
    }

    @SuppressWarnings("unchecked")
    private void collectToolResult(String toolName, Object rawResult) {
        if (!(rawResult instanceof Map<?, ?> rawMap)) {
            return;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        rawMap.forEach((key, value) -> {
            if (key != null) {
                result.put(String.valueOf(key), value);
            }
        });
        if (result.containsKey("output") && result.get("output") instanceof Map<?, ?> outputMap) {
            Map<String, Object> nested = new LinkedHashMap<>();
            outputMap.forEach((key, value) -> {
                if (key != null) {
                    nested.put(String.valueOf(key), value);
                }
            });
            collectToolResult(toolName, nested);
            return;
        }
        if (!isConsumable(result)) {
            return;
        }
        collectValue(toolName, "$", result);
    }

    private void collectValue(String toolName, String path, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null || ToolResultValidator.METADATA_KEY.equals(String.valueOf(entry.getKey()))) {
                    continue;
                }
                collectValue(toolName, path + "." + entry.getKey(), entry.getValue());
            }
            return;
        }
        if (value instanceof Collection<?> collection) {
            int index = 0;
            for (Object item : collection) {
                collectValue(toolName, path + "[" + index + "]", item);
                index++;
            }
            return;
        }

        String text = String.valueOf(value).trim();
        if (text.isBlank()) {
            return;
        }
        allToolEvidence.append(' ').append(text);
        List<ClaimType> types = resolveTypes(toolName, path, text);
        for (ClaimType type : types) {
            append(type, text);
        }
        appendCommonUnitVariants(path, text, types);
    }

    private void appendCommonUnitVariants(String path, String text, List<ClaimType> types) {
        String lowerPath = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (types.contains(ClaimType.WEATHER) && lowerPath.contains("temperature") && text.matches("-?\\d+(?:\\.\\d+)?")) {
            append(ClaimType.WEATHER, text + "℃");
            append(ClaimType.WEATHER, text + "°C");
        }
        if (types.contains(ClaimType.TRAFFIC)
                && (lowerPath.contains("duration") || lowerPath.contains("time"))
                && text.matches("\\d+(?:\\.\\d+)?")) {
            append(ClaimType.TRAFFIC, text + "分钟");
            append(ClaimType.TRAFFIC, text + "min");
        }
        if (types.contains(ClaimType.TRAFFIC)
                && lowerPath.contains("distance")
                && text.matches("\\d+(?:\\.\\d+)?")) {
            append(ClaimType.TRAFFIC, text + "米");
            append(ClaimType.TRAFFIC, text + "m");
        }
    }

    private List<ClaimType> resolveTypes(String toolName, String path, String text) {
        String lowerPath = path.toLowerCase(Locale.ROOT);
        String lowerTool = toolName == null ? "" : toolName.toLowerCase(Locale.ROOT);
        String lowerText = text.toLowerCase(Locale.ROOT);
        List<ClaimType> types = new ArrayList<>();

        if (lowerPath.contains("phone") || lowerPath.contains("tel") || lowerPath.contains("mobile")
                || lowerPath.contains("contact") || lowerPath.contains("电话") || lowerPath.contains("手机")) {
            types.add(ClaimType.PHONE);
        }
        if (lowerPath.contains("url") || lowerPath.contains("link") || lowerText.contains("http://")
                || lowerText.contains("https://") || lowerText.contains("www.")) {
            types.add(ClaimType.URL);
        }
        if (lowerPath.contains("price") || lowerPath.contains("fee") || lowerPath.contains("cost")
                || lowerPath.contains("ticket") || lowerPath.contains("票") || lowerText.contains("元")
                || lowerText.contains("cny") || lowerText.contains("rmb") || lowerText.contains("¥")
                || lowerText.contains("￥")) {
            types.add(ClaimType.PRICE);
        }
        if (lowerPath.contains("address") || lowerPath.contains("formattedaddress")
                || lowerPath.contains("location") || lowerPath.contains("地址")) {
            types.add(ClaimType.ADDRESS);
        }
        if (lowerPath.contains("time") || lowerPath.contains("hour") || lowerPath.contains("slot")
                || lowerPath.contains("date") || lowerPath.contains("open") || lowerPath.contains("时间")) {
            types.add(ClaimType.TIME);
        }
        if (WeatherTool.NAME.equals(lowerTool) || lowerPath.contains("weather")
                || lowerPath.contains("temperature") || lowerPath.contains("wind")
                || lowerPath.contains("humidity") || lowerPath.contains("天气")) {
            types.add(ClaimType.WEATHER);
        }
        if (TrafficTimeTool.NAME.equals(lowerTool) || lowerPath.contains("duration")
                || lowerPath.contains("distance") || lowerPath.contains("route")
                || lowerPath.contains("traffic") || lowerPath.contains("交通")) {
            types.add(ClaimType.TRAFFIC);
        }
        if (lowerTool.contains("booking") || lowerPath.contains("booking") || lowerPath.contains("reservation")
                || lowerPath.contains("availability") || lowerPath.contains("reservable")
                || lowerPath.contains("预约") || lowerPath.contains("余票")) {
            types.add(ClaimType.BOOKING);
        }
        if (GeocodeTool.NAME.equals(lowerTool)) {
            types.add(ClaimType.ADDRESS);
        }
        return types;
    }

    private boolean isConsumable(Map<String, Object> result) {
        if (Boolean.FALSE.equals(result.get("available"))) {
            return false;
        }
        Object validation = result.get(ToolResultValidator.METADATA_KEY);
        if (validation instanceof Map<?, ?> validationMap && Boolean.FALSE.equals(validationMap.get("valid"))) {
            return false;
        }
        return true;
    }

    private void addSystem(ClaimType type, Object value) {
        if (value != null) {
            append(type, String.valueOf(value));
        }
    }

    private void append(ClaimType type, String text) {
        evidenceByType.computeIfAbsent(type, ignored -> new StringBuilder()).append(' ').append(text);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "")
                .replace('：', ':')
                .replace('－', '-')
                .replace('—', '-')
                .replace('~', '-')
                .replace('至', '-')
                .trim();
    }

    private static String digitsOnly(String value) {
        return value == null ? "" : value.replaceAll("\\D+", "");
    }
}
