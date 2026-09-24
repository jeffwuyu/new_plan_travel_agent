package com.travelagent.agent.freshness;

import java.time.Instant;

public record CacheFreshnessMetadata(boolean cacheHit,
                                     Instant queryTime,
                                     Instant expiresAt,
                                     String source,
                                     String freshnessNote) {
}
