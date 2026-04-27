package com.travelagent.agent.tools;

import com.travelagent.agent.mcp.McpToolExecutionService;
import com.travelagent.aop.IdempotentTool;
import com.travelagent.client.amap.AmapClient;
import com.travelagent.config.CacheConfig;
import com.travelagent.mapper.AttractionMapper;
import com.travelagent.model.entity.Attraction;
import com.travelagent.service.cache.MultiLevelCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Tool that resolves an attraction name to GPS coordinates.
 *
 * <p>Lookup order:
 * <ol>
 *   <li>L1/L2/L3 cache via {@link MultiLevelCacheService} → {@link AttractionMapper} (MySQL)</li>
 *   <li>Amap Geocoding API on full miss</li>
 * </ol>
 *
 * <p>On an Amap hit the result is persisted to the {@code attractions} table
 * so subsequent calls are served from MySQL (L3) without hitting Amap.
 */

/**
 * 中文注释：Agent 工具类，负责执行 Geocode Tool 相关的工具调用能力。
 */

@Component
public class GeocodeTool implements AgentTool {

    public static final String NAME = "geocode";

    private static final Logger log = LoggerFactory.getLogger(GeocodeTool.class);

    @Autowired
    private AmapClient amapClient;

    @Autowired
    private AttractionMapper attractionMapper;

    @Autowired
    private MultiLevelCacheService cacheService;

    @Autowired
    private McpToolExecutionService mcpToolExecutionService;

    /**
     * 获取name。
     * @return 返回处理结果。
     */
    @Override
    public String getName() {
        return NAME;
    }

    /**
     * 处理execute。
     * @param arguments 工具调用参数
     * @param idempotencyKey i de mp ot en cy Ke y 参数
     * @return 返回处理后的映射结果。
     */
    @Override
    @IdempotentTool(ttl = "24h")
    public Map<String, Object> execute(Map<String, Object> arguments, String idempotencyKey) {
        String name   = (String) arguments.get("name");
        String region = (String) arguments.get("region");

        log.debug("[GeocodeTool] Resolving name={} region={}", name, region);

        // L1 / L2 / L3 lookup
        Attraction cached = cacheService.get(
                CacheConfig.ATTRACTION_BASIC,
                name + ":" + region,
                Attraction.class,
                () -> attractionMapper.findByNameAndRegion(name, region)
        );

        if (cached != null && cached.getLatitude() != null && cached.getLongitude() != null) {
            log.debug("[GeocodeTool] Cache hit for name={}", name);
            // adcode is not stored in attractions table; return empty string
            // (WeatherTool will get adcode from geocode result via AmapClient cache)
            return Map.of(
                    "lat",    cached.getLatitude().doubleValue(),
                    "lng",    cached.getLongitude().doubleValue(),
                    "adcode", ""
            );
        }

        // Full miss -> prefer MCP and then fall back to the existing REST client.
        log.debug("[GeocodeTool] Cache miss, resolving via MCP/Amap for name={}", name);
        Map<String, Object> result = callProvider(arguments, name, region);

        // Persist to attractions table (L3)
        Attraction newAttr = new Attraction();
        newAttr.setName(name);
        newAttr.setRegion(region);
        newAttr.setLatitude(BigDecimal.valueOf(((Number) result.get("lat")).doubleValue()));
        newAttr.setLongitude(BigDecimal.valueOf(((Number) result.get("lng")).doubleValue()));
        newAttr.setSource("geocode");
        newAttr.setCachedAt(LocalDateTime.now());
        newAttr.setLastSyncedAt(LocalDateTime.now());
        try {
            attractionMapper.insert(newAttr);
        } catch (Exception e) {
            // DB insert failure is non-fatal (cache miss next time is acceptable)
            log.warn("[GeocodeTool] Failed to persist attraction name={}: {}", name, e.getMessage());
        }

        // Backfill L1 + L2
        cacheService.put(CacheConfig.ATTRACTION_BASIC, name + ":" + region, newAttr, java.time.Duration.ofHours(1));

        return result;
    }

    /**
     * 处理callProvider。
     * @param arguments 工具调用参数
     * @param name n am e 参数
     * @param region 区域信息
     * @return 返回处理后的映射结果。
     */
    private Map<String, Object> callProvider(Map<String, Object> arguments, String name, String region) {
        if (mcpToolExecutionService.isEnabled()) {
            try {
                return mcpToolExecutionService.execute(NAME, arguments);
            } catch (Exception e) {
                log.warn("[GeocodeTool] MCP geocode failed for name={} region={}, falling back to REST: {}",
                        name, region, e.getMessage());
                Map<String, Object> fallback = new HashMap<>(amapClient.geocode(name, region));
                fallback.put("mcpFallback", true);
                fallback.put("mcpProvider", "amap-rest");
                return fallback;
            }
        }
        return amapClient.geocode(name, region);
    }
}
