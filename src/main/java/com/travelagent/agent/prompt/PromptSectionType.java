package com.travelagent.agent.prompt;

import java.util.Arrays;
import java.util.List;

public enum PromptSectionType {
    SYSTEM("System"),
    POLICY("Policy"),
    MEMORY("Memory"),
    FEW_SHOT("Few-shot"),
    CURRENT_GOAL("Current Goal"),
    SCRATCHPAD("Scratchpad"),
    TOOL_DESCRIPTION("Tool Description"),
    CONVERSATION("Conversation"),
    OBSERVATION("Observation"),
    OUTPUT_FORMAT("Output Format");

    public static final List<PromptSectionType> RENDER_ORDER = Arrays.asList(values());

    private final String title;

    PromptSectionType(String title) {
        this.title = title;
    }

    public String title() {
        return title;
    }
}
