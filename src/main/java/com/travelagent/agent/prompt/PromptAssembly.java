package com.travelagent.agent.prompt;

import java.util.ArrayList;
import java.util.List;

public class PromptAssembly {

    private final List<PromptSection> sections = new ArrayList<>();

    public static PromptAssembly create() {
        return new PromptAssembly();
    }

    public static PromptAssembly legacy(String systemPrompt, String userMessage) {
        return create()
                .add(PromptSectionType.SYSTEM, systemPrompt)
                .add(PromptSectionType.CURRENT_GOAL, userMessage);
    }

    public PromptAssembly add(PromptSectionType type, String content) {
        PromptSection section = new PromptSection(type, content);
        if (!section.isBlank()) {
            sections.add(section);
        }
        return this;
    }

    public PromptAssembly add(PromptSection section) {
        if (section != null && !section.isBlank()) {
            sections.add(section);
        }
        return this;
    }

    public List<PromptSection> sections() {
        return List.copyOf(sections);
    }

    public String render() {
        return new PromptAssembler().render(this);
    }
}
