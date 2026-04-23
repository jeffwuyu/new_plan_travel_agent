package com.travelagent.client.dashscope;

import com.travelagent.advisor.JsonSchemaAdvisor;
import com.travelagent.advisor.RagContextAdvisor;
import com.travelagent.advisor.TravelPlanningAdvisor;
import com.travelagent.exception.AgentErrorCode;
import com.travelagent.exception.AgentException;
import com.travelagent.mapper.LlmCallLogMapper;
import com.travelagent.model.entity.LlmCallLog;
import com.travelagent.monitoring.TaskMetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * Client for Tongyi Qianwen LLM calls via Spring AI 1.0.0.
 *
 * <p><b>Bean injection note:</b> The {@link ChatModel} bean is created explicitly in
 * {@code LlmConfig} from the {@code dashscope.*} keys in {@code application.yml}.
 * Do <em>not</em> inject the raw Dashscope {@code Generation} bean defined in
 * {@code LlmConfig}; that bean is reserved for Phase 5 embeddings.
 *
 * <p>All calls are logged to the {@code llm_call_logs} table for:
 * <ul>
 *   <li>Quota reconstruction after Redis restart</li>
 *   <li>Billing and latency analysis</li>
 * </ul>
 *
 * <p>Streaming ({@link #callStreaming}) runs {@code Flux.blockLast()} on the caller's
 * thread. This is safe because {@code AgentServiceImpl} executes on the
 * {@code agentTaskExecutor} thread pool (non-reactive), never on a Netty event loop.
 */
@Service
public class DashscopeLlmClient {

    private static final Logger log = LoggerFactory.getLogger(DashscopeLlmClient.class);

    @Value("${dashscope.llm.model:qwen-plus}")
    private String model;

    @Value("${dashscope.max-retries:3}")
    private int maxRetries;

    private final ChatClient chatClient;
    private final LlmCallLogMapper llmCallLogMapper;
    private final Map<String, Advisor> advisorRegistry;

    @Autowired
    private TaskMetricsService taskMetricsService;

    @Autowired
    public DashscopeLlmClient(ChatClient.Builder chatClientBuilder,
                              LlmCallLogMapper llmCallLogMapper,
                              List<Advisor> advisors) {
        this.chatClient = chatClientBuilder.build();
        this.llmCallLogMapper = llmCallLogMapper;
        this.advisorRegistry = buildAdvisorRegistry(advisors);
    }

    /**
     * Synchronous LLM call with automatic retry.
     *
     * @param taskId DB task primary key (for audit log, may be null during tests)
     * @param userId user primary key (for audit log)
     * @param callType one of: {@code planning}, {@code tool_call}, {@code history_compress}
     * @param systemPrompt system message text
     * @param history conversation history from checkpoint
     * @param userMessage current user turn
     * @param idempotencyKey used in the audit log for deduplication queries
     * @return LLM response text
     */
    public String call(Long taskId, Long userId, String callType,
                       String systemPrompt, List<Map<String, Object>> history,
                       String userMessage, String idempotencyKey) {
        return callWithUsage(taskId, userId, callType, systemPrompt, history, userMessage,
                idempotencyKey, List.of(), Map.of()).content();
    }

    public String call(Long taskId, Long userId, String callType,
                       String systemPrompt, List<Map<String, Object>> history,
                       String userMessage, String idempotencyKey,
                       List<String> advisorNames,
                       Map<String, Object> advisorContext) {
        return callWithUsage(taskId, userId, callType, systemPrompt, history, userMessage,
                idempotencyKey, advisorNames, advisorContext).content();
    }

    public LlmCallResult callWithUsage(Long taskId, Long userId, String callType,
                                       String systemPrompt, List<Map<String, Object>> history,
                                       String userMessage, String idempotencyKey) {
        return callWithUsage(taskId, userId, callType, systemPrompt, history, userMessage,
                idempotencyKey, List.of(), Map.of());
    }

    public LlmCallResult callWithUsage(Long taskId, Long userId, String callType,
                                       String systemPrompt, List<Map<String, Object>> history,
                                       String userMessage, String idempotencyKey,
                                       List<String> advisorNames,
                                       Map<String, Object> advisorContext) {

        List<Message> messages = buildMessages(systemPrompt, history, userMessage);
        List<Advisor> resolvedAdvisors = resolveAdvisors(advisorNames);
        Map<String, Object> requestContext = advisorContext != null ? advisorContext : Map.of();

        long t0 = System.currentTimeMillis();
        boolean success = true;
        try {
            return withRetry(() -> {
                long start = System.currentTimeMillis();
                String status = "success";
                int promptTokens = 0;
                int completionTokens = 0;

                try {
                    ChatResponse resp = applyAdvisors(
                            chatClient.prompt().messages(messages),
                            resolvedAdvisors,
                            requestContext)
                            .call()
                            .chatResponse();

                    String content = resp.getResult().getOutput().getText();
                    long latencyMs = System.currentTimeMillis() - start;

                    var usage = resp.getMetadata().getUsage();
                    if (usage != null) {
                        promptTokens = (int) usage.getPromptTokens();
                        completionTokens = (int) usage.getCompletionTokens();
                    }

                    auditLog(taskId, userId, callType, promptTokens, completionTokens,
                            latencyMs, status, idempotencyKey);

                    return new LlmCallResult(content, promptTokens + completionTokens);
                } catch (Exception e) {
                    long latencyMs = System.currentTimeMillis() - start;
                    auditLog(taskId, userId, callType, 0, 0, latencyMs, "error", idempotencyKey);
                    throw e;
                }
            }, "LLM call [" + callType + "]");
        } catch (Exception e) {
            success = false;
            throw e;
        } finally {
            taskMetricsService.recordLlmCall(System.currentTimeMillis() - t0, success);
        }
    }

    /**
     * Streaming LLM call; each token is delivered to {@code tokenConsumer} as it arrives.
     *
     * <p>The method blocks until the stream is exhausted and returns the full assembled response.
     * Token counts are not available in streaming mode; the audit log records 0.
     *
     * @param tokenConsumer receives each token string as it streams
     * @return full assembled response text
     */
    public LlmCallResult callStreaming(Long taskId, Long userId, String callType,
                                       String systemPrompt, List<Map<String, Object>> history,
                                       String userMessage, String idempotencyKey,
                                       Consumer<String> tokenConsumer) {
        return callStreaming(taskId, userId, callType, systemPrompt, history, userMessage,
                idempotencyKey, tokenConsumer, List.of(), Map.of());
    }

    public LlmCallResult callStreaming(Long taskId, Long userId, String callType,
                                       String systemPrompt, List<Map<String, Object>> history,
                                       String userMessage, String idempotencyKey,
                                       Consumer<String> tokenConsumer,
                                       List<String> advisorNames,
                                       Map<String, Object> advisorContext) {

        List<Message> messages = buildMessages(systemPrompt, history, userMessage);
        List<Advisor> resolvedAdvisors = resolveAdvisors(advisorNames);
        Map<String, Object> requestContext = advisorContext != null ? advisorContext : Map.of();
        long start = System.currentTimeMillis();
        boolean streamSuccess = true;

        try {
            Flux<ChatResponse> flux = applyAdvisors(
                    chatClient.prompt().messages(messages),
                    resolvedAdvisors,
                    requestContext)
                    .stream()
                    .chatResponse();

            StringBuilder sb = new StringBuilder();
            int[] tokenCount = {0};

            flux.doOnNext(resp -> {
                String token = resp.getResult() != null && resp.getResult().getOutput() != null
                        ? resp.getResult().getOutput().getText() : null;
                if (token != null) {
                    if (tokenConsumer != null) tokenConsumer.accept(token);
                    sb.append(token);
                }
                // Collect token usage from the final chunk (provider-dependent)
                if (resp.getMetadata() != null && resp.getMetadata().getUsage() != null) {
                    long total = resp.getMetadata().getUsage().getTotalTokens();
                    if (total > 0) tokenCount[0] = (int) total;
                }
            }).blockLast();

            long latencyMs = System.currentTimeMillis() - start;
            auditLog(taskId, userId, callType, 0, tokenCount[0], latencyMs, "success", idempotencyKey);
            return new LlmCallResult(sb.toString(), tokenCount[0]);
        } catch (AgentException ae) {
            streamSuccess = false;
            long latencyMs = System.currentTimeMillis() - start;
            auditLog(taskId, userId, callType, 0, 0, latencyMs, "error", idempotencyKey);
            log.error("[DashscopeLlmClient] Streaming call failed: code={} retryable={}: {}",
                    ae.getErrorCode(), ae.isRetryable(), ae.getMessage());
            throw ae;
        } catch (Exception e) {
            streamSuccess = false;
            long latencyMs = System.currentTimeMillis() - start;
            auditLog(taskId, userId, callType, 0, 0, latencyMs, "error", idempotencyKey);
            AgentException classified = classifyLlmException(e);
            log.error("[DashscopeLlmClient] Streaming call failed: code={} retryable={}: {}",
                    classified.getErrorCode(), classified.isRetryable(), classified.getMessage());
            throw classified;
        } finally {
            taskMetricsService.recordLlmCall(System.currentTimeMillis() - start, streamSuccess);
        }
    }

    private List<Message> buildMessages(String systemPrompt,
                                        List<Map<String, Object>> history,
                                        String userMessage) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));

        if (history != null) {
            for (Map<String, Object> entry : history) {
                String role = (String) entry.get("role");
                String content = (String) entry.get("content");
                if (content == null) {
                    continue;
                }
                if ("user".equals(role)) {
                    messages.add(new UserMessage(content));
                } else if ("assistant".equals(role)) {
                    messages.add(new AssistantMessage(content));
                }
            }
        }

        messages.add(new UserMessage(userMessage));
        return messages;
    }

    private ChatClient.ChatClientRequestSpec applyAdvisors(ChatClient.ChatClientRequestSpec spec,
                                                           List<Advisor> advisors,
                                                           Map<String, Object> advisorContext) {
        if (advisors.isEmpty() && (advisorContext == null || advisorContext.isEmpty())) {
            return spec;
        }
        return spec.advisors(advisorSpec -> {
            if (advisorContext != null && !advisorContext.isEmpty()) {
                advisorSpec.params(advisorContext);
            }
            if (!advisors.isEmpty()) {
                advisorSpec.advisors(advisors);
            }
        });
    }

    private Map<String, Advisor> buildAdvisorRegistry(List<Advisor> advisors) {
        Map<String, Advisor> registry = new LinkedHashMap<>();
        if (advisors == null) {
            return Collections.unmodifiableMap(registry);
        }
        for (Advisor advisor : advisors) {
            registry.put(advisor.getName(), advisor);
        }
        return Collections.unmodifiableMap(registry);
    }

    private List<Advisor> resolveAdvisors(List<String> advisorNames) {
        if (advisorNames == null || advisorNames.isEmpty()) {
            return List.of();
        }
        List<Advisor> resolved = new ArrayList<>();
        for (String advisorName : advisorNames) {
            Advisor advisor = advisorRegistry.get(advisorName);
            if (advisor == null) {
                throw new IllegalArgumentException("Unknown advisor: " + advisorName);
            }
            resolved.add(advisor);
        }
        return resolved;
    }

    public List<String> defaultPlanningAdvisors() {
        return List.of(
                TravelPlanningAdvisor.NAME,
                RagContextAdvisor.NAME,
                JsonSchemaAdvisor.NAME
        );
    }

    private void auditLog(Long taskId, Long userId, String callType,
                          int promptTokens, int completionTokens,
                          long latencyMs, String status, String idempotencyKey) {
        try {
            LlmCallLog entry = new LlmCallLog();
            entry.setTaskId(taskId);
            entry.setUserId(userId);
            entry.setCallType(callType);
            entry.setModel(model);
            entry.setPromptTokens(promptTokens);
            entry.setCompletionTokens(completionTokens);
            entry.setTotalTokens(promptTokens + completionTokens);
            entry.setLatencyMs((int) latencyMs);
            entry.setStatus(status);
            entry.setIdempotencyKey(idempotencyKey);
            entry.setCreatedAt(LocalDateTime.now());
            llmCallLogMapper.insert(entry);
        } catch (Exception e) {
            log.warn("[DashscopeLlmClient] Failed to write audit log: {}", e.getMessage());
        }
    }

    private <T> T withRetry(Callable<T> action, String operationName) {
        AgentException lastException = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                return action.call();
            } catch (AgentException ae) {
                lastException = ae;
                log.warn("[DashscopeLlmClient] {} attempt {}/{} - code={} retryable={}: {}",
                        operationName, attempt, maxRetries, ae.getErrorCode(), ae.isRetryable(), ae.getMessage());
                if (!ae.isRetryable()) break;
            } catch (Exception e) {
                AgentException classified = classifyLlmException(e);
                lastException = classified;
                log.warn("[DashscopeLlmClient] {} attempt {}/{} - code={} retryable={}: {}",
                        operationName, attempt, maxRetries,
                        classified.getErrorCode(), classified.isRetryable(), classified.getMessage());
                if (!classified.isRetryable()) break;
            }
            if (attempt < maxRetries) {
                try {
                    Thread.sleep(1000L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (lastException != null) throw lastException;
        throw new AgentException(AgentErrorCode.LLM_SERVICE_ERROR,
                operationName + " failed after " + maxRetries + " attempts");
    }

    private AgentException classifyLlmException(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        Throwable cause = e.getCause();
        if (msg.contains("timeout") || msg.contains("timed out") || msg.contains("read timeout")
                || cause instanceof java.net.SocketTimeoutException
                || cause instanceof java.util.concurrent.TimeoutException) {
            return new AgentException(AgentErrorCode.LLM_TIMEOUT,
                    "Dashscope request timed out: " + e.getMessage(), e);
        }
        if (msg.contains("429") || msg.contains("rate limit") || msg.contains("too many requests")
                || msg.contains("throttl")) {
            return new AgentException(AgentErrorCode.LLM_RATE_LIMIT,
                    "Dashscope rate limit exceeded: " + e.getMessage(), e);
        }
        if (msg.contains("400") || msg.contains("401") || msg.contains("403")
                || msg.contains("invalid") || msg.contains("unauthorized")) {
            return new AgentException(AgentErrorCode.LLM_INVALID_RESPONSE,
                    "Dashscope non-retryable error: " + e.getMessage(), e);
        }
        return new AgentException(AgentErrorCode.LLM_SERVICE_ERROR,
                "Dashscope service error: " + e.getMessage(), e);
    }
}
