package com.travelagent.integration;

import com.travelagent.mapper.TaskMapper;
import com.travelagent.model.entity.Task;
import com.travelagent.model.enums.TaskStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("Task mapper redispatch integration tests")
class TaskMapperRedispatchIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TaskMapper taskMapper;

    @Test
    void adminRedispatchReset_clearsTerminalFields() {
        Task task = new Task();
        task.setTaskUuid(UUID.randomUUID().toString());
        task.setUserId(1L);
        task.setStatus(TaskStatus.FAILED.getCode());
        task.setRegion("Xi'an");
        task.setSchemaVersion("2.0");
        taskMapper.insert(task);

        Task inserted = taskMapper.findByUuid(task.getTaskUuid());
        inserted.setErrorMessage("Task exceeded max recovery attempts");
        inserted.setCompletedAt(LocalDateTime.now());
        taskMapper.update(inserted);

        taskMapper.resetForAdminRedispatch(inserted.getId(), TaskStatus.RESUMING.getCode(),
                "{\"currentState\":\"resuming\"}");

        Task reset = taskMapper.findByUuid(task.getTaskUuid());
        assertEquals(TaskStatus.RESUMING.getCode(), reset.getStatus());
        assertEquals("{\"currentState\":\"resuming\"}", reset.getCheckpointJson());
        assertNull(reset.getErrorMessage());
        assertNull(reset.getCompletedAt());
    }
}
