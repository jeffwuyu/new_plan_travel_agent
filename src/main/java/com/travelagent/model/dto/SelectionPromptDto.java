package com.travelagent.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
public class SelectionPromptDto {

    private String pendingInputType;
    private String selectionStage;
    private String selectedBranchType;
    private String startLocationQuery;
    private Integer stepIndex;
    private Integer dayNumber;
    private String currentPositionName;
    private Integer remainingTimeBudgetMin;
    private String destinationName;
    private String userPreferencePrompt;
    private String weatherSummary;
    private List<String> weatherConstraintHints = new ArrayList<>();

    public static SelectionPromptDto from(String pendingInputType,
                                          String selectionStage,
                                          String selectedBranchType,
                                          String startLocationQuery,
                                          Integer stepIndex,
                                          Integer dayNumber,
                                          Map<String, Object> currentContext,
                                          Map<String, Object> weatherContext) {
        SelectionPromptDto dto = new SelectionPromptDto();
        dto.pendingInputType = pendingInputType;
        dto.selectionStage = selectionStage;
        dto.selectedBranchType = selectedBranchType;
        dto.startLocationQuery = startLocationQuery;
        dto.stepIndex = stepIndex;
        dto.dayNumber = dayNumber;

        if (currentContext != null) {
            dto.currentPositionName = stringValue(currentContext.get("currentPositionName"));
            dto.remainingTimeBudgetMin = intValue(currentContext.get("remainingTimeBudgetMin"));
            dto.destinationName = stringValue(currentContext.get("destinationName"));
            dto.userPreferencePrompt = stringValue(currentContext.get("userPreferencePrompt"));
            if (dto.dayNumber == null) {
                dto.dayNumber = intValue(currentContext.get("dayNumber"));
            }
        }

        if (weatherContext != null) {
            dto.weatherSummary = stringValue(weatherContext.get("summary"));
            dto.weatherConstraintHints = stringList(weatherContext.get("constraintHints"));
        }
        return dto;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(item -> item != null)
                    .map(String::valueOf)
                    .toList();
        }
        return new ArrayList<>();
    }
}
