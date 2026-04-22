package com.travelagent.agent.mcp;

import java.util.List;
import java.util.Map;

public record McpToolCallResult(
        boolean isError,
        Map<String, Object> structuredContent,
        List<Map<String, Object>> content,
        Map<String, Object> rawResult
) {
}
