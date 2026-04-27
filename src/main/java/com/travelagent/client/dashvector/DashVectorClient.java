package com.travelagent.client.dashvector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.travelagent.exception.AgentErrorCode;
import com.travelagent.exception.AgentException;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 中文注释：客户端类，通过 DashVector REST API 执行向量 upsert、ANN 检索和删除操作。
 * 不引入额外 SDK，复用 LlmConfig 中已配置的 OkHttpClient Bean。
 */
@Component
public class DashVectorClient {

    private static final Logger log = LoggerFactory.getLogger(DashVectorClient.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    @Value("${dashvector.api-key}")
    private String apiKey;

    @Value("${dashvector.endpoint}")
    private String endpoint;

    @Value("${dashvector.collection}")
    private String collection;

    @Autowired
    private OkHttpClient okHttpClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Result record returned by {@link #search}.
     */
    public static class DashVectorResult {
        public final String id;
        public final float score;
        public final Map<String, String> fields;

        /**
         * 处理DashVectorResult。
         * @param id 主键ID
         * @param score s co re 参数
         * @param fields f ie ld s 参数
         */
        public DashVectorResult(String id, float score, Map<String, String> fields) {
            this.id = id;
            this.score = score;
            this.fields = fields;
        }
    }

    /**
     * 处理upsert。
     * @param id 主键ID
     * @param vector v ec to r 参数
     * @param fields f ie ld s 参数
     */
    public void upsert(String id, float[] vector, Map<String, String> fields) {
        try {
            ObjectNode doc = objectMapper.createObjectNode();
            doc.put("id", id);

            ArrayNode vecArr = doc.putArray("vector");
            for (float v : vector) {
                vecArr.add(v);
            }

            ObjectNode fieldsNode = objectMapper.createObjectNode();
            fields.forEach(fieldsNode::put);
            doc.set("fields", fieldsNode);

            ArrayNode docs = objectMapper.createArrayNode();
            docs.add(doc);

            ObjectNode body = objectMapper.createObjectNode();
            body.set("docs", docs);

            String url = endpoint + "/v1/collections/" + collection + "/docs";
            executePost(url, body.toString());
            log.debug("DashVector upsert OK: id={}", id);
        } catch (AgentException ae) {
            throw ae;
        } catch (Exception e) {
            throw classifyDashVectorException(e);
        }
    }

    /**
     * 处理search。
     * @param queryVector q ue ry Ve ct or 参数
     * @param topK t op K 参数
     * @param regionFilter r eg io nF il te r 参数
     * @return 返回处理后的列表结果。
     */
    public List<DashVectorResult> search(float[] queryVector, int topK, String regionFilter) {
        try {
            ObjectNode body = objectMapper.createObjectNode();

            ArrayNode vecArr = body.putArray("vector");
            for (float v : queryVector) {
                vecArr.add(v);
            }
            body.put("topk", topK);
            body.put("include_fields", objectMapper.createArrayNode().add("region").add("chunkText"));

            if (regionFilter != null && !regionFilter.isBlank()) {
                body.put("filter", "region = '" + regionFilter.replace("'", "\\'") + "'");
            }

            String url = endpoint + "/v1/collections/" + collection + "/query";
            String responseBody = executePost(url, body.toString());

            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode output = root.path("output");
            List<DashVectorResult> results = new ArrayList<>();
            if (output.isArray()) {
                for (JsonNode item : output) {
                    String id = item.path("id").asText();
                    float score = (float) item.path("score").asDouble();
                    Map<String, String> fields = new HashMap<>();
                    JsonNode f = item.path("fields");
                    f.fields().forEachRemaining(e -> fields.put(e.getKey(), e.getValue().asText()));
                    results.add(new DashVectorResult(id, score, fields));
                }
            }
            log.debug("DashVector search returned {} results (topK={})", results.size(), topK);
            return results;
        } catch (AgentException ae) {
            throw ae;
        } catch (Exception e) {
            throw classifyDashVectorException(e);
        }
    }

    /**
     * 处理delete。
     * @param ids 主键ID集合
     */
    public void delete(List<String> ids) {
        if (ids == null || ids.isEmpty()) return;
        try {
            ObjectNode body = objectMapper.createObjectNode();
            ArrayNode arr = body.putArray("ids");
            ids.forEach(arr::add);

            String url = endpoint + "/v1/collections/" + collection + "/docs";
            executeDelete(url, body.toString());
            log.debug("DashVector delete OK: ids={}", ids);
        } catch (AgentException ae) {
            throw ae;
        } catch (Exception e) {
            throw classifyDashVectorException(e);
        }
    }

    // -----------------------------------------------------------------------
    // HTTP helpers
    // -----------------------------------------------------------------------

    /**
     * 执行post。
     * @param url 请求地址
     * @param jsonBody j so nB od y 参数
     * @return 返回处理结果。
     */
    private String executePost(String url, String jsonBody) throws IOException {
        RequestBody rb = RequestBody.create(jsonBody, JSON);
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(rb)
                .build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new AgentException(AgentErrorCode.TOOL_DASHVECTOR_ERROR,
                        "DashVector HTTP " + response.code() + ": " + body);
            }
            return body;
        }
    }

    /**
     * 执行delete。
     * @param url 请求地址
     * @param jsonBody j so nB od y 参数
     */
    private void executeDelete(String url, String jsonBody) throws IOException {
        RequestBody rb = RequestBody.create(jsonBody, JSON);
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .delete(rb)
                .build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String body = response.body() != null ? response.body().string() : "";
                throw new AgentException(AgentErrorCode.TOOL_DASHVECTOR_ERROR,
                        "DashVector DELETE HTTP " + response.code() + ": " + body);
            }
        }
    }

    /**
     * 处理classifyDashVectorException。
     * @param e 异常对象
     * @return 返回处理结果。
     */
    private AgentException classifyDashVectorException(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        if (msg.contains("timeout") || msg.contains("timed out")
                || e.getCause() instanceof java.net.SocketTimeoutException) {
            return new AgentException(AgentErrorCode.TOOL_DASHVECTOR_TIMEOUT,
                    "DashVector request timed out: " + e.getMessage(), e);
        }
        return new AgentException(AgentErrorCode.TOOL_DASHVECTOR_ERROR,
                "DashVector error: " + e.getMessage(), e);
    }
}
