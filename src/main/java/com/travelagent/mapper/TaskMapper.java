package com.travelagent.mapper;

import com.travelagent.model.entity.Task;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 中文注释：Mapper 接口，负责 Task Mapper 相关的数据访问与持久化映射。
 */

@Mapper
public interface TaskMapper {

    int insert(Task task);

    Task findByUuid(@Param("taskUuid") String taskUuid);

    Task findById(@Param("id") Long id);

    List<Task> findByUserId(@Param("userId") Long userId);

    /** Used by TaskDispatcher to poll for runnable tasks. */
    List<Task> findByStatus(@Param("status") String status, @Param("limit") int limit);

    /** Count tasks in active states for a user (concurrency limit check). */
    int countActiveByUserId(@Param("userId") Long userId);

    int updateStatus(@Param("id") Long id, @Param("status") String status);

    int updateCheckpoint(Task task);

    int update(Task task);
}
