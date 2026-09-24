package com.travelagent.mapper;

import com.travelagent.model.entity.UserMemoryFact;
import com.travelagent.model.entity.UserMemoryProfile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface UserMemoryMapper {

    UserMemoryProfile findProfileByUserId(@Param("userId") Long userId);

    int upsertProfile(@Param("userId") Long userId,
                      @Param("sourceSummary") String sourceSummary,
                      @Param("profileSummary") String profileSummary);

    List<UserMemoryFact> findActiveFactsByUserId(@Param("userId") Long userId);

    int upsertFact(UserMemoryFact fact);

    int softDeleteFact(@Param("userId") Long userId,
                       @Param("memoryType") String memoryType,
                       @Param("memoryKey") String memoryKey,
                       @Param("memoryValue") String memoryValue);

    int softDeleteProfile(@Param("userId") Long userId);

    int softDeleteFacts(@Param("userId") Long userId);
}
