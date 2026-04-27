package com.travelagent.client.amap;

import com.travelagent.exception.AgentErrorCode;
import com.travelagent.exception.AgentException;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Sliding-window rate limiter for AMap APIs (3 req/sec per API endpoint).
 */
@Component
public class AmapRateLimiter {

    private static final int MAX_REQUESTS_PER_SECOND = 3;
    private static final long WINDOW_MILLIS = 1000L;

    private final ConcurrentMap<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    /**
     * 处理acquire。
     * @param apiName a pi Na me 参数
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
     * 处理sleep。
     * @param millis m il li s 参数
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
     * 处理trimExpired。
     * @param window w in do w 参数
     * @param now n ow 参数
     */
    private void trimExpired(Deque<Long> window, long now) {
        while (!window.isEmpty() && now - window.peekFirst() >= WINDOW_MILLIS) {
            window.pollFirst();
        }
    }
}
