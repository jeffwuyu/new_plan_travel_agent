package com.travelagent.mapper;

import com.travelagent.model.entity.LlmCallLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 中文注释：Mapper 接口，负责 Llm Call Log Mapper 相关的数据访问与持久化映射。
 */

@Mapper
public interface LlmCallLogMapper {

    int insert(LlmCallLog log);

    /** Sum of total_tokens for a user within a period for quota reconstruction. */
    int sumTokensByUserAndPeriod(@Param("userId") Long userId,
                                 @Param("startDate") String startDate,
                                 @Param("endDate") String endDate);

    /** Latest successful token usage for a task call, used as streaming usage fallback. */
    Integer findLatestSuccessfulTotalTokens(@Param("taskId") Long taskId,
                                            @Param("idempotencyKey") String idempotencyKey);
}
