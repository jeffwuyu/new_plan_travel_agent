package com.travelagent.agent.prompt;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class PromptAssembler {

    public String render(PromptAssembly assembly) {
        if (assembly == null) {
            return "";
        }
        Map<PromptSectionType, List<String>> grouped = new EnumMap<>(PromptSectionType.class);
        for (PromptSection section : assembly.sections()) {
            if (section == null || section.isBlank()) {
                continue;
            }
            grouped.computeIfAbsent(section.type(), ignored -> new ArrayList<>())
                    .add(section.content().trim());
        }

        StringBuilder rendered = new StringBuilder();
        for (PromptSectionType type : PromptSectionType.RENDER_ORDER) {
            List<String> values = grouped.get(type);
            if (values == null || values.isEmpty()) {
                continue;
            }
            if (!rendered.isEmpty()) {
                rendered.append("\n\n");
            }
            rendered.append("## ").append(type.title()).append("\n");
            rendered.append(String.join("\n\n", values));
        }
        return rendered.toString();
    }
}
