package com.travelagent.aop;

import com.travelagent.exception.QuotaExhaustedException;
import com.travelagent.service.user.QuotaService;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 中文注释：测试类，验证 QuotaInterceptor 的配额检查拦截逻辑（AOP @Before）。
 *
 * <p>使用 {@link AspectJProxyFactory} 手动创建 AOP 代理，避免启动完整的 Spring 容器。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("QuotaInterceptor Tests")
class QuotaInterceptorTest {

    @Mock
    private QuotaService quotaService;

    @InjectMocks
    private QuotaInterceptor quotaInterceptor;

    // Target service to be proxied
    private QuotaTargetService proxy;
    private QuotaTargetService rawTarget;

    @BeforeEach
    void setUp() {
        rawTarget = new QuotaTargetService();
        AspectJProxyFactory factory = new AspectJProxyFactory(rawTarget);
        factory.addAspect(quotaInterceptor);
        proxy = factory.getProxy();
    }

    // -----------------------------------------------------------------------
    // Happy path
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("quota OK: method proceeds without exception")
    void quotaOk_methodProceeds() {
        doNothing().when(quotaService).checkDailyQuota(1L, 1);

        assertThatCode(() -> proxy.guardedMethod(1L, 1, "extra"))
                .doesNotThrowAnyException();

        verify(quotaService).checkDailyQuota(1L, 1);
    }

    // -----------------------------------------------------------------------
    // Quota exhausted
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("quota exhausted: QuotaExhaustedException propagates from aspect")
    void quotaExhausted_throwsException() {
        doThrow(new QuotaExhaustedException("daily"))
                .when(quotaService).checkDailyQuota(2L, 2);

        assertThatThrownBy(() -> proxy.guardedMethod(2L, 2, "extra"))
                .isInstanceOf(QuotaExhaustedException.class);
    }

    // -----------------------------------------------------------------------
    // Edge cases — skip checks gracefully
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("insufficient args: quota check skipped, method proceeds normally")
    void insufficientArgs_skipsCheck() {
        // This method only has 1 arg; annotation asks for index 1 → out of range
        assertThatCode(() -> proxy.singleArgMethod(1L))
                .doesNotThrowAnyException();

        verify(quotaService, never()).checkDailyQuota(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("wrong arg type at userIdArgIndex: quota check skipped")
    void wrongArgType_skipsCheck() {
        // userId arg is a String, not Long → interceptor should skip
        assertThatCode(() -> proxy.wrongTypeMethod("not-a-long", 1))
                .doesNotThrowAnyException();

        verify(quotaService, never()).checkDailyQuota(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("custom arg indices: interceptor reads correct positions")
    void customArgIndices_readsCorrectPositions() {
        doNothing().when(quotaService).checkDailyQuota(99L, 3);

        assertThatCode(() -> proxy.customIndexMethod("ignored", 3, 99L))
                .doesNotThrowAnyException();

        verify(quotaService).checkDailyQuota(99L, 3);
    }

    // -----------------------------------------------------------------------
    // Inner helper target class (methods annotated with @QuotaGuarded)
    // -----------------------------------------------------------------------

    static class QuotaTargetService {

        /** Standard usage: userId at 0, userLevel at 1. */
        @QuotaGuarded
        public void guardedMethod(Long userId, int userLevel, String extra) {
            // target body — should execute only when quota is OK
        }

        /** Only 1 arg; default indices (0, 1) both exceed args length. */
        @QuotaGuarded
        public void singleArgMethod(Long userId) {
        }

        /** userId arg is String (wrong type). */
        @QuotaGuarded
        public void wrongTypeMethod(String notALong, int userLevel) {
        }

        /** userId at index 2, userLevel at index 1. */
        @QuotaGuarded(userIdArgIndex = 2, userLevelArgIndex = 1)
        public void customIndexMethod(String ignored, int userLevel, Long userId) {
        }
    }
}
