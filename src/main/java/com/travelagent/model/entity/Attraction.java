package com.travelagent.model.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Local cache of attraction/POI data from Amap.
 * Reduces external API calls for frequently referenced attractions.
 * Re-fetched if cached_at is older than 24 hours.
 */

/**
 * 中文注释：实体类，用于定义 Attraction 的持久化数据结构。
 */

@Data
@NoArgsConstructor
public class Attraction {

    private Long id;

    /** Amap POI ID (unique identifier from 高德地图) */
    private String amapPoiId;

    private String name;

    private String region;

    private String category;

    private BigDecimal latitude;

    private BigDecimal longitude;

    private String address;

    private BigDecimal rating;

    private String description;

    /** DashVector embedding ID, set after RAG indexing */
    private String dashvectorId;

    private LocalDateTime cachedAt;
}
