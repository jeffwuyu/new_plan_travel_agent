package com.travelagent.integration;

import com.travelagent.mapper.TaskMapper;
import com.travelagent.model.entity.Task;
import com.travelagent.model.enums.TaskStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.BeforeEach;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@DisplayName("Task lifecycle governance mapper integration tests")
class TaskLifecycleGovernanceMapperIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TaskMapper taskMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void prepareFencingSchema() {
        jdbcTemplate.execute("ALTER TABLE tasks ADD COLUMN IF NOT EXISTS revision BIGINT NOT NULL DEFAULT 1");
        jdbcTemplate.execute("ALTER TABLE tasks ADD COLUMN IF NOT EXISTS lease_token VARCHAR(64)");
        jdbcTemplate.execute("ALTER TABLE tasks ADD COLUMN IF NOT EXISTS lease_expires_at TIMESTAMP");
        jdbcTemplate.execute("ALTER TABLE tasks ADD COLUMN IF NOT EXISTS execution_owner VARCHAR(128)");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS task_operations (id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, task_uuid VARCHAR(64) NOT NULL, operation_id VARCHAR(128) NOT NULL, operation_type VARCHAR(64) NOT NULL, result_status VARCHAR(32) NOT NULL DEFAULT 'RUNNING', result_json CLOB, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, CONSTRAINT uk_task_operation UNIQUE (user_id, task_uuid, operation_id))");
        jdbcTemplate.update("DELETE FROM task_operations");
    }

    @Test
    void findStaleByStatusAndTransitionStatusIfCurrent_workTogether() {
        Task stale = insertTask("lifecycle-stale-001", TaskStatus.AWAITING_USER_INPUT);
        Task fresh = insertTask("lifecycle-fresh-001", TaskStatus.AWAITING_USER_INPUT);

        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("UPDATE tasks SET updated_at = ? WHERE id = ?", now.minusHours(25), stale.getId());
        jdbcTemplate.update("UPDATE tasks SET updated_at = ? WHERE id = ?", now.minusMinutes(5), fresh.getId());

        List<Task> staleTasks = taskMapper.findStaleByStatus(
                TaskStatus.AWAITING_USER_INPUT.getCode(),
                now.minusHours(24),
                10
        );

        assertEquals(1, staleTasks.size());
        assertEquals("lifecycle-stale-001", staleTasks.get(0).getTaskUuid());

        int updated = taskMapper.transitionStatusIfCurrent(
                stale.getId(),
                TaskStatus.AWAITING_USER_INPUT.getCode(),
                TaskStatus.PAUSED.getCode(),
                "{\"currentState\":\"paused\"}",
                "Task paused after waiting too long",
                stale.getRevision()
        );
        assertEquals(1, updated);

        Task reloaded = taskMapper.findByUuid("lifecycle-stale-001");
        assertNotNull(reloaded);
        assertEquals(TaskStatus.PAUSED.getCode(), reloaded.getStatus());
        assertEquals("Task paused after waiting too long", reloaded.getErrorMessage());

        int staleUpdate = taskMapper.transitionStatusIfCurrent(
                stale.getId(),
                TaskStatus.AWAITING_USER_INPUT.getCode(),
                TaskStatus.CANCELLED.getCode(),
                "{}",
                "should not update",
                stale.getRevision()
        );
        assertEquals(0, staleUpdate);
    }

    @Test
    void concurrentClaimsOnlyAllowOneOwnerAndFenceOldCheckpointWriter() throws Exception {
        Task task = insertTask("lifecycle-lease-001", TaskStatus.PLANNING);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a = pool.submit(() -> claim(task, "owner-a", "token-a", ready, start));
            Future<Integer> b = pool.submit(() -> claim(task, "owner-b", "token-b", ready, start));
            ready.await();
            start.countDown();
            assertEquals(1, a.get() + b.get());
        } finally {
            pool.shutdownNow();
        }
        Task claimed = taskMapper.findById(task.getId());
        String currentToken = claimed.getLeaseToken();
        assertNotNull(currentToken);
        assertEquals(2L, claimed.getRevision());
        Task staleWriter = new Task();
        staleWriter.setId(task.getId());
        staleWriter.setRevision(claimed.getRevision());
        staleWriter.setCheckpointJson("stale");
        staleWriter.setStatus(TaskStatus.PLANNING.getCode());
        staleWriter.setSchemaVersion("2.0");
        staleWriter.setTotalTokensUsed(0);
        assertEquals(0, taskMapper.updateCheckpointIfOwned(staleWriter, claimed.getRevision(), "expired-token"));
        assertEquals(1, taskMapper.updateCheckpointIfOwned(staleWriter, claimed.getRevision(), currentToken));
        assertEquals(3L, taskMapper.findById(task.getId()).getRevision());
    }

    @Test
    void operationIdIsUniqueAndReplayDoesNotCreateAnotherOperation() {
        insertTask("lifecycle-operation-001", TaskStatus.PLANNING);
        assertEquals(1, taskMapper.insertOperationIfAbsent(42L, "lifecycle-operation-001", "op-1", "resume"));
        for (int i = 0; i < 10; i++) {
            assertEquals(0, taskMapper.insertOperationIfAbsent(42L, "lifecycle-operation-001", "op-1", "resume"));
        }
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM task_operations WHERE operation_id = 'op-1'", Integer.class));
        assertEquals(1, taskMapper.completeOperation(42L, "lifecycle-operation-001", "op-1", "SUCCEEDED", "{\"ok\":true}"));
        assertEquals(0, taskMapper.completeOperation(42L, "lifecycle-operation-001", "op-1", "SUCCEEDED", "{\"ok\":true}"));
    }

    private int claim(Task task, String owner, String token, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        return taskMapper.claimExecution(task.getId(), TaskStatus.PLANNING.getCode(), owner, token,
                LocalDateTime.now().plusMinutes(2), task.getRevision());
    }

    private Task insertTask(String taskUuid, TaskStatus status) {
        Task task = new Task();
        task.setTaskUuid(taskUuid);
        task.setUserId(42L);
        task.setStatus(status.getCode());
        task.setRegion("Xi'an");
        task.setSchemaVersion("2.0");
        task.setTotalTokensUsed(0);
        taskMapper.insert(task);
        return task;
    }
}
