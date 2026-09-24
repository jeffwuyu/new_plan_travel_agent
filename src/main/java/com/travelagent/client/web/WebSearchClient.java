package com.travelagent.client.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.travelagent.exception.AgentErrorCode;
import com.travelagent.exception.AgentException;
import com.travelagent.util.JsonUtil;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class WebSearchClient {

    @Value("${web-search.endpoint:}")
    private String endpoint;

    @Value("${web-search.api-key:}")
    private String apiKey;

    @Autowired
    private OkHttpClient okHttpClient;

    @Autowired
    private JsonUtil jsonUtil;

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> search(WebSearchQuery query) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new AgentException(AgentErrorCode.TOOL_WEB_SEARCH_ERROR,
                    "web-search.endpoint is not configured");
        }
        HttpUrl baseUrl = HttpUrl.parse(endpoint);
        if (baseUrl == null) {
            throw new AgentException(AgentErrorCode.TOOL_WEB_SEARCH_ERROR,
                    "Invalid web-search.endpoint: " + endpoint);
        }
        HttpUrl url = baseUrl.newBuilder()
                .addQueryParameter("q", buildQueryText(query))
                .addQueryParameter("top_k", String.valueOf(Math.max(1, query.getTopK())))
                .build();
        Request.Builder requestBuilder = new Request.Builder().url(url).get();
        if (apiKey != null && !apiKey.isBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer " + apiKey);
        }

        try (Response response = okHttpClient.newCall(requestBuilder.build()).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new AgentException(AgentErrorCode.TOOL_WEB_SEARCH_ERROR,
                        "Web search HTTP " + response.code() + ": " + body);
            }
            Map<String, Object> root = jsonUtil.fromJson(body, new TypeReference<Map<String, Object>>() {});
            Object results = root.get("results");
            if (results instanceof List<?> list) {
                return (List<Map<String, Object>>) list;
            }
            return List.of();
        } catch (AgentException e) {
            throw e;
        } catch (IOException e) {
            throw classifyException(e);
        }
    }

    private String buildQueryText(WebSearchQuery query) {
        if (query.getQueryText() != null && !query.getQueryText().isBlank()) {
            return query.getQueryText();
        }
        return String.join(" ",
                nullToEmpty(query.getCity()),
                nullToEmpty(query.getAttraction()),
                nullToEmpty(query.getDate()),
                nullToEmpty(query.getInfoType()),
                "官方 最新");
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private AgentException classifyException(Exception e) {
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        if (msg.contains("timeout") || msg.contains("timed out")
                || e.getCause() instanceof java.net.SocketTimeoutException) {
            return new AgentException(AgentErrorCode.TOOL_WEB_SEARCH_TIMEOUT,
                    "Web search request timed out: " + e.getMessage(), e);
        }
        return new AgentException(AgentErrorCode.TOOL_WEB_SEARCH_ERROR,
                "Web search error: " + e.getMessage(), e);
    }
}
