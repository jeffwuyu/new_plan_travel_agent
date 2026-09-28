package com.travelagent.agent.prompt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PromptAssembler Tests")
class PromptAssemblerTest {

    @Test
    void render_ordersSectionsAndSkipsBlankValues() {
        PromptAssembly assembly = PromptAssembly.create()
                .add(PromptSectionType.OBSERVATION, "observed")
                .add(PromptSectionType.SYSTEM, "system")
                .add(PromptSectionType.POLICY, "policy")
                .add(PromptSectionType.MEMORY, " ")
                .add(PromptSectionType.OUTPUT_FORMAT, "json");

        String rendered = new PromptAssembler().render(assembly);

        assertThat(rendered).contains("## System", "## Policy", "## Observation", "## Output Format");
        assertThat(rendered).doesNotContain("## Memory");
        assertThat(rendered.indexOf("## System")).isLessThan(rendered.indexOf("## Policy"));
        assertThat(rendered.indexOf("## Policy")).isLessThan(rendered.indexOf("## Observation"));
        assertThat(rendered.indexOf("## Observation")).isLessThan(rendered.indexOf("## Output Format"));
    }

    @Test
    void render_mergesRepeatedSectionsInAppendOrder() {
        PromptAssembly assembly = PromptAssembly.create()
                .add(PromptSectionType.POLICY, "first")
                .add(PromptSectionType.POLICY, "second");

        String rendered = new PromptAssembler().render(assembly);

        assertThat(rendered).isEqualTo("## Policy\nfirst\n\nsecond");
    }
}
