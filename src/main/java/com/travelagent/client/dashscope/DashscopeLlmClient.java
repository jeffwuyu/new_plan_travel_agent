package com.travelagent.client.dashscope;

import com.travelagent.mapper.LlmCallLogMapper;
import com.travelagent.model.entity.LlmCallLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
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

    @Autowired
    public DashscopeLlmClient(ChatModel chatModel, LlmCallLogMapper llmCallLogMapper) {
        this.chatClient = ChatClient.create(chatModel);
        this.llmCallLogMapper = llmCallLogMapper;
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

        List<Message> messages = buildMessages(systemPrompt, history, userMessage);

        return withRetry(() -> {
            long start = System.currentTimeMillis();
            String status = "success";
            int promptTokens = 0;
            int completionTokens = 0;

            try {
                ChatResponse resp = chatClient.prompt()
                        .messages(messages)
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

                return content;
            } catch (Exception e) {
                long latencyMs = System.currentTimeMillis() - start;
                auditLog(taskId, userId, callType, 0, 0, latencyMs, "error", idempotencyKey);
                throw e;
            }
        }, "LLM call [" + callType + "]");
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
    public String callStreaming(Long taskId, Long userId, String callType,
                                String systemPrompt, List<Map<String, Object>> history,
                                String userMessage, String idempotencyKey,
                                Consumer<String> tokenConsumer) {

        List<Message> messages = buildMessages(systemPrompt, history, userMessage);
        long start = System.currentTimeMillis();

        try {
            Flux<String> flux = chatClient.prompt()
                    .messages(messages)
                    .stream()
                    .content();

            StringBuilder sb = new StringBuilder();
            flux.doOnNext(token -> {
                if (tokenConsumer != null) {
                    tokenConsumer.accept(token);
                }
                sb.append(token);
            }).blockLast();

            long latencyMs = System.currentTimeMillis() - start;
            auditLog(taskId, userId, callType, 0, 0, latencyMs, "success", idempotencyKey);
            return sb.toString();
        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - start;
            auditLog(taskId, userId, callType, 0, 0, latencyMs, "error", idempotencyKey);
            log.error("[DashscopeLlmClient] Streaming call failed: {}", e.getMessage(), e);
            throw new RuntimeException("LLM streaming call failed: " + e.getMessage(), e);
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
        Exception lastException = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                return action.call();
            } catch (Exception e) {
                lastException = e;
                log.warn("[DashscopeLlmClient] {} attempt {}/{} failed: {}",
                        operationName, attempt, maxRetries, e.getMessage());
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(1000L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        throw new RuntimeException(operationName + " failed after " + maxRetries + " attempts", lastException);
    }
}
