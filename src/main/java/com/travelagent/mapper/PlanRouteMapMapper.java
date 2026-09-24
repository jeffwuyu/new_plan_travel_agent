package com.travelagent.mapper;

import com.travelagent.model.entity.PlanDayRouteMap;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface PlanRouteMapMapper {

    int insert(PlanDayRouteMap routeMap);

    PlanDayRouteMap findById(@Param("id") Long id);

    PlanDayRouteMap findByPlanDayStyle(@Param("planId") Long planId,
                                       @Param("dayNumber") Integer dayNumber,
                                       @Param("style") String style);

    List<PlanDayRouteMap> findByPlanId(@Param("planId") Long planId,
                                       @Param("style") String style);

    List<PlanDayRouteMap> findByStatuses(@Param("statuses") List<String> statuses);

    List<PlanDayRouteMap> findFailedDispatchBefore(@Param("cutoff") LocalDateTime cutoff,
                                                   @Param("limit") int limit);

    int countCreatedByUserBetween(@Param("userId") Long userId,
                                  @Param("start") LocalDateTime start,
                                  @Param("end") LocalDateTime end);

    Map<String, Object> summarizeBetween(@Param("start") LocalDateTime start,
                                         @Param("end") LocalDateTime end);

    List<Map<String, Object>> countByStatusBetween(@Param("start") LocalDateTime start,
                                                   @Param("end") LocalDateTime end);

    List<Map<String, Object>> countFailuresByErrorCodeBetween(@Param("start") LocalDateTime start,
                                                              @Param("end") LocalDateTime end,
                                                              @Param("limit") int limit);

    List<Map<String, Object>> countByUserBetween(@Param("start") LocalDateTime start,
                                                 @Param("end") LocalDateTime end,
                                                 @Param("limit") int limit);

    int markRunning(@Param("id") Long id,
                    @Param("status") String status,
                    @Param("progressPercent") Integer progressPercent,
                    @Param("errorCode") String errorCode,
                    @Param("errorMessage") String errorMessage);

    int updateGeometry(@Param("id") Long id,
                       @Param("status") String status,
                       @Param("progressPercent") Integer progressPercent,
                       @Param("routeGeometryJson") String routeGeometryJson,
                       @Param("stopsJson") String stopsJson,
                       @Param("segmentsJson") String segmentsJson,
                       @Param("boundsJson") String boundsJson);

    int updateSkeleton(@Param("id") Long id,
                       @Param("status") String status,
                       @Param("progressPercent") Integer progressPercent,
                       @Param("skeletonOssKey") String skeletonOssKey);

    int updateAiTask(@Param("id") Long id,
                     @Param("status") String status,
                     @Param("progressPercent") Integer progressPercent,
                     @Param("model") String model,
                     @Param("requestId") String requestId,
                     @Param("taskId") String taskId,
                     @Param("retryCount") Integer retryCount);

    int updateAiRaw(@Param("id") Long id,
                    @Param("status") String status,
                    @Param("progressPercent") Integer progressPercent,
                    @Param("aiRawOssKey") String aiRawOssKey);

    int updateCompleted(@Param("id") Long id,
                        @Param("status") String status,
                        @Param("progressPercent") Integer progressPercent,
                        @Param("aiRawOssKey") String aiRawOssKey,
                        @Param("finalOssKey") String finalOssKey,
                        @Param("errorCode") String errorCode,
                        @Param("errorMessage") String errorMessage,
                        @Param("latencyMs") Long latencyMs);

    int incrementManualRegen(@Param("id") Long id);

    int incrementRetryCount(@Param("id") Long id);
}
