package com.travelagent.agent.tools;

import com.travelagent.aop.IdempotentTool;
import com.travelagent.service.rag.RagSearchResult;
import com.travelagent.service.rag.RagService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class RagTool implements AgentTool {

    public static final String NAME = "rag";

    @Autowired
    private RagService ragService;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getSource() {
        return "rag:hybrid";
    }

    @Override
    public boolean isRealtime() {
        return false;
    }

    @Override
    @IdempotentTool(ttl = "24h")
    public Map<String, Object> execute(Map<String, Object> arguments, String idempotencyKey) {
        Map<String, Object> safeArguments = arguments == null ? Map.of() : arguments;
        String query = resolveQuery(safeArguments);
        String region = firstString(safeArguments, "region", "destination", "city");
        int topK = intArg(safeArguments.get("topK"), 5);
        List<RagSearchResult> matches = ragService.hybridSearch(query, region, topK);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", !matches.isEmpty());
        result.put("query", query);
        result.put("region", region);
        result.put("topK", topK);
        result.put("recallCount", matches.size());
        result.put("chunks", matches.stream().map(RagSearchResult::chunkText).toList());
        result.put("matches", matches.stream().map(this::toMap).toList());
        result.put("sourceTypes", matches.stream()
                .map(RagSearchResult::sourceType)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList());
        result.put("source", getSource());
        result.put("queryTime", Instant.now().toString());
        return result;
    }

    private String resolveQuery(Map<String, Object> arguments) {
        String query = firstString(arguments, "query", "queryText", "rawText");
        if (query != null && !query.isBlank()) {
            return query;
        }
        StringBuilder builder = new StringBuilder();
        append(builder, firstString(arguments, "destination", "region", "city"));
        Object preferences = arguments.get("attractionPreference");
        if (preferences instanceof List<?> list) {
            for (Object item : list) {
                append(builder, item == null ? null : item.toString());
            }
        } else {
            append(builder, preferences == null ? null : preferences.toString());
        }
        append(builder, firstString(arguments, "foodPreference"));
        append(builder, firstString(arguments, "travelPace"));
        String text = builder.toString().trim();
        return text.isBlank() ? "旅游 行程 景点 推荐" : text;
    }

    private void append(StringBuilder builder, String value) {
        if (value != null && !value.isBlank()) {
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(value.trim());
        }
    }

    private Map<String, Object> toMap(RagSearchResult result) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("chunkId", result.chunkId());
        item.put("documentId", result.documentId());
        item.put("chunkText", result.chunkText());
        item.put("title", result.title());
        item.put("region", result.region());
        item.put("sourceType", result.sourceType());
        item.put("sourceName", result.sourceName());
        item.put("sourceUrl", result.sourceUrl());
        item.put("vectorScore", result.vectorScore());
        item.put("bm25Score", result.bm25Score());
        item.put("freshnessScore", result.freshnessScore());
        item.put("finalScore", result.finalScore());
        item.put("rankReason", result.rankReason());
        return item;
    }

    private String firstString(Map<String, Object> arguments, String... keys) {
        for (String key : keys) {
            Object value = arguments.get(key);
            if (value != null && !value.toString().isBlank()) {
                return value.toString().trim();
            }
        }
        return null;
    }

    private int intArg(Object value, int fallback) {
        if (value instanceof Number number) {
            return Math.max(1, number.intValue());
        }
        if (value != null) {
            try {
                return Math.max(1, Integer.parseInt(value.toString()));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
