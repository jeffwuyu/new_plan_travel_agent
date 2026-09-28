package com.travelagent.agent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelagent.config.AgentMcpProperties;
import com.travelagent.validation.JsonSchemaValidationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("McpToolInvoker Tests")
class McpToolInvokerTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void callTool_invalidArguments_doesNotSendToolsCall() {
        AgentMcpProperties properties = new AgentMcpProperties();
        McpToolCatalog catalog = mock(McpToolCatalog.class);
        McpSessionClient sessionClient = mock(McpSessionClient.class);
        McpToolDefinition definition = new McpToolDefinition(
                "maps_weather",
                "Weather",
                "weather lookup",
                Map.of(
                        "type", "object",
                        "required", List.of("city"),
                        "additionalProperties", false,
                        "properties", Map.of("city", Map.of("type", "string", "minLength", 1))
                ));
        when(catalog.requireTool("maps_weather")).thenReturn(definition);

        McpToolInvoker invoker = new McpToolInvoker(
                properties,
                catalog,
                sessionClient,
                objectMapper,
                new JsonSchemaValidationService(objectMapper));

        assertThatThrownBy(() -> invoker.callTool("maps_weather", Map.of("adcode", "330100")))
                .isInstanceOf(McpException.class)
                .hasMessageContaining("MCP arguments for maps_weather");
        verify(sessionClient, never()).sendRequest(
                org.mockito.ArgumentMatchers.eq("tools/call"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }
}
