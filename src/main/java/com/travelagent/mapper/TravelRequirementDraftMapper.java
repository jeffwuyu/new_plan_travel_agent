package com.travelagent.mapper;

import com.travelagent.model.entity.TravelRequirementDraft;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TravelRequirementDraftMapper {
    int insert(TravelRequirementDraft draft);
    TravelRequirementDraft findByIdAndUser(@Param("id") Long id, @Param("userId") Long userId);
    TravelRequirementDraft findByIdempotency(@Param("userId") Long userId, @Param("key") String key);
    int updateIfRevision(@Param("id") Long id, @Param("userId") Long userId,
                         @Param("revision") int revision, @Param("rawText") String rawText,
                         @Param("constraintsJson") String constraintsJson,
                         @Param("questionsJson") String questionsJson,
                         @Param("status") String status);
    int confirm(@Param("id") Long id, @Param("userId") Long userId,
                @Param("revision") int revision, @Param("idempotencyKey") String idempotencyKey,
                @Param("taskUuid") String taskUuid);
}
