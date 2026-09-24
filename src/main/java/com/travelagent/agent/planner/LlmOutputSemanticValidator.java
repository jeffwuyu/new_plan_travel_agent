package com.travelagent.agent.planner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class LlmOutputSemanticValidator {

    private static final Logger log = LoggerFactory.getLogger(LlmOutputSemanticValidator.class);

    @SuppressWarnings("unchecked")
    public Map<String, Object> filterRouteCandidates(Map<String, Object> parsed,
                                                     Map<String, Object> validationContext) {
        Object routesObj = parsed == null ? null : parsed.get("routes");
        if (!(routesObj instanceof List<?> routes)) {
            return parsed;
        }

        Set<String> allowedIds = stringSet(validationContext, "allowedRouteIds", "candidateIds", "ragCandidateIds");
        Set<String> allowedNames = lowerStringSet(validationContext, "allowedAttractionNames", "candidateNames", "ragCandidateNames");
        Integer remainingBudget = positiveInteger(validationContext == null ? null : validationContext.get("remainingTimeBudgetMin"));

        List<Object> validRoutes = new ArrayList<>();
        for (Object item : routes) {
            if (!(item instanceof Map<?, ?> route)) {
                continue;
            }
            List<String> violations = validateRoute(route, allowedIds, allowedNames, remainingBudget);
            if (violations.isEmpty()) {
                validRoutes.add(item);
            } else {
                log.warn("[LlmOutputSemanticValidator] Dropping invalid route candidate routeId={} reasons={}",
                        route.get("routeId"), violations);
            }
        }

        Map<String, Object> filtered = new LinkedHashMap<>(parsed);
        filtered.put("routes", validRoutes);
        return filtered;
    }

    private List<String> validateRoute(Map<?, ?> route,
                                       Set<String> allowedIds,
                                       Set<String> allowedNames,
                                       Integer remainingBudget) {
        List<String> violations = new ArrayList<>();
        String routeId = stringValue(route.get("routeId"));
        if (!allowedIds.isEmpty() && !allowedIds.contains(routeId)) {
            violations.add("routeId not in ranked candidate list");
        }

        String targetName = firstNonBlank(route.get("targetAttractionName"), route.get("title"));
        if (!allowedNames.isEmpty()
                && !targetName.isBlank()
                && !allowedNames.contains(targetName.toLowerCase(Locale.ROOT))) {
            violations.add("target attraction not in ranked candidate list");
        }

        Integer duration = positiveInteger(route.get("estimatedTotalDurationMin"));
        if (remainingBudget != null && duration != null && duration > remainingBudget) {
            violations.add("duration exceeds remaining budget");
        }

        Object highlights = route.get("reasonHighlights");
        if (highlights instanceof List<?> list && (list.size() < 2 || list.size() > 4)) {
            violations.add("reasonHighlights must contain 2 to 4 items");
        }
        return violations;
    }

    private Set<String> stringSet(Map<String, Object> context, String... keys) {
        Set<String> values = new LinkedHashSet<>();
        if (context == null) {
            return values;
        }
        for (String key : keys) {
            Object value = context.get(key);
            if (value instanceof List<?> list) {
                for (Object item : list) {
                    String text = stringValue(item);
                    if (!text.isBlank()) {
                        values.add(text);
                    }
                }
            }
        }
        return values;
    }

    private Set<String> lowerStringSet(Map<String, Object> context, String... keys) {
        Set<String> values = new LinkedHashSet<>();
        for (String value : stringSet(context, keys)) {
            values.add(value.toLowerCase(Locale.ROOT));
        }
        return values;
    }

    private Integer positiveInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue() > 0 ? number.intValue() : null;
        }
        if (value != null) {
            try {
                int parsed = Integer.parseInt(value.toString());
                return parsed > 0 ? parsed : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String firstNonBlank(Object... values) {
        for (Object value : values == null ? new Object[0] : values) {
            String text = stringValue(value);
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString().trim();
    }
}
