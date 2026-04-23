package com.travelagent.agent.planner;

import com.travelagent.mapper.TaskMapper;
import com.travelagent.model.entity.Task;
import com.travelagent.service.agent.AgentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TaskExecutionDispatcher Tests")
class TaskExecutionDispatcherTest {

    @Mock private TaskMapper taskMapper;
    @Mock private AgentService agentService;

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

    private TaskExecutionDispatcher newDispatcher() {
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
        return dispatcher;
    }
}
