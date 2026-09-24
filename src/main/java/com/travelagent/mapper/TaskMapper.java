package com.travelagent.mapper;

import com.travelagent.model.entity.Task;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.time.LocalDateTime;

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

    List<Task> findRecoverableExpiredLeases(@Param("statuses") List<String> statuses,
                                            @Param("before") LocalDateTime before,
                                            @Param("afterId") Long afterId,
                                            @Param("limit") int limit);

    int claimRecoveryIfExpired(@Param("id") Long id,
                               @Param("expectedRevision") long expectedRevision,
                               @Param("owner") String owner,
                               @Param("leaseToken") String leaseToken,
                               @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt);

    /** Returns stale tasks in a specific lifecycle state, oldest first. */
    List<Task> findStaleByStatus(@Param("status") String status,
                                 @Param("before") LocalDateTime before,
                                 @Param("limit") int limit);

    /**
     * Admin cross-status paginated query. status=null returns all statuses.
     * Call PageHelper.startPage() before invoking this method.
     */
    List<Task> findAllWithFilter(@Param("status") String status);

    /** Returns active tasks for a user (used when an admin disables the account). */
    List<Task> findActiveByUserId(@Param("userId") Long userId);

    /** Count tasks in active states for a user (concurrency limit check). */
    int countActiveByUserId(@Param("userId") Long userId);

    int updateStatusIfRevision(@Param("id") Long id,
                               @Param("status") String status,
                               @Param("expectedRevision") long expectedRevision,
                               @Param("leaseToken") String leaseToken);

    default int updateStatus(Task task, String status) {
        long revision = task.getRevision() == null ? 1L : task.getRevision();
        int updated = updateStatusIfRevision(task.getId(), status, revision, task.getLeaseToken());
        if (updated == 1) {
            task.setRevision(revision + 1);
            task.setStatus(status);
        }
        return updated;
    }

    default int updateStatus(Long id, String status) {
        Task current = findById(id);
        return current == null ? 0 : updateStatus(current, status);
    }

    int resetForAdminRedispatchIfRevision(@Param("id") Long id,
                                          @Param("status") String status,
                                          @Param("checkpointJson") String checkpointJson,
                                          @Param("expectedRevision") long expectedRevision);

    default int resetForAdminRedispatch(Long id, String status, String checkpointJson) {
        Task current = findById(id);
        if (current == null) {
            return 0;
        }
        return resetForAdminRedispatchIfRevision(id, status, checkpointJson,
                current.getRevision() == null ? 1L : current.getRevision());
    }

    int transitionStatusIfCurrent(@Param("id") Long id,
                                  @Param("expectedStatus") String expectedStatus,
                                  @Param("nextStatus") String nextStatus,
                                  @Param("checkpointJson") String checkpointJson,
                                  @Param("errorMessage") String errorMessage,
                                  @Param("expectedRevision") long expectedRevision);

    int claimExecution(@Param("id") Long id,
                       @Param("expectedStatus") String expectedStatus,
                       @Param("owner") String owner,
                       @Param("leaseToken") String leaseToken,
                       @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt,
                       @Param("expectedRevision") long expectedRevision);

    int renewExecutionLease(@Param("id") Long id,
                            @Param("leaseToken") String leaseToken,
                            @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt);

    int updateCheckpointIfOwned(@Param("task") Task task,
                                @Param("expectedRevision") long expectedRevision,
                                @Param("leaseToken") String leaseToken);

    int clearExpiredLease(@Param("id") Long id,
                          @Param("expectedRevision") long expectedRevision,
                          @Param("now") LocalDateTime now);

    int insertOperationIfAbsent(@Param("userId") Long userId,
                                @Param("taskUuid") String taskUuid,
                                @Param("operationId") String operationId,
                                @Param("operationType") String operationType);

    java.util.Map<String, Object> findOperation(@Param("userId") Long userId,
                                                @Param("taskUuid") String taskUuid,
                                                @Param("operationId") String operationId);

    int completeOperation(@Param("userId") Long userId,
                          @Param("taskUuid") String taskUuid,
                          @Param("operationId") String operationId,
                          @Param("resultStatus") String resultStatus,
                          @Param("resultJson") String resultJson);

    int updateCheckpointIfRevision(Task task);

    default int updateCheckpoint(Task task) {
        int updated = updateCheckpointIfRevision(task);
        if (updated == 1 && task.getRevision() != null) {
            task.setRevision(task.getRevision() + 1);
        }
        return updated;
    }

    int updateIfRevision(Task task);

    default int update(Task task) {
        int updated = updateIfRevision(task);
        if (updated == 1 && task.getRevision() != null) {
            task.setRevision(task.getRevision() + 1);
        }
        return updated;
    }
}
