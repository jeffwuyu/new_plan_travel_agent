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
        verify(amapClient).getWeather("610100");
    }
}
