package com.travelagent.agent.trace;

import java.util.Map;

public record TraceEvent(String node,
                         String route,
                         Map<String, Object> input,
                         Map<String, Object> output,
                         String message) {
}
