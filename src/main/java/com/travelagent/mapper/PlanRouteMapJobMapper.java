package com.travelagent.mapper;

import com.travelagent.model.entity.PlanRouteMapJob;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface PlanRouteMapJobMapper {

    int insert(PlanRouteMapJob job);

    PlanRouteMapJob findById(@Param("id") Long id);

    PlanRouteMapJob findLatestByRouteMapId(@Param("routeMapId") Long routeMapId);

    List<PlanRouteMapJob> findDispatchable(@Param("now") LocalDateTime now,
                                           @Param("limit") int limit);

    int markRunning(@Param("id") Long id,
                    @Param("lockedBy") String lockedBy,
                    @Param("lockedUntil") LocalDateTime lockedUntil);

    int markSucceeded(@Param("id") Long id);

    int markFailed(@Param("id") Long id,
                   @Param("status") String status,
                   @Param("errorCode") String errorCode,
                   @Param("errorMessage") String errorMessage,
                   @Param("availableAt") LocalDateTime availableAt);

    int markDeadLetter(@Param("id") Long id,
                       @Param("errorCode") String errorCode,
                       @Param("errorMessage") String errorMessage);
}
