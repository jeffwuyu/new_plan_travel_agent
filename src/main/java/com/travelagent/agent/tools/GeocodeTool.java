package com.travelagent.agent.tools;

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

    @Override
    public String getName() {
        return NAME;
    }

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

        // Full miss — call Amap
        log.debug("[GeocodeTool] Cache miss, calling Amap for name={}", name);
        Map<String, Object> result = amapClient.geocode(name, region);

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
}
