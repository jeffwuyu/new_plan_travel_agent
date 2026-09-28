package com.travelagent.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("JsonSchemaValidationService Tests")
class JsonSchemaValidationServiceTest {

    private final JsonSchemaValidationService service =
            new JsonSchemaValidationService(new ObjectMapper().findAndRegisterModules());

    @Test
    void validate_validPayload_returnsNoErrors() {
        Map<String, Object> schema = routeSchema();
        Map<String, Object> payload = Map.of(
                "routes", List.of(Map.of(
                        "routeId", "candidate-1",
                        "durationMin", 90
                ))
        );

        assertThat(service.validate("route payload", payload, schema)).isEmpty();
    }

    @Test
    void validate_missingRequiredField_reportsPath() {
        Map<String, Object> payload = Map.of("routes", List.of(Map.of("durationMin", 90)));

        assertThatThrownBy(() -> service.validateOrThrow("route payload", payload, routeSchema()))
                .isInstanceOf(JsonSchemaValidationException.class)
                .hasMessageContaining("routeId");
    }

    @Test
    void validate_typeError_reportsNestedPath() {
        Map<String, Object> payload = Map.of(
                "routes", List.of(Map.of(
                        "routeId", "candidate-1",
                        "durationMin", "ninety"
                ))
        );

        List<JsonSchemaValidationService.ValidationError> errors =
                service.validate("route payload", payload, routeSchema());

        assertThat(errors).isNotEmpty();
        assertThat(errors.get(0).path()).contains("durationMin");
    }

    @Test
    void validate_additionalProperty_reportsViolation() {
        Map<String, Object> payload = Map.of(
                "routes", List.of(Map.of(
                        "routeId", "candidate-1",
                        "durationMin", 90,
                        "extra", true
                ))
        );

        assertThat(service.validate("route payload", payload, routeSchema()))
                .anySatisfy(error -> assertThat(error.message()).contains("extra"));
    }

    private Map<String, Object> routeSchema() {
        return Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", List.of("routes"),
                "properties", Map.of(
                        "routes", Map.of(
                                "type", "array",
                                "items", Map.of(
                                        "type", "object",
                                        "additionalProperties", false,
                                        "required", List.of("routeId", "durationMin"),
                                        "properties", Map.of(
                                                "routeId", Map.of("type", "string"),
                                                "durationMin", Map.of("type", "integer")
                                        )
                                )
                        )
                )
        );
    }
}
