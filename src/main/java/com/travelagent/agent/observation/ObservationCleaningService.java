package com.travelagent.agent.observation;

import com.travelagent.agent.prompt.PromptAssembly;
import com.travelagent.agent.prompt.PromptSectionType;
import com.travelagent.client.dashscope.DashscopeLlmClient;
import com.travelagent.client.dashscope.LlmCallResult;
import com.travelagent.service.llm.LlmUsageAccountingService;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class ObservationCleaningService {

    public static final String PIPELINE_NAME = "cleaner_extract_summary_structured_data";
    private static final String CALL_TYPE = "observation_summary";
    private static final Pattern HTML_PATTERN = Pattern.compile(
            "(?is)<!doctype\\s+html|<\\s*html\\b|<\\s*body\\b|<\\s*(script|style|div|span|p|article|section)\\b");
    private static final Pattern TAG_PATTERN = Pattern.compile("(?is)<[^>]+>");
    private static final Pattern CSS_RULE_PATTERN = Pattern.compile("^\\s*[.#]?[a-zA-Z0-9_-]+[^{]{0,80}\\{\\s*$");
    private static final Pattern CSS_DECL_PATTERN = Pattern.compile("^\\s*[a-zA-Z-]{2,40}\\s*:\\s*[^;]+;?\\s*$");
    private static final Pattern JS_LINE_PATTERN = Pattern.compile(
            "^\\s*(function\\b|const\\b|let\\b|var\\b|import\\b|export\\b|window\\.|document\\.|\\}\\)?;?\\s*$).*");

    private final ObservationCleaningProperties properties;

    @Autowired(required = false)
    private DashscopeLlmClient llmClient;

    @Autowired(required = false)
    private LlmUsageAccountingService usageAccountingService;

    public ObservationCleaningService(ObservationCleaningProperties properties) {
        this.properties = properties;
    }

    public boolean isEnabled() {
        return properties != null && properties.isEnabled();
    }

    public CleanedObservation cleanWebObservation(Map<String, Object> rawOutput,
                                                  Long taskId,
                                                  Long userId,
                                                  String idempotencyKey) {
        Map<String, Object> raw = rawOutput == null ? Map.of() : rawOutput;
        if (!isEnabled()) {
            Map<String, Object> copied = copyMap(raw);
            copied.put("cleaningMetadata", Map.of("pipeline", PIPELINE_NAME, "enabled", false));
            return new CleanedObservation(stringValue(copied.get("summary")), List.of(), List.of(), Map.of(), Map.of(), copied);
        }

        int rawChars = serializedChars(raw);
        List<String> warnings = new ArrayList<>();
        List<Map<String, Object>> evidences = extractEvidences(raw, warnings);
        String cleanedCorpus = buildCleanedCorpus(raw, evidences);
        boolean inputTruncated = false;
        if (cleanedCorpus.length() > properties.getMaxInputChars()) {
            cleanedCorpus = cleanedCorpus.substring(0, properties.getMaxInputChars());
            inputTruncated = true;
            warnings.add("input_truncated");
        }

        int cleanedCharsBeforeLimit = cleanedCorpus.length();
        String summary = summarize(cleanedCorpus, raw, taskId, userId, idempotencyKey, warnings);
        SummaryLimit limitedSummary = enforceSummaryLimit(summary, cleanedCorpus.length());
        if (limitedSummary.truncated()) {
            warnings.add("summary_truncated");
        }

        Map<String, Object> output = copyCoreContract(raw);
        output.put("available", raw.getOrDefault("available", !evidences.isEmpty()));
        output.put("summary", limitedSummary.summary());
        output.put("sources", evidences);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("rawChars", rawChars);
        stats.put("cleanedChars", Math.min(cleanedCharsBeforeLimit, properties.getMaxCleanedChars()));
        stats.put("evidenceCount", evidences.size());
        stats.put("generatedAt", Instant.now().toString());

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("pipeline", PIPELINE_NAME);
        metadata.put("rawChars", rawChars);
        metadata.put("cleanedChars", Math.min(cleanedCharsBeforeLimit, properties.getMaxCleanedChars()));
        metadata.put("summaryChars", limitedSummary.summary().length());
        metadata.put("summaryRatioLimit", properties.getMaxSummaryRatio());
        metadata.put("summaryTruncated", limitedSummary.truncated());
        metadata.put("inputTruncated", inputTruncated);
        metadata.put("llmSummaryUsed", !warnings.contains("llm_summary_failed") && llmClient != null && !cleanedCorpus.isBlank());
        metadata.put("rawArtifactStored", false);
        metadata.put("warnings", warnings);
        output.put("cleaningMetadata", metadata);

        return new CleanedObservation(limitedSummary.summary(), evidences, warnings, stats, metadata, output);
    }

    public Map<String, Object> markRawArtifactStored(Map<String, Object> cleanedOutput, boolean stored) {
        Map<String, Object> output = copyMap(cleanedOutput);
        Object metadataValue = output.get("cleaningMetadata");
        Map<String, Object> metadata = metadataValue instanceof Map<?, ?> map
                ? copyMapFromUnknown(map)
                : new LinkedHashMap<>();
        metadata.put("rawArtifactStored", stored);
        output.put("cleaningMetadata", metadata);
        return output;
    }

    public boolean shouldStoreRawArtifact() {
        return isEnabled() && properties.isRawArtifactEnabled();
    }

    private List<Map<String, Object>> extractEvidences(Map<String, Object> raw, List<String> warnings) {
        List<Map<String, Object>> evidences = new ArrayList<>();
        Object sourcesValue = raw.get("sources");
        if (sourcesValue instanceof Collection<?> sources) {
            int index = 0;
            for (Object source : sources) {
                if (index >= properties.getMaxEvidenceItems()) {
                    warnings.add("evidence_truncated");
                    break;
                }
                if (source instanceof Map<?, ?> map) {
                    evidences.add(cleanEvidence(copyMapFromUnknown(map)));
                    index++;
                }
            }
        }
        if (evidences.isEmpty()) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("title", firstNonBlank(raw.get("title"), raw.get("query")));
            fallback.put("url", stringValue(raw.get("url")));
            fallback.put("source", firstNonBlank(raw.get("source"), "web-search"));
            fallback.put("snippet", cleanText(firstNonBlank(raw.get("summary"), raw.get("snippet"), raw.get("content"))));
            fallback.put("cleanText", limit(cleanText(firstNonBlank(raw.get("summary"), raw.get("content"), raw.get("body"))),
                    properties.getMaxCleanedChars()));
            fallback.put("facts", factsFromText(stringValue(fallback.get("cleanText"))));
            evidences.add(fallback);
        }
        return evidences;
    }

    private Map<String, Object> cleanEvidence(Map<String, Object> source) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        String title = cleanInline(firstNonBlank(source.get("title"), source.get("name")));
        String url = cleanInline(firstNonBlank(source.get("url"), source.get("sourceUrl"), source.get("link")));
        String sourceName = cleanInline(firstNonBlank(source.get("source"), source.get("sourceName"), source.get("provider")));
        String text = firstNonBlank(
                source.get("content"),
                source.get("body"),
                source.get("html"),
                source.get("text"),
                source.get("summary"),
                source.get("snippet"));
        String cleanText = limit(cleanText(text), properties.getMaxCleanedChars());
        String snippet = cleanInline(firstNonBlank(source.get("snippet"), source.get("summary"), cleanText));
        evidence.put("title", title);
        evidence.put("url", url);
        evidence.put("domain", domain(url));
        evidence.put("source", sourceName);
        evidence.put("sourceType", firstNonBlank(source.get("sourceType"), "general_web"));
        evidence.put("trustScore", numberOrDefault(source.get("trustScore"), 0));
        evidence.put("snippet", limit(snippet, 500));
        evidence.put("cleanText", cleanText);
        evidence.put("facts", factsFromText(cleanText));
        return evidence;
    }

    private Map<String, Object> copyCoreContract(Map<String, Object> raw) {
        Map<String, Object> output = new LinkedHashMap<>();
        putIfPresent(output, raw, "available");
        putIfPresent(output, raw, "query");
        putIfPresent(output, raw, "attraction");
        putIfPresent(output, raw, "city");
        putIfPresent(output, raw, "date");
        putIfPresent(output, raw, "infoType");
        putIfPresent(output, raw, "conflictDetected");
        putIfPresent(output, raw, "uncertaintyNote");
        putIfPresent(output, raw, "source");
        putIfPresent(output, raw, "queryTime");
        return output;
    }

    private void putIfPresent(Map<String, Object> target, Map<String, Object> source, String key) {
        if (source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }

    private String buildCleanedCorpus(Map<String, Object> raw, List<Map<String, Object>> evidences) {
        StringBuilder builder = new StringBuilder();
        appendLine(builder, cleanText(firstNonBlank(raw.get("summary"), raw.get("content"), raw.get("body"))));
        for (Map<String, Object> evidence : evidences) {
            appendLine(builder, stringValue(evidence.get("title")));
            appendLine(builder, stringValue(evidence.get("snippet")));
            appendLine(builder, stringValue(evidence.get("cleanText")));
        }
        return normalizeWhitespace(builder.toString());
    }

    private String summarize(String cleanedCorpus,
                             Map<String, Object> raw,
                             Long taskId,
                             Long userId,
                             String idempotencyKey,
                             List<String> warnings) {
        if (cleanedCorpus == null || cleanedCorpus.isBlank()) {
            return fallbackSummary(raw, "");
        }
        if (llmClient == null) {
            return fallbackSummary(raw, cleanedCorpus);
        }
        int cap = summaryCap(cleanedCorpus.length());
        String userGoal = "Summarize this web observation only.";
        PromptAssembly prompt = PromptAssembly.create()
                .add(PromptSectionType.SYSTEM, "You clean and summarize raw web observations. Return plain text only.")
                .add(PromptSectionType.POLICY, "Do not use any external or prior context. Remove HTML/CSS/JS noise. Keep factual claims, dates, prices, availability, warnings, and source nuance.")
                .add(PromptSectionType.CURRENT_GOAL, userGoal)
                .add(PromptSectionType.OBSERVATION, limit(cleanedCorpus, properties.getMaxInputChars()))
                .add(PromptSectionType.OUTPUT_FORMAT, "Return plain text only. The summary must be no longer than " + cap
                        + " characters and no more than 40% of the input length.");
        String key = (idempotencyKey == null || idempotencyKey.isBlank() ? "observation" : idempotencyKey)
                + "-observation-summary-" + hash(cleanedCorpus);
        try {
            LlmCallResult result = llmClient.callWithUsage(
                    taskId,
                    userId,
                    CALL_TYPE,
                    prompt,
                    List.of(),
                    userGoal,
                    key);
            if (usageAccountingService != null) {
                usageAccountingService.recordUsageLenient(taskId, userId, result.totalTokens());
            }
            return cleanInline(result.content());
        } catch (Exception e) {
            warnings.add("llm_summary_failed");
            return fallbackSummary(raw, cleanedCorpus);
        }
    }

    private SummaryLimit enforceSummaryLimit(String summary, int cleanedLength) {
        String value = cleanInline(summary);
        int cap = summaryCap(cleanedLength);
        if (value.length() <= cap) {
            return new SummaryLimit(value, false);
        }
        return new SummaryLimit(limit(value, cap), true);
    }

    private int summaryCap(int cleanedLength) {
        int ratioCap = Math.max(1, (int) Math.floor(Math.max(1, cleanedLength) * properties.getMaxSummaryRatio()));
        return Math.max(1, Math.min(properties.getMaxSummaryChars(), ratioCap));
    }

    private String fallbackSummary(Map<String, Object> raw, String cleanedCorpus) {
        String base = firstNonBlank(raw.get("summary"), raw.get("snippet"), cleanedCorpus);
        if (base.isBlank()) {
            return "No usable web observation content.";
        }
        return firstSentences(cleanText(base), 3);
    }

    private String cleanText(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String limited = limit(raw, properties.getMaxInputChars());
        String text;
        if (looksLikeHtml(limited)) {
            Document doc = Jsoup.parse(limited);
            doc.select("script,style,noscript,svg,canvas,iframe,object,embed,link,meta,nav,footer,header,form,aside").remove();
            for (Element element : doc.select("[style], [onclick], [onload], [onerror]")) {
                element.removeAttr("style");
                element.removeAttr("onclick");
                element.removeAttr("onload");
                element.removeAttr("onerror");
            }
            text = doc.text();
        } else {
            text = TAG_PATTERN.matcher(limited).replaceAll(" ");
        }
        return normalizeWhitespace(removeCodeNoise(text));
    }

    private boolean looksLikeHtml(String value) {
        return HTML_PATTERN.matcher(value).find() || TAG_PATTERN.matcher(value).find();
    }

    private String removeCodeNoise(String text) {
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        StringBuilder builder = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (CSS_RULE_PATTERN.matcher(trimmed).matches()
                    || CSS_DECL_PATTERN.matcher(trimmed).matches()
                    || JS_LINE_PATTERN.matcher(trimmed).matches()
                    || trimmed.startsWith("{") || trimmed.startsWith("}") || trimmed.endsWith("};")) {
                continue;
            }
            appendLine(builder, trimmed);
        }
        return builder.toString();
    }

    private List<String> factsFromText(String text) {
        String normalized = normalizeWhitespace(text);
        if (normalized.isBlank()) {
            return List.of();
        }
        List<String> facts = new ArrayList<>();
        for (String sentence : normalized.split("(?<=[.!?。！？])\\s+")) {
            String fact = sentence.trim();
            if (!fact.isBlank()) {
                facts.add(limit(fact, 240));
            }
            if (facts.size() >= 3) {
                break;
            }
        }
        if (facts.isEmpty()) {
            facts.add(limit(normalized, 240));
        }
        return facts;
    }

    private String firstSentences(String text, int maxSentences) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String[] sentences = normalizeWhitespace(text).split("(?<=[.!?。！？])\\s+");
        StringBuilder builder = new StringBuilder();
        for (String sentence : sentences) {
            appendLine(builder, sentence.trim());
            if (builder.toString().split("\\n").length >= maxSentences) {
                break;
            }
        }
        return normalizeWhitespace(builder.toString());
    }

    private void appendLine(StringBuilder builder, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append('\n');
        }
        builder.append(value.trim());
    }

    private String cleanInline(String value) {
        return normalizeWhitespace(cleanText(value));
    }

    private String normalizeWhitespace(String value) {
        return value == null ? "" : value
                .replace('\u00A0', ' ')
                .replaceAll("[\\t\\x0B\\f]+", " ")
                .replaceAll(" {2,}", " ")
                .replaceAll("\\n{2,}", "\n")
                .trim();
    }

    private String limit(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(0, maxChars)).trim();
    }

    private String firstNonBlank(Object... values) {
        if (values == null) {
            return "";
        }
        for (Object value : values) {
            String text = stringValue(value);
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private Number numberOrDefault(Object value, Number fallback) {
        if (value instanceof Number number) {
            return number;
        }
        if (value != null) {
            try {
                return Double.parseDouble(value.toString());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private String domain(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            String host = URI.create(url).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return "";
        }
    }

    private int serializedChars(Object value) {
        return value == null ? 0 : value.toString().length();
    }

    private Map<String, Object> copyMap(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (source != null) {
            source.forEach((key, value) -> copy.put(key, value));
        }
        return copy;
    }

    private Map<String, Object> copyMapFromUnknown(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null) {
                copy.put(String.valueOf(key), value);
            }
        });
        return copy;
    }

    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < Math.min(8, bytes.length); i++) {
                builder.append(String.format("%02x", bytes[i]));
            }
            return builder.toString();
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private record SummaryLimit(String summary, boolean truncated) {
    }
}
