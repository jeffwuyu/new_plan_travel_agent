package com.travelagent.agent.mcp;

import com.travelagent.config.AgentMcpProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class McpProcessManager {

    private static final Logger log = LoggerFactory.getLogger(McpProcessManager.class);

    private final AgentMcpProperties properties;

    public McpProcessManager(AgentMcpProperties properties) {
        this.properties = properties;
    }

    public McpProcessHandle startProcess() {
        try {
            List<String> command = new ArrayList<>();
            command.add(properties.getServer().getCommand());
            command.addAll(properties.getServer().getArgs());

            ProcessBuilder builder = new ProcessBuilder(command);
            Map<String, String> env = builder.environment();
            env.putAll(properties.getServer().getEnv());

            Process process = builder.start();
            log.info("[MCP] Started process: {}", command);
            return new McpProcessHandle(
                    process,
                    new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)),
                    new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)),
                    new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))
            );
        } catch (IOException e) {
            throw new McpException("Failed to start MCP server process", e);
        }
    }

    public void stopProcess(McpProcessHandle handle) {
        if (handle == null) {
            return;
        }
        closeQuietly(handle.stdin());
        closeQuietly(handle.stdout());
        closeQuietly(handle.stderr());

        Process process = handle.process();
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }

    private void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }
}
