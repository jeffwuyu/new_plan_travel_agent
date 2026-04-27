package com.travelagent.agent.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelagent.config.AgentMcpProperties;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
public class McpToolInvoker {

    private final AgentMcpProperties properties;
    private final McpToolCatalog toolCatalog;
    private final McpSessionClient sessionClient;
    private final ObjectMapper objectMapper;

    /**
     * 初始化McpToolInvoker 实例。
     * @param properties 配置属性
     * @param toolCatalog t oo lC at al og 参数
     * @param sessionClient s es si on Cl ie nt 参数
     * @param objectMapper o bj ec tM ap pe r 参数
     */
    public McpToolInvoker(AgentMcpProperties properties,
                          McpToolCatalog toolCatalog,
                          McpSessionClient sessionClient,
                          ObjectMapper objectMapper) {
        this.properties = properties;
        this.toolCatalog = toolCatalog;
        this.sessionClient = sessionClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 处理callTool。
     * @param toolName t oo lN am e 参数
     * @param arguments 工具调用参数
     * @return 返回处理结果。
     */
    public McpToolCallResult callTool(String toolName, Map<String, Object> arguments) {
        toolCatalog.requireTool(toolName);
        JsonNode resultNode = sessionClient.sendRequest("tools/call", Map.of(
                "name", toolName,
                "arguments", arguments == null ? Map.of() : arguments
        ), Duration.ofMillis(properties.getRequestTimeoutMs()));

        boolean isError = resultNode.has("isError") && resultNode.get("isError").asBoolean(false);
        Map<String, Object> structuredContent = nodeToMap(resultNode.get("structuredContent"));
        List<Map<String, Object>> content = nodeToList(resultNode.get("content"));
        Map<String, Object> raw = nodeToMap(resultNode);
        return new McpToolCallResult(isError, structuredContent, content, raw);
    }

    /**
     * 处理nodeToMap。
     * @param node n od e 参数
     * @return 返回处理后的映射结果。
     */
    private Map<String, Object> nodeToMap(JsonNode node) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        return objectMapper.convertValue(node, new TypeReference<>() {});
    }

    /**
     * 处理nodeToList。
     * @param node n od e 参数
     * @return 返回处理后的列表结果。
     */
    private List<Map<String, Object>> nodeToList(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        return objectMapper.convertValue(node, new TypeReference<>() {});
    }
}
