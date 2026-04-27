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

    /**
     * 初始化AgentMcpProperties 实例。
     */
    public AgentMcpProperties() {
        toolMapping.put("geocode", "maps_geo");
        toolMapping.put("weather", "maps_weather");
        toolMapping.put("traffic_time.driving", "maps_direction_driving");
        toolMapping.put("traffic_time.walking", "maps_direction_walking");
        toolMapping.put("traffic_time.bicycling", "maps_direction_bicycling");
        toolMapping.put("traffic_time.transit", "maps_direction_transit");
    }

    /**
     * 判断enabled。
     * @return 是否满足当前条件。
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 处理setEnabled。
     * @param enabled e na bl ed 参数
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 获取protocolversion。
     * @return 返回处理结果。
     */
    public String getProtocolVersion() {
        return protocolVersion;
    }

    /**
     * 处理setProtocolVersion。
     * @param protocolVersion p ro to co lV er si on 参数
     */
    public void setProtocolVersion(String protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    /**
     * 获取startuptimeoutms。
     * @return 返回处理结果。
     */
    public long getStartupTimeoutMs() {
        return startupTimeoutMs;
    }

    /**
     * 处理setStartupTimeoutMs。
     * @param startupTimeoutMs s ta rt up Ti me ou tM s 参数
     */
    public void setStartupTimeoutMs(long startupTimeoutMs) {
        this.startupTimeoutMs = startupTimeoutMs;
    }

    /**
     * 获取requesttimeoutms。
     * @return 返回处理结果。
     */
    public long getRequestTimeoutMs() {
        return requestTimeoutMs;
    }

    /**
     * 处理setRequestTimeoutMs。
     * @param requestTimeoutMs r eq ue st Ti me ou tM s 参数
     */
    public void setRequestTimeoutMs(long requestTimeoutMs) {
        this.requestTimeoutMs = requestTimeoutMs;
    }

    /**
     * 获取server。
     * @return 返回处理结果。
     */
    public Server getServer() {
        return server;
    }

    /**
     * 获取toolmapping。
     * @return 返回处理后的映射结果。
     */
    public Map<String, String> getToolMapping() {
        return toolMapping;
    }

    public static class Server {

        private String command = "npx";
        private List<String> args = new ArrayList<>(List.of("-y", "@amap/amap-maps-mcp-server"));
        private final Map<String, String> env = new LinkedHashMap<>();

        /**
         * 获取command。
         * @return 返回处理结果。
         */
        public String getCommand() {
            return command;
        }

        /**
         * 处理setCommand。
         * @param command c om ma nd 参数
         */
        public void setCommand(String command) {
            this.command = command;
        }

        /**
         * 获取args。
         * @return 返回处理后的列表结果。
         */
        public List<String> getArgs() {
            return args;
        }

        /**
         * 处理setArgs。
         * @param args a rg s 参数
         */
        public void setArgs(List<String> args) {
            this.args = args;
        }

        /**
         * 获取env。
         * @return 返回处理后的映射结果。
         */
        public Map<String, String> getEnv() {
            return env;
        }
    }
}
