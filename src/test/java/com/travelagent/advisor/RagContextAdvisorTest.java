package com.travelagent.advisor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RagContextAdvisor Tests")
class RagContextAdvisorTest {

    private final RagContextAdvisor advisor = new RagContextAdvisor();

    @Test
    @DisplayName("buildRagInstructions injects chunk list")
    void buildRagInstructions_injectsChunkList() {
        String text = advisor.buildRagInstructions(Map.of(
                AdvisorContextKeys.RAG_CHUNKS, List.of("兵马俑位于西安临潼区。", "建议预留半天游览时间。")
        ));

        assertThat(text).contains("Reference information from travel guides:");
        assertThat(text).contains("兵马俑位于西安临潼区。");
        assertThat(text).contains("建议预留半天游览时间。");
    }
}
