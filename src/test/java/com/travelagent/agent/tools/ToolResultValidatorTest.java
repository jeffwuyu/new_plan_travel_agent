package com.travelagent.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelagent.agent.safety.SensitiveInfoGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ToolResultValidator Tests")
class ToolResultValidatorTest {

    private final ToolResultValidationProperties properties = new ToolResultValidationProperties();
    private final ToolResultValidator validator = new ToolResultValidator(
            properties,
            new SensitiveInfoGuard(),
            new ObjectMapper().findAndRegisterModules());

    @Test
    void emptyResultIsInvalidAndUnavailable() {
        ToolResultValidationResult result = validator.validate(WebSearchTool.NAME, Map.of());

        assertThat(result.isValid()).isFalse();
        assertThat(result.getOutput()).containsEntry("available", false);
        assertThat(issueCodes(result)).contains("empty_result", "missing_core_data");
        assertThat(result.getOutput()).containsKey("resultValidation");
    }

    @Test
    void oversizedStringIsInvalid() {
        properties.setMaxStringChars(256);
        ToolResultValidationResult result = validator.validate(WebSearchTool.NAME, Map.of(
                "sources", List.of(Map.of("title", "Official")),
                "summary", "x".repeat(300)
        ));

        assertThat(result.isValid()).isFalse();
        assertThat(result.getOutput()).containsEntry("available", false);
        assertThat(issueCodes(result)).contains("string_too_long");
    }

    @Test
    void garbledTextIsInvalid() {
        ToolResultValidationResult result = validator.validate(WebSearchTool.NAME, Map.of(
                "sources", List.of(Map.of("title", "Official")),
                "summary", "bad \uFFFD text"
        ));

        assertThat(result.isValid()).isFalse();
        assertThat(issueCodes(result)).contains("garbled_text");
    }

    @Test
    void htmlContentIsInvalid() {
        ToolResultValidationResult result = validator.validate(WebSearchTool.NAME, Map.of(
                "sources", List.of(Map.of("title", "Official")),
                "summary", "<!doctype html><html><body>blocked</body></html>"
        ));

        assertThat(result.isValid()).isFalse();
        assertThat(issueCodes(result)).contains("html_content");
    }

    @Test
    void lowRiskSensitiveContentIsSanitizedButValid() {
        ToolResultValidationResult result = validator.validate(WebSearchTool.NAME, Map.of(
                "sources", List.of(Map.of(
                        "title", "Official",
                        "snippet", "real name is required for ticket holders"
                ))
        ));

        assertThat(result.isValid()).isTrue();
        assertThat(result.isSanitized()).isTrue();
        assertThat(issueCodes(result)).contains("sensitive_content");
        assertThat(result.getOutput().toString()).contains("[REDACTED:real_name_context]");
    }

    @Test
    void highRiskSensitiveContentIsInvalidAndRawValueIsNotStored() {
        ToolResultValidationResult result = validator.validate(WebSearchTool.NAME, Map.of(
                "sources", List.of(Map.of(
                        "title", "Official",
                        "snippet", "phone 13800138000"
                ))
        ));

        assertThat(result.isValid()).isFalse();
        assertThat(result.getOutput()).containsEntry("available", false);
        assertThat(result.getOutput().toString()).doesNotContain("13800138000");
        assertThat(issueCodes(result)).contains("sensitive_content");
    }

    private List<String> issueCodes(ToolResultValidationResult result) {
        return result.getIssues().stream().map(ToolResultValidationIssue::code).toList();
    }
}
