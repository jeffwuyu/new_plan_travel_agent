package com.travelagent.agent.freshness;

import java.time.Duration;

public record DataFreshnessRule(DataFreshnessType type,
                                Duration ttl,
                                boolean realtime,
                                String cacheRegion,
                                String sourceRequirement) {
}
