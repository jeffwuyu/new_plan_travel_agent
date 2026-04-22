package com.travelagent.agent.tools;

import com.travelagent.agent.mcp.McpToolExecutionService;
import com.travelagent.client.amap.AmapClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 中文注释：测试类，用于验证 Traffic Time Tool Test 相关行为是否符合预期。
 */

@ExtendWith(MockitoExtension.class)
@DisplayName("TrafficTimeTool Tests")
class TrafficTimeToolTest {

    @Mock private AmapClient amapClient;
    @Mock private McpToolExecutionService mcpToolExecutionService;

    @InjectMocks
    private TrafficTimeTool trafficTimeTool;

    private static final String IDEMPOTENCY_KEY = "task-uuid-step1-traffic_time";

    // -----------------------------------------------------------------------
    // getName contract
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("getName() returns 'traffic_time'")
    void getName_returnsCorrectName() {
        assertThat(trafficTimeTool.getName()).isEqualTo("traffic_time");
    }

    // -----------------------------------------------------------------------
    // Correct parameter extraction and delegation
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("execute: extracts coordinates and delegates to AmapClient")
    void execute_extractsCoordinatesAndDelegates() {
        when(amapClient.getDrivingDuration(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(Map.of("durationMin", 25));

        Map<String, Object> args = Map.of(
                "originLng", 109.278927,
                "originLat", 34.384232,
                "destLng",   108.939981,
                "destLat",   34.263161
        );
        Map<String, Object> result = trafficTimeTool.execute(args, IDEMPOTENCY_KEY);

        assertThat(((Number) result.get("durationMin")).intValue()).isEqualTo(25);

        ArgumentCaptor<Double> captor = ArgumentCaptor.forClass(Double.class);
        verify(amapClient).getDrivingDuration(captor.capture(), captor.capture(),
                captor.capture(), captor.capture());
        java.util.List<Double> values = captor.getAllValues();
        assertThat(values.get(0)).isCloseTo(109.278927, within(0.0001)); // originLng
        assertThat(values.get(1)).isCloseTo(34.384232,  within(0.0001)); // originLat
        assertThat(values.get(2)).isCloseTo(108.939981, within(0.0001)); // destLng
        assertThat(values.get(3)).isCloseTo(34.263161,  within(0.0001)); // destLat
    }

    // -----------------------------------------------------------------------
    // Integer coordinates are converted to double
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("execute: handles Integer type for coordinates (Number→double cast)")
    void execute_integerCoordinates_convertedToDouble() {
        when(amapClient.getDrivingDuration(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(Map.of("durationMin", 10));

        // Use Integer values (simulating JSON number parsing without decimal)
        Map<String, Object> args = Map.of(
                "originLng", 109, "originLat", 34,
                "destLng", 108, "destLat", 33
        );

        assertThatNoException().isThrownBy(() -> trafficTimeTool.execute(args, IDEMPOTENCY_KEY));
    }
    @Test
    @DisplayName("execute: prefers MCP when enabled")
    void execute_prefersMcp() {
        when(mcpToolExecutionService.isEnabled()).thenReturn(true);
        when(mcpToolExecutionService.execute(eq("traffic_time"), any()))
                .thenReturn(Map.of("durationMin", 18, "distanceMeters", 4500));

        Map<String, Object> result = trafficTimeTool.execute(Map.of(
                "originLng", 120.1,
                "originLat", 30.1,
                "destLng", 120.2,
                "destLat", 30.2
        ), IDEMPOTENCY_KEY);

        assertThat(result.get("durationMin")).isEqualTo(18);
        verifyNoInteractions(amapClient);
    }

    @Test
    @DisplayName("execute: MCP failure falls back to AmapClient")
    void execute_mcpFailure_fallsBack() {
        when(mcpToolExecutionService.isEnabled()).thenReturn(true);
        when(mcpToolExecutionService.execute(eq("traffic_time"), any()))
                .thenThrow(new RuntimeException("mcp unavailable"));
        when(amapClient.getDrivingDuration(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(Map.of("durationMin", 25));

        Map<String, Object> result = trafficTimeTool.execute(Map.of(
                "originLng", 120.1,
                "originLat", 30.1,
                "destLng", 120.2,
                "destLat", 30.2
        ), IDEMPOTENCY_KEY);

        assertThat(result.get("mcpFallback")).isEqualTo(true);
        verify(amapClient).getDrivingDuration(anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }
}
