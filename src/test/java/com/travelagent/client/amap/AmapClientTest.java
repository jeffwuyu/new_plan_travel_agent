package com.travelagent.client.amap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelagent.exception.AgentErrorCode;
import com.travelagent.exception.AgentException;
import com.travelagent.util.JsonUtil;
import com.travelagent.util.RedisUtil;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AmapClient Tests")
class AmapClientTest {

    @Mock private OkHttpClient okHttpClient;
    @Mock private RedisUtil redisUtil;
    @Mock private Call okHttpCall;

    @InjectMocks
    private AmapClient amapClient;

    private final JsonUtil jsonUtil = new JsonUtil();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(amapClient, "apiKey", "test_key");
        ReflectionTestUtils.setField(amapClient, "geocodeUrl", "https://restapi.amap.com/v3/geocode/geo");
        ReflectionTestUtils.setField(amapClient, "weatherUrl", "https://restapi.amap.com/v3/weather/weatherInfo");
        ReflectionTestUtils.setField(amapClient, "directionUrl", "https://restapi.amap.com/v3/direction/driving");
        ReflectionTestUtils.setField(amapClient, "walkingDirectionUrl", "https://restapi.amap.com/v3/direction/walking");
        ReflectionTestUtils.setField(amapClient, "nearbySearchUrl", "https://restapi.amap.com/v3/place/around");
        ReflectionTestUtils.setField(jsonUtil, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(amapClient, "jsonUtil", jsonUtil);
    }

    @Test
    @DisplayName("geocode: Redis cache hit skips OkHttp call")
    void geocode_cacheHit_skipsHttp() {
        String cachedJson = "{\"lat\":34.38,\"lng\":109.28,\"adcode\":\"610100\"}";
        when(redisUtil.getString(startsWith("amap:geocode:"))).thenReturn(cachedJson);

        Map<String, Object> result = amapClient.geocode("兵马俑", "西安市");

        assertThat(result.get("lat")).isEqualTo(34.38);
        assertThat(result.get("lng")).isEqualTo(109.28);
        assertThat(result.get("adcode")).isEqualTo("610100");
        verifyNoInteractions(okHttpClient);
    }

    @Test
    @DisplayName("geocode: cache miss triggers HTTP call and writes to cache")
    void geocode_cacheMiss_callsAmapAndCaches() throws IOException {
        when(redisUtil.getString(any())).thenReturn(null);

        String amapResponse = """
                {
                  "status": "1",
                  "geocodes": [
                    { "location": "109.278927,34.384232", "adcode": "610100" }
                  ]
                }
                """;
        mockHttpResponse(amapResponse);

        Map<String, Object> result = amapClient.geocode("兵马俑", "西安市");

        assertThat(((Number) result.get("lat")).doubleValue()).isCloseTo(34.384232, within(0.0001));
        assertThat(((Number) result.get("lng")).doubleValue()).isCloseTo(109.278927, within(0.0001));
        assertThat(result.get("adcode")).isEqualTo("610100");
        verify(redisUtil).setString(startsWith("amap:geocode:"), any(), any());
    }

    @Test
    @DisplayName("getWeather: Redis cache hit skips HTTP call")
    void getWeather_cacheHit_skipsHttp() {
        String cachedJson = "{\"weather\":\"Sunny\",\"temperature\":\"22\",\"windDirection\":\"North\",\"windPower\":\"3\",\"humidity\":\"45\"}";
        when(redisUtil.getString(startsWith("amap:weather:"))).thenReturn(cachedJson);

        Map<String, Object> result = amapClient.getWeather("610100");

        assertThat(result.get("weather")).isEqualTo("Sunny");
        assertThat(result.get("temperature")).isEqualTo("22");
        verifyNoInteractions(okHttpClient);
    }

    @Test
    @DisplayName("getWeather: cache miss calls Amap and returns parsed weather")
    void getWeather_cacheMiss_callsAmapAndParsesResponse() throws IOException {
        when(redisUtil.getString(any())).thenReturn(null);

        String amapResponse = """
                {
                  "status": "1",
                  "lives": [
                    {
                      "weather": "Sunny",
                      "temperature": "22",
                      "winddirection": "North",
                      "windpower": "3",
                      "humidity": "45"
                    }
                  ]
                }
                """;
        mockHttpResponse(amapResponse);

        Map<String, Object> result = amapClient.getWeather("610100");

        assertThat(result.get("weather")).isEqualTo("Sunny");
        assertThat(result.get("temperature")).isEqualTo("22");
        assertThat(result.get("windDirection")).isEqualTo("North");
        verify(redisUtil).setString(startsWith("amap:weather:"), any(), any());
    }

    @Test
    @DisplayName("getDrivingDuration: converts API seconds to minutes correctly")
    void getDrivingDuration_convertsSecondsToMinutes() throws IOException {
        when(redisUtil.getString(any())).thenReturn(null);

        String amapResponse = """
                {
                  "status": "1",
                  "route": {
                    "paths": [
                      { "duration": "1500" }
                    ]
                  }
                }
                """;
        mockHttpResponse(amapResponse);

        Map<String, Object> result = amapClient.getDrivingDuration(109.28, 34.38, 109.00, 34.26);

        assertThat(((Number) result.get("durationMin")).intValue()).isEqualTo(25);
    }

    @Test
    @DisplayName("geocode: classifies normal Amap errors as TOOL_AMAP_ERROR")
    void geocode_amapError_throwsToolAmapError() throws IOException {
        when(redisUtil.getString(any())).thenReturn(null);

        String errorResponse = "{\"status\":\"0\",\"info\":\"INVALID_USER_KEY\"}";
        mockHttpResponse(errorResponse);

        assertThatThrownBy(() -> amapClient.geocode("兵马俑", "西安市"))
                .isInstanceOf(AgentException.class)
                .satisfies(ex -> assertThat(((AgentException) ex).getErrorCode()).isEqualTo(AgentErrorCode.TOOL_AMAP_ERROR));
    }

    @Test
    @DisplayName("geocode: classifies QPS limit as retryable rate limit")
    void geocode_rateLimit_throwsRetryableAgentException() throws IOException {
        when(redisUtil.getString(any())).thenReturn(null);

        String errorResponse = "{\"status\":\"0\",\"info\":\"CUQPS_HAS_EXCEEDED_THE_LIMIT\"}";
        mockHttpResponse(errorResponse);

        assertThatThrownBy(() -> amapClient.geocode("西湖", "杭州"))
                .isInstanceOf(AgentException.class)
                .satisfies(ex -> {
                    AgentException agentException = (AgentException) ex;
                    assertThat(agentException.getErrorCode()).isEqualTo(AgentErrorCode.TOOL_AMAP_RATE_LIMIT);
                    assertThat(agentException.isRetryable()).isTrue();
                });
    }

    @Test
    @DisplayName("geocode: local limiter throttles after three calls per second")
    void geocode_localLimiter_throttlesBurstRequests() throws IOException {
        when(redisUtil.getString(any())).thenReturn(null);

        String amapResponse = """
                {
                  "status": "1",
                  "geocodes": [
                    { "location": "120.155070,30.274085", "adcode": "330100" }
                  ]
                }
                """;
        mockHttpResponse(amapResponse);

        long start = System.nanoTime();
        amapClient.geocode("西湖1", "杭州");
        amapClient.geocode("西湖2", "杭州");
        amapClient.geocode("西湖3", "杭州");
        amapClient.geocode("西湖4", "杭州");
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMillis).isGreaterThanOrEqualTo(900L);
    }

    private void mockHttpResponse(String body) throws IOException {
        when(okHttpClient.newCall(any())).thenReturn(okHttpCall);
        when(okHttpCall.execute()).thenAnswer(invocation -> new Response.Builder()
                .request(new Request.Builder().url("https://example.com").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create(body, okhttp3.MediaType.parse("application/json")))
                .build());
    }
}
