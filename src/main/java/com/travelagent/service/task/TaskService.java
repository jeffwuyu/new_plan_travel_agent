package com.travelagent.service.task;

import com.travelagent.model.dto.CreateTaskRequest;
import com.travelagent.model.dto.TaskResponse;

import java.util.List;

/**
 * Task lifecycle management: create, query, cancel, and resume agent tasks.
 *
 * <p>All mutating methods validate ownership: a user can only modify their own tasks.
 * Quota and concurrency checks are performed inside {@link #createTask}.
 */

/**
 * 中文注释：服务接口，定义 Task Service 相关业务能力。
 */

public interface TaskService {

    /**
     * Create a new travel planning task for the given user.
     *
     * @param userId      authenticated user's DB id
     * @param userLevel   user's level (1=regular, 2=VIP, 3=admin)
     * @param request     validated request body
     * @return the newly created task with status {@code PENDING}
     * @throws com.travelagent.exception.QuotaExhaustedException if daily quota is exhausted
     * @throws com.travelagent.exception.BusinessException 429 if concurrent task limit reached
     */
    TaskResponse createTask(Long userId, int userLevel, CreateTaskRequest request);

    /**
     * Retrieve a single task by UUID, verifying caller ownership.
     *
     * @throws com.travelagent.exception.TaskNotFoundException if UUID does not exist
     * @throws com.travelagent.exception.BusinessException 403 if caller does not own the task
     */
    TaskResponse getTask(String taskUuid, Long requestingUserId);

    /**
     * List all tasks belonging to the given user, newest first.
     */
    List<TaskResponse> listTasks(Long userId);

    /**
     * Cancel a task in any non-terminal state.
     *
     * @throws com.travelagent.exception.TaskNotFoundException if UUID does not exist
     * @throws com.travelagent.exception.BusinessException 403 if caller does not own the task
     * @throws com.travelagent.exception.BusinessException 400 if the task is already in a terminal state
     */
    void cancelTask(String taskUuid, Long requestingUserId);

    /**
     * Resume a PAUSED task. Transitions it to RESUMING so that TaskDispatcher
     * will pick it up and re-dispatch to the agent thread pool.
     *
     * @throws com.travelagent.exception.TaskNotFoundException if UUID does not exist
     * @throws com.travelagent.exception.BusinessException 403 if caller does not own the task
     * @throws com.travelagent.exception.BusinessException 400 if the task is not in PAUSED state
     */
    TaskResponse resumeTask(String taskUuid, Long requestingUserId);
}
