package com.travelagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "agent.mcp")
public class AgentMcpProperties {

    private boolean enabled = false;
    private String protocolVersion = "2025-03-26";
    private long startupTimeoutMs = 10000L;
    private long requestTimeoutMs = 10000L;
    private final Server server = new Server();
    private final Map<String, String> toolMapping = new LinkedHashMap<>();

    public AgentMcpProperties() {
        toolMapping.put("geocode", "maps_geo");
        toolMapping.put("weather", "maps_weather");
        toolMapping.put("traffic_time.driving", "maps_direction_driving");
        toolMapping.put("traffic_time.walking", "maps_direction_walking");
        toolMapping.put("traffic_time.bicycling", "maps_direction_bicycling");
        toolMapping.put("traffic_time.transit", "maps_direction_transit");
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getProtocolVersion() {
        return protocolVersion;
    }

    public void setProtocolVersion(String protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    public long getStartupTimeoutMs() {
        return startupTimeoutMs;
    }

    public void setStartupTimeoutMs(long startupTimeoutMs) {
        this.startupTimeoutMs = startupTimeoutMs;
    }

    public long getRequestTimeoutMs() {
        return requestTimeoutMs;
    }

    public void setRequestTimeoutMs(long requestTimeoutMs) {
        this.requestTimeoutMs = requestTimeoutMs;
    }

    public Server getServer() {
        return server;
    }

    public Map<String, String> getToolMapping() {
        return toolMapping;
    }

    public static class Server {

        private String command = "npx";
        private List<String> args = new ArrayList<>(List.of("-y", "@amap/amap-maps-mcp-server"));
        private final Map<String, String> env = new LinkedHashMap<>();

        public String getCommand() {
            return command;
        }

        public void setCommand(String command) {
            this.command = command;
        }

        public List<String> getArgs() {
            return args;
        }

        public void setArgs(List<String> args) {
            this.args = args;
        }

        public Map<String, String> getEnv() {
            return env;
        }
    }
}
