package com.travelagent.service.task;

import com.travelagent.model.dto.TaskExecutionProgressResponse;
import com.travelagent.model.entity.TaskExecutionEvent;

public interface TaskProgressService {

    /**
     * Persists a single execution event. Never throws — internal DB errors are logged and swallowed
     * so the calling agent loop is never interrupted by event recording failures.
     */
    void recordEvent(String taskUuid, String eventType, String status,
                     Integer stepIndex, Integer totalSteps,
                     String message, Object detailsPayload);

    /**
     * Returns the most recent N events for a task, ordered by created_at ASC.
     */
    TaskExecutionProgressResponse getProgress(String taskUuid, int limit);

    /**
     * Returns the single most recent event for a task (used for PROGRESS_SNAPSHOT on SSE connect).
     * Returns null if no events exist.
     */
    TaskExecutionEvent getLatestEvent(String taskUuid);
}
