package com.travelagent.agent.mcp;

import java.util.Map;

/**
 * 初始化McpInitializeResult 实例。
 * @param protocolVersion p ro to co lV er si on 参数
 * @param capabilities c ap ab il it ie s 参数
 * @param serverInfo s er ve rI nf o 参数
 */
public record McpInitializeResult(
        String protocolVersion,
        Map<String, Object> capabilities,
        Map<String, Object> serverInfo
) {
}
