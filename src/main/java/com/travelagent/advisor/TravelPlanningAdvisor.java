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
                    .collect(Collectors.joining("、"));
            if (!visited.isBlank()) {
                sb.append("- 已规划景点（不得重复推荐）：").append(visited).append("\n");
            }

            // Day-break context: detect if the current step is the first of a new day
            Integer currentDayNumber = asInteger(context.get(AdvisorContextKeys.CURRENT_DAY_NUMBER));
            if (currentDayNumber != null) {
                boolean isFirstOfNewDay = completedSteps.stream()
                        .noneMatch(s -> s.getDayNumber() == currentDayNumber);
                if (isFirstOfNewDay) {
                    CompletedStep prevDayLast = completedSteps.get(completedSteps.size() - 1);
                    sb.append("- 这是新一天的第一个景点，请在地理上与「")
                      .append(prevDayLast.getAttractionName())
                      .append("」所在区域有所区分，开拓新的参观区域。\n");
                }
            }
        }
        if (sameDayRadiusKm != null) {
            // Within-day follow-ons use a tighter 15km radius; day-starts use full radius
            sb.append("- 同一天内续站景点须在上一站 15km 以内，新一天首站不受距离限制。\n");
        }
        sb.append("- 优先保证路线连贯、游览顺序合理、避免大范围折返。");
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
