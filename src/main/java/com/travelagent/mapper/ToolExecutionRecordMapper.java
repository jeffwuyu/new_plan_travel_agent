package com.travelagent.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Map;

@Mapper
public interface ToolExecutionRecordMapper {

    int insertRunning(@Param("taskUuid") String taskUuid,
                      @Param("userId") Long userId,
                      @Param("toolName") String toolName,
                      @Param("idempotencyKey") String idempotencyKey,
                      @Param("argumentFingerprint") String argumentFingerprint);

    Map<String, Object> findByKey(@Param("taskUuid") String taskUuid,
                                  @Param("idempotencyKey") String idempotencyKey);

    int resetFailedForRetry(@Param("taskUuid") String taskUuid,
                            @Param("idempotencyKey") String idempotencyKey,
                            @Param("argumentFingerprint") String argumentFingerprint);

    int finish(@Param("taskUuid") String taskUuid,
               @Param("idempotencyKey") String idempotencyKey,
               @Param("status") String status,
               @Param("resultJson") String resultJson,
               @Param("errorMessage") String errorMessage);
}
