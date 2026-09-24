package com.travelagent.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TaskCheckpointArtifactMapper {

    int upsertArtifact(@Param("taskUuid") String taskUuid,
                       @Param("taskId") Long taskId,
                       @Param("artifactType") String artifactType,
                       @Param("payloadJson") String payloadJson,
                       @Param("itemCount") Integer itemCount,
                       @Param("schemaVersion") String schemaVersion);
}
