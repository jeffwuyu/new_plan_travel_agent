package com.travelagent.mapper;

import com.travelagent.model.entity.Attraction;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * MyBatis mapper for the {@code attractions} table.
 * Used as the L3 (MySQL) loader in the multi-level cache for POI coordinates.
 */

/**
 * 中文注释：Mapper 接口，负责 Attraction Mapper 相关的数据访问与持久化映射。
 */

@Mapper
public interface AttractionMapper {

    /**
     * Find a cached attraction by name and region.
     * Returns the most recently cached entry (LIMIT 1).
     */
    Attraction findByNameAndRegion(@Param("name") String name, @Param("region") String region);

    /**
     * Find a cached attraction by Amap POI ID.
     */
    Attraction findByAmapPoiId(@Param("amapPoiId") String amapPoiId);

    java.util.List<Attraction> searchByRegionAndKeyword(@Param("region") String region,
                                                        @Param("keyword") String keyword,
                                                        @Param("limit") int limit);

    /**
     * Insert a new attraction record. Sets {@code id} via useGeneratedKeys.
     */
    int insert(Attraction attraction);

    /**
     * Update an existing attraction's coordinates and metadata by {@code id}.
     */
    int update(Attraction attraction);

    /**
     * Update or insert an attraction matched by Amap POI ID if present, otherwise by name+region.
     */
    int upsert(Attraction attraction);
}
