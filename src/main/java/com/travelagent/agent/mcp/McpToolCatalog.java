package com.travelagent.agent.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelagent.config.AgentMcpProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class McpToolCatalog {

    private static final Logger log = LoggerFactory.getLogger(McpToolCatalog.class);

    private final AgentMcpProperties properties;
    private final McpHandshakeService handshakeService;
    private final McpSessionClient sessionClient;
    private final ObjectMapper objectMapper;
    private volatile Map<String, McpToolDefinition> toolsByName = Map.of();
    private volatile long loadedRevision = -1;

    /**
     * 初始化McpToolCatalog 实例。
     * @param properties 配置属性
     * @param handshakeService h an ds ha ke Se rv ic e 参数
     * @param sessionClient s es si on Cl ie nt 参数
     * @param objectMapper o bj ec tM ap pe r 参数
     */
    public McpToolCatalog(AgentMcpProperties properties,
                          McpHandshakeService handshakeService,
                          McpSessionClient sessionClient,
                          ObjectMapper objectMapper) {
        this.properties = properties;
        this.handshakeService = handshakeService;
        this.sessionClient = sessionClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 处理ensureLoaded。
     * @return 返回处理后的映射结果。
     */
    public synchronized Map<String, McpToolDefinition> ensureLoaded() {
        long currentRevision = sessionClient.currentRevision();
        if (!toolsByName.isEmpty() && loadedRevision == currentRevision) {
            return toolsByName;
        }

        handshakeService.ensureInitialized();
        Map<String, McpToolDefinition> loaded = new LinkedHashMap<>();
        String cursor = null;

        do {
            JsonNode resultNode = sessionClient.sendRequest("tools/list",
                    cursor == null ? Map.of() : Map.of("cursor", cursor),
                    Duration.ofMillis(properties.getRequestTimeoutMs()));

            JsonNode toolsNode = resultNode.get("tools");
            if (toolsNode != null && toolsNode.isArray()) {
                for (JsonNode toolNode : toolsNode) {
                    String name = readText(toolNode, "name");
                    if (name == null || name.isBlank()) {
                        continue;
                    }
                    loaded.put(name, new McpToolDefinition(
                            name,
                            readText(toolNode, "title"),
                            readText(toolNode, "description"),
                            nodeToMap(toolNode.get("inputSchema"))
                    ));
                }
            }
            cursor = readText(resultNode, "nextCursor");
        } while (cursor != null && !cursor.isBlank());

        toolsByName = loaded;
        loadedRevision = sessionClient.currentRevision();
        log.info("[MCP] Loaded tools: {}", loaded.keySet());
        return loaded;
    }

    /**
     * 处理requireTool。
     * @param name n am e 参数
     * @return 返回处理结果。
     */
    public McpToolDefinition requireTool(String name) {
        McpToolDefinition tool = ensureLoaded().get(name);
        if (tool == null) {
            throw new McpException("MCP tool not available: " + name);
        }
        return tool;
    }

    /**
     * 处理invalidate。
     */
    public synchronized void invalidate() {
        toolsByName = Map.of();
        loadedRevision = -1;
        handshakeService.invalidate();
    }

    /**
     * 处理readText。
     * @param node n od e 参数
     * @param field f ie ld 参数
     * @return 返回处理结果。
     */
    private String readText(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
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
}
