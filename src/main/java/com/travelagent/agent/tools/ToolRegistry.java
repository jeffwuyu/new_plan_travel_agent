package com.travelagent.agent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Registry of all available {@link AgentTool} implementations.
 *
 * <p>Spring collects every {@code AgentTool} bean via list injection and
 * registers them by {@link AgentTool#getName()} at startup.  Adding a new tool
 * only requires annotating it with {@code @Component}; no manual wiring needed.
 */

/**
 * 中文注释：Agent 工具类，负责执行 Tool Registry 相关的工具调用能力。
 */

@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, AgentTool> registry = new HashMap<>();

    @Autowired
    public ToolRegistry(List<AgentTool> tools) {
        for (AgentTool tool : tools) {
            registry.put(tool.getName(), tool);
            log.info("[ToolRegistry] Registered tool: {}", tool.getName());
        }
    }

    /**
     * Retrieve a tool by name.
     *
     * @throws IllegalArgumentException if no tool is registered under {@code name}
     */
    public AgentTool getTool(String name) {
        AgentTool tool = registry.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("Unknown agent tool: '" + name
                    + "'. Registered tools: " + registry.keySet());
        }
        return tool;
    }

    /**
     * Returns {@code true} if a tool with the given name is registered.
     */
    public boolean hasTool(String name) {
        return registry.containsKey(name);
    }

    /**
     * Returns the set of all registered tool names.
     */
    public Set<String> getToolNames() {
        return registry.keySet();
    }
}
