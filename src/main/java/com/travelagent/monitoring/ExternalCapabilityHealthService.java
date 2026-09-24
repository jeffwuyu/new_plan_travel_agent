package com.travelagent.monitoring;

import com.travelagent.client.oss.OssClient;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ExternalCapabilityHealthService {

    private final Environment environment;
    private final StringRedisTemplate redisTemplate;
    private final DataSource dataSource;
    @Autowired(required = false) private OkHttpClient okHttpClient;
    @Autowired(required = false) private OssClient ossClient;
    @Value("${external.health.live-probe-enabled:false}")
    private boolean liveProbeEnabled;
    @Value("${external.health.circuit-breaker.enabled:true}")
    private boolean circuitBreakerEnabled = true;
    @Value("${external.health.circuit-breaker.failure-threshold:3}")
    private int circuitBreakerFailureThreshold = 3;
    @Value("${external.health.circuit-breaker.cooldown-seconds:60}")
    private long circuitBreakerCooldownSeconds = 60L;
    private final Map<String, ProbeCircuitState> probeCircuitStates = new ConcurrentHashMap<>();

    public ExternalCapabilityHealthService(Environment environment,
                                           StringRedisTemplate redisTemplate,
                                           DataSource dataSource) {
        this.environment = environment;
        this.redisTemplate = redisTemplate;
        this.dataSource = dataSource;
    }

    public List<ExternalCapabilityHealth> getHealthMatrix() {
        List<ExternalCapabilityHealth> values = new ArrayList<>();
        values.add(amapHealth());
        values.add(ossHealth());
        values.add(bailianImageHealth());
        values.add(ctripHealth());
        values.add(webSearchHealth());
        values.add(redisHealth());
        values.add(postgresPgvectorHealth());
        values.add(dashVectorHealth());
        return values;
    }

    private ExternalCapabilityHealth amapHealth() {
        boolean configured = hasText("amap.api-key");
        if (!configured || !liveProbeEnabled) {
            return configOnly("amap", configured, "missing amap.api-key");
        }
        String baseUrl = string("amap.geocode-url", "https://restapi.amap.com/v3/geocode/geo");
        HttpUrl parsed = HttpUrl.parse(baseUrl);
        if (parsed == null) {
            return liveProbeResult("amap", true, false, "invalid amap.geocode-url", "live_probe");
        }
        HttpUrl url = parsed.newBuilder()
                .addQueryParameter("key", string("amap.api-key", ""))
                .addQueryParameter("address", "北京")
                .build();
        return guardedLiveProbe("amap", () -> httpGetProbe("amap", url.toString(), null, "live_probe"));
    }

    private ExternalCapabilityHealth ossHealth() {
        boolean configured = hasText("oss.access-key-id") && hasText("oss.access-key-secret")
                && hasText("oss.bucket-name");
        if (!configured || !liveProbeEnabled) {
            return configOnly("oss", configured, "missing OSS credentials or bucket");
        }
        String checkedAt = Instant.now().toString();
        if (ossClient == null) {
            return new ExternalCapabilityHealth("oss", true, false, true,
                    "OSS client bean is missing", checkedAt, "live_probe");
        }
        try {
            return guardedLiveProbe("oss", () -> {
                boolean reachable = ossClient.bucketExists();
                return new ExternalCapabilityHealth("oss", true, reachable, !reachable,
                        reachable ? null : "OSS bucket does not exist or is not accessible",
                        checkedAt, "live_probe");
            });
        } catch (Exception e) {
            return new ExternalCapabilityHealth("oss", true, false, true, e.getMessage(), checkedAt, "live_probe");
        }
    }

    private ExternalCapabilityHealth webSearchHealth() {
        boolean configured = hasText("web-search.endpoint") && hasText("web-search.api-key");
        if (!configured || !liveProbeEnabled) {
            return configOnly("web_search", configured, "missing web-search endpoint or api-key");
        }
        HttpUrl parsed = HttpUrl.parse(string("web-search.endpoint", ""));
        if (parsed == null) {
            return liveProbeResult("web_search", true, false, "invalid web-search.endpoint", "live_probe");
        }
        HttpUrl url = parsed.newBuilder()
                .addQueryParameter("q", "travel health check")
                .addQueryParameter("top_k", "1")
                .build();
        return guardedLiveProbe("web_search", () -> httpGetProbe("web_search", url.toString(), authHeader("web-search.api-key"), "live_probe"));
    }

    private ExternalCapabilityHealth redisHealth() {
        String checkedAt = Instant.now().toString();
        try {
            if (redisTemplate.getConnectionFactory() == null) {
                return new ExternalCapabilityHealth("redis", false, false, true,
                        "Redis connection factory is missing", checkedAt, "ping");
            }
            String pong;
            try (RedisConnection connection = redisTemplate.getConnectionFactory().getConnection()) {
                pong = connection.ping();
            }
            boolean reachable = "PONG".equalsIgnoreCase(pong);
            return new ExternalCapabilityHealth("redis", true, reachable, !reachable,
                    reachable ? null : "Redis ping did not return PONG", checkedAt, "ping");
        } catch (Exception e) {
            return new ExternalCapabilityHealth("redis", true, false, true, e.getMessage(), checkedAt, "ping");
        }
    }

    private ExternalCapabilityHealth postgresPgvectorHealth() {
        String checkedAt = Instant.now().toString();
        String backend = string("rag.vector-backend", "postgres");
        boolean required = "postgres".equalsIgnoreCase(backend)
                || "postgres_with_dashvector_fallback".equalsIgnoreCase(backend);
        String ragUrl = string("rag.postgres.url", "");
        String mainUrl = string("spring.datasource.url", "");
        boolean dedicatedConfigured = ragUrl.toLowerCase().contains("postgresql");
        boolean mainDatasourceConfigured = mainUrl.toLowerCase().contains("postgresql");
        boolean configured = required && (dedicatedConfigured || mainDatasourceConfigured);
        if (!required) {
            return new ExternalCapabilityHealth("postgres_pgvector", false, false, false,
                    "not required by rag.vector-backend=" + backend, checkedAt, "disabled");
        }
        if (!configured) {
            return new ExternalCapabilityHealth("postgres_pgvector", false, false, true,
                    "rag.vector-backend requires PostgreSQL, but rag.postgres.url is not configured", checkedAt, "postgres_extension");
        }
        if (dedicatedConfigured) {
            return checkPgvectorWithDriverManager(ragUrl, checkedAt);
        }
        try (Connection ignored = dataSource.getConnection()) {
            JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
            Integer exists = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM pg_extension WHERE extname = 'vector'", Integer.class);
            boolean reachable = exists != null && exists > 0;
            return new ExternalCapabilityHealth("postgres_pgvector", true, reachable, !reachable,
                    reachable ? null : "pgvector extension is not installed", checkedAt, "postgres_extension");
        } catch (Exception e) {
            return new ExternalCapabilityHealth("postgres_pgvector", true, false, true, e.getMessage(), checkedAt, "postgres_extension");
        }
    }

    private ExternalCapabilityHealth checkPgvectorWithDriverManager(String url, String checkedAt) {
        try (Connection connection = DriverManager.getConnection(
                url,
                string("rag.postgres.username", ""),
                string("rag.postgres.password", ""));
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM pg_extension WHERE extname = 'vector'")) {
            boolean reachable = resultSet.next() && resultSet.getInt(1) > 0;
            return new ExternalCapabilityHealth("postgres_pgvector", true, reachable, !reachable,
                    reachable ? null : "pgvector extension is not installed", checkedAt, "postgres_extension");
        } catch (Exception e) {
            return new ExternalCapabilityHealth("postgres_pgvector", true, false, true, e.getMessage(), checkedAt, "postgres_extension");
        }
    }

    private ExternalCapabilityHealth dashVectorHealth() {
        String backend = string("rag.vector-backend", "postgres");
        boolean required = "dashvector".equalsIgnoreCase(backend)
                || "postgres_with_dashvector_fallback".equalsIgnoreCase(backend);
        boolean configured = hasText("dashvector.api-key") && hasText("dashvector.endpoint")
                && hasText("dashvector.collection");
        if (!required) {
            return new ExternalCapabilityHealth("dashvector", configured, false, false,
                    "optional for rag.vector-backend=" + backend, Instant.now().toString(), "config_only");
        }
        if (!configured || !liveProbeEnabled) {
            return configOnly("dashvector", configured, "missing dashvector api-key, endpoint or collection");
        }
        String endpoint = string("dashvector.endpoint", "");
        String collection = string("dashvector.collection", "");
        if (endpoint.isBlank() || collection.isBlank()) {
            return liveProbeResult("dashvector", true, false,
                    "missing dashvector endpoint or collection", "live_probe");
        }
        String url = endpoint.replaceAll("/$", "") + "/v1/collections/" + collection + "/query";
        String payload = "{\"vector\":[0.0],\"topk\":1}";
        return guardedLiveProbe("dashvector", () -> httpPostProbe("dashvector", url, payload, authHeader("dashvector.api-key")));
    }

    private ExternalCapabilityHealth bailianImageHealth() {
        if (!bool("dashscope.image.enabled", true)) {
            return new ExternalCapabilityHealth("bailian_image", false, false, false,
                    "disabled by dashscope.image.enabled=false", Instant.now().toString(), "disabled");
        }
        boolean configured = hasText("dashscope.image.api-key") && hasText("dashscope.image.endpoint");
        if (!configured || !liveProbeEnabled) {
            return configOnly("bailian_image", configured,
                    "missing dashscope.image.api-key or dashscope.image.endpoint");
        }
        return guardedLiveProbe("bailian_image", () -> httpGetProbe("bailian_image",
                string("dashscope.image.endpoint", ""),
                authHeader("dashscope.image.api-key"),
                "live_probe"));
    }

    private ExternalCapabilityHealth ctripHealth() {
        boolean configured = hasText("ctrip.api.base-url") && hasText("ctrip.api.app-id")
                && hasText("ctrip.api.secret");
        if (!configured || !liveProbeEnabled) {
            return configOnly("ctrip", configured, "missing ctrip api config");
        }
        String baseUrl = string("ctrip.api.base-url", "");
        HttpUrl parsed = HttpUrl.parse(baseUrl);
        if (parsed == null) {
            return liveProbeResult("ctrip", true, false, "invalid ctrip.api.base-url", "live_probe");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("appId", string("ctrip.api.app-id", ""));
        payload.put("cityName", "Beijing");
        payload.put("checkInDate", "2099-01-01");
        payload.put("checkOutDate", "2099-01-02");
        payload.put("adultCount", 1);
        payload.put("roomCount", 1);
        payload.put("signature", string("ctrip.api.secret", ""));
        return guardedLiveProbe("ctrip", () -> httpPostProbe("ctrip", baseUrl, JsonSupportHolder.toJson(payload), null));
    }

    private ExternalCapabilityHealth configOnly(String name, boolean configured, String missingMessage) {
        return new ExternalCapabilityHealth(name, configured, false, !configured,
                configured ? "configuration present; live API probe not executed" : missingMessage,
                Instant.now().toString(), "config_only");
    }

    private ExternalCapabilityHealth httpGetProbe(String name, String url, Map<String, String> headers, String checkType) {
        if (okHttpClient == null) {
            return liveProbeResult(name, true, false, "OkHttpClient bean is missing", checkType);
        }
        try {
            HttpUrl parsed = HttpUrl.parse(url);
            if (parsed == null) {
                return liveProbeResult(name, true, false, "invalid url", checkType);
            }
            Request.Builder builder = new Request.Builder().url(parsed).get();
            addHeaders(builder, headers);
            try (Response response = okHttpClient.newCall(builder.build()).execute()) {
                return toProbeResult(name, response, checkType);
            }
        } catch (Exception e) {
            return liveProbeResult(name, true, false, e.getMessage(), checkType);
        }
    }

    private ExternalCapabilityHealth httpPostProbe(String name, String url, String jsonPayload, Map<String, String> headers) {
        if (okHttpClient == null) {
            return liveProbeResult(name, true, false, "OkHttpClient bean is missing", "live_probe");
        }
        try {
            HttpUrl parsed = HttpUrl.parse(url);
            if (parsed == null) {
                return liveProbeResult(name, true, false, "invalid url", "live_probe");
            }
            Request.Builder builder = new Request.Builder()
                    .url(parsed)
                    .post(okhttp3.RequestBody.create(jsonPayload, okhttp3.MediaType.get("application/json; charset=utf-8")));
            addHeaders(builder, headers);
            try (Response response = okHttpClient.newCall(builder.build()).execute()) {
                return toProbeResult(name, response, "live_probe");
            }
        } catch (Exception e) {
            return liveProbeResult(name, true, false, e.getMessage(), "live_probe");
        }
    }

    private ExternalCapabilityHealth toProbeResult(String name, Response response, String checkType) {
        boolean reachable = response.code() >= 200 && response.code() < 400;
        return liveProbeResult(name, true, reachable,
                reachable ? null : "HTTP " + response.code(), checkType);
    }

    private Map<String, String> authHeader(String key) {
        String token = string(key, "");
        if (token.isBlank()) {
            return null;
        }
        return Map.of("Authorization", "Bearer " + token);
    }

    private void addHeaders(Request.Builder builder, Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return;
        }
        headers.forEach((key, value) -> {
            if (value != null && !value.isBlank()) {
                builder.addHeader(key, value);
            }
        });
    }

    private ExternalCapabilityHealth liveProbeResult(String name,
                                                     boolean configured,
                                                     boolean reachable,
                                                     String error,
                                                     String checkType) {
        return new ExternalCapabilityHealth(name, configured, reachable, !reachable,
                error, Instant.now().toString(), checkType);
    }

    private ExternalCapabilityHealth guardedLiveProbe(String name, LiveProbeCall call) {
        if (!circuitBreakerEnabled) {
            return call.execute();
        }
        ProbeCircuitState state = probeCircuitStates.computeIfAbsent(name, ignored -> new ProbeCircuitState());
        Instant now = Instant.now();
        if (state.openedUntil != null && now.isBefore(state.openedUntil)) {
            return new ExternalCapabilityHealth(name, true, false, true,
                    "live probe circuit open until " + state.openedUntil,
                    now.toString(), "circuit_open");
        }
        ExternalCapabilityHealth result;
        try {
            result = call.execute();
        } catch (Exception e) {
            result = liveProbeResult(name, true, false, e.getMessage(), "live_probe");
        }
        if (result.degraded()) {
            state.failureCount++;
            if (state.failureCount >= normalizedCircuitBreakerFailureThreshold()) {
                state.openedUntil = now.plusSeconds(normalizedCircuitBreakerCooldownSeconds());
            }
        } else {
            state.failureCount = 0;
            state.openedUntil = null;
        }
        return result;
    }

    private int normalizedCircuitBreakerFailureThreshold() {
        return Math.max(1, circuitBreakerFailureThreshold);
    }

    private long normalizedCircuitBreakerCooldownSeconds() {
        return Math.max(1L, circuitBreakerCooldownSeconds);
    }

    private boolean hasText(String key) {
        String value = environment.getProperty(key);
        return value != null && !value.isBlank();
    }

    private String string(String key, String defaultValue) {
        String value = environment.getProperty(key);
        return value == null ? defaultValue : value;
    }

    private boolean bool(String key, boolean defaultValue) {
        String value = environment.getProperty(key);
        return value == null ? defaultValue : Boolean.parseBoolean(value);
    }

    private static final class JsonSupportHolder {
        private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

        private JsonSupportHolder() {
        }

        private static String toJson(Object value) {
            try {
                return MAPPER.writeValueAsString(value);
            } catch (Exception e) {
                throw new IllegalStateException("failed to serialize live probe payload", e);
            }
        }
    }

    @FunctionalInterface
    private interface LiveProbeCall {
        ExternalCapabilityHealth execute();
    }

    private static final class ProbeCircuitState {
        private int failureCount;
        private Instant openedUntil;
    }
}
