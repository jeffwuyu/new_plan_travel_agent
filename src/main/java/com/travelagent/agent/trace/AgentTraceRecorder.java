package com.travelagent.agent.trace;

import com.travelagent.agent.orchestration.AgentNodeTrace;
import com.travelagent.agent.orchestration.AgentWorkflowContext;
import com.travelagent.agent.validation.ValidatorResult;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class AgentTraceRecorder {

    public TraceSnapshot fromWorkflow(AgentWorkflowContext context) {
        TraceSnapshot snapshot = new TraceSnapshot();
        snapshot.setTraceId("trace-" + Instant.now().toEpochMilli());
        snapshot.setUserInput(context == null ? null : context.getRequestText());
        if (context == null) {
            return snapshot;
        }
        snapshot.setStructuredConstraints(context.getConstraints());
        snapshot.setPlan(context.getPlan());
        snapshot.setFinalOutput(context.getFinalItinerary());
        for (AgentNodeTrace trace : context.getTraces()) {
            snapshot.getEvents().add(new TraceEvent(trace.getNode().name(), trace.getRoute().name(),
                    trace.getInput(), trace.getOutput(), trace.getMessage()));
        }
        return snapshot;
    }

    public TraceSnapshot recordValidator(TraceSnapshot snapshot, ValidatorResult validatorResult) {
        TraceSnapshot target = snapshot == null ? new TraceSnapshot() : snapshot;
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("valid", validatorResult == null || validatorResult.isValid());
        output.put("issues", validatorResult == null ? List.of() : validatorResult.getIssues());
        target.getEvents().add(new TraceEvent("VALIDATOR", "NEXT", Map.of(), output, "validator recorded"));
        return target;
    }

    public TraceSnapshot recordToolCall(TraceSnapshot snapshot,
                                        String toolName,
                                        Map<String, Object> input,
                                        Map<String, Object> output,
                                        String message) {
        TraceSnapshot target = snapshot == null ? new TraceSnapshot() : snapshot;
        target.getEvents().add(new TraceEvent("TOOL:" + nullToUnknown(toolName), "NEXT",
                copy(input), copy(output), message));
        return target;
    }

    public TraceSnapshot recordRagSearch(TraceSnapshot snapshot,
                                         String query,
                                         List<Map<String, Object>> recalledDocuments,
                                         List<Map<String, Object>> rankingResults) {
        TraceSnapshot target = snapshot == null ? new TraceSnapshot() : snapshot;
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("query", query);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("recalledDocuments", recalledDocuments == null ? List.of() : recalledDocuments);
        output.put("rankingResults", rankingResults == null ? List.of() : rankingResults);
        target.getEvents().add(new TraceEvent("RAG_SEARCH", "NEXT", input, output, "rag search recorded"));
        return target;
    }

    public TraceSnapshot recordUserFeedback(TraceSnapshot snapshot, String feedback) {
        TraceSnapshot target = snapshot == null ? new TraceSnapshot() : snapshot;
        target.getEvents().add(new TraceEvent("USER_FEEDBACK", "NEXT", Map.of("feedback", feedback), Map.of(), "feedback recorded"));
        return target;
    }

    private Map<String, Object> copy(Map<String, Object> value) {
        return value == null ? Map.of() : new LinkedHashMap<>(value);
    }

    private String nullToUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
