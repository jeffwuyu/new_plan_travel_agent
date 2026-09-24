package com.travelagent.client.amap;

import com.travelagent.exception.AgentErrorCode;
import com.travelagent.exception.AgentException;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 高德接口滑动窗口限流器。
 *
 * <p>按 API 名称维持本地 1 秒窗口，避免同一实例内对高德单接口发起过密请求。</p>
 */
@Component
public class AmapRateLimiter {

    private static final int MAX_REQUESTS_PER_SECOND = 3;
    private static final long WINDOW_MILLIS = 1000L;

    private final ConcurrentMap<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    /**
     * 获取指定高德 API 的调用许可，必要时阻塞等待窗口释放。
     *
     * @param apiName API 名称
     */
    public void acquire(String apiName) {
        Deque<Long> window = windows.computeIfAbsent(apiName, k -> new ArrayDeque<>());
        long waitMillis = 0L;
        synchronized (window) {
            long now = System.currentTimeMillis();
            trimExpired(window, now);
            if (window.size() >= MAX_REQUESTS_PER_SECOND) {
                long oldest = window.peekFirst() == null ? now : window.peekFirst();
                waitMillis = Math.max(1L, WINDOW_MILLIS - (now - oldest));
            }
        }

        if (waitMillis > 0L) {
            sleep(waitMillis);
        }

        synchronized (window) {
            long now = System.currentTimeMillis();
            trimExpired(window, now);
            while (window.size() >= MAX_REQUESTS_PER_SECOND) {
                long oldest = window.peekFirst() == null ? now : window.peekFirst();
                sleep(Math.max(1L, WINDOW_MILLIS - (now - oldest)));
                now = System.currentTimeMillis();
                trimExpired(window, now);
            }
            window.addLast(now);
        }
    }

    /**
     * 执行限流等待，并在中断时转换为工具异常。
     *
     * @param millis 等待毫秒数
     */
    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentException(AgentErrorCode.TOOL_AMAP_ERROR,
                    "Interrupted while waiting for Amap rate limiter", e);
        }
    }

    /**
     * 清理滑动窗口中过期的请求时间戳。
     *
     * @param window 指定 API 的请求时间窗口
     * @param now 当前时间戳
     */
    private void trimExpired(Deque<Long> window, long now) {
        while (!window.isEmpty() && now - window.peekFirst() >= WINDOW_MILLIS) {
            window.pollFirst();
        }
    }
}
