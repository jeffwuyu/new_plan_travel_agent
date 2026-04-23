package com.travelagent.service.task;

import com.travelagent.model.dto.ConfirmOriginSelectionRequest;
import com.travelagent.model.dto.CreateTaskRequest;
import com.travelagent.model.dto.NodeChatRequest;
import com.travelagent.model.dto.RewindTaskRequest;
import com.travelagent.model.dto.TaskResponse;
import com.travelagent.model.entity.Task;

import java.util.List;

public interface TaskService {

    TaskResponse createTask(Long userId, int userLevel, CreateTaskRequest request);

    TaskResponse getTask(String taskUuid, Long requestingUserId);

    List<TaskResponse> listTasks(Long userId);

    void cancelTask(String taskUuid, Long requestingUserId);

    TaskResponse resumeTask(String taskUuid, Long requestingUserId);

    TaskResponse confirmOriginSelection(String taskUuid, Long requestingUserId, ConfirmOriginSelectionRequest request);

    TaskResponse rewindTask(String taskUuid, Long requestingUserId, RewindTaskRequest request);

    TaskResponse refreshNodeSelection(String taskUuid, Long requestingUserId, NodeChatRequest request);

    Task getTaskEntity(String taskUuid, Long requestingUserId);
}
