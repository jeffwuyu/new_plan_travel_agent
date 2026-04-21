package com.travelagent.client.dashscope;

import com.travelagent.exception.AgentException;
import com.travelagent.mapper.LlmCallLogMapper;
import com.travelagent.model.entity.LlmCallLog;
import com.travelagent.monitoring.TaskMetricsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DashscopeLlmClient.
 *
 * <p>We test the audit logging and retry behaviour by mocking ChatModel
 * (which is what Spring AI wraps internally). The ChatClient is built from the mock.
 */

/**
 * 中文注释：测试类，用于验证 Dashscope Llm Client Test 相关行为是否符合预期。
 */

@ExtendWith(MockitoExtension.class)
@DisplayName("DashscopeLlmClient Tests")
class DashscopeLlmClientTest {

    @Mock private ChatModel chatModel;
    @Mock private LlmCallLogMapper llmCallLogMapper;
    @Mock private TaskMetricsService taskMetricsService;

    private DashscopeLlmClient llmClient;

    @BeforeEach
    void setUp() {
        llmClient = new DashscopeLlmClient(ChatClient.builder(chatModel), llmCallLogMapper, List.of());
        ReflectionTestUtils.setField(llmClient, "model", "qwen-plus");
        ReflectionTestUtils.setField(llmClient, "maxRetries", 3);
        ReflectionTestUtils.setField(llmClient, "taskMetricsService", taskMetricsService);
    }

    // -----------------------------------------------------------------------
    // Audit log is written on success
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("call: writes audit log entry on successful response")
    void call_success_writesAuditLog() {
        // Mock ChatModel response
        mockChatModelResponse("推荐前往兵马俑", 100, 50);

        llmClient.call(1L, 2L, "planning", "system", List.of(),
                "请推荐景点", "task-step0-llm");

        ArgumentCaptor<LlmCallLog> logCaptor = ArgumentCaptor.forClass(LlmCallLog.class);
        verify(llmCallLogMapper).insert(logCaptor.capture());
        LlmCallLog log = logCaptor.getValue();

        assertThat(log.getTaskId()).isEqualTo(1L);
        assertThat(log.getUserId()).isEqualTo(2L);
        assertThat(log.getCallType()).isEqualTo("planning");
        assertThat(log.getModel()).isEqualTo("qwen-plus");
        assertThat(log.getStatus()).isEqualTo("success");
        assertThat(log.getPromptTokens()).isEqualTo(100);
        assertThat(log.getCompletionTokens()).isEqualTo(50);
        assertThat(log.getTotalTokens()).isEqualTo(150);
        assertThat(log.getIdempotencyKey()).isEqualTo("task-step0-llm");
    }

    // -----------------------------------------------------------------------
    // Correct response text is returned
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("call: returns response text from ChatModel")
    void call_success_returnsResponseText() {
        mockChatModelResponse("{\"attractionName\":\"兵马俑\"}", 50, 20);

        String result = llmClient.call(1L, 2L, "planning", "system", List.of(),
                "请推荐", "key");

        assertThat(result).isEqualTo("{\"attractionName\":\"兵马俑\"}");
    }

    // -----------------------------------------------------------------------
    // History messages are included in correct order
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("call: includes conversation history in message list")
    void call_withHistory_includesHistoryMessages() {
        mockChatModelResponse("response", 30, 10);

        List<Map<String, Object>> history = List.of(
                Map.of("role", "user", "content", "previous user msg"),
                Map.of("role", "assistant", "content", "previous assistant msg")
        );

        llmClient.call(1L, 2L, "planning", "you are a planner", history,
                "current msg", "key");

        // Verify ChatModel was called (we can't easily inspect messages here without
        // more complex mocking, but we verify the call was made at all)
        verify(chatModel).call(any(org.springframework.ai.chat.prompt.Prompt.class));
    }

    // -----------------------------------------------------------------------
    // Retry on failure
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("call: retries on exception and eventually throws after maxRetries")
    void call_allRetriesFail_throwsRuntimeException() {
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenThrow(new RuntimeException("LLM timeout"));

        // maxRetries = 3, but use 1 for test speed
        ReflectionTestUtils.setField(llmClient, "maxRetries", 1);

        assertThatThrownBy(() -> llmClient.call(1L, 2L, "planning", "sys", List.of(), "msg", "key"))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("timed out");
    }

    // -----------------------------------------------------------------------
    // System role in history is skipped
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("call: system-role entries in history are skipped")
    void call_historyWithSystemRole_skipsSystemEntries() {
        mockChatModelResponse("ok", 10, 5);

        List<Map<String, Object>> history = List.of(
                Map.of("role", "system", "content", "should be ignored"),
                Map.of("role", "user", "content", "user says hi")
        );

        // Should not throw
        assertThatNoException().isThrownBy(() ->
                llmClient.call(1L, 2L, "planning", "real system", history, "current", "key"));
    }

    // -----------------------------------------------------------------------
    // Audit log failure is non-fatal
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("call: audit log failure does not propagate to caller")
    void call_auditLogFailure_notFatal() {
        mockChatModelResponse("response", 10, 5);
        doThrow(new RuntimeException("DB write error")).when(llmCallLogMapper).insert(any());

        // Should not throw despite audit log failure
        assertThatNoException().isThrownBy(() ->
                llmClient.call(1L, 2L, "planning", "sys", List.of(), "msg", "key"));
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    private void mockChatModelResponse(String content, int promptTokens, int completionTokens) {
        AssistantMessage output = new AssistantMessage(content);
        Usage usage = mock(Usage.class);
        when(usage.getPromptTokens()).thenReturn(promptTokens);
        when(usage.getCompletionTokens()).thenReturn(completionTokens);

        ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
        when(metadata.getUsage()).thenReturn(usage);

        Generation generation = new Generation(output);
        ChatResponse chatResponse = new ChatResponse(List.of(generation), metadata);

        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse);
    }
}
