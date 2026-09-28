package com.travelagent.agent.planner;

import com.travelagent.mapper.TaskMapper;
import com.travelagent.model.entity.Task;
import com.travelagent.service.agent.AgentService;
import com.travelagent.service.task.RedisTaskLockService;
import com.travelagent.service.task.TaskDispatchQueueService;
import com.travelagent.service.task.TaskProgressService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TaskExecutionDispatcher Tests")
class TaskExecutionDispatcherTest {

    @Mock private TaskMapper taskMapper;
    @Mock private AgentService agentService;
    @Mock private RedisTaskLockService redisTaskLockService;
    @Mock private TaskDispatchQueueService taskDispatchQueueService;
    @Mock private TaskProgressService taskProgressService;

    private ThreadPoolTaskExecutor executor;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdown();
        }
    }

    @Test
    void dispatchTask_duplicateUuid_executesOnce() throws Exception {
        TaskExecutionDispatcher dispatcher = newDispatcher();
        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(invocation -> {
            latch.countDown();
            Thread.sleep(100);
            return null;
        }).when(agentService).executeTask("uuid");

        boolean first = dispatcher.dispatchTask("uuid", "resume:test");
        boolean second = dispatcher.dispatchTask("uuid", "resume:test");

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        verify(agentService, times(1)).executeTask("uuid");
    }

    @Test
    void dispatchByStatus_submitsRunnableTasks() throws Exception {
        TaskExecutionDispatcher dispatcher = newDispatcher();
        Task first = new Task();
        first.setTaskUuid("uuid-1");
        Task second = new Task();
        second.setTaskUuid("uuid-2");
        when(taskMapper.findByStatus("resuming", 4)).thenReturn(List.of(first, second));

        CountDownLatch latch = new CountDownLatch(2);
        doAnswer(invocation -> {
            latch.countDown();
            return null;
        }).when(agentService).executeTask(anyString());

        dispatcher.dispatchByStatus("resuming", 4);

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        verify(agentService).executeTask("uuid-1");
        verify(agentService).executeTask("uuid-2");
    }

    @Test
    void dispatchTask_whenRedisLockDenied_doesNotExecute() {
        TaskExecutionDispatcher dispatcher = newDispatcher(redisTaskLockService);
        when(redisTaskLockService.acquireForDispatch("uuid", "resume:test"))
                .thenReturn(RedisTaskLockService.AcquireResult.ALREADY_LOCKED);

        boolean dispatched = dispatcher.dispatchTask("uuid", "resume:test");

        assertThat(dispatched).isFalse();
        verify(agentService, times(0)).executeTask("uuid");
    }

    @Test
    void dispatchTask_whenRedisLockAcquired_releasesAfterExecution() throws Exception {
        TaskExecutionDispatcher dispatcher = newDispatcher(redisTaskLockService);
        when(redisTaskLockService.acquireForDispatch("uuid", "resume:test"))
                .thenReturn(RedisTaskLockService.AcquireResult.ACQUIRED);
        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(invocation -> {
            latch.countDown();
            return null;
        }).when(agentService).executeTask("uuid");

        boolean dispatched = dispatcher.dispatchTask("uuid", "resume:test");

        assertThat(dispatched).isTrue();
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        verify(redisTaskLockService, timeout(1000)).release("uuid");
    }

    @Test
    void dispatchTask_whenRedisUnavailableFailClosed_recordsBlockedAndDoesNotExecute() {
        TaskExecutionDispatcher dispatcher = newDispatcher(redisTaskLockService);
        when(redisTaskLockService.acquireForDispatch("uuid", "resume:test"))
                .thenReturn(RedisTaskLockService.AcquireResult.REDIS_UNAVAILABLE_FAIL_CLOSED);

        boolean dispatched = dispatcher.dispatchTask("uuid", "resume:test");

        assertThat(dispatched).isFalse();
        verify(agentService, never()).executeTask("uuid");
        verify(taskProgressService).recordEvent(
                anyString(),
                org.mockito.ArgumentMatchers.eq("DISPATCH_BLOCKED"),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.contains("Redis task lock unavailable"),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void dispatchTask_whenRedisUnavailableLocalFallback_executesAndRecordsDegraded() throws Exception {
        TaskExecutionDispatcher dispatcher = newDispatcher(redisTaskLockService);
        when(redisTaskLockService.acquireForDispatch("uuid", "resume:test"))
                .thenReturn(RedisTaskLockService.AcquireResult.REDIS_UNAVAILABLE_LOCAL_FALLBACK);
        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(invocation -> {
            latch.countDown();
            return null;
        }).when(agentService).executeTask("uuid");

        boolean dispatched = dispatcher.dispatchTask("uuid", "resume:test");

        assertThat(dispatched).isTrue();
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        verify(taskProgressService).recordEvent(
                anyString(),
                org.mockito.ArgumentMatchers.eq("DISPATCH_DEGRADED"),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.contains("local fallback"),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void dispatchTask_whenQueueEnabled_enqueuesAndExecutesViaDrain() throws Exception {
        TaskExecutionDispatcher dispatcher = newDispatcher(null, taskDispatchQueueService);
        when(taskDispatchQueueService.isEnabled()).thenReturn(true);
        when(taskDispatchQueueService.enqueue("uuid", "resume:test")).thenReturn("1710000000000-0");

        @SuppressWarnings("unchecked")
        MapRecord<String, Object, Object> record = org.mockito.Mockito.mock(MapRecord.class);
        when(record.getId()).thenReturn(RecordId.of("1710000000000-0"));
        Map<Object, Object> body = new LinkedHashMap<>();
        body.put("taskUuid", "uuid");
        body.put("trigger", "resume:test");
        when(record.getValue()).thenReturn(body);
        when(taskDispatchQueueService.readOldest(1)).thenReturn(List.of(record));

        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(invocation -> {
            latch.countDown();
            return null;
        }).when(agentService).executeTask("uuid");

        boolean dispatched = dispatcher.dispatchTask("uuid", "resume:test");

        assertThat(dispatched).isTrue();
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        verify(taskDispatchQueueService).enqueue("uuid", "resume:test");
        verify(taskDispatchQueueService).ack("1710000000000-0");
        verify(agentService).executeTask("uuid");
        verify(taskProgressService).recordEvent(
                anyString(),
                org.mockito.ArgumentMatchers.eq("DISPATCH_QUEUED"),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.contains("enqueued"),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void drainQueuedTasks_whenDispatchNotAccepted_requeuesWithoutAckingOriginal() {
        TaskExecutionDispatcher dispatcher = newDispatcher(redisTaskLockService, taskDispatchQueueService);
        when(taskDispatchQueueService.isEnabled()).thenReturn(true);
        when(redisTaskLockService.acquireForDispatch(anyString(), anyString()))
                .thenReturn(RedisTaskLockService.AcquireResult.ALREADY_LOCKED);

        @SuppressWarnings("unchecked")
        MapRecord<String, Object, Object> record = org.mockito.Mockito.mock(MapRecord.class);
        when(record.getId()).thenReturn(RecordId.of("1710000000000-0"));
        Map<Object, Object> body = new LinkedHashMap<>();
        body.put("taskUuid", "uuid");
        body.put("trigger", "resume:test");
        when(record.getValue()).thenReturn(body);
        when(taskDispatchQueueService.readOldest(1)).thenReturn(List.of(record));

        int dispatched = dispatcher.drainQueuedTasks(1);

        assertThat(dispatched).isZero();
        verify(taskDispatchQueueService).retryLaterOrDeadLetter(record, "dispatch not accepted");
        verify(taskDispatchQueueService, never()).ack("1710000000000-0");
        verify(agentService, never()).executeTask("uuid");
    }

    private TaskExecutionDispatcher newDispatcher() {
        return newDispatcher(null, null);
    }

    private TaskExecutionDispatcher newDispatcher(RedisTaskLockService lockService) {
        return newDispatcher(lockService, null);
    }

    private TaskExecutionDispatcher newDispatcher(RedisTaskLockService lockService,
                                                  TaskDispatchQueueService queueService) {
        TaskExecutionDispatcher dispatcher = new TaskExecutionDispatcher();
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(10);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();

        ReflectionTestUtils.setField(dispatcher, "taskMapper", taskMapper);
        ReflectionTestUtils.setField(dispatcher, "agentService", agentService);
        ReflectionTestUtils.setField(dispatcher, "executor", executor);
        ReflectionTestUtils.setField(dispatcher, "redisTaskLockService", lockService);
        ReflectionTestUtils.setField(dispatcher, "taskProgressService", taskProgressService);
        ReflectionTestUtils.setField(dispatcher, "taskDispatchQueueService", queueService);
        return dispatcher;
    }
}
