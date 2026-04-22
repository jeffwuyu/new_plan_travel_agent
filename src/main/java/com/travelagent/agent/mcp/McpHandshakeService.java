package com.travelagent.agent.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelagent.config.AgentMcpProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;

@Service
public class McpHandshakeService {

    private static final Logger log = LoggerFactory.getLogger(McpHandshakeService.class);

    private final AgentMcpProperties properties;
    private final McpSessionClient sessionClient;
    private final ObjectMapper objectMapper;
    private volatile McpInitializeResult initializeResult;
    private volatile long initializedRevision = -1;

    public McpHandshakeService(AgentMcpProperties properties,
                               McpSessionClient sessionClient,
                               ObjectMapper objectMapper) {
        this.properties = properties;
        this.sessionClient = sessionClient;
        this.objectMapper = objectMapper;
    }

    public synchronized McpInitializeResult ensureInitialized() {
        long currentRevision = sessionClient.currentRevision();
        if (initializeResult != null && initializedRevision == currentRevision) {
            return initializeResult;
        }

        JsonNode resultNode = sessionClient.sendRequest("initialize", Map.of(
                "protocolVersion", properties.getProtocolVersion(),
                "capabilities", Map.of(),
                "clientInfo", Map.of(
                        "name", "travel-agent",
                        "version", "1.0.0"
                )
        ), Duration.ofMillis(properties.getStartupTimeoutMs()));

        String protocolVersion = textValue(resultNode, "protocolVersion");
        if (protocolVersion == null || protocolVersion.isBlank()) {
            throw new McpException("MCP initialize response missing protocolVersion");
        }

        Map<String, Object> capabilities = mapValue(resultNode.get("capabilities"));
        Map<String, Object> serverInfo = mapValue(resultNode.get("serverInfo"));
        sessionClient.sendNotification("notifications/initialized", null);

        initializeResult = new McpInitializeResult(protocolVersion, capabilities, serverInfo);
        initializedRevision = sessionClient.currentRevision();
        log.info("[MCP] Handshake complete. protocolVersion={}, serverInfo={}",
                protocolVersion, serverInfo);
        return initializeResult;
    }

    public synchronized void invalidate() {
        initializeResult = null;
        initializedRevision = -1;
        sessionClient.resetSession();
    }

    private String textValue(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private Map<String, Object> mapValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        return objectMapper.convertValue(node, new TypeReference<>() {});
    }
}
