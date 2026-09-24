package com.travelagent.service.notification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

@Service
public class SseTicketService {

    private final StringRedisTemplate redisTemplate;
    private final Duration ttl;
    private final SecureRandom secureRandom = new SecureRandom();

    public SseTicketService(StringRedisTemplate redisTemplate,
                            @Value("${agent.sse.ticket-ttl-seconds:60}") long ttlSeconds) {
        this.redisTemplate = redisTemplate;
        this.ttl = Duration.ofSeconds(Math.max(10L, ttlSeconds));
    }

    public String issue(Long userId, String taskUuid) {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        redisTemplate.opsForValue().set(key(ticket), userId + "|" + taskUuid, ttl);
        return ticket;
    }

    public Optional<TicketPrincipal> validate(String ticket, String taskUuid) {
        if (ticket == null || ticket.isBlank()) {
            return Optional.empty();
        }
        String redisKey = key(ticket);
        String value = redisTemplate.opsForValue().get(redisKey);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String[] parts = value.split("\\|", 2);
        if (parts.length != 2 || !parts[1].equals(taskUuid)) {
            return Optional.empty();
        }
        try {
            redisTemplate.expire(redisKey, Duration.ofSeconds(Math.min(ttl.getSeconds(), 15L)));
            return Optional.of(new TicketPrincipal(Long.valueOf(parts[0]), taskUuid));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private String key(String ticket) {
        return "sse:ticket:" + ticket;
    }

    public record TicketPrincipal(Long userId, String taskUuid) {}
}
