package com.travelagent.agent.mcp;

import java.util.Map;

public record McpToolDefinition(
        String name,
        String title,
        String description,
        Map<String, Object> inputSchema
) {
}
