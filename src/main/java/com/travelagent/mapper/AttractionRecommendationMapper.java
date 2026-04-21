package com.travelagent.mapper;

import com.travelagent.model.entity.Attraction;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface AttractionRecommendationMapper {

    List<Attraction> findByRegion(@Param("region") String region,
                                  @Param("limit") int limit);

    List<Attraction> findByBoundingBox(@Param("minLat") BigDecimal minLat,
                                       @Param("maxLat") BigDecimal maxLat,
                                       @Param("minLng") BigDecimal minLng,
                                       @Param("maxLng") BigDecimal maxLng,
                                       @Param("limit") int limit);
}
