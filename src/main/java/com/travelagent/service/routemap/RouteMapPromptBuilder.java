package com.travelagent.service.routemap;

import com.travelagent.agent.prompt.PromptAssembly;
import com.travelagent.agent.prompt.PromptSectionType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class RouteMapPromptBuilder {

    public String build(String style, List<Map<String, Object>> stops) {
        String styleText = switch (style) {
            case RouteMapStyles.JOURNAL -> "hand-drawn travel journal map, paper texture, stickers, clean handwritten feeling";
            case RouteMapStyles.WATERCOLOR -> "soft watercolor travel map, airy city blocks, gentle low saturation colors";
            default -> "bright anime travel illustration map, clean sunny city atmosphere, crisp scenic route";
        };
        String stopList = stops.stream()
                .map(s -> s.get("order") + ". " + s.get("name"))
                .collect(Collectors.joining("; "));
        return PromptAssembly.create()
                .add(PromptSectionType.SYSTEM, "Transform the input route skeleton into a stylized travel route map.")
                .add(PromptSectionType.POLICY, """
                        Hard constraints:
                        1. Preserve every numbered stop and its relative position.
                        2. Preserve the route connection order from stop 1 to the final stop.
                        3. Do not add extra attractions, roads, arrows, or labels that change the route.
                        4. Do not invent Chinese place names. The backend will overlay final Chinese labels.
                        5. Keep enough clean space around labels and route lines.
                        """)
                .add(PromptSectionType.CURRENT_GOAL, "Style: " + styleText + ".")
                .add(PromptSectionType.OBSERVATION, "Stops: " + stopList + ".")
                .add(PromptSectionType.OUTPUT_FORMAT, "Return only the edited image content. Do not render extra labels that change the route.")
                .render();
    }
}
