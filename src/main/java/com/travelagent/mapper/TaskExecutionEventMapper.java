package com.travelagent.mapper;

import com.travelagent.model.entity.TaskExecutionEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TaskExecutionEventMapper {

    int insert(TaskExecutionEvent event);

    List<TaskExecutionEvent> findByTaskUuid(@Param("taskUuid") String taskUuid,
                                            @Param("limit") int limit);

    TaskExecutionEvent findLatestByTaskUuid(@Param("taskUuid") String taskUuid);

    int countByTaskUuid(@Param("taskUuid") String taskUuid);
}
