package com.travelagent.advisor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JsonSchemaAdvisor Tests")
class JsonSchemaAdvisorTest {

    private final JsonSchemaAdvisor advisor = new JsonSchemaAdvisor();

    @Test
    @DisplayName("buildSchemaInstructions enforces JSON-only response")
    void buildSchemaInstructions_enforcesJsonOnly() {
        String text = advisor.buildSchemaInstructions(Map.of(
                AdvisorContextKeys.RESPONSE_SCHEMA, Map.of(
                        "type", "object",
                        "required", List.of("attractionName", "reason")
                )
        ));

        assertThat(text).contains("JSON");
        assertThat(text).contains("Markdown");
        assertThat(text).contains("object");
        assertThat(text).contains("attractionName, reason");
    }
}
