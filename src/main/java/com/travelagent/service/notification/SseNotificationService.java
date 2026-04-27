package com.travelagent.service.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry and dispatcher for Server-Sent Events (SSE) per agent task.
 *
 * <h3>Thread safety</h3>
 * {@link SseEmitter#send} is not documented as thread-safe. Multiple agent
 * threads may push events concurrently for the same task (e.g. dispatcher +
 * agent loop). Each {@code sendEvent()} call synchronizes on the emitter
 * instance to prevent interleaved writes.
 *
 * <h3>Lifetime</h3>
 * <ul>
 *   <li>Emitters are created on client connection (SseController).</li>
 *   <li>Emitters are removed on timeout, client disconnect, or task completion.</li>
 *   <li>Creating a new emitter for an existing task UUID completes the old one first.</li>
 * </ul>
 *
 * <h3>Single-instance deployment</h3>
 * The in-memory map is sufficient for a single-instance deployment.
 * For horizontal scaling, events would need to be routed via Redis Pub/Sub.
 */

/**
 * 中文注释：服务类，定义 Sse Notification Service 相关业务能力。
 */

@Service
public class SseNotificationService {

    private static final Logger log = LoggerFactory.getLogger(SseNotificationService.class);

    private static final long SSE_TIMEOUT_MS = 600_000L;

    private final ConcurrentHashMap<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    // -----------------------------------------------------------------------
    // Emitter lifecycle
    // -----------------------------------------------------------------------

    /**
     * 创建emitter。
     * @param taskUuid 任务唯一标识
     * @return 返回处理结果。
     */
    public SseEmitter createEmitter(String taskUuid) {
        SseEmitter existing = emitters.get(taskUuid);
        if (existing != null) {
            try { existing.complete(); } catch (Exception ignored) {}
        }

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitters.put(taskUuid, emitter);

        emitter.onTimeout(() -> {
            log.debug("SSE timeout for task={}", taskUuid);
            emitters.remove(taskUuid, emitter);
        });
        emitter.onError(ex -> {
            log.debug("SSE error for task={}: {}", taskUuid, ex.getMessage());
            emitters.remove(taskUuid, emitter);
        });
        emitter.onCompletion(() -> emitters.remove(taskUuid, emitter));

        return emitter;
    }

    /**
     * 处理sendEvent。
     * @param taskUuid 任务唯一标识
     * @param eventType e ve nt Ty pe 参数
     * @param payload 事件载荷
     */
    public void sendEvent(String taskUuid, SseEvent eventType, Object payload) {
        SseEmitter emitter = emitters.get(taskUuid);
        if (emitter == null) return;

        try {
            synchronized (emitter) {
                emitter.send(SseEmitter.event()
                    .name(eventType.name())
                    .data(payload, MediaType.APPLICATION_JSON));
            }
        } catch (IOException e) {
            log.debug("Failed to send SSE event type={} for task={}, removing emitter",
                eventType, taskUuid);
            emitters.remove(taskUuid, emitter);
        } catch (IllegalStateException e) {
            // Emitter already completed — remove stale entry
            log.debug("Emitter already completed for task={}", taskUuid);
            emitters.remove(taskUuid, emitter);
        }
    }

    /**
     * 处理completeEmitter。
     * @param taskUuid 任务唯一标识
     */
    public void completeEmitter(String taskUuid) {
        SseEmitter emitter = emitters.remove(taskUuid);
        if (emitter != null) {
            try { emitter.complete(); } catch (Exception ignored) {}
        }
    }

    /**
     * 移除emitter。
     * @param taskUuid 任务唯一标识
     */
    public void removeEmitter(String taskUuid) {
        emitters.remove(taskUuid);
    }

    /**
     * 判断是否具备activeemitter。
     * @param taskUuid 任务唯一标识
     * @return 是否满足当前条件。
     */
    public boolean hasActiveEmitter(String taskUuid) {
        return emitters.containsKey(taskUuid);
    }
}
