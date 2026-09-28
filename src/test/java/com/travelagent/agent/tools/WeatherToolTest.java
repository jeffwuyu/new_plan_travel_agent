package com.travelagent.agent.tools;

import com.travelagent.agent.mcp.McpToolExecutionService;
import com.travelagent.client.amap.AmapClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 中文注释：测试类，用于验证 Weather Tool Test 相关行为是否符合预期。
 */

@ExtendWith(MockitoExtension.class)
@DisplayName("WeatherTool Tests")
class WeatherToolTest {

    @Mock private AmapClient amapClient;
    @Mock private McpToolExecutionService mcpToolExecutionService;

    @InjectMocks
    private WeatherTool weatherTool;

    private static final String IDEMPOTENCY_KEY = "task-uuid-step1-weather";

    // -----------------------------------------------------------------------
    // getName contract
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("getName() returns 'weather'")
    void getName_returnsCorrectName() {
        assertThat(weatherTool.getName()).isEqualTo("weather");
    }

    // -----------------------------------------------------------------------
    // Delegates to AmapClient
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("execute: delegates to AmapClient with correct adcode")
    void execute_delegatesToAmapClient() {
        Map<String, Object> weatherData = Map.of(
                "weather", "晴", "temperature", "22",
                "windDirection", "东", "windPower", "3", "humidity", "45"
        );
        when(amapClient.getWeather("610100")).thenReturn(weatherData);

        Map<String, Object> result = weatherTool.execute(Map.of("adcode", "610100"), IDEMPOTENCY_KEY);

        assertThat(result.get("weather")).isEqualTo("晴");
        assertThat(result.get("temperature")).isEqualTo("22");
        assertThat(result.get("outdoorRisk")).isEqualTo("LOW");
        assertThat(result.get("summary").toString()).contains("晴 22C");
        assertThat(result.get("queryTime")).isNotNull();
        assertThat(result.get("cacheTtl")).isEqualTo("PT1H");
        verify(amapClient).getWeather("610100");
    }

    // -----------------------------------------------------------------------
    // AmapClient failure propagates
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("execute: propagates AmapClient exceptions")
    void execute_amapFailure_propagatesException() {
        when(amapClient.getWeather(any())).thenThrow(new RuntimeException("Amap unavailable"));

        assertThatThrownBy(() -> weatherTool.execute(Map.of("adcode", "610100"), IDEMPOTENCY_KEY))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Amap unavailable");
    }
    @Test
    @DisplayName("execute: prefers MCP when enabled")
    void execute_prefersMcp() {
        when(mcpToolExecutionService.isEnabled()).thenReturn(true);
        when(mcpToolExecutionService.execute(eq("weather"), any()))
                .thenReturn(Map.of("weather", "晴", "temperature", "23"));

        Map<String, Object> result = weatherTool.execute(Map.of("adcode", "330100"), IDEMPOTENCY_KEY);

        assertThat(result.get("weather")).isEqualTo("晴");
        assertThat(result.get("outdoorRisk")).isEqualTo("LOW");
        verifyNoInteractions(amapClient);
    }

    @Test
    @DisplayName("execute: MCP failure falls back to AmapClient")
    void execute_mcpFailure_fallsBack() {
        when(mcpToolExecutionService.isEnabled()).thenReturn(true);
        when(mcpToolExecutionService.execute(eq("weather"), any()))
                .thenThrow(new RuntimeException("mcp unavailable"));
        when(amapClient.getWeather("610100")).thenReturn(Map.of("weather", "Sunny", "temperature", "22"));

        Map<String, Object> result = weatherTool.execute(Map.of("adcode", "610100"), IDEMPOTENCY_KEY);

        assertThat(result.get("mcpFallback")).isEqualTo(true);
        assertThat(result.get("mcpProvider")).isEqualTo("amap-rest");
        assertThat(result.get("outdoorRisk")).isEqualTo("LOW");
        verify(amapClient).getWeather("610100");
    }

    @Test
    @DisplayName("execute: date range uses forecast and flags outdoor risks")
    void execute_dateRange_usesForecastAndBuildsRiskAdvice() {
        when(amapClient.getWeatherForecast("330100")).thenReturn(Map.of(
                "weather", "小雨",
                "temperature", "34",
                "windDirection", "东",
                "windPower", "6",
                "humidity", "",
                "forecastDays", java.util.List.of(
                        Map.of(
                                "date", "2026-06-05",
                                "dayWeather", "小雨",
                                "nightWeather", "阴",
                                "tempHigh", "34",
                                "tempLow", "25",
                                "dayWindPower", "6",
                                "nightWindPower", "4"
                        ),
                        Map.of(
                                "date", "2026-06-06",
                                "dayWeather", "晴",
                                "nightWeather", "晴",
                                "tempHigh", "29",
                                "tempLow", "22",
                                "dayWindPower", "3",
                                "nightWindPower", "3"
                        )
                )
        ));

        Map<String, Object> result = weatherTool.execute(Map.of(
                "city", "330100",
                "startDate", "2026-06-05",
                "endDate", "2026-06-06"
        ), IDEMPOTENCY_KEY);

        assertThat(result.get("outdoorRisk")).isEqualTo("MEDIUM");
        assertThat(result.get("avoidRain")).isEqualTo(true);
        assertThat(result.get("avoidWind")).isEqualTo(true);
        assertThat((java.util.List<?>) result.get("constraintHints")).isNotEmpty();
        assertThat((java.util.List<?>) result.get("adjustmentSuggestions"))
                .anySatisfy(item -> assertThat(item.toString()).contains("室内"));
        assertThat((java.util.List<?>) result.get("forecastDays")).hasSize(2);
        verify(amapClient).getWeatherForecast("330100");
        verify(amapClient, never()).getWeather(any());
    }
}
