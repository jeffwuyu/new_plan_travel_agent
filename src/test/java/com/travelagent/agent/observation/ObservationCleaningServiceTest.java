package com.travelagent.agent.observation;

import com.travelagent.agent.prompt.PromptAssembly;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelagent.agent.safety.SensitiveInfoGuard;
import com.travelagent.agent.tools.ToolResultValidationProperties;
import com.travelagent.agent.tools.ToolResultValidationResult;
import com.travelagent.agent.tools.ToolResultValidator;
import com.travelagent.agent.tools.WebSearchTool;
import com.travelagent.client.dashscope.DashscopeLlmClient;
import com.travelagent.client.dashscope.LlmCallResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ObservationCleaningService Tests")
class ObservationCleaningServiceTest {

    @Mock private DashscopeLlmClient llmClient;

    @Test
    void cleansLargeHtmlCssAndJsIntoStructuredEvidence() {
        ObservationCleaningService service = serviceWithDefaults();
        String html = largeHtml();

        CleanedObservation cleaned = service.cleanWebObservation(Map.of(
                "available", true,
                "query", "Forbidden City reservation",
                "summary", html,
                "sources", List.of(Map.of(
                        "title", "Official notice",
                        "url", "https://example.gov.cn/notice",
                        "source", "official",
                        "sourceType", "official",
                        "trustScore", 95,
                        "html", html
                )),
                "conflictDetected", false,
                "uncertaintyNote", "Confirm official information before departure.",
                "source", "web-search",
                "queryTime", "2026-06-23T00:00:00Z"
        ), 1L, 1L, "idem-web");

        assertThat(cleaned.toolOutput()).containsKeys("available", "query", "summary", "sources", "cleaningMetadata");
        assertThat(cleaned.toolOutput().toString()).doesNotContain("<script").doesNotContain("function noisy");
        List<?> sources = (List<?>) cleaned.toolOutput().get("sources");
        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).toString()).contains("Ticket booking is required").doesNotContain(".hero");
    }

    @Test
    void llmSummaryIsCappedAtFortyPercentOfCleanedInput() {
        ObservationCleaningProperties properties = new ObservationCleaningProperties();
        properties.setMaxSummaryChars(10_000);
        ObservationCleaningService service = new ObservationCleaningService(properties);
        ReflectionTestUtils.setField(service, "llmClient", llmClient);
        when(llmClient.callWithUsage(any(), any(), anyString(), any(PromptAssembly.class), anyList(), anyString(), anyString()))
                .thenReturn(new LlmCallResult("x".repeat(1_000), 12));

        CleanedObservation cleaned = service.cleanWebObservation(Map.of(
                "summary", "a".repeat(100),
                "sources", List.of(Map.of("title", "Official", "snippet", "a".repeat(100)))
        ), 1L, 1L, "idem-web");

        Map<?, ?> metadata = (Map<?, ?>) cleaned.toolOutput().get("cleaningMetadata");
        int cleanedChars = ((Number) metadata.get("cleanedChars")).intValue();
        int cap = (int) Math.floor(cleanedChars * 0.4d);
        assertThat(cleaned.summary().length()).isLessThanOrEqualTo(cap);
        assertThat(metadata.get("summaryTruncated")).isEqualTo(true);
    }

    @Test
    void llmFailureFallsBackToRuleSummary() {
        ObservationCleaningService service = serviceWithDefaults();
        ReflectionTestUtils.setField(service, "llmClient", llmClient);
        when(llmClient.callWithUsage(any(), any(), anyString(), any(PromptAssembly.class), anyList(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("llm down"));

        CleanedObservation cleaned = service.cleanWebObservation(Map.of(
                "summary", "Official notice says reservation is required. Tickets may sell out.",
                "sources", List.of(Map.of("title", "Official", "snippet", "Reservation is required."))
        ), 1L, 1L, "idem-web");

        assertThat(cleaned.summary()).contains("reservation is required");
        assertThat(cleaned.warnings()).contains("llm_summary_failed");
    }

    @Test
    void cleanedWebObservationPassesToolResultValidation() {
        ObservationCleaningService service = serviceWithDefaults();
        CleanedObservation cleaned = service.cleanWebObservation(Map.of(
                "summary", "<!doctype html><html><body><script>bad()</script><p>Open daily.</p></body></html>",
                "sources", List.of(Map.of(
                        "title", "Official",
                        "url", "https://example.gov.cn",
                        "html", "<html><body><style>.x{color:red}</style><p>Open daily.</p></body></html>"
                ))
        ), 1L, 1L, "idem-web");

        ToolResultValidator validator = new ToolResultValidator(
                new ToolResultValidationProperties(),
                new SensitiveInfoGuard(),
                new ObjectMapper().findAndRegisterModules());
        ToolResultValidationResult result = validator.validate(WebSearchTool.NAME, cleaned.toolOutput());

        assertThat(result.isValid()).isTrue();
        assertThat(result.getIssues()).extracting("code").doesNotContain("html_content");
    }

    private ObservationCleaningService serviceWithDefaults() {
        ObservationCleaningProperties properties = new ObservationCleaningProperties();
        return new ObservationCleaningService(properties);
    }

    private String largeHtml() {
        StringBuilder builder = new StringBuilder();
        builder.append("<!doctype html><html><head><style>.hero{color:red}</style></head><body>");
        builder.append("<nav>menu menu menu</nav><script>function noisy(){return true;}</script>");
        for (int i = 0; i < 10_000; i++) {
            builder.append("<p>Ticket booking is required. Official opening notice line ")
                    .append(i)
                    .append(".</p>");
        }
        builder.append("<footer>footer links</footer></body></html>");
        return builder.toString();
    }
}
