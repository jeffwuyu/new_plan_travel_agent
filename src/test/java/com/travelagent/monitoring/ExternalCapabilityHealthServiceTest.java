package com.travelagent.monitoring;

import com.travelagent.client.oss.OssClient;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ExternalCapabilityHealthService Tests")
class ExternalCapabilityHealthServiceTest {

    @Test
    @DisplayName("postgres backend reports degraded when dedicated rag.postgres.url is missing")
    void postgresBackendRequiresDedicatedRagPostgresUrl() {
        Environment environment = mock(Environment.class);
        when(environment.getProperty("rag.vector-backend")).thenReturn("postgres");
        when(environment.getProperty("rag.postgres.url")).thenReturn("");
        when(environment.getProperty("spring.datasource.url")).thenReturn("jdbc:mysql://localhost:3306/travel_agent");

        ExternalCapabilityHealthService service = new ExternalCapabilityHealthService(
                environment,
                mock(StringRedisTemplate.class),
                mock(DataSource.class));

        ExternalCapabilityHealth health = find(service.getHealthMatrix(), "postgres_pgvector");

        assertThat(health.configured()).isFalse();
        assertThat(health.reachable()).isFalse();
        assertThat(health.degraded()).isTrue();
        assertThat(health.lastError()).contains("rag.postgres.url");
        assertThat(health.errorCode()).isEqualTo(ExternalCapabilityErrorCode.NOT_CONFIGURED.name());
    }

    @Test
    @DisplayName("dashvector backend marks postgres pgvector as optional")
    void dashvectorBackendDoesNotRequirePostgresPgvector() {
        Environment environment = mock(Environment.class);
        when(environment.getProperty("rag.vector-backend")).thenReturn("dashvector");

        ExternalCapabilityHealthService service = new ExternalCapabilityHealthService(
                environment,
                mock(StringRedisTemplate.class),
                mock(DataSource.class));

        ExternalCapabilityHealth health = find(service.getHealthMatrix(), "postgres_pgvector");

        assertThat(health.configured()).isFalse();
        assertThat(health.reachable()).isFalse();
        assertThat(health.degraded()).isFalse();
        assertThat(health.lastError()).contains("not required");
    }

    @Test
    @DisplayName("config-only capability is configured but not reported as live reachable")
    void configOnlyCapabilityDoesNotPretendLiveReachable() {
        Environment environment = mock(Environment.class);
        when(environment.getProperty("amap.api-key")).thenReturn("test-key");

        ExternalCapabilityHealthService service = new ExternalCapabilityHealthService(
                environment,
                mock(StringRedisTemplate.class),
                mock(DataSource.class));

        ExternalCapabilityHealth health = find(service.getHealthMatrix(), "amap");

        assertThat(health.configured()).isTrue();
        assertThat(health.reachable()).isFalse();
        assertThat(health.degraded()).isFalse();
        assertThat(health.checkType()).isEqualTo("config_only");
        assertThat(health.lastError()).contains("live API probe not executed");
        assertThat(health.errorCode()).isNull();
    }

    @Test
    @DisplayName("disabled image generation is not degraded")
    void disabledBailianImageIsNotDegraded() {
        Environment environment = mock(Environment.class);
        when(environment.getProperty("dashscope.image.enabled")).thenReturn("false");

        ExternalCapabilityHealthService service = new ExternalCapabilityHealthService(
                environment,
                mock(StringRedisTemplate.class),
                mock(DataSource.class));

        ExternalCapabilityHealth health = find(service.getHealthMatrix(), "bailian_image");

        assertThat(health.configured()).isFalse();
        assertThat(health.reachable()).isFalse();
        assertThat(health.degraded()).isFalse();
        assertThat(health.checkType()).isEqualTo("disabled");
        assertThat(health.lastError()).contains("disabled");
        assertThat(health.errorCode()).isNull();
    }

    @Test
    @DisplayName("live probe marks bailian image reachable when endpoint responds")
    void bailianLiveProbeReportsReachable() throws Exception {
        Environment environment = mock(Environment.class);
        when(environment.getProperty("dashscope.image.enabled")).thenReturn("true");
        when(environment.getProperty("dashscope.image.api-key")).thenReturn("image-key");
        when(environment.getProperty("dashscope.image.endpoint"))
                .thenReturn("https://dashscope.aliyuncs.com/api/v1/services/aigc/image-generation/generation");

        ExternalCapabilityHealthService service = new ExternalCapabilityHealthService(
                environment,
                mock(StringRedisTemplate.class),
                mock(DataSource.class));
        OkHttpClient okHttpClient = mock(OkHttpClient.class);
        Call call = mock(Call.class);
        lenient().when(okHttpClient.newCall(any(Request.class))).thenReturn(call);
        when(call.execute()).thenReturn(httpResponse(200, "{\"ok\":true}"));
        ReflectionTestUtils.setField(service, "okHttpClient", okHttpClient);
        ReflectionTestUtils.setField(service, "liveProbeEnabled", true);

        ExternalCapabilityHealth health = find(service.getHealthMatrix(), "bailian_image");

        assertThat(health.configured()).isTrue();
        assertThat(health.reachable()).isTrue();
        assertThat(health.degraded()).isFalse();
        assertThat(health.checkType()).isEqualTo("live_probe");
        assertThat(health.errorCode()).isNull();
        verify(okHttpClient).newCall(any(Request.class));
    }

    @Test
    @DisplayName("live probe maps ctrip auth failure to unified error code")
    void ctripLiveProbeMapsAuthFailure() throws Exception {
        Environment environment = mock(Environment.class);
        when(environment.getProperty("ctrip.api.base-url")).thenReturn("https://ctrip.example.test/hotels/search");
        when(environment.getProperty("ctrip.api.app-id")).thenReturn("ctrip-app");
        when(environment.getProperty("ctrip.api.secret")).thenReturn("ctrip-secret");

        ExternalCapabilityHealthService service = new ExternalCapabilityHealthService(
                environment,
                mock(StringRedisTemplate.class),
                mock(DataSource.class));
        OkHttpClient okHttpClient = mock(OkHttpClient.class);
        Call call = mock(Call.class);
        lenient().when(okHttpClient.newCall(any(Request.class))).thenReturn(call);
        when(call.execute()).thenReturn(httpResponse(401, "{\"message\":\"unauthorized in probe sandbox\"}"));
        ReflectionTestUtils.setField(service, "okHttpClient", okHttpClient);
        ReflectionTestUtils.setField(service, "liveProbeEnabled", true);

        ExternalCapabilityHealth health = find(service.getHealthMatrix(), "ctrip");

        assertThat(health.configured()).isTrue();
        assertThat(health.reachable()).isFalse();
        assertThat(health.degraded()).isTrue();
        assertThat(health.checkType()).isEqualTo("live_probe");
        assertThat(health.lastError()).contains("HTTP 401");
        assertThat(health.errorCode()).isEqualTo(ExternalCapabilityErrorCode.AUTH_FAILED.name());
        verify(okHttpClient).newCall(any(Request.class));
    }

    @Test
    @DisplayName("live probe maps rate limit response to unified error code")
    void liveProbeMapsRateLimitFailure() throws Exception {
        Environment environment = mock(Environment.class);
        when(environment.getProperty("web-search.endpoint")).thenReturn("https://search.example.test/query");
        when(environment.getProperty("web-search.api-key")).thenReturn("search-key");

        ExternalCapabilityHealthService service = new ExternalCapabilityHealthService(
                environment,
                mock(StringRedisTemplate.class),
                mock(DataSource.class));
        OkHttpClient okHttpClient = mock(OkHttpClient.class);
        Call call = mock(Call.class);
        lenient().when(okHttpClient.newCall(any(Request.class))).thenReturn(call);
        when(call.execute()).thenReturn(httpResponse(429, "{\"message\":\"too many requests\"}"));
        ReflectionTestUtils.setField(service, "okHttpClient", okHttpClient);
        ReflectionTestUtils.setField(service, "liveProbeEnabled", true);

        ExternalCapabilityHealth health = find(service.getHealthMatrix(), "web_search");

        assertThat(health.degraded()).isTrue();
        assertThat(health.errorCode()).isEqualTo(ExternalCapabilityErrorCode.RATE_LIMITED.name());
    }

    @Test
    @DisplayName("live probe marks dashvector degraded on 5xx response")
    void dashvectorLiveProbeReportsFailure() throws Exception {
        Environment environment = mock(Environment.class);
        when(environment.getProperty("rag.vector-backend")).thenReturn("dashvector");
        when(environment.getProperty("dashvector.api-key")).thenReturn("dash-key");
        when(environment.getProperty("dashvector.endpoint")).thenReturn("https://dashvector.example.test");
        when(environment.getProperty("dashvector.collection")).thenReturn("travel");

        ExternalCapabilityHealthService service = new ExternalCapabilityHealthService(
                environment,
                mock(StringRedisTemplate.class),
                mock(DataSource.class));
        OkHttpClient okHttpClient = mock(OkHttpClient.class);
        Call call = mock(Call.class);
        lenient().when(okHttpClient.newCall(any(Request.class))).thenReturn(call);
        when(call.execute()).thenReturn(httpResponse(503, "{\"message\":\"service unavailable\"}"));
        ReflectionTestUtils.setField(service, "okHttpClient", okHttpClient);
        ReflectionTestUtils.setField(service, "liveProbeEnabled", true);

        ExternalCapabilityHealth health = find(service.getHealthMatrix(), "dashvector");

        assertThat(health.configured()).isTrue();
        assertThat(health.reachable()).isFalse();
        assertThat(health.degraded()).isTrue();
        assertThat(health.checkType()).isEqualTo("live_probe");
        assertThat(health.lastError()).contains("HTTP 503");
        assertThat(health.errorCode()).isEqualTo(ExternalCapabilityErrorCode.PROVIDER_DOWN.name());
        verify(okHttpClient).newCall(any(Request.class));
    }

    @Test
    @DisplayName("live probe opens circuit after repeated provider failures")
    void liveProbeOpensCircuitAfterRepeatedFailures() throws Exception {
        Environment environment = mock(Environment.class);
        when(environment.getProperty("web-search.endpoint")).thenReturn("https://search.example.test/query");
        when(environment.getProperty("web-search.api-key")).thenReturn("search-key");

        ExternalCapabilityHealthService service = new ExternalCapabilityHealthService(
                environment,
                mock(StringRedisTemplate.class),
                mock(DataSource.class));
        OkHttpClient okHttpClient = mock(OkHttpClient.class);
        Call firstCall = mock(Call.class);
        Call secondCall = mock(Call.class);
        when(okHttpClient.newCall(any(Request.class))).thenReturn(firstCall, secondCall);
        when(firstCall.execute()).thenReturn(httpResponse(503, "{\"message\":\"service unavailable\"}"));
        when(secondCall.execute()).thenReturn(httpResponse(503, "{\"message\":\"service unavailable\"}"));
        ReflectionTestUtils.setField(service, "okHttpClient", okHttpClient);
        ReflectionTestUtils.setField(service, "liveProbeEnabled", true);
        ReflectionTestUtils.setField(service, "circuitBreakerFailureThreshold", 2);
        ReflectionTestUtils.setField(service, "circuitBreakerCooldownSeconds", 120L);

        ExternalCapabilityHealth first = find(service.getHealthMatrix(), "web_search");
        ExternalCapabilityHealth second = find(service.getHealthMatrix(), "web_search");
        ExternalCapabilityHealth third = find(service.getHealthMatrix(), "web_search");

        assertThat(first.errorCode()).isEqualTo(ExternalCapabilityErrorCode.PROVIDER_DOWN.name());
        assertThat(second.errorCode()).isEqualTo(ExternalCapabilityErrorCode.PROVIDER_DOWN.name());
        assertThat(third.checkType()).isEqualTo("circuit_open");
        assertThat(third.errorCode()).isEqualTo(ExternalCapabilityErrorCode.CIRCUIT_OPEN.name());
        verify(okHttpClient, times(2)).newCall(any(Request.class));
    }

    @Test
    @DisplayName("successful live probe resets circuit failure count")
    void successfulLiveProbeResetsCircuitFailureCount() throws Exception {
        Environment environment = mock(Environment.class);
        when(environment.getProperty("web-search.endpoint")).thenReturn("https://search.example.test/query");
        when(environment.getProperty("web-search.api-key")).thenReturn("search-key");

        ExternalCapabilityHealthService service = new ExternalCapabilityHealthService(
                environment,
                mock(StringRedisTemplate.class),
                mock(DataSource.class));
        OkHttpClient okHttpClient = mock(OkHttpClient.class);
        Call failCall = mock(Call.class);
        Call successCall = mock(Call.class);
        Call secondFailCall = mock(Call.class);
        when(okHttpClient.newCall(any(Request.class))).thenReturn(failCall, successCall, secondFailCall);
        when(failCall.execute()).thenReturn(httpResponse(503, "{\"message\":\"service unavailable\"}"));
        when(successCall.execute()).thenReturn(httpResponse(200, "{\"ok\":true}"));
        when(secondFailCall.execute()).thenReturn(httpResponse(503, "{\"message\":\"service unavailable\"}"));
        ReflectionTestUtils.setField(service, "okHttpClient", okHttpClient);
        ReflectionTestUtils.setField(service, "liveProbeEnabled", true);
        ReflectionTestUtils.setField(service, "circuitBreakerFailureThreshold", 2);

        ExternalCapabilityHealth first = find(service.getHealthMatrix(), "web_search");
        ExternalCapabilityHealth second = find(service.getHealthMatrix(), "web_search");
        ExternalCapabilityHealth third = find(service.getHealthMatrix(), "web_search");

        assertThat(first.errorCode()).isEqualTo(ExternalCapabilityErrorCode.PROVIDER_DOWN.name());
        assertThat(second.degraded()).isFalse();
        assertThat(third.errorCode()).isEqualTo(ExternalCapabilityErrorCode.PROVIDER_DOWN.name());
        assertThat(third.checkType()).isEqualTo("live_probe");
        verify(okHttpClient, times(3)).newCall(any(Request.class));
    }

    @Test
    @DisplayName("invalid live probe url maps to invalid config")
    void liveProbeMapsInvalidConfig() {
        Environment environment = mock(Environment.class);
        when(environment.getProperty("web-search.endpoint")).thenReturn("not-a-url");
        when(environment.getProperty("web-search.api-key")).thenReturn("search-key");

        ExternalCapabilityHealthService service = new ExternalCapabilityHealthService(
                environment,
                mock(StringRedisTemplate.class),
                mock(DataSource.class));
        ReflectionTestUtils.setField(service, "liveProbeEnabled", true);

        ExternalCapabilityHealth health = find(service.getHealthMatrix(), "web_search");

        assertThat(health.degraded()).isTrue();
        assertThat(health.errorCode()).isEqualTo(ExternalCapabilityErrorCode.INVALID_CONFIG.name());
    }

    @Test
    @DisplayName("oss live probe still uses bucket existence check")
    void ossLiveProbeUsesBucketExists() {
        Environment environment = mock(Environment.class);
        when(environment.getProperty("oss.access-key-id")).thenReturn("ak");
        when(environment.getProperty("oss.access-key-secret")).thenReturn("sk");
        when(environment.getProperty("oss.bucket-name")).thenReturn("travel-agent");

        ExternalCapabilityHealthService service = new ExternalCapabilityHealthService(
                environment,
                mock(StringRedisTemplate.class),
                mock(DataSource.class));
        OssClient ossClient = mock(OssClient.class);
        when(ossClient.bucketExists()).thenReturn(true);
        ReflectionTestUtils.setField(service, "ossClient", ossClient);
        ReflectionTestUtils.setField(service, "liveProbeEnabled", true);

        ExternalCapabilityHealth health = find(service.getHealthMatrix(), "oss");

        assertThat(health.configured()).isTrue();
        assertThat(health.reachable()).isTrue();
        assertThat(health.checkType()).isEqualTo("live_probe");
        verify(ossClient).bucketExists();
    }

    private ExternalCapabilityHealth find(List<ExternalCapabilityHealth> values, String name) {
        return values.stream()
                .filter(value -> name.equals(value.name()))
                .findFirst()
                .orElseThrow();
    }

    private Response httpResponse(int code, String body) {
        return new Response.Builder()
                .request(new Request.Builder().url("https://probe.example.test/health").build())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("OK")
                .body(ResponseBody.create(body, okhttp3.MediaType.get("application/json")))
                .build();
    }
}
