package com.travelagent.service.routemap;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PlanRouteMapSseService {

    private static final long SSE_TIMEOUT_MS = 600_000L;
    private final Map<Long, Map<String, SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter createEmitter(Long planId, Long userId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        String key = userId + ":" + System.nanoTime();
        emitters.computeIfAbsent(planId, ignored -> new ConcurrentHashMap<>()).put(key, emitter);
        emitter.onCompletion(() -> remove(planId, key));
        emitter.onTimeout(() -> remove(planId, key));
        emitter.onError(error -> remove(planId, key));
        return emitter;
    }

    public void send(Long planId, Object payload) {
        Map<String, SseEmitter> planEmitters = emitters.get(planId);
        if (planEmitters == null || planEmitters.isEmpty()) {
            return;
        }
        for (Map.Entry<String, SseEmitter> entry : planEmitters.entrySet()) {
            try {
                SseEmitter emitter = entry.getValue();
                synchronized (emitter) {
                    emitter.send(SseEmitter.event()
                            .name("route_map_progress")
                            .data(payload, MediaType.APPLICATION_JSON));
                }
            } catch (IOException | IllegalStateException e) {
                remove(planId, entry.getKey());
            }
        }
    }

    private void remove(Long planId, String key) {
        Map<String, SseEmitter> planEmitters = emitters.get(planId);
        if (planEmitters == null) {
            return;
        }
        planEmitters.remove(key);
        if (planEmitters.isEmpty()) {
            emitters.remove(planId, planEmitters);
        }
    }
}
