package com.travelagent.agent.scratchpad;

import com.travelagent.agent.context.TaskCheckpoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ScratchpadManagementService Tests")
class ScratchpadManagementServiceTest {

    @Test
    void compact_keepsRecentStepsSummarizesOlderAndDedupesObservations() {
        ScratchpadManagementProperties properties = new ScratchpadManagementProperties();
        properties.setKeepRecentSteps(2);
        ScratchpadManagementService service = new ScratchpadManagementService(properties);
        TaskCheckpoint checkpoint = new TaskCheckpoint();

        for (int step = 0; step < 5; step++) {
            service.recordThought(checkpoint, step, "planning", "choose attraction step " + step);
            service.recordAction(checkpoint, step, "weather", Map.of("adcode", "110000"));
            service.recordObservation(checkpoint, step, "weather", Map.of(
                    "weather", "sunny",
                    "temperature", "28",
                    "queryTime", "2026-06-23T00:00:00Z"
            ), "SUCCESS");
        }
        service.recordObservation(checkpoint, 4, "weather", Map.of(
                "weather", "sunny",
                "temperature", "28",
                "queryTime", "2026-06-23T00:00:00Z"
        ), "SUCCESS");

        service.compact(checkpoint);

        List<Map<String, Object>> scratchpad = checkpoint.getReactScratchpad();
        assertThat(scratchpad).isNotEmpty();
        assertThat(scratchpad.get(0)).containsEntry("type", ScratchpadManagementService.TYPE_SUMMARY);
        assertThat(scratchpad.get(0).get("content").toString()).contains("step 0").contains("step 2");
        assertThat(scratchpad)
                .filteredOn(entry -> ScratchpadManagementService.TYPE_OBSERVATION.equals(entry.get("type")))
                .hasSize(1);
        assertThat(scratchpad)
                .filteredOn(entry -> !ScratchpadManagementService.TYPE_SUMMARY.equals(entry.get("type")))
                .allSatisfy(entry -> assertThat((Integer) entry.get("stepIndex")).isIn(3, 4));
        assertThat(checkpoint.getScratchpadTrimmedAt()).isNotNull();
    }

    @Test
    void recordAction_redactsSensitiveArguments() {
        ScratchpadManagementService service = new ScratchpadManagementService(new ScratchpadManagementProperties());
        TaskCheckpoint checkpoint = new TaskCheckpoint();

        service.recordAction(checkpoint, 0, "web_search", Map.of(
                "query", "hotel",
                "apiKey", "secret-value",
                "nested", Map.of("token", "abc")
        ));

        String scratchpad = checkpoint.getReactScratchpad().toString();
        assertThat(scratchpad).contains("[REDACTED]");
        assertThat(scratchpad).doesNotContain("secret-value").doesNotContain("abc");
    }
}
