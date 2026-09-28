package com.travelagent.service.routemap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RouteMapPromptBuilder Tests")
class RouteMapPromptBuilderTest {

    private final RouteMapPromptBuilder builder = new RouteMapPromptBuilder();

    @Test
    void build_rendersSectionedPromptWithConstraintsAndStops() {
        String prompt = builder.build(RouteMapStyles.JOURNAL, List.of(
                Map.of("order", 1, "name", "West Lake"),
                Map.of("order", 2, "name", "Lingyin Temple")
        ));

        assertThat(prompt).contains("## System", "## Policy", "## Current Goal", "## Observation", "## Output Format");
        assertThat(prompt).contains("hand-drawn travel journal map");
        assertThat(prompt).contains("Preserve every numbered stop");
        assertThat(prompt).contains("1. West Lake; 2. Lingyin Temple");
    }
}
