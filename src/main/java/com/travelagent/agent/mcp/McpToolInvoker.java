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

    public McpToolInvoker(AgentMcpProperties properties,
                          McpToolCatalog toolCatalog,
                          McpSessionClient sessionClient,
                          ObjectMapper objectMapper) {
        this.properties = properties;
        this.toolCatalog = toolCatalog;
        this.sessionClient = sessionClient;
        this.objectMapper = objectMapper;
    }

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

    private Map<String, Object> nodeToMap(JsonNode node) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        return objectMapper.convertValue(node, new TypeReference<>() {});
    }

    private List<Map<String, Object>> nodeToList(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        return objectMapper.convertValue(node, new TypeReference<>() {});
    }
}
