package com.travelagent.advisor;

import com.travelagent.agent.context.CompletedStep;
import com.travelagent.agent.context.PlanningConfig;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Adds travel-planning constraints and domain context to the system prompt.
 */
@Component
public class TravelPlanningAdvisor implements BaseAdvisor {

    public static final String NAME = "travelPlanning";

    private static final int ORDER = 100;

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain advisorChain) {
        String addition = buildPlanningInstructions(request.context());
        if (addition.isBlank()) {
            return request;
        }
        return request.mutate()
                .prompt(appendSystemText(request.prompt(), addition))
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain advisorChain) {
        return response;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    String buildPlanningInstructions(Map<String, Object> context) {
        String region = asString(context.get(AdvisorContextKeys.REGION));
        String userIntent = asString(context.get(AdvisorContextKeys.USER_INTENT));
        PlanningConfig planningConfig = getPlanningConfig(context);
        List<CompletedStep> completedSteps = getCompletedSteps(context);
        Integer sameDayRadiusKm = asInteger(context.get(AdvisorContextKeys.SAME_DAY_RADIUS_KM));

        StringBuilder sb = new StringBuilder();
        sb.append("Travel-planning constraints:\n");
        if (!region.isBlank()) {
            sb.append("- Destination region: ").append(region).append("\n");
        }
        if (planningConfig != null) {
            sb.append("- Trip parameters: totalDays=")
                    .append(planningConfig.getTotalDays())
                    .append(", attractionsPerDay=")
                    .append(planningConfig.getAttractionsPerDay())
                    .append(", travelMode=")
                    .append(nullToEmpty(planningConfig.getTravelMode()))
                    .append("\n");
            if (planningConfig.getPreferenceKeywords() != null
                    && !planningConfig.getPreferenceKeywords().isEmpty()) {
                sb.append("- User preferences: ")
                        .append(String.join(", ", planningConfig.getPreferenceKeywords()))
                        .append("\n");
            }
        }
        if (!userIntent.isBlank()) {
            sb.append("- User intent: ").append(userIntent).append("\n");
        }
        if (!completedSteps.isEmpty()) {
            String visited = completedSteps.stream()
                    .map(CompletedStep::getAttractionName)
                    .filter(Objects::nonNull)
                    .filter(name -> !name.isBlank())
                    .collect(Collectors.joining(", "));
            if (!visited.isBlank()) {
                sb.append("- Already planned attractions: ").append(visited).append("\n");
                sb.append("- Do not recommend already planned attractions.\n");
            }
        }
        if (sameDayRadiusKm != null) {
            sb.append("- Attractions within the same day must be within ")
                    .append(sameDayRadiusKm)
                    .append(" km of each other.\n");
        }
        sb.append("- Prefer coherent sightseeing order and realistic travel continuity.");
        return sb.toString();
    }

    private Prompt appendSystemText(Prompt prompt, String addition) {
        List<Message> messages = new ArrayList<>(prompt.getInstructions());
        int systemIndex = findSystemMessageIndex(messages);
        if (systemIndex >= 0) {
            SystemMessage existing = (SystemMessage) messages.get(systemIndex);
            String merged = mergeText(existing.getText(), addition);
            messages.set(systemIndex, new SystemMessage(merged));
        } else {
            messages.add(0, new SystemMessage(addition));
        }
        return new Prompt(messages, prompt.getOptions());
    }

    private int findSystemMessageIndex(List<Message> messages) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof SystemMessage) {
                return i;
            }
        }
        return -1;
    }

    @SuppressWarnings("unchecked")
    private List<CompletedStep> getCompletedSteps(Map<String, Object> context) {
        Object value = context.get(AdvisorContextKeys.COMPLETED_STEPS);
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(CompletedStep.class::isInstance)
                    .map(CompletedStep.class::cast)
                    .toList();
        }
        return List.of();
    }

    private PlanningConfig getPlanningConfig(Map<String, Object> context) {
        Object value = context.get(AdvisorContextKeys.PLANNING_CONFIG);
        return value instanceof PlanningConfig config ? config : null;
    }

    private Integer asInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return null;
    }

    private String asString(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String mergeText(String existing, String addition) {
        if (existing == null || existing.isBlank()) {
            return addition;
        }
        return existing + "\n\n" + addition;
    }
}
