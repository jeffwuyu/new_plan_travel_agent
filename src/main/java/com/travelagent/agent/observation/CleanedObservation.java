package com.travelagent.agent.observation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record CleanedObservation(String summary,
                                 List<Map<String, Object>> evidences,
                                 List<String> warnings,
                                 Map<String, Object> stats,
                                 Map<String, Object> cleaningMetadata,
                                 Map<String, Object> toolOutput) {

    public CleanedObservation {
        evidences = evidences == null ? List.of() : List.copyOf(evidences);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        stats = stats == null ? Map.of() : new LinkedHashMap<>(stats);
        cleaningMetadata = cleaningMetadata == null ? Map.of() : new LinkedHashMap<>(cleaningMetadata);
        toolOutput = toolOutput == null ? Map.of() : new LinkedHashMap<>(toolOutput);
    }
}
