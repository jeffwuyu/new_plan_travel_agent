package com.travelagent.aop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelagent.util.JsonUtil;
import com.travelagent.util.RedisUtil;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 中文注释：测试类，用于验证 Idempotency Aspect Test 相关行为是否符合预期。
 */

@ExtendWith(MockitoExtension.class)
@DisplayName("IdempotencyAspect Tests")
class IdempotencyAspectTest {

    @Mock private RedisUtil redisUtil;
    @Mock private ProceedingJoinPoint pjp;
    @Mock private IdempotentTool annotation;
    @Mock private MethodSignature methodSignature;

    @InjectMocks
    private IdempotencyAspect aspect;

    private final JsonUtil jsonUtil = new JsonUtil();

    @BeforeEach
    void setUp() {
        // Inject a real JsonUtil backed by a usable ObjectMapper.
        org.springframework.test.util.ReflectionTestUtils.setField(jsonUtil, "objectMapper", new ObjectMapper());
        org.springframework.test.util.ReflectionTestUtils.setField(aspect, "jsonUtil", jsonUtil);
    }

    // -----------------------------------------------------------------------
    // Cache miss — execute and store result
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("First call: proceeds and stores result in Redis")
    @SuppressWarnings("unchecked")
    void around_cacheMiss_proceedsAndStoresResult() throws Throwable {
        Map<String, Object> args = Map.of("name", "兵马俑");
        String idempotencyKey = "task-uuid-step0-geocode";
        when(pjp.getArgs()).thenReturn(new Object[]{args, idempotencyKey});
        when(redisUtil.getString("idempotency:" + idempotencyKey + ":result")).thenReturn(null);

        Map<String, Object> expectedResult = Map.of("lat", 34.38, "lng", 109.28, "adcode", "610100");
        when(pjp.proceed()).thenReturn(expectedResult);

        Object result = aspect.around(pjp, annotation);

        assertThat(result).isEqualTo(expectedResult);
        // Verify result was stored in Redis
        verify(redisUtil).setString(
                eq("idempotency:" + idempotencyKey + ":result"),
                contains("lat"),
                any()
        );
    }

    // -----------------------------------------------------------------------
    // Cache hit — return cached result without calling proceed()
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Second call: returns cached result without executing tool")
    @SuppressWarnings("unchecked")
    void around_cacheHit_returnsCachedResultWithoutProceed() throws Throwable {
        String idempotencyKey = "task-uuid-step0-geocode";
        when(pjp.getArgs()).thenReturn(new Object[]{Map.of(), idempotencyKey});

        String cachedJson = "{\"lat\":34.38,\"lng\":109.28,\"adcode\":\"610100\"}";
        when(redisUtil.getString("idempotency:" + idempotencyKey + ":result")).thenReturn(cachedJson);

        Object result = aspect.around(pjp, annotation);

        // Should NOT call proceed
        verify(pjp, never()).proceed();

        assertThat(result).isInstanceOf(Map.class);
        Map<String, Object> resultMap = (Map<String, Object>) result;
        assertThat(((Number) resultMap.get("lat")).doubleValue()).isEqualTo(34.38);
        assertThat(resultMap.get("adcode")).isEqualTo("610100");
    }

    // -----------------------------------------------------------------------
    // No idempotency key argument — proceed without protection
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("No idempotency key: proceeds normally without Redis interaction")
    void around_noIdempotencyKey_proceedsNormally() throws Throwable {
        when(pjp.getSignature()).thenReturn(methodSignature);
        when(methodSignature.getName()).thenReturn("execute");
        when(pjp.getArgs()).thenReturn(new Object[]{"only one arg"});
        when(pjp.proceed()).thenReturn("some result");

        Object result = aspect.around(pjp, annotation);

        assertThat(result).isEqualTo("some result");
        verifyNoInteractions(redisUtil);
    }

    // -----------------------------------------------------------------------
    // Redis read failure — proceed normally (degrade gracefully)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Redis read failure: proceeds normally and attempts to store result")
    void around_redisReadFailure_proceedsNormally() throws Throwable {
        String idempotencyKey = "task-uuid-step0-geocode";
        when(pjp.getArgs()).thenReturn(new Object[]{Map.of(), idempotencyKey});
        when(redisUtil.getString(any())).thenThrow(new RuntimeException("Redis connection refused"));
        when(pjp.proceed()).thenReturn(Map.of("lat", 34.38));

        // Should not throw
        Object result = aspect.around(pjp, annotation);

        assertThat(result).isNotNull();
        verify(pjp).proceed();
    }

    // -----------------------------------------------------------------------
    // Null result — not stored in Redis
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Null result: not stored in Redis")
    void around_nullResult_notStoredInRedis() throws Throwable {
        String idempotencyKey = "task-uuid-step0-geocode";
        when(pjp.getArgs()).thenReturn(new Object[]{Map.of(), idempotencyKey});
        when(redisUtil.getString(any())).thenReturn(null);
        when(pjp.proceed()).thenReturn(null);

        aspect.around(pjp, annotation);

        verify(redisUtil, never()).setString(any(), any(), any());
    }
}
