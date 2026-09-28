package com.travelagent.agent.planner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelagent.model.dto.LocationCandidateItem;
import com.travelagent.util.JsonUtil;
import com.travelagent.validation.JsonSchemaValidationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PlannerResponseParser Tests")
class PlannerResponseParserTest {

    private final PlannerResponseParser responseParser = new PlannerResponseParser();

    @BeforeEach
    void setUp() {
        JsonUtil jsonUtil = new JsonUtil();
        ReflectionTestUtils.setField(jsonUtil, "objectMapper",
                new ObjectMapper().findAndRegisterModules());
        ReflectionTestUtils.setField(responseParser, "jsonUtil", jsonUtil);
        ReflectionTestUtils.setField(responseParser, "jsonSchemaValidationService",
                new JsonSchemaValidationService(new ObjectMapper().findAndRegisterModules()));
        ReflectionTestUtils.setField(responseParser, "semanticValidator", new LlmOutputSemanticValidator());
    }

    @Test
    void parseLlmAttractionName_validJson_returnsName() {
        String json = "{\"attractionName\":\"Terracotta Army\",\"reason\":\"Famous site\"}";

        assertThat(responseParser.parseLlmAttractionName(json, 0))
                .isEqualTo("Terracotta Army");
    }

    @Test
    void parseRouteCandidates_prefersLlmReasonHighlightsAndFiltersProcessTerms() {
        String response = """
                {
                  "routes": [
                    {
                      "routeId": "route-1",
                      "title": "Xi'an City Wall Night Walk",
                      "targetAttractionName": "Xi'an City Wall",
                      "stops": ["Yongning Gate", "City Wall"],
                      "reasonHighlights": ["historical atmosphere", "route recommendation", "night view", "weather fit"],
                      "reason": "Great for a relaxed evening walk.",
                      "estimatedTotalDurationMin": 180,
                      "weatherSuitability": "comfortable"
                    }
                  ]
                }
                """;

        List<LocationCandidateItem> result = responseParser.parseRouteCandidates(response, Map.of("summary", "clear sky"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getHighlights()).containsExactly("night view");
    }

    @Test
    void parseRouteCandidates_withoutReasonHighlights_fallsBackToAttractionKeywords() {
        String response = """
                {
                  "routes": [
                    {
                      "routeId": "route-2",
                      "title": "Muslim Quarter Food Walk",
                      "targetAttractionName": "Muslim Quarter",
                      "stops": ["Drum Tower", "Muslim Quarter"],
                      "reason": "food experience lively night vibe",
                      "estimatedTotalDurationMin": 150,
                      "weatherSuitability": "normal"
                    }
                  ]
                }
                """;

        List<LocationCandidateItem> result = responseParser.parseRouteCandidates(response, Map.of("summary", "clear sky"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getHighlights()).isNotEmpty();
        assertThat(result.get(0).getHighlights())
                .doesNotContain("route", "weather", "budget");
        assertThat(result.get(0).getHighlights())
                .anySatisfy(value -> assertThat(value)
                        .isIn("Muslim", "Quarter", "Food", "Walk", "Drum", "Tower", "food", "experience", "lively", "night", "vibe"));
    }

    @Test
    void parseRouteCandidates_schemaViolation_returnsEmptyList() {
        String response = """
                {
                  "routes": [
                    {
                      "routeId": "route-1",
                      "title": "Bad route",
                      "targetAttractionName": "Unknown",
                      "stops": ["Unknown"],
                      "reason": "Missing duration type.",
                      "estimatedTotalDurationMin": "two hours",
                      "weatherSuitability": "normal"
                    }
                  ]
                }
                """;

        List<LocationCandidateItem> result = responseParser.parseRouteCandidates(response, Map.of("summary", "clear sky"));

        assertThat(result).isEmpty();
    }

    @Test
    void parseRouteCandidates_semanticInvalidRouteId_isFiltered() {
        String response = """
                {
                  "routes": [
                    {
                      "routeId": "not-from-rag",
                      "title": "Xi'an City Wall",
                      "targetAttractionName": "Xi'an City Wall",
                      "stops": ["Xi'an City Wall"],
                      "reasonHighlights": ["history", "night view"],
                      "reason": "Good fit.",
                      "estimatedTotalDurationMin": 120,
                      "weatherSuitability": "normal"
                    }
                  ]
                }
                """;

        List<LocationCandidateItem> result = responseParser.parseRouteCandidates(response,
                Map.of("summary", "clear sky", "allowedRouteIds", List.of("candidate-1")));

        assertThat(result).isEmpty();
    }
}
