package com.travelagent.agent.prompt;

public record PromptSection(PromptSectionType type, String content) {

    public PromptSection {
        if (type == null) {
            throw new IllegalArgumentException("Prompt section type is required");
        }
    }

    public boolean isBlank() {
        return content == null || content.isBlank();
    }
}
