package com.travelagent.service.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class TaskDispatchQueueService {

    private static final Logger log = LoggerFactory.getLogger(TaskDispatchQueueService.class);

    private final StringRedisTemplate redisTemplate;
    private final boolean enabled;
    private final String streamKey;
    private final String deadLetterStreamKey;
    private final int maxAttempts;
    private final long retryDelaySeconds;
    private final String groupName;
    private final String consumerName;
    private final long pendingClaimIdleSeconds;
    private final AtomicBoolean groupReady = new AtomicBoolean(false);

    public TaskDispatchQueueService(StringRedisTemplate redisTemplate,
                                    @Value("${agent.task.queue.enabled:true}") boolean enabled,
                                    @Value("${agent.task.queue.stream-key:agent:task:dispatch-stream}") String streamKey,
                                    @Value("${agent.task.queue.dead-letter-stream-key:agent:task:dispatch-dlq}") String deadLetterStreamKey,
                                    @Value("${agent.task.queue.max-attempts:3}") int maxAttempts,
                                    @Value("${agent.task.queue.retry-delay-seconds:30}") long retryDelaySeconds,
                                    @Value("${agent.task.queue.consumer-group:travel-agent-dispatchers}") String groupName,
                                    @Value("${agent.task.queue.consumer-name:}") String configuredConsumerName,
                                    @Value("${agent.instance-id:}") String agentInstanceId,
                                    @Value("${agent.task.queue.pending-claim-idle-seconds:60}") long pendingClaimIdleSeconds) {
        this.redisTemplate = redisTemplate;
        this.enabled = enabled;
        this.streamKey = streamKey;
        this.deadLetterStreamKey = deadLetterStreamKey;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retryDelaySeconds = Math.max(0L, retryDelaySeconds);
        this.groupName = (groupName == null || groupName.isBlank())
                ? "travel-agent-dispatchers"
                : groupName.trim();
        this.consumerName = resolveConsumerName(configuredConsumerName, agentInstanceId);
        this.pendingClaimIdleSeconds = Math.max(1L, pendingClaimIdleSeconds);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String enqueue(String taskUuid, String trigger) {
        if (!enabled) {
            return null;
        }
        Map<String, String> body = new LinkedHashMap<>();
        body.put("taskUuid", taskUuid);
        body.put("trigger", trigger == null ? "" : trigger);
        body.put("enqueuedAt", Instant.now().toString());
        body.put("dispatchAttempts", "0");
        body.put("availableAt", Instant.now().toString());
        RecordId id = redisTemplate.opsForStream().add(streamKey, body);
        ensureConsumerGroup();
        return id == null ? null : id.getValue();
    }

    public List<MapRecord<String, Object, Object>> readOldest(int limit) {
        if (!enabled || limit <= 0) {
            return List.of();
        }
        ensureConsumerGroup();
        List<MapRecord<String, Object, Object>> records = new ArrayList<>();
        records.addAll(claimIdlePending(limit));
        int remaining = Math.max(0, limit - records.size());
        if (remaining > 0) {
            List<MapRecord<String, Object, Object>> fresh = redisTemplate.opsForStream().read(
                    Consumer.from(groupName, consumerName),
                    StreamReadOptions.empty().count(remaining),
                    StreamOffset.create(streamKey, ReadOffset.lastConsumed()));
            if (fresh != null && !fresh.isEmpty()) {
                records.addAll(fresh);
            }
        }
        if (records.isEmpty()) {
            return List.of();
        }
        Instant now = Instant.now();
        return records.stream()
                .filter(record -> isAvailable(record, now))
                .limit(limit)
                .toList();
    }

    public void ack(String recordId) {
        if (!enabled || recordId == null || recordId.isBlank()) {
            return;
        }
        redisTemplate.opsForStream().acknowledge(streamKey, groupName, recordId);
        redisTemplate.opsForStream().delete(streamKey, recordId);
    }

    public void deadLetter(MapRecord<String, Object, Object> record, String reason) {
        if (!enabled || record == null) {
            return;
        }
        Map<String, String> body = new LinkedHashMap<>();
        record.getValue().forEach((key, value) -> body.put(String.valueOf(key), String.valueOf(value)));
        body.put("failedAt", Instant.now().toString());
        body.put("failureReason", reason == null ? "" : reason);
        redisTemplate.opsForStream().add(deadLetterStreamKey, body);
        ack(record.getId().getValue());
        log.warn("Moved task dispatch record {} to dead-letter stream: {}", record.getId(), reason);
    }

    public boolean retryLaterOrDeadLetter(MapRecord<String, Object, Object> record, String reason) {
        if (!enabled || record == null) {
            return false;
        }
        int attempts = attemptCount(record) + 1;
        if (attempts >= maxAttempts) {
            deadLetter(record, reason);
            return false;
        }
        Map<String, String> body = new LinkedHashMap<>();
        record.getValue().forEach((key, value) -> body.put(String.valueOf(key), String.valueOf(value)));
        body.put("dispatchAttempts", String.valueOf(attempts));
        body.put("lastFailureReason", reason == null ? "" : reason);
        body.put("availableAt", Instant.now().plusSeconds(retryDelaySeconds).toString());
        body.put("retriedAt", Instant.now().toString());
        redisTemplate.opsForStream().add(streamKey, body);
        ack(record.getId().getValue());
        log.warn("Requeued task dispatch record {} after attempt {}/{}: {}",
                record.getId(), attempts, maxAttempts, reason);
        return true;
    }

    public Map<String, Object> getQueueSnapshot() {
        Long pending = redisTemplate.opsForStream().size(streamKey);
        Long deadLetters = redisTemplate.opsForStream().size(deadLetterStreamKey);
        long pendingEntries = 0L;
        try {
            ensureConsumerGroup();
            PendingMessagesSummary summary = redisTemplate.opsForStream().pending(streamKey, groupName);
            pendingEntries = summary == null ? 0L : summary.getTotalPendingMessages();
        } catch (Exception e) {
            log.debug("Unable to read task dispatch pending summary: {}", e.getMessage());
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("enabled", enabled);
        snapshot.put("streamKey", streamKey);
        snapshot.put("consumerGroup", groupName);
        snapshot.put("consumerName", consumerName);
        snapshot.put("pendingRecords", pending == null ? 0L : pending);
        snapshot.put("pendingEntries", pendingEntries);
        snapshot.put("deadLetterStreamKey", deadLetterStreamKey);
        snapshot.put("deadLetterRecords", deadLetters == null ? 0L : deadLetters);
        snapshot.put("maxAttempts", maxAttempts);
        snapshot.put("retryDelaySeconds", retryDelaySeconds);
        snapshot.put("pendingClaimIdleSeconds", pendingClaimIdleSeconds);
        return snapshot;
    }

    private List<MapRecord<String, Object, Object>> claimIdlePending(int limit) {
        try {
            PendingMessages pendingMessages = redisTemplate.opsForStream().pending(
                    streamKey,
                    groupName,
                    Range.unbounded(),
                    limit);
            if (pendingMessages == null || pendingMessages.isEmpty()) {
                return List.of();
            }
            Duration minIdle = Duration.ofSeconds(pendingClaimIdleSeconds);
            List<RecordId> claimableIds = new ArrayList<>();
            for (PendingMessage message : pendingMessages) {
                if (!message.getElapsedTimeSinceLastDelivery().minus(minIdle).isNegative()) {
                    claimableIds.add(message.getId());
                }
            }
            if (claimableIds.isEmpty()) {
                return List.of();
            }
            List<MapRecord<String, Object, Object>> claimed = redisTemplate.opsForStream().claim(
                    streamKey,
                    groupName,
                    consumerName,
                    minIdle,
                    claimableIds.toArray(RecordId[]::new));
            return claimed == null ? List.of() : claimed;
        } catch (Exception e) {
            log.debug("Unable to claim pending task dispatch records: {}", e.getMessage());
            return List.of();
        }
    }

    private void ensureConsumerGroup() {
        if (!enabled || groupReady.get()) {
            return;
        }
        try {
            redisTemplate.opsForStream().createGroup(streamKey, ReadOffset.latest(), groupName);
            groupReady.set(true);
            log.info("Created task dispatch Redis Stream consumer group stream={} group={} consumer={}",
                    streamKey, groupName, consumerName);
        } catch (RedisSystemException e) {
            if (isBusyGroup(e)) {
                groupReady.set(true);
                return;
            }
            throw e;
        } catch (RuntimeException e) {
            if (isBusyGroup(e)) {
                groupReady.set(true);
                return;
            }
            throw e;
        }
    }

    private boolean isBusyGroup(RuntimeException e) {
        String message = e.getMessage();
        return message != null && message.toUpperCase().contains("BUSYGROUP");
    }

    private String resolveConsumerName(String configuredConsumerName, String agentInstanceId) {
        if (configuredConsumerName != null && !configuredConsumerName.isBlank()) {
            return configuredConsumerName.trim();
        }
        if (agentInstanceId != null && !agentInstanceId.isBlank()) {
            return agentInstanceId.trim();
        }
        try {
            String host = InetAddress.getLocalHost().getHostName();
            if (host != null && !host.isBlank()) {
                return host + "-" + UUID.randomUUID();
            }
        } catch (Exception ignored) {
        }
        return "consumer-" + UUID.randomUUID();
    }

    private boolean isAvailable(MapRecord<String, Object, Object> record, Instant now) {
        Object value = record.getValue().get("availableAt");
        if (value == null) {
            return true;
        }
        try {
            return !Instant.parse(String.valueOf(value)).isAfter(now);
        } catch (Exception ignored) {
            return true;
        }
    }

    private int attemptCount(MapRecord<String, Object, Object> record) {
        Object value = record.getValue().get("dispatchAttempts");
        if (value == null) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(String.valueOf(value)));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
