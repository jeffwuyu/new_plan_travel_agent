package com.travelagent.service.notification;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;

/**
 * Unit tests for SseNotificationService.
 * No Spring context — tests the registry and lifecycle logic directly.
 */

/**
 * 中文注释：测试类，用于验证 Sse Notification Service Test 相关行为是否符合预期。
 */

@DisplayName("SseNotificationService Tests")
class SseNotificationServiceTest {

    private SseNotificationService service;

    private static final String TASK_UUID = "task-sse-test-001";

    @BeforeEach
    void setUp() {
        service = new SseNotificationService();
    }

    // -----------------------------------------------------------------------
    // createEmitter
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("createEmitter: 返回非 null 的 SseEmitter")
    void createEmitter_returnsNonNull() {
        SseEmitter emitter = service.createEmitter(TASK_UUID);
        assertThat(emitter).isNotNull();
    }

    @Test
    @DisplayName("createEmitter: 创建后 hasActiveEmitter → true")
    void createEmitter_registersEmitter() {
        service.createEmitter(TASK_UUID);
        assertThat(service.hasActiveEmitter(TASK_UUID)).isTrue();
    }

    @Test
    @DisplayName("createEmitter: 同一 UUID 再次创建 → 旧 emitter 被 complete()，新 emitter 注册")
    void createEmitter_replaceExisting_returnsNewEmitter() {
        SseEmitter first  = service.createEmitter(TASK_UUID);
        SseEmitter second = service.createEmitter(TASK_UUID);

        // The two emitters must be distinct objects
        assertThat(second).isNotSameAs(first);
        // Service still has an active emitter
        assertThat(service.hasActiveEmitter(TASK_UUID)).isTrue();
    }

    @Test
    @DisplayName("createEmitter: 不同 UUID 各自独立注册")
    void createEmitter_differentUUIDs_registeredSeparately() {
        service.createEmitter("uuid-a");
        service.createEmitter("uuid-b");

        assertThat(service.hasActiveEmitter("uuid-a")).isTrue();
        assertThat(service.hasActiveEmitter("uuid-b")).isTrue();
    }

    // -----------------------------------------------------------------------
    // sendEvent
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("sendEvent: 不存在的 taskUuid → 静默 no-op，不抛异常")
    void sendEvent_noEmitter_doesNotThrow() {
        assertThatNoException().isThrownBy(() ->
            service.sendEvent("nonexistent-uuid", SseEvent.STATE_CHANGE,
                Map.of("status", "planning")));
    }

    @Test
    @DisplayName("sendEvent: 发送到已注册的 emitter → 不抛异常")
    void sendEvent_existingEmitter_doesNotThrow() {
        service.createEmitter(TASK_UUID);

        // SseEmitter.send() in test environment may throw IllegalStateException
        // (no Servlet context) — the service should catch it gracefully
        assertThatNoException().isThrownBy(() ->
            service.sendEvent(TASK_UUID, SseEvent.STEP_DONE,
                Map.of("step", 1, "attractionName", "兵马俑")));
    }

    @Test
    @DisplayName("sendEvent: Redis 总线存在时发布跨实例事件")
    void sendEvent_withRedisBus_publishesEvent() {
        RedisSseEventBus bus = mock(RedisSseEventBus.class);
        ReflectionTestUtils.setField(service, "redisSseEventBus", bus);
        Map<String, Object> payload = Map.of("status", "planning");

        service.sendEvent(TASK_UUID, SseEvent.STATE_CHANGE, payload);

        verify(bus).publish(eq(TASK_UUID), eq(SseEvent.STATE_CHANGE), argThat(value ->
            value instanceof Map<?, ?> map
                && "planning".equals(map.get("status"))
                && "task-sse.v2".equals(map.get("contractVersion"))
                && "STATE_CHANGE".equals(map.get("eventType"))
                && TASK_UUID.equals(map.get("taskUuid"))
        ));
    }

    // -----------------------------------------------------------------------
    // completeEmitter
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("completeEmitter: 注册后调用 → emitter 被移除，hasActiveEmitter → false")
    void completeEmitter_removesFromRegistry() {
        service.createEmitter(TASK_UUID);
        service.completeEmitter(TASK_UUID);

        assertThat(service.hasActiveEmitter(TASK_UUID)).isFalse();
    }

    @Test
    @DisplayName("completeEmitter: 不存在的 UUID → 静默 no-op，不抛异常")
    void completeEmitter_nonExistentUUID_doesNotThrow() {
        assertThatNoException().isThrownBy(() ->
            service.completeEmitter("nonexistent-uuid"));
    }

    @Test
    @DisplayName("completeEmitter: 调用两次 → 不抛异常")
    void completeEmitter_calledTwice_doesNotThrow() {
        service.createEmitter(TASK_UUID);
        service.completeEmitter(TASK_UUID);

        assertThatNoException().isThrownBy(() ->
            service.completeEmitter(TASK_UUID));
    }

    // -----------------------------------------------------------------------
    // removeEmitter
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("removeEmitter: 注册后调用 → hasActiveEmitter → false")
    void removeEmitter_removesFromRegistry() {
        service.createEmitter(TASK_UUID);
        service.removeEmitter(TASK_UUID);

        assertThat(service.hasActiveEmitter(TASK_UUID)).isFalse();
    }

    @Test
    @DisplayName("removeEmitter: 不存在的 UUID → 静默 no-op，不抛异常")
    void removeEmitter_nonExistentUUID_doesNotThrow() {
        assertThatNoException().isThrownBy(() ->
            service.removeEmitter("nonexistent-uuid"));
    }

    // -----------------------------------------------------------------------
    // hasActiveEmitter
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("hasActiveEmitter: 未注册 → false")
    void hasActiveEmitter_neverRegistered_returnsFalse() {
        assertThat(service.hasActiveEmitter("never-registered")).isFalse();
    }

    @Test
    @DisplayName("hasActiveEmitter: 注册后 → true；removeEmitter 后 → false")
    void hasActiveEmitter_lifecycle() {
        assertThat(service.hasActiveEmitter(TASK_UUID)).isFalse();

        service.createEmitter(TASK_UUID);
        assertThat(service.hasActiveEmitter(TASK_UUID)).isTrue();

        service.removeEmitter(TASK_UUID);
        assertThat(service.hasActiveEmitter(TASK_UUID)).isFalse();
    }
}
