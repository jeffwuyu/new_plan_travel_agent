package com.travelagent.agent.mcp;

import java.util.List;
import java.util.Map;

/**
 * 初始化McpToolCallResult 实例。
 * @param isError i sE rr or 参数
 * @param structuredContent 结构化内容
 * @param content 内容
 * @param rawResult 原始结果
 */
public record McpToolCallResult(
        boolean isError,
        Map<String, Object> structuredContent,
        List<Map<String, Object>> content,
        Map<String, Object> rawResult
) {
}
