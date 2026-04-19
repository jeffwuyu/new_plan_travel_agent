package com.travelagent.agent.tools;

import com.travelagent.aop.IdempotentTool;
import com.travelagent.client.amap.AmapClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Tool that fetches current weather for a city by its Amap adcode.
 *
 * <p>Delegates to {@link AmapClient#getWeather(String)} which caches responses
 * in Redis for 1 hour.  The {@link IdempotentTool} layer adds a per-task
 * 24-hour idempotency guarantee so that a resumed task never re-calls the API
 * for a step that already completed.
 *
 * <p>Expected input keys: {@code adcode} (String — Amap city administrative code).
 *
 * <p>Output keys: {@code weather}, {@code temperature}, {@code windDirection},
 * {@code windPower}, {@code humidity}.
 */

/**
 * 中文注释：Agent 工具类，负责执行 Weather Tool 相关的工具调用能力。
 */

@Component
public class WeatherTool implements AgentTool {

    public static final String NAME = "weather";

    private static final Logger log = LoggerFactory.getLogger(WeatherTool.class);

    @Autowired
    private AmapClient amapClient;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    @IdempotentTool(ttl = "24h")
    public Map<String, Object> execute(Map<String, Object> arguments, String idempotencyKey) {
        String adcode = (String) arguments.get("adcode");
        log.debug("[WeatherTool] Fetching weather for adcode={}", adcode);
        return amapClient.getWeather(adcode);
    }
}
