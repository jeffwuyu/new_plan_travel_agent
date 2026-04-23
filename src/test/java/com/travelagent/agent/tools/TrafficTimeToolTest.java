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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TrafficTimeTool Tests")
class TrafficTimeToolTest {

    @Mock private AmapClient amapClient;
    @Mock private McpToolExecutionService mcpToolExecutionService;

    @InjectMocks
    private TrafficTimeTool trafficTimeTool;

    private static final String IDEMPOTENCY_KEY = "task-uuid-step1-traffic_time";

    @Test
    @DisplayName("getName() returns 'traffic_time'")
    void getName_returnsCorrectName() {
        assertThat(trafficTimeTool.getName()).isEqualTo("traffic_time");
    }

    @Test
    @DisplayName("execute delegates requested travel mode to AmapClient")
    void execute_extractsCoordinatesAndDelegates() {
        when(amapClient.getTravelDuration(anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq("walking")))
                .thenReturn(Map.of("durationMin", 25, "routeMode", "walking"));

        Map<String, Object> result = trafficTimeTool.execute(Map.of(
                "originLng", 109.278927,
                "originLat", 34.384232,
                "destLng", 108.939981,
                "destLat", 34.263161,
                "travelMode", "walking"
        ), IDEMPOTENCY_KEY);

        assertThat(((Number) result.get("durationMin")).intValue()).isEqualTo(25);
        assertThat(result.get("routeMode")).isEqualTo("walking");
        verify(amapClient).getTravelDuration(109.278927, 34.384232, 108.939981, 34.263161, "walking");
    }

    @Test
    @DisplayName("execute handles integer coordinates")
    void execute_integerCoordinates_convertedToDouble() {
        when(amapClient.getTravelDuration(anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq("driving")))
                .thenReturn(Map.of("durationMin", 10, "routeMode", "driving"));

        Map<String, Object> args = Map.of(
                "originLng", 109,
                "originLat", 34,
                "destLng", 108,
                "destLat", 33
        );

        assertThatNoException().isThrownBy(() -> trafficTimeTool.execute(args, IDEMPOTENCY_KEY));
    }

    @Test
    @DisplayName("execute prefers MCP when enabled")
    void execute_prefersMcp() {
        when(mcpToolExecutionService.isEnabled()).thenReturn(true);
        when(mcpToolExecutionService.execute(eq("traffic_time"), any()))
                .thenReturn(Map.of("durationMin", 18, "distanceMeters", 4500, "routeMode", "transit"));

        Map<String, Object> result = trafficTimeTool.execute(Map.of(
                "originLng", 120.1,
                "originLat", 30.1,
                "destLng", 120.2,
                "destLat", 30.2,
                "travelMode", "transit"
        ), IDEMPOTENCY_KEY);

        assertThat(result.get("durationMin")).isEqualTo(18);
        assertThat(result.get("routeMode")).isEqualTo("transit");
        verifyNoInteractions(amapClient);
    }

    @Test
    @DisplayName("execute falls back to REST using the same travel mode")
    void execute_mcpFailure_fallsBack() {
        when(mcpToolExecutionService.isEnabled()).thenReturn(true);
        when(mcpToolExecutionService.execute(eq("traffic_time"), any()))
                .thenThrow(new RuntimeException("mcp unavailable"));
        when(amapClient.getTravelDuration(anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq("transit")))
                .thenReturn(Map.of("durationMin", 25, "routeMode", "bicycling"));

        Map<String, Object> result = trafficTimeTool.execute(Map.of(
                "originLng", 120.1,
                "originLat", 30.1,
                "destLng", 120.2,
                "destLat", 30.2,
                "travelMode", "transit"
        ), IDEMPOTENCY_KEY);

        assertThat(result.get("mcpFallback")).isEqualTo(true);
        assertThat(result.get("routeMode")).isEqualTo("bicycling");
        verify(amapClient).getTravelDuration(anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq("transit"));
    }
}
