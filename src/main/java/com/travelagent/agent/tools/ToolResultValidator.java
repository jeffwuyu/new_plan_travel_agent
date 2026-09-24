package com.travelagent.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelagent.agent.safety.SensitiveInfoGuard;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class ToolResultValidator {

    public static final String METADATA_KEY = "resultValidation";

    private static final Pattern HTML_PATTERN = Pattern.compile(
            "(?is)<!doctype\\s+html|<\\s*html\\b|<\\s*body\\b|<\\s*(script|iframe|object|embed)\\b");
    private static final Pattern TAG_HEAVY_PATTERN = Pattern.compile("(?is)<\\s*(div|span|p|table|section|article|main|head)\\b[^>]*>");
    private static final Pattern MOJIBAKE_PATTERN = Pattern.compile("(锟.|\\uFFFD|Ã.|Â.|å.|æ.|ç.)");

    private final ToolResultValidationProperties properties;
    private final SensitiveInfoGuard sensitiveInfoGuard;
    private final ObjectMapper objectMapper;

    public ToolResultValidator(ToolResultValidationProperties properties,
                               SensitiveInfoGuard sensitiveInfoGuard,
                               ObjectMapper objectMapper) {
        this.properties = properties;
        this.sensitiveInfoGuard = sensitiveInfoGuard;
        this.objectMapper = objectMapper;
    }

    public ToolResultValidationResult validate(String toolName, Map<String, Object> output) {
        Instant checkedAt = Instant.now();
        if (properties != null && !properties.isEnabled()) {
            Map<String, Object> copied = copyMap(output);
            ToolResultValidationResult result = new ToolResultValidationResult(true, false, checkedAt, List.of(), copied);
            copied.put(METADATA_KEY, result.toMetadata());
            return new ToolResultValidationResult(true, false, checkedAt, List.of(), copied);
        }

        Map<String, Object> safeOutput = copyMap(output);
        List<ToolResultValidationIssue> issues = new ArrayList<>();
        boolean sanitized = sanitizeSensitiveValues(safeOutput, issues);
        boolean highRiskSensitive = issues.stream()
                .anyMatch(issue -> "sensitive_content".equals(issue.code()) && "ERROR".equals(issue.severity()));

        collectGenericIssues(safeOutput, issues);
        collectToolSpecificIssues(toolName, safeOutput, issues);

        boolean valid = issues.stream().noneMatch(issue -> "ERROR".equals(issue.severity()));
        boolean storageUnsafe = highRiskSensitive || issues.stream()
                .anyMatch(issue -> "serialized_too_long".equals(issue.code()));

        Map<String, Object> finalOutput = storageUnsafe
                ? minimalInvalidOutput(toolName, issues)
                : safeOutput;
        if (!valid) {
            finalOutput.put("available", false);
        }

        ToolResultValidationResult result = new ToolResultValidationResult(valid, sanitized || storageUnsafe, checkedAt, issues, finalOutput);
        finalOutput.put(METADATA_KEY, result.toMetadata());
        return new ToolResultValidationResult(valid, sanitized || storageUnsafe, checkedAt, issues, finalOutput);
    }

    private void collectGenericIssues(Map<String, Object> output, List<ToolResultValidationIssue> issues) {
        if (output == null || output.isEmpty()) {
            issues.add(issue("empty_result", "Tool result is empty.", "ERROR", "$", Map.of()));
            return;
        }
        if (Boolean.FALSE.equals(output.get("available"))) {
            issues.add(issue("unavailable_result", "Tool result declares available=false.", "ERROR", "$.available", Map.of()));
        }
        String serialized = serialize(output);
        if (serialized.length() > properties.getMaxSerializedChars()) {
            issues.add(issue("serialized_too_long", "Tool result is too long to store safely.", "ERROR", "$",
                    Map.of("length", serialized.length(), "max", properties.getMaxSerializedChars())));
        }
        inspectValue(output, "$", issues);
    }

    private void inspectValue(Object value, String path, List<ToolResultValidationIssue> issues) {
        if (value == null) {
            return;
        }
        if (value instanceof String text) {
            inspectString(text, path, issues);
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null && METADATA_KEY.equals(String.valueOf(entry.getKey()))) {
                    continue;
                }
                inspectValue(entry.getValue(), path + "." + entry.getKey(), issues);
            }
            return;
        }
        if (value instanceof Collection<?> collection) {
            if (collection.size() > properties.getMaxCollectionItems()) {
                issues.add(issue("collection_too_long", "Tool result collection is too long.", "ERROR", path,
                        Map.of("size", collection.size(), "max", properties.getMaxCollectionItems())));
            }
            int index = 0;
            for (Object item : collection) {
                inspectValue(item, path + "[" + index + "]", issues);
                index++;
            }
        }
    }

    private void inspectString(String text, String path, List<ToolResultValidationIssue> issues) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (text.length() > properties.getMaxStringChars()) {
            issues.add(issue("string_too_long", "Tool result string is too long.", "ERROR", path,
                    Map.of("length", text.length(), "max", properties.getMaxStringChars())));
        }
        if (looksLikeHtml(text)) {
            issues.add(issue("html_content", "Tool result contains HTML instead of structured data.", "ERROR", path,
                    Map.of("sample", sample(text))));
        }
        if (looksGarbled(text)) {
            issues.add(issue("garbled_text", "Tool result appears garbled.", "ERROR", path,
                    Map.of("sample", sample(text))));
        }
    }

    private boolean sanitizeSensitiveValues(Object value, List<ToolResultValidationIssue> issues) {
        return sanitizeSensitiveValues(value, "$", issues);
    }

    @SuppressWarnings("unchecked")
    private boolean sanitizeSensitiveValues(Object value, String path, List<ToolResultValidationIssue> issues) {
        boolean sanitized = false;
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> mutable = (Map<Object, Object>) map;
            for (Map.Entry<Object, Object> entry : mutable.entrySet()) {
                Object child = entry.getValue();
                if (child instanceof String text) {
                    SensitiveInfoGuard.ScanResult scan = sensitiveInfoGuard.scan(text);
                    if (scan.manualActionRequired()) {
                        boolean highRisk = scan.highRisk();
                        issues.add(issue("sensitive_content",
                                highRisk ? "Tool result contains high-risk sensitive content." : "Tool result contains low-risk sensitive content and was sanitized.",
                                highRisk ? "ERROR" : "WARN",
                                path + "." + entry.getKey(),
                                Map.of("findings", scan.findings().stream().map(this::findingToMap).toList())));
                        mutable.put(entry.getKey(), sensitiveInfoGuard.sanitize(text, true));
                        sanitized = true;
                    }
                } else {
                    sanitized = sanitizeSensitiveValues(child, path + "." + entry.getKey(), issues) || sanitized;
                }
            }
            return sanitized;
        }
        if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                Object child = list.get(i);
                if (child instanceof String text) {
                    SensitiveInfoGuard.ScanResult scan = sensitiveInfoGuard.scan(text);
                    if (scan.manualActionRequired()) {
                        boolean highRisk = scan.highRisk();
                        issues.add(issue("sensitive_content",
                                highRisk ? "Tool result contains high-risk sensitive content." : "Tool result contains low-risk sensitive content and was sanitized.",
                                highRisk ? "ERROR" : "WARN",
                                path + "[" + i + "]",
                                Map.of("findings", scan.findings().stream().map(this::findingToMap).toList())));
                        if (list instanceof java.util.ArrayList<?> arrayList) {
                            ((java.util.ArrayList<Object>) arrayList).set(i, sensitiveInfoGuard.sanitize(text, true));
                            sanitized = true;
                        }
                    }
                } else {
                    sanitized = sanitizeSensitiveValues(child, path + "[" + i + "]", issues) || sanitized;
                }
            }
        }
        return sanitized;
    }

    private void collectToolSpecificIssues(String toolName, Map<String, Object> output, List<ToolResultValidationIssue> issues) {
        String normalized = toolName == null ? "" : toolName.toLowerCase(Locale.ROOT);
        switch (normalized) {
            case GeocodeTool.NAME -> requireCoordinates(output, issues);
            case WeatherTool.NAME -> requireWeather(output, issues);
            case TrafficTimeTool.NAME -> requirePositiveNumber(output, "durationMin", issues);
            case WebSearchTool.NAME, BookingQueryTool.NAME -> requireNonEmptyCollection(output, "sources", issues);
            case RagTool.NAME -> {
                if (isEmptyCollection(output.get("matches")) && isEmptyCollection(output.get("chunks"))) {
                    issues.add(issue("missing_core_data", "RAG result must contain matches or chunks.", "ERROR", "$",
                            Map.of("requiredAny", List.of("matches", "chunks"))));
                }
            }
            case AmapMapTool.NAME -> requireNonEmptyCollection(output, "poiResults", issues);
            default -> {
            }
        }
    }

    private void requireCoordinates(Map<String, Object> output, List<ToolResultValidationIssue> issues) {
        if (!(output.get("lat") instanceof Number) || !(output.get("lng") instanceof Number)) {
            issues.add(issue("missing_core_data", "Geocode result must contain numeric lat/lng.", "ERROR", "$",
                    Map.of("required", List.of("lat", "lng"))));
        }
    }

    private void requireWeather(Map<String, Object> output, List<ToolResultValidationIssue> issues) {
        String weather = stringValue(output.get("weather"));
        if ((weather.isBlank() || "unknown".equalsIgnoreCase(weather)) && isEmptyCollection(output.get("forecastDays"))) {
            issues.add(issue("missing_core_data", "Weather result must contain weather text or forecastDays.", "ERROR", "$",
                    Map.of("requiredAny", List.of("weather", "forecastDays"))));
        }
    }

    private void requirePositiveNumber(Map<String, Object> output, String field, List<ToolResultValidationIssue> issues) {
        Object value = output.get(field);
        if (!(value instanceof Number number) || number.doubleValue() <= 0) {
            issues.add(issue("missing_core_data", "Tool result must contain a positive " + field + ".", "ERROR", "$." + field,
                    Map.of("required", field)));
        }
    }

    private void requireNonEmptyCollection(Map<String, Object> output, String field, List<ToolResultValidationIssue> issues) {
        if (isEmptyCollection(output.get(field))) {
            issues.add(issue("missing_core_data", "Tool result must contain non-empty " + field + ".", "ERROR", "$." + field,
                    Map.of("required", field)));
        }
    }

    private boolean isEmptyCollection(Object value) {
        return !(value instanceof Collection<?> collection) || collection.isEmpty();
    }

    private boolean looksLikeHtml(String text) {
        return HTML_PATTERN.matcher(text).find() || TAG_HEAVY_PATTERN.matcher(text).find();
    }

    private boolean looksGarbled(String text) {
        int suspicious = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\uFFFD' || (Character.isISOControl(ch) && ch != '\n' && ch != '\r' && ch != '\t')) {
                suspicious++;
            }
        }
        return suspicious > 0 || MOJIBAKE_PATTERN.matcher(text).find();
    }

    private Map<String, Object> minimalInvalidOutput(String toolName, List<ToolResultValidationIssue> issues) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("available", false);
        output.put("message", "Tool result failed validation for " + toolName);
        output.put("validationIssueCodes", issues.stream().map(ToolResultValidationIssue::code).distinct().toList());
        return output;
    }

    private Map<String, Object> copyMap(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (source == null) {
            return copy;
        }
        source.forEach((key, value) -> copy.put(key, copyValue(value)));
        return copy;
    }

    @SuppressWarnings("unchecked")
    private Object copyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, child) -> {
                if (key != null) {
                    copy.put(String.valueOf(key), copyValue(child));
                }
            });
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(copyValue(item));
            }
            return copy;
        }
        return value;
    }

    private ToolResultValidationIssue issue(String code, String message, String severity, String path, Map<String, Object> details) {
        return new ToolResultValidationIssue(code, message, severity, path, details);
    }

    private Map<String, Object> findingToMap(SensitiveInfoGuard.Finding finding) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", finding.type());
        map.put("maskedValue", finding.maskedValue());
        map.put("highRisk", finding.highRisk());
        return map;
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private String sample(String value) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120);
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString().trim();
    }
}
