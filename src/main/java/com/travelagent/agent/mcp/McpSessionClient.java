package com.travelagent.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class McpSessionClient {

    private static final Logger log = LoggerFactory.getLogger(McpSessionClient.class);

    private final McpProcessManager processManager;
    private final ObjectMapper objectMapper;
    private final AtomicLong requestIds = new AtomicLong(1);
    private final AtomicLong sessionRevision = new AtomicLong(0);
    private final Object sessionLock = new Object();
    private volatile SessionState sessionState;

    /**
     * 初始化McpSessionClient 实例。
     * @param processManager p ro ce ss Ma na ge r 参数
     * @param objectMapper o bj ec tM ap pe r 参数
     */
    public McpSessionClient(McpProcessManager processManager, ObjectMapper objectMapper) {
        this.processManager = processManager;
        this.objectMapper = objectMapper;
    }

    /**
     * 处理sendRequest。
     * @param method m et ho d 参数
     * @param params p ar am s 参数
     * @param timeout t im eo ut 参数
     * @return 返回处理结果。
     */
    public JsonNode sendRequest(String method, Map<String, Object> params, Duration timeout) {
        SessionState state = ensureReady();
        String id = "req-" + requestIds.getAndIncrement();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        state.pendingResponses.put(id, future);

        try {
            writeMessage(state, Map.of(
                    "jsonrpc", "2.0",
                    "id", id,
                    "method", method,
                    "params", params == null ? Map.of() : params
            ));
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new McpException("MCP request timed out: " + method, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException("Interrupted while waiting for MCP response: " + method, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new McpException("MCP request failed: " + method + ": " + cause.getMessage(), cause);
        } finally {
            state.pendingResponses.remove(id);
        }
    }

    /**
     * 处理sendNotification。
     * @param method m et ho d 参数
     * @param params p ar am s 参数
     */
    public void sendNotification(String method, Map<String, Object> params) {
        SessionState state = ensureReady();
        writeMessage(state, Map.of(
                "jsonrpc", "2.0",
                "method", method,
                "params", params == null ? Map.of() : params
        ));
    }

    /**
     * 处理resetSession。
     */
    public void resetSession() {
        synchronized (sessionLock) {
            shutdownCurrentSession();
        }
    }

    /**
     * 处理currentRevision。
     * @return 返回处理结果。
     */
    public long currentRevision() {
        return sessionRevision.get();
    }

    /**
     * 处理ensureReady。
     * @return 返回处理结果。
     */
    private SessionState ensureReady() {
        synchronized (sessionLock) {
            if (sessionState != null && sessionState.isAlive()) {
                return sessionState;
            }

            shutdownCurrentSession();
            McpProcessHandle handle = processManager.startProcess();
            SessionState newState = new SessionState(handle);
            startReaders(newState);
            sessionState = newState;
            sessionRevision.incrementAndGet();
            return newState;
        }
    }

    /**
     * 处理startReaders。
     * @param state s ta te 参数
     */
    private void startReaders(SessionState state) {
        state.ioExecutor.submit(() -> readStdout(state));
        state.ioExecutor.submit(() -> readStderr(state));
        state.handle.process().onExit().thenRun(() -> handleProcessExit(state));
    }

    /**
     * 处理readStdout。
     * @param state s ta te 参数
     */
    private void readStdout(SessionState state) {
        try {
            String line;
            while ((line = state.handle.stdout().readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node = objectMapper.readTree(line);
                if (node.isArray()) {
                    for (JsonNode item : node) {
                        processIncomingMessage(state, item);
                    }
                } else {
                    processIncomingMessage(state, node);
                }
            }
        } catch (IOException e) {
            if (state.isAlive()) {
                log.warn("[MCP] stdout reader stopped unexpectedly: {}", e.getMessage());
            }
        }
    }

    /**
     * 处理readStderr。
     * @param state s ta te 参数
     */
    private void readStderr(SessionState state) {
        try {
            String line;
            while ((line = state.handle.stderr().readLine()) != null) {
                if (!line.isBlank()) {
                    log.info("[MCP][stderr] {}", line);
                }
            }
        } catch (IOException e) {
            if (state.isAlive()) {
                log.warn("[MCP] stderr reader stopped unexpectedly: {}", e.getMessage());
            }
        }
    }

    /**
     * 处理processIncomingMessage。
     * @param state s ta te 参数
     * @param node n od e 参数
     */
    private void processIncomingMessage(SessionState state, JsonNode node) {
        JsonNode idNode = node.get("id");
        if (idNode != null && (node.has("result") || node.has("error"))) {
            CompletableFuture<JsonNode> future = state.pendingResponses.get(idNode.asText());
            if (future != null) {
                if (node.has("error")) {
                    future.completeExceptionally(new McpException(node.get("error").toString()));
                } else {
                    future.complete(node.get("result"));
                }
            }
            return;
        }

        String method = node.has("method") ? node.get("method").asText() : "";
        if (!method.isBlank()) {
            log.debug("[MCP] Notification received: {}", method);
        }
    }

    /**
     * 处理processexit。
     * @param state s ta te 参数
     */
    private void handleProcessExit(SessionState state) {
        state.closed = true;
        for (CompletableFuture<JsonNode> future : state.pendingResponses.values()) {
            future.completeExceptionally(new McpException("MCP process exited"));
        }
        state.pendingResponses.clear();

        synchronized (sessionLock) {
            if (sessionState == state) {
                processManager.stopProcess(state.handle);
                sessionState = null;
            }
        }
    }

    /**
     * 处理writeMessage。
     * @param state s ta te 参数
     * @param message 提示信息
     */
    private void writeMessage(SessionState state, Map<String, Object> message) {
        try {
            synchronized (state.writeLock) {
                state.handle.stdin().write(objectMapper.writeValueAsString(message));
                state.handle.stdin().write('\n');
                state.handle.stdin().flush();
            }
        } catch (IOException e) {
            throw new McpException("Failed to write MCP message", e);
        }
    }

    /**
     * 处理shutdownCurrentSession。
     */
    private void shutdownCurrentSession() {
        SessionState current = sessionState;
        if (current == null) {
            return;
        }
        current.closed = true;
        for (CompletableFuture<JsonNode> future : current.pendingResponses.values()) {
            future.completeExceptionally(new McpException("MCP session reset"));
        }
        current.pendingResponses.clear();
        current.ioExecutor.shutdownNow();
        processManager.stopProcess(current.handle);
        sessionState = null;
    }

    /**
     * 处理shutdown。
     */
    @PreDestroy
    public void shutdown() {
        resetSession();
    }

    private static final class SessionState {
        private final McpProcessHandle handle;
        private final Map<String, CompletableFuture<JsonNode>> pendingResponses = new ConcurrentHashMap<>();
        private final Object writeLock = new Object();
        private final ExecutorService ioExecutor = Executors.newFixedThreadPool(2, new ThreadFactory() {
            private final AtomicLong idx = new AtomicLong(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, "mcp-io-" + idx.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            }
        });
        private volatile boolean closed = false;

        /**
         * 处理SessionState。
         * @param handle h an dl e 参数
         */
        private SessionState(McpProcessHandle handle) {
            this.handle = Objects.requireNonNull(handle);
        }

        /**
         * 判断alive。
         * @return 是否满足当前条件。
         */
        private boolean isAlive() {
            return !closed && handle.process() != null && handle.process().isAlive();
        }
    }
}
