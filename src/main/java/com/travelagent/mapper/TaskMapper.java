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

    /**
     * Returns tasks whose status is in the given list, up to {@code limit} rows, oldest-first.
     * Used by the startup recovery scan to find tasks stuck mid-execution after a JVM crash.
     */
    List<Task> findByStatusIn(@Param("statuses") List<String> statuses,
                              @Param("limit") int limit);

    /**
     * Admin cross-status paginated query. status=null returns all statuses.
     * Call PageHelper.startPage() before invoking this method.
     */
    List<Task> findAllWithFilter(@Param("status") String status);

    /** Count tasks in active states for a user (concurrency limit check). */
    int countActiveByUserId(@Param("userId") Long userId);

    int updateStatus(@Param("id") Long id, @Param("status") String status);

    int updateCheckpoint(Task task);

    int update(Task task);
}
