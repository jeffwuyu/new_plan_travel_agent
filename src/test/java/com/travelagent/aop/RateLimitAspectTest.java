package com.travelagent.aop;

import com.travelagent.exception.RateLimitExceededException;
import com.travelagent.util.RedisUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 中文注释：测试类，验证 RateLimitAspect 对匿名用户的 IP 级限流逻辑（AOP @Before）。
 *
 * <p>使用 {@link AspectJProxyFactory} 手动创建 AOP 代理，
 * 配合 {@link MockHttpServletRequest} + {@link RequestContextHolder} 模拟 HTTP 请求上下文。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RateLimitAspect Tests")
class RateLimitAspectTest {

    @Mock
    private RedisUtil redisUtil;

    @InjectMocks
    private RateLimitAspect rateLimitAspect;

    private RateLimitTargetController proxy;
    private MockHttpServletRequest mockRequest;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(rateLimitAspect, "hourlyLimit", 10);

        RateLimitTargetController target = new RateLimitTargetController();
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(rateLimitAspect);
        proxy = factory.getProxy();

        mockRequest = new MockHttpServletRequest();
        mockRequest.setRemoteAddr("192.168.1.1");
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    // -----------------------------------------------------------------------
    // Anonymous user — within limit
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("anonymous user under limit: request proceeds")
    void anonymousUser_underLimit_proceeds() {
        bindRequest(mockRequest);
        when(redisUtil.incrementWithTtl(anyString(), eq(1L), any(Duration.class)))
                .thenReturn(5L); // 5 < 10

        assertThatCode(() -> proxy.publicEndpoint())
                .doesNotThrowAnyException();

        verify(redisUtil).incrementWithTtl(anyString(), eq(1L), any(Duration.class));
    }

    // -----------------------------------------------------------------------
    // Anonymous user — over limit
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("anonymous user over limit: RateLimitExceededException thrown")
    void anonymousUser_overLimit_throwsRateLimitExceededException() {
        bindRequest(mockRequest);
        when(redisUtil.incrementWithTtl(anyString(), eq(1L), any(Duration.class)))
                .thenReturn(11L); // 11 > 10

        assertThatThrownBy(() -> proxy.publicEndpoint())
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    @DisplayName("anonymous user exactly at limit: request proceeds (limit is exclusive)")
    void anonymousUser_exactlyAtLimit_proceeds() {
        bindRequest(mockRequest);
        when(redisUtil.incrementWithTtl(anyString(), eq(1L), any(Duration.class)))
                .thenReturn(10L); // 10 == limit, not strictly greater

        assertThatCode(() -> proxy.publicEndpoint())
                .doesNotThrowAnyException();
    }

    // -----------------------------------------------------------------------
    // Authenticated user — exempt
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("authenticated user: rate limit not applied")
    void authenticatedUser_notRateLimited() {
        mockRequest.setAttribute("userId", 42L); // JWT interceptor sets this
        bindRequest(mockRequest);

        assertThatCode(() -> proxy.publicEndpoint())
                .doesNotThrowAnyException();

        verify(redisUtil, never()).incrementWithTtl(anyString(), anyLong(), any());
    }

    // -----------------------------------------------------------------------
    // No HTTP request context (async agent thread)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("no HTTP request context: aspect silently skips check")
    void noRequestContext_skipsCheck() {
        // No RequestContextHolder binding — simulates async executor thread
        assertThatCode(() -> proxy.publicEndpoint())
                .doesNotThrowAnyException();

        verify(redisUtil, never()).incrementWithTtl(anyString(), anyLong(), any());
    }

    // -----------------------------------------------------------------------
    // X-Forwarded-For header handling
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("X-Forwarded-For header: first IP in chain is used for rate limit key")
    void xForwardedFor_usesFirstIp() {
        mockRequest.addHeader("X-Forwarded-For", "10.0.0.1, 172.16.0.1, 192.168.1.1");
        bindRequest(mockRequest);
        when(redisUtil.incrementWithTtl(anyString(), eq(1L), any(Duration.class)))
                .thenReturn(1L);

        proxy.publicEndpoint();

        // The Redis key should contain the first IP from X-Forwarded-For
        verify(redisUtil).incrementWithTtl(
                org.mockito.ArgumentMatchers.contains("10.0.0.1"),
                eq(1L), any(Duration.class));
    }

    @Test
    @DisplayName("X-Real-IP header: used as fallback when X-Forwarded-For absent")
    void xRealIp_usedWhenNoForwardedFor() {
        mockRequest.addHeader("X-Real-IP", "203.0.113.5");
        bindRequest(mockRequest);
        when(redisUtil.incrementWithTtl(anyString(), eq(1L), any(Duration.class)))
                .thenReturn(1L);

        proxy.publicEndpoint();

        verify(redisUtil).incrementWithTtl(
                org.mockito.ArgumentMatchers.contains("203.0.113.5"),
                eq(1L), any(Duration.class));
    }

    // -----------------------------------------------------------------------
    // Redis key pattern
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("rate limit Redis key contains IP and hour bucket")
    void redisKey_containsIpAndHour() {
        mockRequest.setRemoteAddr("1.2.3.4");
        bindRequest(mockRequest);
        when(redisUtil.incrementWithTtl(anyString(), eq(1L), any(Duration.class)))
                .thenReturn(1L);

        proxy.publicEndpoint();

        verify(redisUtil).incrementWithTtl(
                org.mockito.ArgumentMatchers.matches("ratelimit:ip:1\\.2\\.3\\.4:\\d{4}-\\d{2}-\\d{2}-\\d{2}"),
                eq(1L), eq(Duration.ofHours(2)));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void bindRequest(MockHttpServletRequest request) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    // -----------------------------------------------------------------------
    // Inner target class
    // -----------------------------------------------------------------------

    static class RateLimitTargetController {

        @RateLimit
        public void publicEndpoint() {
            // Simulates a REST endpoint accessible without authentication
        }
    }
}
