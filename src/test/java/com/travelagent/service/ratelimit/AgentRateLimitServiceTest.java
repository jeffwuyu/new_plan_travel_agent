package com.travelagent.service.ratelimit;

import com.travelagent.exception.RateLimitExceededException;
import com.travelagent.util.RedisUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentRateLimitService Tests")
class AgentRateLimitServiceTest {

    @Mock private RedisUtil redisUtil;

    private AgentRateLimitService service;

    @BeforeEach
    void setUp() {
        service = new AgentRateLimitService(redisUtil);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "windowSeconds", 60L);
        ReflectionTestUtils.setField(service, "redisUnavailablePolicy", "fail_closed");
        ReflectionTestUtils.setField(service, "toolPerUserPerMinute", 20L);
        ReflectionTestUtils.setField(service, "toolPerIpPerMinute", 60L);
        ReflectionTestUtils.setField(service, "llmPerUserPerMinute", 5L);
        ReflectionTestUtils.setField(service, "llmPerIpPerMinute", 20L);
    }

    @Test
    void checkToolLimit_underLimit_allowsAndSetsTtl() {
        when(redisUtil.incrementWithTtl(anyString(), anyLong(), any(Duration.class))).thenReturn(1L, 1L);

        assertThatNoException().isThrownBy(() -> service.checkToolLimit(7L, "203.0.113.10"));

        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(redisUtil, org.mockito.Mockito.times(2)).incrementWithTtl(anyString(), anyLong(), ttlCaptor.capture());
        assertThat(ttlCaptor.getAllValues()).containsOnly(Duration.ofSeconds(65));
    }

    @Test
    void checkLlmLimit_equalToLimit_allows() {
        when(redisUtil.incrementWithTtl(anyString(), anyLong(), any(Duration.class))).thenReturn(5L, 20L);

        assertThatNoException().isThrownBy(() -> service.checkLlmLimit(7L, "203.0.113.10"));
    }

    @Test
    void checkLlmLimit_overUserLimit_throws429() {
        when(redisUtil.incrementWithTtl(anyString(), anyLong(), any(Duration.class))).thenReturn(6L);

        assertThatThrownBy(() -> service.checkLlmLimit(7L, "203.0.113.10"))
                .isInstanceOf(RateLimitExceededException.class)
                .satisfies(ex -> assertThat(((RateLimitExceededException) ex).getHttpStatus()).isEqualTo(429));
    }

    @Test
    void checkToolLimit_redisUnavailableFailClosed_throws429() {
        when(redisUtil.incrementWithTtl(anyString(), anyLong(), any(Duration.class)))
                .thenThrow(new IllegalStateException("redis down"));

        assertThatThrownBy(() -> service.checkToolLimit(7L, "203.0.113.10"))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining("RATE_LIMIT_REDIS_UNAVAILABLE");
    }
}
