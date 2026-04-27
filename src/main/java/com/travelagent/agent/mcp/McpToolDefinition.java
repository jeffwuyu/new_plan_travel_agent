package com.travelagent.agent.mcp;

import java.util.Map;

/**
 * 初始化McpToolDefinition 实例。
 * @param name n am e 参数
 * @param title t it le 参数
 * @param description d es cr ip ti on 参数
 * @param inputSchema i np ut Sc he ma 参数
 */
public record McpToolDefinition(
        String name,
        String title,
        String description,
        Map<String, Object> inputSchema
) {
}
