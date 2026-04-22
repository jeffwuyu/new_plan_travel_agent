package com.travelagent.agent.mcp;

import com.travelagent.config.AgentMcpProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("McpToolExecutionService Tests")
class McpToolExecutionServiceTest {

    @Mock
    private McpToolInvoker toolInvoker;

    @Test
    @DisplayName("geocode result is normalized from structuredContent")
    void execute_geocode_normalizesStructuredContent() {
        AgentMcpProperties properties = new AgentMcpProperties();
        properties.setEnabled(true);
        McpToolExecutionService service = new McpToolExecutionService(properties, toolInvoker);

        when(toolInvoker.callTool("maps_geo", Map.of("address", "西湖", "city", "杭州")))
                .thenReturn(new McpToolCallResult(false,
                        Map.of(
                                "location", "120.15507,30.274084",
                                "adcode", "330106",
                                "province", "浙江省",
                                "city", "杭州市",
                                "district", "西湖区",
                                "formatted_address", "浙江省杭州市西湖区西湖景区"
                        ),
                        List.of(),
                        Map.of()));

        Map<String, Object> result = service.execute("geocode", Map.of("name", "西湖", "region", "杭州"));

        assertThat(result.get("lat")).isEqualTo(30.274084d);
        assertThat(result.get("lng")).isEqualTo(120.15507d);
        assertThat(result.get("adcode")).isEqualTo("330106");
        assertThat(result.get("formattedAddress")).isEqualTo("浙江省杭州市西湖区西湖景区");
    }

    @Test
    @DisplayName("weather result is normalized from forecast payload")
    void execute_weather_normalizesForecastPayload() {
        AgentMcpProperties properties = new AgentMcpProperties();
        properties.setEnabled(true);
        McpToolExecutionService service = new McpToolExecutionService(properties, toolInvoker);

        when(toolInvoker.callTool("maps_weather", Map.of("city", "330100")))
                .thenReturn(new McpToolCallResult(false,
                        Map.of(
                                "city", "杭州",
                                "forecasts", List.of(Map.of(
                                        "dayweather", "晴",
                                        "daytemp", "26",
                                        "daywind", "东北",
                                        "daypower", "3"
                                ))
                        ),
                        List.of(),
                        Map.of()));

        Map<String, Object> result = service.execute("weather", Map.of("adcode", "330100"));

        assertThat(result.get("weather")).isEqualTo("晴");
        assertThat(result.get("temperature")).isEqualTo("26");
        assertThat(result.get("windDirection")).isEqualTo("东北");
        assertThat(result.get("windPower")).isEqualTo("3");
    }

    @Test
    @DisplayName("traffic result is normalized from route payload")
    void execute_traffic_normalizesRoutePayload() {
        AgentMcpProperties properties = new AgentMcpProperties();
        properties.setEnabled(true);
        McpToolExecutionService service = new McpToolExecutionService(properties, toolInvoker);

        when(toolInvoker.callTool("maps_direction_driving",
                Map.of("origin", "120.1,30.1", "destination", "120.2,30.2")))
                .thenReturn(new McpToolCallResult(false,
                        Map.of(
                                "route", Map.of(
                                        "paths", List.of(Map.of(
                                                "duration", "600",
                                                "distance", "5200"
                                        ))
                                )
                        ),
                        List.of(),
                        Map.of()));

        Map<String, Object> result = service.execute("traffic_time", Map.of(
                "originLng", 120.1,
                "originLat", 30.1,
                "destLng", 120.2,
                "destLat", 30.2
        ));

        assertThat(result.get("durationMin")).isEqualTo(10);
        assertThat(result.get("distanceMeters")).isEqualTo(5200);
        assertThat(result.get("routeMode")).isEqualTo("driving");
    }
}
