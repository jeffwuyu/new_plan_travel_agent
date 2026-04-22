package com.travelagent.agent.tools;

import com.travelagent.agent.mcp.McpToolExecutionService;
import com.travelagent.aop.IdempotentTool;
import com.travelagent.client.amap.AmapClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Tool that calculates the driving duration between two GPS coordinates.
 *
 * <p>Delegates to {@link AmapClient#getDrivingDuration} which returns the
 * duration in minutes (converted from Amap's seconds-based response).
 *
 * <p>Expected input keys:
 * <ul>
 *   <li>{@code originLng} / {@code originLat} — departure coordinates</li>
 *   <li>{@code destLng} / {@code destLat}     — destination coordinates</li>
 * </ul>
 *
 * <p>Output keys: {@code durationMin} (Integer).
 */

/**
 * 中文注释：Agent 工具类，负责执行 Traffic Time Tool 相关的工具调用能力。
 */

@Component
public class TrafficTimeTool implements AgentTool {

    public static final String NAME = "traffic_time";

    private static final Logger log = LoggerFactory.getLogger(TrafficTimeTool.class);

    @Autowired
    private AmapClient amapClient;

    @Autowired
    private McpToolExecutionService mcpToolExecutionService;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    @IdempotentTool(ttl = "24h")
    public Map<String, Object> execute(Map<String, Object> arguments, String idempotencyKey) {
        double originLng = ((Number) arguments.get("originLng")).doubleValue();
        double originLat = ((Number) arguments.get("originLat")).doubleValue();
        double destLng   = ((Number) arguments.get("destLng")).doubleValue();
        double destLat   = ((Number) arguments.get("destLat")).doubleValue();

        log.debug("[TrafficTimeTool] Driving duration from ({},{}) to ({},{})",
                originLng, originLat, destLng, destLat);

        if (mcpToolExecutionService.isEnabled()) {
            try {
                return mcpToolExecutionService.execute(NAME, arguments);
            } catch (Exception e) {
                log.warn("[TrafficTimeTool] MCP traffic tool failed, falling back to REST: {}", e.getMessage());
                Map<String, Object> fallback =
                        new HashMap<>(amapClient.getDrivingDuration(originLng, originLat, destLng, destLat));
                fallback.put("mcpFallback", true);
                fallback.put("mcpProvider", "amap-rest");
                return fallback;
            }
        }
        return amapClient.getDrivingDuration(originLng, originLat, destLng, destLat);
    }
}
