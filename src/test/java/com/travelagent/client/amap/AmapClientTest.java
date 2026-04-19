package com.travelagent.client.amap;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 中文注释：测试类，用于验证 Amap Client Test 相关行为是否符合预期。
 */

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
    void setUp() throws Exception {
        ReflectionTestUtils.setField(amapClient, "apiKey", "test_key");
        ReflectionTestUtils.setField(amapClient, "geocodeUrl",  "https://restapi.amap.com/v3/geocode/geo");
        ReflectionTestUtils.setField(amapClient, "weatherUrl",  "https://restapi.amap.com/v3/weather/weatherInfo");
        ReflectionTestUtils.setField(amapClient, "directionUrl","https://restapi.amap.com/v3/direction/driving");
        ReflectionTestUtils.setField(jsonUtil, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(amapClient, "jsonUtil", jsonUtil);
    }

    // -----------------------------------------------------------------------
    // geocode — cache hit
    // -----------------------------------------------------------------------

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

    // -----------------------------------------------------------------------
    // geocode — cache miss, HTTP call succeeds
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("geocode: cache miss triggers HTTP call and writes to cache")
    void geocode_cacheMiss_callsAmapAndCaches() throws IOException {
        when(redisUtil.getString(any())).thenReturn(null); // cache miss

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

        // Verify result is written to cache
        verify(redisUtil).setString(startsWith("amap:geocode:"), anyString(), any());
    }

    // -----------------------------------------------------------------------
    // weather — cache hit
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("getWeather: Redis cache hit skips HTTP call")
    void getWeather_cacheHit_skipsHttp() {
        String cachedJson = "{\"weather\":\"晴\",\"temperature\":\"22\",\"windDirection\":\"东\",\"windPower\":\"3\",\"humidity\":\"45\"}";
        when(redisUtil.getString(startsWith("amap:weather:"))).thenReturn(cachedJson);

        Map<String, Object> result = amapClient.getWeather("610100");

        assertThat(result.get("weather")).isEqualTo("晴");
        assertThat(result.get("temperature")).isEqualTo("22");
        verifyNoInteractions(okHttpClient);
    }

    // -----------------------------------------------------------------------
    // weather — cache miss
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("getWeather: cache miss calls Amap and returns parsed weather")
    void getWeather_cacheMiss_callsAmapAndParsesResponse() throws IOException {
        when(redisUtil.getString(any())).thenReturn(null);

        String amapResponse = """
                {
                  "status": "1",
                  "lives": [
                    {
                      "weather": "晴",
                      "temperature": "22",
                      "winddirection": "东",
                      "windpower": "3",
                      "humidity": "45"
                    }
                  ]
                }
                """;
        mockHttpResponse(amapResponse);

        Map<String, Object> result = amapClient.getWeather("610100");

        assertThat(result.get("weather")).isEqualTo("晴");
        assertThat(result.get("temperature")).isEqualTo("22");
        assertThat(result.get("windDirection")).isEqualTo("东");
        verify(redisUtil).setString(startsWith("amap:weather:"), anyString(), any());
    }

    // -----------------------------------------------------------------------
    // getDrivingDuration — parses seconds → minutes
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("getDrivingDuration: converts API seconds to minutes correctly")
    void getDrivingDuration_convertsSecondsToMinutes() throws IOException {
        when(redisUtil.getString(any())).thenReturn(null);

        // 25 minutes = 1500 seconds
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

    // -----------------------------------------------------------------------
    // Amap API error status
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("geocode: throws RuntimeException when Amap returns status != 1")
    void geocode_amapError_throwsException() throws IOException {
        when(redisUtil.getString(any())).thenReturn(null);

        String errorResponse = "{\"status\":\"0\",\"info\":\"INVALID_USER_KEY\"}";
        mockHttpResponse(errorResponse);

        assertThatThrownBy(() -> amapClient.geocode("兵马俑", "西安市"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Amap API error");
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    private void mockHttpResponse(String body) throws IOException {
        Response response = new Response.Builder()
                .request(new Request.Builder().url("https://example.com").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create(body,
                        okhttp3.MediaType.parse("application/json")))
                .build();

        when(okHttpClient.newCall(any())).thenReturn(okHttpCall);
        when(okHttpCall.execute()).thenReturn(response);
    }
}
