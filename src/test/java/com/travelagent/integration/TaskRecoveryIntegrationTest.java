package com.travelagent.integration;

import com.travelagent.mapper.TaskMapper;
import com.travelagent.model.entity.Task;
import com.travelagent.model.enums.TaskStatus;
import com.travelagent.service.agent.impl.AgentServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Integration tests for AgentServiceImpl.recoverStuckTasks().
 *
 * Validates that tasks left in PLANNING or TOOL_CALLING after a JVM crash
 * are transitioned to RESUMING at startup, while other statuses are untouched.
 *
 * Uses the full Spring context with H2 in-memory DB (no real Redis / external APIs).
 * Schema and data are reset before each test via @Sql in BaseIntegrationTest.
 */
@DisplayName("TaskRecovery Integration Tests")
class TaskRecoveryIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AgentServiceImpl agentService;

    @Autowired
    private TaskMapper taskMapper;

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Insert a minimal task row with a given status directly via mapper. */
    private Task insertTask(String status) {
        Task task = new Task();
        task.setTaskUuid(UUID.randomUUID().toString());
        task.setUserId(1L);
        task.setStatus(status);
        task.setRegion("西安");
        task.setSchemaVersion("1.0");
        taskMapper.insert(task);
        return taskMapper.findByUuid(task.getTaskUuid());
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("PLANNING 状态的任务 → recoverStuckTasks() 后变为 RESUMING")
    void planningTask_recoveredToResuming() {
        Task task = insertTask(TaskStatus.PLANNING.getCode());

        agentService.recoverStuckTasks();

        Task recovered = taskMapper.findByUuid(task.getTaskUuid());
        assertEquals(TaskStatus.RESUMING.getCode(), recovered.getStatus());
    }

    @Test
    @DisplayName("TOOL_CALLING 状态的任务 → recoverStuckTasks() 后变为 RESUMING")
    void toolCallingTask_recoveredToResuming() {
        Task task = insertTask(TaskStatus.TOOL_CALLING.getCode());

        agentService.recoverStuckTasks();

        Task recovered = taskMapper.findByUuid(task.getTaskUuid());
        assertEquals(TaskStatus.RESUMING.getCode(), recovered.getStatus());
    }

    @Test
    @DisplayName("PENDING 状态的任务不受恢复影响")
    void pendingTask_notAffectedByRecovery() {
        Task task = insertTask(TaskStatus.PENDING.getCode());

        agentService.recoverStuckTasks();

        Task unchanged = taskMapper.findByUuid(task.getTaskUuid());
        assertEquals(TaskStatus.PENDING.getCode(), unchanged.getStatus());
    }

    @Test
    @DisplayName("混合状态：只有 PLANNING 转为 RESUMING，PENDING 保持不变")
    void mixedStatuses_onlyStuckOnesTransitioned() {
        Task planningTask    = insertTask(TaskStatus.PLANNING.getCode());
        Task toolCallingTask = insertTask(TaskStatus.TOOL_CALLING.getCode());
        Task pendingTask     = insertTask(TaskStatus.PENDING.getCode());
        Task completedTask   = insertTask(TaskStatus.COMPLETED.getCode());

        agentService.recoverStuckTasks();

        assertEquals(TaskStatus.RESUMING.getCode(),
                taskMapper.findByUuid(planningTask.getTaskUuid()).getStatus());
        assertEquals(TaskStatus.RESUMING.getCode(),
                taskMapper.findByUuid(toolCallingTask.getTaskUuid()).getStatus());
        assertEquals(TaskStatus.PENDING.getCode(),
                taskMapper.findByUuid(pendingTask.getTaskUuid()).getStatus());
        assertEquals(TaskStatus.COMPLETED.getCode(),
                taskMapper.findByUuid(completedTask.getTaskUuid()).getStatus());
    }

    @Test
    @DisplayName("无卡住任务 → recoverStuckTasks() 静默完成，不抛异常")
    void noStuckTasks_completesQuietly() {
        // DB is empty after @Sql reset — should not throw
        agentService.recoverStuckTasks();
        // If we get here, no exception was thrown
    }
}
