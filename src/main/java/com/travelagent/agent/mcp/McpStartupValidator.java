package com.travelagent.agent.mcp;

import com.travelagent.config.AgentMcpProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class McpStartupValidator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(McpStartupValidator.class);

    private final AgentMcpProperties properties;
    private final McpHandshakeService handshakeService;
    private final McpToolCatalog toolCatalog;

    public McpStartupValidator(AgentMcpProperties properties,
                               McpHandshakeService handshakeService,
                               McpToolCatalog toolCatalog) {
        this.properties = properties;
        this.handshakeService = handshakeService;
        this.toolCatalog = toolCatalog;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isEnabled()) {
            return;
        }

        try {
            McpInitializeResult init = handshakeService.ensureInitialized();
            Map<String, McpToolDefinition> tools = toolCatalog.ensureLoaded();
            log.info("[MCP] Startup validation complete. serverInfo={}, tools={}",
                    init.serverInfo(), tools.keySet());

            for (Map.Entry<String, String> entry : properties.getToolMapping().entrySet()) {
                if (!tools.containsKey(entry.getValue())) {
                    log.warn("[MCP] Missing mapped tool. internalTool={} mcpTool={}",
                            entry.getKey(), entry.getValue());
                }
            }
        } catch (Exception e) {
            log.warn("[MCP] Startup validation failed, MCP tools will use fallback when invoked: {}",
                    e.getMessage());
            toolCatalog.invalidate();
        }
    }
}
