package com.travelagent.agent.tools;

import com.travelagent.aop.IdempotentTool;
import com.travelagent.client.web.WebSearchClient;
import com.travelagent.client.web.WebSearchQuery;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class WebSearchTool implements AgentTool {

    public static final String NAME = "web_search";

    private static final List<String> TRUSTED_HINTS = List.of(
            ".gov.cn", "gov.cn", "mct.gov.cn", "12306.cn", "amap.com", "ctrip.com",
            "trip.com", "meituan.com", "dianping.com", "景区官网", "官方", "文化和旅游局"
    );
    private static final List<String> CONFLICT_HINTS = List.of(
            "关闭", "闭园", "暂停", "限流", "预约", "售罄", "调整", "维修", "临时"
    );

    @Autowired
    private WebSearchClient webSearchClient;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getSource() {
        return "web-search";
    }

    @Override
    @IdempotentTool(ttl = "6h")
    public Map<String, Object> execute(Map<String, Object> arguments, String idempotencyKey) {
        WebSearchQuery query = buildQuery(arguments);
        List<Map<String, Object>> rawResults = webSearchClient.search(query);
        List<Map<String, Object>> ranked = rankAndNormalize(rawResults);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", !ranked.isEmpty());
        result.put("query", queryText(query));
        result.put("attraction", query.getAttraction());
        result.put("city", query.getCity());
        result.put("date", query.getDate());
        result.put("infoType", query.getInfoType());
        result.put("summary", summarize(query, ranked));
        result.put("sources", ranked);
        result.put("conflictDetected", hasConflict(ranked));
        result.put("uncertaintyNote", buildUncertaintyNote(ranked));
        result.put("source", getSource());
        result.put("queryTime", Instant.now().toString());
        return result;
    }

    private WebSearchQuery buildQuery(Map<String, Object> arguments) {
        return WebSearchQuery.builder()
                .attraction(stringArg(arguments, "attraction", "attractionName", "poiName", "scenicSpot"))
                .city(stringArg(arguments, "city", "region"))
                .date(stringArg(arguments, "date", "travelDate"))
                .infoType(stringArg(arguments, "infoType", "informationType", "type"))
                .queryText(stringArg(arguments, "query", "queryText"))
                .topK(intArg(arguments.get("topK"), 5))
                .build();
    }

    private List<Map<String, Object>> rankAndNormalize(List<Map<String, Object>> rawResults) {
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Map<String, Object> raw : rawResults == null ? List.<Map<String, Object>>of() : rawResults) {
            Map<String, Object> item = new LinkedHashMap<>(raw);
            int trustScore = trustScore(item);
            item.put("trustScore", trustScore);
            item.put("trustedSource", trustScore >= 70);
            item.putIfAbsent("sourceType", trustScore >= 90 ? "official" : trustScore >= 70 ? "high_trust" : "general_web");
            normalized.add(item);
        }
        normalized.sort(Comparator
                .comparingInt((Map<String, Object> item) -> ((Number) item.getOrDefault("trustScore", 0)).intValue())
                .reversed());
        return normalized;
    }

    private int trustScore(Map<String, Object> item) {
        String text = (stringValue(item.get("url")) + " " + stringValue(item.get("source")) + " "
                + stringValue(item.get("title"))).toLowerCase(Locale.ROOT);
        if (text.contains("官方") || text.contains(".gov.cn") || text.contains("gov.cn")) {
            return 95;
        }
        for (String hint : TRUSTED_HINTS) {
            if (text.contains(hint.toLowerCase(Locale.ROOT))) {
                return 80;
            }
        }
        return 50;
    }

    private boolean hasConflict(List<Map<String, Object>> results) {
        boolean hasOpenOrAvailable = false;
        boolean hasRestricted = false;
        for (Map<String, Object> item : results) {
            String text = (stringValue(item.get("title")) + " " + stringValue(item.get("snippet")) + " "
                    + stringValue(item.get("summary"))).toLowerCase(Locale.ROOT);
            if (text.contains("开放") || text.contains("可预约") || text.contains("available") || text.contains("open")) {
                hasOpenOrAvailable = true;
            }
            if (CONFLICT_HINTS.stream().anyMatch(text::contains) || text.contains("closed") || text.contains("unavailable")) {
                hasRestricted = true;
            }
        }
        return hasOpenOrAvailable && hasRestricted;
    }

    private String summarize(WebSearchQuery query, List<Map<String, Object>> ranked) {
        if (ranked.isEmpty()) {
            return "No web result available for " + queryText(query);
        }
        Object snippet = ranked.get(0).get("snippet");
        if (snippet == null) {
            snippet = ranked.get(0).get("summary");
        }
        return snippet == null ? String.valueOf(ranked.get(0).getOrDefault("title", queryText(query))) : String.valueOf(snippet);
    }

    private String buildUncertaintyNote(List<Map<String, Object>> ranked) {
        if (ranked.isEmpty()) {
            return "实时 Web 信息不可用，建议出行前确认官方渠道。";
        }
        if (hasConflict(ranked)) {
            return "不同来源可能存在冲突，已优先展示官方或高可信来源，出行前请再次确认官方信息。";
        }
        return "实时信息可能变化，出行前建议再次确认官方信息。";
    }

    private String queryText(WebSearchQuery query) {
        if (query.getQueryText() != null && !query.getQueryText().isBlank()) {
            return query.getQueryText();
        }
        return String.join(" ",
                nullToEmpty(query.getCity()),
                nullToEmpty(query.getAttraction()),
                nullToEmpty(query.getDate()),
                nullToEmpty(query.getInfoType())).trim();
    }

    private String stringArg(Map<String, Object> arguments, String... keys) {
        if (arguments == null) {
            return null;
        }
        for (String key : keys) {
            Object value = arguments.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value).trim();
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
                return Math.max(1, Integer.parseInt(String.valueOf(value)));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
