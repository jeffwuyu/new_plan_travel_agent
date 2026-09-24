package com.travelagent.client.bailian;

import com.fasterxml.jackson.core.type.TypeReference;
import com.travelagent.util.JsonUtil;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class BailianImageClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    @Value("${dashscope.image.enabled:true}")
    private boolean enabled;

    @Value("${dashscope.image.api-key:}")
    private String apiKey;

    @Value("${dashscope.image.model:wan2.7-image-pro}")
    private String model;

    @Value("${dashscope.image.endpoint:https://dashscope.aliyuncs.com/api/v1/services/aigc/image-generation/generation}")
    private String endpoint;

    @Value("${dashscope.image.task-endpoint:https://dashscope.aliyuncs.com/api/v1/tasks}")
    private String taskEndpoint;

    @Value("${dashscope.image.size:2K}")
    private String size;

    @Value("${dashscope.image.n:1}")
    private int n;

    @Value("${dashscope.image.timeout-ms:180000}")
    private long timeoutMs;

    @Value("${dashscope.image.poll-initial-interval-ms:3000}")
    private long pollInitialIntervalMs;

    @Value("${dashscope.image.poll-max-interval-ms:10000}")
    private long pollMaxIntervalMs;

    @Autowired
    private OkHttpClient okHttpClient;

    @Autowired
    private JsonUtil jsonUtil;

    public String getModel() {
        return model;
    }

    public BailianImageResult submit(String imageUrl, String prompt) {
        ensureEnabled();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("input", Map.of("messages", List.of(Map.of(
                "role", "user",
                "content", List.of(
                        Map.of("image", imageUrl),
                        Map.of("text", prompt)
                )
        ))));
        payload.put("parameters", Map.of(
                "size", size,
                "n", n,
                "watermark", false
        ));

        Request request = new Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("X-DashScope-Async", "enable")
                .post(RequestBody.create(jsonUtil.toJson(payload), JSON))
                .build();

        Map<String, Object> root = executeJson(request);
        BailianImageResult result = new BailianImageResult();
        result.setRequestId(text(root.get("request_id")));
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) root.get("output");
        result.setTaskId(firstNonBlank(
                output == null ? null : output.get("task_id"),
                root.get("task_id")));
        result.setStatus(firstNonBlank(output == null ? null : output.get("task_status"), "submitted"));
        if (result.getTaskId() == null || result.getTaskId().isBlank()) {
            result.setErrorCode("BAILIAN_SUBMIT_FAILED");
            result.setErrorMessage("Missing task_id in Bailian response");
        }
        return result;
    }

    public BailianImageResult waitForResult(String taskId) {
        ensureEnabled();
        long deadline = System.currentTimeMillis() + timeoutMs;
        long interval = Math.max(500L, pollInitialIntervalMs);
        while (System.currentTimeMillis() < deadline) {
            BailianImageResult polled = poll(taskId);
            String status = polled.getStatus() == null ? "" : polled.getStatus().toUpperCase(Locale.ROOT);
            if (status.contains("SUCCEEDED") || status.contains("SUCCESS")) {
                return polled;
            }
            if (status.contains("FAILED") || status.contains("CANCELED") || status.contains("UNKNOWN")) {
                if (polled.getErrorCode() == null) {
                    polled.setErrorCode("BAILIAN_SUBMIT_FAILED");
                }
                return polled;
            }
            sleep(interval);
            interval = Math.min(Math.max(interval, pollInitialIntervalMs) * 2, pollMaxIntervalMs);
        }
        BailianImageResult timeout = new BailianImageResult();
        timeout.setTaskId(taskId);
        timeout.setStatus("timeout");
        timeout.setErrorCode("BAILIAN_TIMEOUT");
        timeout.setErrorMessage("Bailian image generation polling timed out");
        return timeout;
    }

    public byte[] downloadImage(String imageUrl) {
        Request request = new Request.Builder().url(imageUrl).get().build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IllegalStateException("Image download failed, http=" + response.code());
            }
            return response.body().bytes();
        } catch (IOException e) {
            throw new IllegalStateException("Image download failed: " + e.getMessage(), e);
        }
    }

    private BailianImageResult poll(String taskId) {
        Request request = new Request.Builder()
                .url(taskEndpoint.replaceAll("/$", "") + "/" + taskId)
                .addHeader("Authorization", "Bearer " + apiKey)
                .get()
                .build();
        Map<String, Object> root = executeJson(request);
        BailianImageResult result = new BailianImageResult();
        result.setRequestId(text(root.get("request_id")));
        result.setTaskId(taskId);
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) root.get("output");
        result.setStatus(firstNonBlank(output == null ? null : output.get("task_status"), root.get("task_status")));
        result.setImageUrl(resolveImageUrl(output, root));
        result.setErrorCode(firstNonBlank(root.get("code"), output == null ? null : output.get("code")));
        result.setErrorMessage(firstNonBlank(root.get("message"), output == null ? null : output.get("message")));
        return result;
    }

    private Map<String, Object> executeJson(Request request) {
        try (Response response = okHttpClient.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IllegalStateException("Bailian image API returned HTTP " + response.code() + ": " + body);
            }
            return jsonUtil.fromJson(body, new TypeReference<Map<String, Object>>() {});
        } catch (IOException e) {
            throw new IllegalStateException("Bailian image API request failed: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private String resolveImageUrl(Map<String, Object> output, Map<String, Object> root) {
        if (output != null) {
            Object direct = firstNonBlank(output.get("url"), output.get("image_url"));
            if (direct != null && !direct.toString().isBlank()) {
                return direct.toString();
            }
            Object resultsObj = output.get("results");
            if (resultsObj instanceof List<?> results && !results.isEmpty() && results.get(0) instanceof Map<?, ?> first) {
                return firstNonBlank(first.get("url"), first.get("image_url"), first.get("orig_prompt"));
            }
        }
        return firstNonBlank(root.get("url"), root.get("image_url"));
    }

    private void ensureEnabled() {
        if (!enabled) {
            throw new IllegalStateException("Bailian image generation is disabled");
        }
        if (apiKey == null || apiKey.isBlank() || apiKey.startsWith("your_")) {
            throw new IllegalStateException("Bailian image API key is not configured");
        }
    }

    private String firstNonBlank(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null && !value.toString().isBlank()) {
                return value.toString();
            }
        }
        return null;
    }

    private String text(Object value) {
        return value == null ? null : value.toString();
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while polling Bailian image task", e);
        }
    }
}
