package com.travelagent.service.task;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("RedisTaskLockService Tests")
class RedisTaskLockServiceTest {

    @Test
    @DisplayName("default policy blocks dispatch when Redis is unavailable")
    void acquireForDispatch_redisUnavailable_defaultFailClosed() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("redis down"));
        RedisTaskLockService service = new RedisTaskLockService(
                redisTemplate,
                600000,
                600000,
                "instance-a",
                "fail_closed");

        RedisTaskLockService.AcquireResult result = service.acquireForDispatch("task-1", "test");

        assertThat(result).isEqualTo(RedisTaskLockService.AcquireResult.REDIS_UNAVAILABLE_FAIL_CLOSED);
        assertThat(result.shouldDispatch()).isFalse();
    }

    @Test
    @DisplayName("local fallback policy allows dispatch but marks Redis unavailable")
    void acquireForDispatch_redisUnavailable_localFallback() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("redis down"));
        RedisTaskLockService service = new RedisTaskLockService(
                redisTemplate,
                600000,
                600000,
                "instance-a",
                "local_fallback");

        RedisTaskLockService.AcquireResult result = service.acquireForDispatch("task-1", "test");

        assertThat(result).isEqualTo(RedisTaskLockService.AcquireResult.REDIS_UNAVAILABLE_LOCAL_FALLBACK);
        assertThat(result.shouldDispatch()).isTrue();
        assertThat(result.redisUnavailable()).isTrue();
    }

    @Test
    @DisplayName("lease check treats Redis errors as invalid lease")
    void hasValidLease_redisUnavailable_returnsFalse() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.getExpire("agent:task:lease:task-1")).thenThrow(new RuntimeException("redis down"));
        RedisTaskLockService service = new RedisTaskLockService(
                redisTemplate,
                600000,
                600000,
                "instance-a",
                "fail_closed");

        assertThat(service.hasValidLease("task-1")).isFalse();
    }

    @Test
    @DisplayName("release uses owner-check Lua script")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void release_usesOwnerCheckedLuaScript() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        RedisTaskLockService service = new RedisTaskLockService(
                redisTemplate,
                600000,
                600000,
                "instance-a",
                "fail_closed");

        service.release("task-1");

        ArgumentCaptor<DefaultRedisScript> scriptCaptor = ArgumentCaptor.forClass(DefaultRedisScript.class);
        verify(redisTemplate).execute(scriptCaptor.capture(), anyList(), any());
        assertThat(scriptCaptor.getValue().getScriptAsString()).contains("DEL", "ARGV[1]");
    }

    @Test
    @DisplayName("getLeaseInfo returns lock and TTL snapshot")
    void getLeaseInfo_returnsSnapshot() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        var valueOps = mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("agent:task:lock:task-1")).thenReturn("instance-a|thread|1|dispatch");
        when(valueOps.get("agent:task:lease:task-1")).thenReturn("instance-a|thread|1|dispatch");
        when(redisTemplate.getExpire("agent:task:lock:task-1")).thenReturn(30L);
        when(redisTemplate.getExpire("agent:task:lease:task-1")).thenReturn(25L);

        RedisTaskLockService service = new RedisTaskLockService(
                redisTemplate,
                600000,
                600000,
                "instance-a",
                "fail_closed");

        var info = service.getLeaseInfo("task-1");

        assertThat(info.get("taskUuid")).isEqualTo("task-1");
        assertThat(info.get("locked")).isEqualTo(true);
        assertThat(info.get("leaseValid")).isEqualTo(true);
        assertThat(info.get("lockTtlSeconds")).isEqualTo(30L);
        assertThat(info.get("leaseTtlSeconds")).isEqualTo(25L);
    }
}
