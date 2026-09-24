package com.travelagent.service.notification;

import com.fasterxml.jackson.core.type.TypeReference;
import com.travelagent.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class RedisSseEventBus implements MessageListener {

    public static final String CHANNEL = "agent:sse:task-events";

    private static final Logger log = LoggerFactory.getLogger(RedisSseEventBus.class);

    private final StringRedisTemplate redisTemplate;
    private final JsonUtil jsonUtil;
    private final ObjectProvider<SseNotificationService> notificationServiceProvider;
    private final String instanceId;
    private final boolean enabled;

    public RedisSseEventBus(StringRedisTemplate redisTemplate,
                            JsonUtil jsonUtil,
                            ObjectProvider<SseNotificationService> notificationServiceProvider,
                            @Value("${agent.sse.redis-enabled:true}") boolean enabled,
                            @Value("${agent.instance-id:}") String configuredInstanceId) {
        this.redisTemplate = redisTemplate;
        this.jsonUtil = jsonUtil;
        this.notificationServiceProvider = notificationServiceProvider;
        this.enabled = enabled;
        this.instanceId = resolveInstanceId(configuredInstanceId);
    }

    public void publish(String taskUuid, SseEvent eventType, Object payload) {
        if (!enabled) {
            return;
        }
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("instanceId", instanceId);
            envelope.put("taskUuid", taskUuid);
            envelope.put("eventType", eventType.name());
            envelope.put("payload", payload);
            envelope.put("publishedAt", Instant.now().toString());
            redisTemplate.convertAndSend(CHANNEL, jsonUtil.toJson(envelope));
        } catch (Exception e) {
            log.warn("Failed to publish SSE event to Redis, task={}, eventType={}: {}",
                    taskUuid, eventType, e.getMessage());
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        if (!enabled || message == null || message.getBody() == null) {
            return;
        }
        try {
            String json = new String(message.getBody(), StandardCharsets.UTF_8);
            Map<String, Object> envelope = jsonUtil.fromJson(json, new TypeReference<>() {});
            if (instanceId.equals(String.valueOf(envelope.get("instanceId")))) {
                return;
            }
            String taskUuid = String.valueOf(envelope.get("taskUuid"));
            SseEvent eventType = SseEvent.valueOf(String.valueOf(envelope.get("eventType")));
            Object payload = envelope.get("payload");
            SseNotificationService service = notificationServiceProvider.getIfAvailable();
            if (service != null) {
                service.sendLocalEvent(taskUuid, eventType, payload);
            }
        } catch (Exception e) {
            log.warn("Failed to consume Redis SSE event: {}", e.getMessage());
        }
    }

    private String resolveInstanceId(String configuredInstanceId) {
        if (configuredInstanceId != null && !configuredInstanceId.isBlank()) {
            return configuredInstanceId.trim();
        }
        try {
            return InetAddress.getLocalHost().getHostName() + "-" + UUID.randomUUID();
        } catch (Exception e) {
            return "agent-" + UUID.randomUUID();
        }
    }
}
