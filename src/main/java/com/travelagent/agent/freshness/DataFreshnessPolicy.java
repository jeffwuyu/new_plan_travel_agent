package com.travelagent.agent.freshness;

import com.travelagent.config.CacheConfig;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

@Component
public class DataFreshnessPolicy {

    private final Map<DataFreshnessType, DataFreshnessRule> rules = new EnumMap<>(DataFreshnessType.class);

    public DataFreshnessPolicy() {
        put(DataFreshnessType.WEB_REALTIME, Duration.ofHours(2), true, CacheConfig.WEB_REALTIME, "source link and query time");
        put(DataFreshnessType.WEATHER, Duration.ofMinutes(30), true, CacheConfig.WEATHER_REALTIME, "weather provider and query time");
        put(DataFreshnessType.MAP_ROUTE, Duration.ofMinutes(20), true, CacheConfig.MAP_ROUTE, "map provider and query time");
        put(DataFreshnessType.BOOKING_STATUS, Duration.ofMinutes(30), true, CacheConfig.BOOKING_STATUS, "official entry or query time");
        put(DataFreshnessType.OPENING_HOURS, Duration.ofHours(2), true, CacheConfig.WEB_REALTIME, "official opening-hour source");
        put(DataFreshnessType.ATTRACTION_BASIC, Duration.ofHours(24), false, CacheConfig.ATTRACTION_BASIC, "static attraction source");
        put(DataFreshnessType.RAG_STATIC_KNOWLEDGE, Duration.ofDays(7), false, CacheConfig.RAG_CHUNK, "RAG document metadata");
        put(DataFreshnessType.USER_PREFERENCE, Duration.ofHours(12), false, CacheConfig.USER_PROFILE, "preference updated time");
        put(DataFreshnessType.SESSION_STATE, Duration.ofMinutes(10), false, CacheConfig.SESSION_STATE, "checkpoint updated time");
    }

    public DataFreshnessRule ruleFor(DataFreshnessType type) {
        return rules.get(type);
    }

    public CacheFreshnessMetadata metadata(DataFreshnessType type, boolean cacheHit, Instant queryTime, String source) {
        DataFreshnessRule rule = ruleFor(type);
        Instant resolvedQueryTime = queryTime == null ? Instant.now() : queryTime;
        Instant expiresAt = rule == null ? resolvedQueryTime : resolvedQueryTime.plus(rule.ttl());
        String note = rule != null && rule.realtime()
                ? "Realtime-sensitive data must be refreshed after TTL expires."
                : "Static or profile data can use longer cache windows.";
        return new CacheFreshnessMetadata(cacheHit, resolvedQueryTime, expiresAt, source, note);
    }

    private void put(DataFreshnessType type, Duration ttl, boolean realtime, String cacheRegion, String sourceRequirement) {
        rules.put(type, new DataFreshnessRule(type, ttl, realtime, cacheRegion, sourceRequirement));
    }
}
