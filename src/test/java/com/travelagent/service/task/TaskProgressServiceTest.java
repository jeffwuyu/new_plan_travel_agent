package com.travelagent.service.task;

import com.travelagent.mapper.TaskExecutionEventMapper;
import com.travelagent.mapper.TaskMapper;
import com.travelagent.model.dto.TaskExecutionProgressResponse;
import com.travelagent.model.entity.Task;
import com.travelagent.model.entity.TaskExecutionEvent;
import com.travelagent.service.task.impl.TaskProgressServiceImpl;
import com.travelagent.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class TaskProgressServiceTest {

    @Mock private TaskExecutionEventMapper eventMapper;
    @Mock private TaskMapper               taskMapper;
    @Mock private JsonUtil                 jsonUtil;

    @InjectMocks
    private TaskProgressServiceImpl service;

    private static final String TASK_UUID = "test-uuid-1234";

    @BeforeEach
    void setup() {
        lenient().when(jsonUtil.toJson(any())).thenReturn("{\"key\":\"value\"}");
    }

    @Test
    @DisplayName("recordEvent: inserts event into mapper with correct fields")
    void recordEvent_success() {
        service.recordEvent(TASK_UUID, "STATE_CHANGE", "planning",
                2, 9, "Starting step 2", Map.of("foo", "bar"));

        ArgumentCaptor<TaskExecutionEvent> captor = ArgumentCaptor.forClass(TaskExecutionEvent.class);
        verify(eventMapper).insert(captor.capture());

        TaskExecutionEvent saved = captor.getValue();
        assertThat(saved.getTaskUuid()).isEqualTo(TASK_UUID);
        assertThat(saved.getEventType()).isEqualTo("STATE_CHANGE");
        assertThat(saved.getStatus()).isEqualTo("planning");
        assertThat(saved.getStepIndex()).isEqualTo(2);
        assertThat(saved.getTotalSteps()).isEqualTo(9);
        assertThat(saved.getMessage()).isEqualTo("Starting step 2");
        assertThat(saved.getDetailsJson()).isEqualTo("{\"key\":\"value\"}");
    }

    @Test
    @DisplayName("recordEvent: DB error does not propagate to caller")
    void recordEvent_dbError_doesNotThrow() {
        doThrow(new RuntimeException("DB connection lost")).when(eventMapper).insert(any());

        assertThatCode(() ->
                service.recordEvent(TASK_UUID, "ERROR", "failed", null, null, "boom", null)
        ).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("recordEvent: message longer than 512 chars is truncated")
    void recordEvent_messageTruncatedAt512Chars() {
        String longMsg = "x".repeat(600);
        service.recordEvent(TASK_UUID, "STATE_CHANGE", null, null, null, longMsg, null);

        ArgumentCaptor<TaskExecutionEvent> captor = ArgumentCaptor.forClass(TaskExecutionEvent.class);
        verify(eventMapper).insert(captor.capture());

        String saved = captor.getValue().getMessage();
        assertThat(saved).hasSize(512);
        assertThat(saved).endsWith("...");
    }

    @Test
    @DisplayName("getProgress: returns paginated events and totalEventCount")
    void getProgress_returnsPaginatedEvents() {
        Task task = new Task();
        task.setStatus("planning");
        when(taskMapper.findByUuid(TASK_UUID)).thenReturn(task);
        when(eventMapper.countByTaskUuid(TASK_UUID)).thenReturn(15);

        TaskExecutionEvent e1 = new TaskExecutionEvent();
        e1.setStepIndex(3);
        e1.setTotalSteps(9);
        TaskExecutionEvent e2 = new TaskExecutionEvent();
        e2.setStepIndex(4);
        e2.setTotalSteps(9);
        when(eventMapper.findByTaskUuid(TASK_UUID, 5)).thenReturn(List.of(e1, e2));

        TaskExecutionProgressResponse resp = service.getProgress(TASK_UUID, 5);

        assertThat(resp.getTaskUuid()).isEqualTo(TASK_UUID);
        assertThat(resp.getCurrentStatus()).isEqualTo("planning");
        assertThat(resp.getTotalEventCount()).isEqualTo(15);
        assertThat(resp.getEvents()).hasSize(2);
        assertThat(resp.getCurrentStepIndex()).isEqualTo(4);
        assertThat(resp.getTotalSteps()).isEqualTo(9);
    }

    @Test
    @DisplayName("getLatestEvent: returns null when mapper returns null")
    void getLatestEvent_returnsNullWhenNoEvents() {
        when(eventMapper.findLatestByTaskUuid(TASK_UUID)).thenReturn(null);

        TaskExecutionEvent result = service.getLatestEvent(TASK_UUID);

        assertThat(result).isNull();
    }
}
