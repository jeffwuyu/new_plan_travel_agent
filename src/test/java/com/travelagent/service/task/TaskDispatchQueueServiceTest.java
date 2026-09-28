package com.travelagent.service.task;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("TaskDispatchQueueService Tests")
class TaskDispatchQueueServiceTest {

    @Test
    @DisplayName("retryLaterOrDeadLetter requeues before max attempts")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void retryLaterOrDeadLetter_requeuesBeforeMaxAttempts() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        StreamOperations streamOperations = mock(StreamOperations.class);
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        TaskDispatchQueueService service = new TaskDispatchQueueService(
                redisTemplate,
                true,
                "dispatch",
                "dispatch-dlq",
                3,
                0,
                "dispatchers",
                "consumer-a",
                "",
                60);
        MapRecord<String, Object, Object> record = record("1-0", Map.of(
                "taskUuid", "task-1",
                "trigger", "resume",
                "dispatchAttempts", "1"));

        boolean requeued = service.retryLaterOrDeadLetter(record, "locked");

        assertThat(requeued).isTrue();
        verify(streamOperations).add(eq("dispatch"), org.mockito.ArgumentMatchers.<Map<String, String>>argThat(body ->
                "task-1".equals(body.get("taskUuid"))
                        && "resume".equals(body.get("trigger"))
                        && "2".equals(body.get("dispatchAttempts"))
                        && "locked".equals(body.get("lastFailureReason"))
                        && body.containsKey("availableAt")
                        && body.containsKey("retriedAt")));
        verify(streamOperations).acknowledge("dispatch", "dispatchers", "1-0");
        verify(streamOperations).delete("dispatch", "1-0");
    }

    @Test
    @DisplayName("retryLaterOrDeadLetter moves to dead letter at max attempts")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void retryLaterOrDeadLetter_deadLettersAtMaxAttempts() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        StreamOperations streamOperations = mock(StreamOperations.class);
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        TaskDispatchQueueService service = new TaskDispatchQueueService(
                redisTemplate,
                true,
                "dispatch",
                "dispatch-dlq",
                2,
                0,
                "dispatchers",
                "consumer-a",
                "",
                60);
        MapRecord<String, Object, Object> record = record("1-0", Map.of(
                "taskUuid", "task-1",
                "trigger", "resume",
                "dispatchAttempts", "1"));

        boolean requeued = service.retryLaterOrDeadLetter(record, "locked");

        assertThat(requeued).isFalse();
        verify(streamOperations).add(eq("dispatch-dlq"), org.mockito.ArgumentMatchers.<Map<String, String>>argThat(body ->
                "task-1".equals(body.get("taskUuid"))
                        && "resume".equals(body.get("trigger"))
                        && "locked".equals(body.get("failureReason"))
                        && body.containsKey("failedAt")));
        verify(streamOperations).acknowledge("dispatch", "dispatchers", "1-0");
        verify(streamOperations).delete("dispatch", "1-0");
    }

    private MapRecord<String, Object, Object> record(String id, Map<String, String> value) {
        @SuppressWarnings("unchecked")
        MapRecord<String, Object, Object> record = mock(MapRecord.class);
        when(record.getId()).thenReturn(RecordId.of(id));
        Map<Object, Object> body = new LinkedHashMap<>(value);
        when(record.getValue()).thenReturn(body);
        return record;
    }
}
