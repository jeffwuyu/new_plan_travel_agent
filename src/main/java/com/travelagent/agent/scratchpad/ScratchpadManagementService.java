package com.travelagent.agent.scratchpad;

import com.travelagent.agent.context.TaskCheckpoint;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class ScratchpadManagementService {

    public static final String TYPE_THOUGHT = "thought";
    public static final String TYPE_ACTION = "action";
    public static final String TYPE_OBSERVATION = "observation";
    public static final String TYPE_SUMMARY = "summary";

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(?i).*(password|passwd|secret|token|api[_-]?key|authorization|cookie|phone|mobile|id[_-]?card).*");

    private final ScratchpadManagementProperties properties;

    public ScratchpadManagementService(ScratchpadManagementProperties properties) {
        this.properties = properties;
    }

    public boolean isEnabled() {
        return properties != null && properties.isEnabled();
    }

    public void recordThought(TaskCheckpoint checkpoint, int stepIndex, String label, String content) {
        append(checkpoint, entry(TYPE_THOUGHT, stepIndex, label, Map.of(
                "content", limit(content, 800)
        )));
    }

    public void recordAction(TaskCheckpoint checkpoint, int stepIndex, String toolName, Map<String, Object> arguments) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("toolName", toolName);
        payload.put("arguments", sanitize(arguments));
        append(checkpoint, entry(TYPE_ACTION, stepIndex, "tool_call", payload));
    }

    public void recordObservation(TaskCheckpoint checkpoint,
                                  int stepIndex,
                                  String toolName,
                                  Map<String, Object> observation,
                                  String status) {
        Map<String, Object> compact = compactObservation(observation);
        compact.put("toolName", toolName);
        compact.put("status", status);
        compact.put("fingerprint", observationFingerprint(compact));
        append(checkpoint, entry(TYPE_OBSERVATION, stepIndex, "tool_result", compact));
    }

    public void compact(TaskCheckpoint checkpoint) {
        if (!isEnabled() || checkpoint == null) {
            return;
        }
        List<Map<String, Object>> scratchpad = checkpoint.getReactScratchpad();
        if (scratchpad == null || scratchpad.isEmpty()) {
            checkpoint.setReactScratchpad(new ArrayList<>());
            return;
        }

        List<Map<String, Object>> deduped = dedupeObservations(scratchpad);
        Set<Integer> recentSteps = collectRecentSteps(deduped, properties.getKeepRecentSteps());
        if (recentSteps.isEmpty()) {
            checkpoint.setReactScratchpad(tail(deduped, properties.getKeepRecentSteps() * 3));
            return;
        }

        List<Map<String, Object>> summaries = new ArrayList<>();
        List<Map<String, Object>> older = new ArrayList<>();
        List<Map<String, Object>> recent = new ArrayList<>();
        for (Map<String, Object> entry : deduped) {
            String type = stringValue(entry.get("type"));
            Integer stepIndex = intValue(entry.get("stepIndex"));
            if (TYPE_SUMMARY.equals(type)) {
                summaries.add(entry);
            } else if (stepIndex != null && !recentSteps.contains(stepIndex)) {
                older.add(entry);
            } else {
                recent.add(entry);
            }
        }

        List<Map<String, Object>> managed = new ArrayList<>();
        if (!older.isEmpty()) {
            managed.add(buildSummaryEntry(summaries, older));
            checkpoint.setScratchpadTrimmedAt(deduped.size());
        } else if (!summaries.isEmpty()) {
            managed.add(summaries.get(summaries.size() - 1));
        }
        managed.addAll(recent);
        checkpoint.setReactScratchpad(managed);
    }

    private void append(TaskCheckpoint checkpoint, Map<String, Object> entry) {
        if (!isEnabled() || checkpoint == null || entry == null) {
            return;
        }
        List<Map<String, Object>> scratchpad = checkpoint.getReactScratchpad();
        if (scratchpad == null) {
            scratchpad = new ArrayList<>();
            checkpoint.setReactScratchpad(scratchpad);
        }
        scratchpad.add(entry);
    }

    private Map<String, Object> entry(String type, int stepIndex, String label, Map<String, Object> payload) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", type);
        entry.put("stepIndex", Math.max(0, stepIndex));
        entry.put("label", label);
        entry.put("createdAt", Instant.now().toString());
        entry.putAll(payload == null ? Map.of() : payload);
        return entry;
    }

    private List<Map<String, Object>> dedupeObservations(List<Map<String, Object>> entries) {
        List<Map<String, Object>> reversed = new ArrayList<>(entries);
        java.util.Collections.reverse(reversed);
        Set<String> seenObservationHashes = new LinkedHashSet<>();
        List<Map<String, Object>> kept = new ArrayList<>();
        for (Map<String, Object> entry : reversed) {
            if (entry == null) {
                continue;
            }
            String type = stringValue(entry.get("type"));
            if (TYPE_OBSERVATION.equals(type)) {
                String hash = firstNonBlank(
                        stringValue(entry.get("fingerprint")),
                        fingerprint(entry));
                if (!seenObservationHashes.add(hash)) {
                    continue;
                }
            }
            kept.add(new LinkedHashMap<>(entry));
        }
        java.util.Collections.reverse(kept);
        return kept;
    }

    private Set<Integer> collectRecentSteps(List<Map<String, Object>> entries, int maxSteps) {
        Set<Integer> steps = new LinkedHashSet<>();
        for (int i = entries.size() - 1; i >= 0 && steps.size() < maxSteps; i--) {
            Map<String, Object> entry = entries.get(i);
            if (TYPE_SUMMARY.equals(stringValue(entry.get("type")))) {
                continue;
            }
            Integer stepIndex = intValue(entry.get("stepIndex"));
            if (stepIndex != null) {
                steps.add(stepIndex);
            }
        }
        return steps;
    }

    private Map<String, Object> buildSummaryEntry(List<Map<String, Object>> previousSummaries,
                                                  List<Map<String, Object>> older) {
        List<Integer> stepIndexes = older.stream()
                .map(entry -> intValue(entry.get("stepIndex")))
                .filter(value -> value != null)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();

        StringBuilder summary = new StringBuilder();
        if (!previousSummaries.isEmpty()) {
            summary.append("Previous scratchpad summary: ")
                    .append(limit(stringValue(previousSummaries.get(previousSummaries.size() - 1).get("content")), 600))
                    .append("\n");
        }
        summary.append("Compressed ReAct scratchpad for older steps");
        if (!stepIndexes.isEmpty()) {
            summary.append(" ").append(stepIndexes);
        }
        summary.append(":\n");

        for (Integer stepIndex : stepIndexes) {
            summary.append("- step ").append(stepIndex).append(": ");
            List<String> parts = older.stream()
                    .filter(entry -> stepIndex.equals(intValue(entry.get("stepIndex"))))
                    .map(this::summarizeEntry)
                    .filter(value -> !value.isBlank())
                    .toList();
            summary.append(limit(String.join("; ", parts), 500)).append("\n");
        }
        if (stepIndexes.isEmpty()) {
            summary.append(limit(older.stream().map(this::summarizeEntry).toList().toString(), 1_000));
        }

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", TYPE_SUMMARY);
        entry.put("label", "scratchpad_compaction");
        entry.put("createdAt", Instant.now().toString());
        entry.put("content", limit(summary.toString(), properties.getMaxSummaryChars()));
        entry.put("coveredStepIndexes", stepIndexes);
        entry.put("compressedEntryCount", older.size());
        return entry;
    }

    private String summarizeEntry(Map<String, Object> entry) {
        String type = stringValue(entry.get("type"));
        if (TYPE_THOUGHT.equals(type)) {
            return "Thought=" + limit(firstNonBlank(stringValue(entry.get("content")), stringValue(entry.get("label"))), 180);
        }
        if (TYPE_ACTION.equals(type)) {
            return "Action=" + stringValue(entry.get("toolName")) + "(" + limit(stringValue(entry.get("arguments")), 180) + ")";
        }
        if (TYPE_OBSERVATION.equals(type)) {
            return "Observation=" + stringValue(entry.get("toolName")) + ":" + limit(observationSummary(entry), 220);
        }
        return limit(stringValue(entry), 180);
    }

    private Map<String, Object> compactObservation(Map<String, Object> observation) {
        Map<String, Object> compact = new LinkedHashMap<>();
        Map<String, Object> safe = observation == null ? Map.of() : observation;
        copyIfPresent(safe, compact, "summary");
        copyIfPresent(safe, compact, "message");
        copyIfPresent(safe, compact, "source");
        copyIfPresent(safe, compact, "provider");
        copyIfPresent(safe, compact, "queryTime");
        copyIfPresent(safe, compact, "available");
        copyIfPresent(safe, compact, "weather");
        copyIfPresent(safe, compact, "temperature");
        copyIfPresent(safe, compact, "durationMin");
        copyIfPresent(safe, compact, "distanceKm");
        copyIfPresent(safe, compact, "name");
        copyIfPresent(safe, compact, "address");
        copyIfPresent(safe, compact, "lat");
        copyIfPresent(safe, compact, "lng");
        if (compact.isEmpty()) {
            compact.put("summary", limit(stringValue(sanitize(safe)), properties.getMaxObservationChars()));
        } else {
            compact.replaceAll((key, value) -> value instanceof String text
                    ? limit(text, properties.getMaxObservationChars())
                    : sanitize(value));
        }
        return compact;
    }

    @SuppressWarnings("unchecked")
    private Object sanitize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            map.entrySet().stream()
                    .sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                    .forEach(entry -> {
                        String key = String.valueOf(entry.getKey());
                        sanitized.put(key, SENSITIVE_KEY.matcher(key).matches()
                                ? "[REDACTED]"
                                : sanitize(entry.getValue()));
                    });
            return sanitized;
        }
        if (value instanceof List<?> list) {
            List<Object> sanitized = new ArrayList<>();
            for (Object item : list) {
                sanitized.add(sanitize(item));
            }
            return sanitized;
        }
        if (value instanceof String text) {
            return limit(text, properties.getMaxObservationChars());
        }
        return value;
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key) && source.get(key) != null) {
            target.put(key, sanitize(source.get(key)));
        }
    }

    private String observationSummary(Map<String, Object> observation) {
        return firstNonBlank(
                stringValue(observation.get("summary")),
                stringValue(observation.get("message")),
                stringValue(observation.get("weather")),
                stringValue(observation));
    }

    private List<Map<String, Object>> tail(List<Map<String, Object>> entries, int maxEntries) {
        if (entries == null || entries.size() <= maxEntries) {
            return entries == null ? new ArrayList<>() : new ArrayList<>(entries);
        }
        return new ArrayList<>(entries.subList(entries.size() - maxEntries, entries.size()));
    }

    private String fingerprint(Object value) {
        String normalized = normalize(stringValue(sanitize(value)));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < Math.min(bytes.length, 8); i++) {
                builder.append(String.format("%02x", bytes[i]));
            }
            return builder.toString();
        } catch (Exception e) {
            return Integer.toHexString(normalized.hashCode());
        }
    }

    private String observationFingerprint(Map<String, Object> observation) {
        Map<String, Object> stable = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : observation.entrySet()) {
            String key = entry.getKey();
            if ("queryTime".equals(key) || "queriedAt".equals(key) || "createdAt".equals(key) || "fingerprint".equals(key)) {
                continue;
            }
            stable.put(key, entry.getValue());
        }
        return fingerprint(stable);
    }

    private String normalize(String value) {
        return WHITESPACE.matcher(value == null ? "" : value.trim().toLowerCase(Locale.ROOT)).replaceAll(" ");
    }

    private Integer intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String limit(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(0, maxChars - 15)) + "...[truncated]";
    }
}
