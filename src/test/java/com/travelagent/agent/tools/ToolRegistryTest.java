package com.travelagent.agent.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * 中文注释：测试类，用于验证 Tool Registry Test 相关行为是否符合预期。
 */

@DisplayName("ToolRegistry Tests")
class ToolRegistryTest {

    // -----------------------------------------------------------------------
    // Stub tools for testing
    // -----------------------------------------------------------------------

    private static AgentTool stubTool(String name) {
        return new AgentTool() {
            @Override public String getName() { return name; }
            @Override public Map<String, Object> execute(Map<String, Object> arguments, String key) {
                return Map.of("tool", name);
            }
        };
    }

    // -----------------------------------------------------------------------
    // Registration
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("All provided tools are registered by getName()")
    void constructor_registersAllTools() {
        AgentTool geocode  = stubTool(GeocodeTool.NAME);
        AgentTool weather  = stubTool(WeatherTool.NAME);
        AgentTool traffic  = stubTool(TrafficTimeTool.NAME);

        ToolRegistry registry = new ToolRegistry(List.of(geocode, weather, traffic));

        assertThat(registry.getToolNames())
                .containsExactlyInAnyOrder(GeocodeTool.NAME, WeatherTool.NAME, TrafficTimeTool.NAME);
    }

    // -----------------------------------------------------------------------
    // getTool — happy path
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("getTool returns the correct tool implementation")
    void getTool_knownName_returnsCorrectTool() {
        AgentTool geocodeTool = stubTool(GeocodeTool.NAME);
        ToolRegistry registry = new ToolRegistry(List.of(geocodeTool));

        AgentTool found = registry.getTool(GeocodeTool.NAME);
        assertThat(found).isSameAs(geocodeTool);
    }

    // -----------------------------------------------------------------------
    // getTool — unknown tool
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("getTool throws IllegalArgumentException for unknown tool name")
    void getTool_unknownName_throwsIllegalArgument() {
        ToolRegistry registry = new ToolRegistry(List.of(stubTool(GeocodeTool.NAME)));

        assertThatThrownBy(() -> registry.getTool("nonexistent_tool"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nonexistent_tool");
    }

    // -----------------------------------------------------------------------
    // hasTool
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("hasTool returns true for registered tools and false for unknown ones")
    void hasTool_returnsCorrectBoolean() {
        ToolRegistry registry = new ToolRegistry(List.of(stubTool(GeocodeTool.NAME)));

        assertThat(registry.hasTool(GeocodeTool.NAME)).isTrue();
        assertThat(registry.hasTool("unknown")).isFalse();
    }

    // -----------------------------------------------------------------------
    // NAME constants
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Tool NAME constants match expected values")
    void toolNameConstants_matchExpectedValues() {
        assertThat(GeocodeTool.NAME).isEqualTo("geocode");
        assertThat(WeatherTool.NAME).isEqualTo("weather");
        assertThat(TrafficTimeTool.NAME).isEqualTo("traffic_time");
    }

    // -----------------------------------------------------------------------
    // Empty registry
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Empty tool list: hasTool returns false, getTool throws")
    void emptyRegistry_behaviourIsCorrect() {
        ToolRegistry registry = new ToolRegistry(List.of());

        assertThat(registry.hasTool(GeocodeTool.NAME)).isFalse();
        assertThat(registry.getToolNames()).isEmpty();
        assertThatThrownBy(() -> registry.getTool(GeocodeTool.NAME))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
