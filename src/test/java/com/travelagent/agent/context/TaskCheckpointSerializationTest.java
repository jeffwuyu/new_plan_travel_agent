package com.travelagent.agent.context;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for TaskCheckpoint (and nested POJOs) Jackson serialization round-trips.
 * No Spring context — uses a plain ObjectMapper matching WebMvcConfig configuration.
 */

/**
 * 中文注释：测试类，用于验证 Task Checkpoint Serialization Test 相关行为是否符合预期。
 */

@DisplayName("TaskCheckpoint Serialization Tests")
class TaskCheckpointSerializationTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    // -----------------------------------------------------------------------
    // Full round-trip
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("完整字段序列化 → 反序列化 → 所有字段一致")
    void roundTrip_allFields_preserved() throws Exception {
        TaskCheckpoint original = buildFullCheckpoint();

        String json = mapper.writeValueAsString(original);
        TaskCheckpoint restored = mapper.readValue(json, TaskCheckpoint.class);

        assertThat(restored.getSchemaVersion()).isEqualTo("1.0");
        assertThat(restored.getTaskId()).isEqualTo(42L);
        assertThat(restored.getTaskUuid()).isEqualTo("test-uuid-1234");
        assertThat(restored.getCurrentState()).isEqualTo("paused");
        assertThat(restored.getRegion()).isEqualTo("西安市");
        assertThat(restored.getUserIntent()).isEqualTo("3天西安历史文化游");

        // PlanningConfig
        assertThat(restored.getPlanningConfig().getTotalDays()).isEqualTo(3);
        assertThat(restored.getPlanningConfig().getAttractionsPerDay()).isEqualTo(3);
        assertThat(restored.getPlanningConfig().getPreferenceKeywords()).containsExactly("历史", "文化");
        assertThat(restored.getPlanningConfig().getTravelMode()).isEqualTo("driving");

        // CompletedSteps
        assertThat(restored.getCompletedSteps()).hasSize(1);
        CompletedStep step = restored.getCompletedSteps().get(0);
        assertThat(step.getStepIndex()).isZero();
        assertThat(step.getDayNumber()).isEqualTo(1);
        assertThat(step.getAttractionName()).isEqualTo("兵马俑");
        assertThat(step.getLat()).isEqualTo(34.384232);
        assertThat(step.getLng()).isEqualTo(109.278927);

        // PendingToolCall
        assertThat(restored.getPendingToolCall()).isNotNull();
        assertThat(restored.getPendingToolCall().getToolName()).isEqualTo("WeatherTool");
        assertThat(restored.getPendingToolCall().getIdempotencyKey()).isEqualTo("test-uuid-step1-weather");

        // TokenBudgetSnapshot
        assertThat(restored.getTokenBudgetSnapshot().getTokensUsedThisTask()).isEqualTo(1247L);
        assertThat(restored.getTokenBudgetSnapshot().getDailyLimit()).isEqualTo(10000L);
        assertThat(restored.getTokenBudgetSnapshot().getPauseReason()).isEqualTo("daily_quota_exhausted");

        // RetryState
        assertThat(restored.getRetryState().getCurrentStepRetryCount()).isZero();
        assertThat(restored.getRetryState().getMaxRetries()).isEqualTo(3);

        // LLM history
        assertThat(restored.getLlmConversationHistory()).hasSize(1);

        // resumableAt
        assertThat(restored.getResumableAt()).isEqualTo(original.getResumableAt());

        // stepIndex
        assertThat(restored.getCurrentStepIndex()).isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // Forward compatibility
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("包含未知字段的 JSON → 反序列化不抛异常")
    void deserialize_withUnknownFields_doesNotThrow() {
        String json = """
            {
              "schemaVersion": "2.0",
              "taskId": 1,
              "taskUuid": "uuid-abc",
              "unknownFutureField": "some-value",
              "anotherNewField": 99
            }
            """;

        assertThatNoException().isThrownBy(() -> mapper.readValue(json, TaskCheckpoint.class));

        TaskCheckpoint cp = null;
        try {
            cp = mapper.readValue(json, TaskCheckpoint.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        assertThat(cp.getSchemaVersion()).isEqualTo("2.0");
        assertThat(cp.getTaskUuid()).isEqualTo("uuid-abc");
    }

    // -----------------------------------------------------------------------
    // Null optional fields
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("null 的可选字段序列化后反序列化仍为 null")
    void roundTrip_nullOptionalFields_remainNull() throws Exception {
        TaskCheckpoint cp = new TaskCheckpoint();
        cp.setSchemaVersion("1.0");
        cp.setTaskUuid("uuid-null-test");
        // pendingToolCall, tokenBudgetSnapshot, retryState, resumableAt, historyTrimmedAt all null

        String json = mapper.writeValueAsString(cp);
        TaskCheckpoint restored = mapper.readValue(json, TaskCheckpoint.class);

        assertThat(restored.getPendingToolCall()).isNull();
        assertThat(restored.getTokenBudgetSnapshot()).isNull();
        assertThat(restored.getRetryState()).isNull();
        assertThat(restored.getResumableAt()).isNull();
        assertThat(restored.getHistoryTrimmedAt()).isNull();
    }

    // -----------------------------------------------------------------------
    // Default list initialization
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("new TaskCheckpoint() 的 completedSteps 和 llmConversationHistory 初始为空集合")
    void defaultConstructor_listFieldsAreEmptyNotNull() {
        TaskCheckpoint cp = new TaskCheckpoint();
        assertThat(cp.getCompletedSteps()).isNotNull().isEmpty();
        assertThat(cp.getLlmConversationHistory()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("JSON 中缺少 completedSteps 键 → 反序列化后为空集合（非 null）")
    void deserialize_missingCompletedSteps_defaultsToEmpty() throws Exception {
        String json = """
            {
              "schemaVersion": "1.0",
              "taskUuid": "uuid-no-steps"
            }
            """;
        // The field is initialized to new ArrayList<>() in the declaration,
        // so if the key is absent Jackson leaves it as-is.
        TaskCheckpoint cp = mapper.readValue(json, TaskCheckpoint.class);
        assertThat(cp.getCompletedSteps()).isNotNull().isEmpty();
        assertThat(cp.getLlmConversationHistory()).isNotNull().isEmpty();
    }

    // -----------------------------------------------------------------------
    // Convenience helpers
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("isAllStepsDone: completedSteps.size == totalSteps → true")
    void isAllStepsDone_whenAllComplete_returnsTrue() {
        TaskCheckpoint cp = new TaskCheckpoint();
        PlanningConfig config = new PlanningConfig(1, 2, null, "driving"); // 2 steps
        cp.setPlanningConfig(config);
        cp.getCompletedSteps().add(new CompletedStep(0, 1, "A", 1.0, 2.0, null));
        cp.getCompletedSteps().add(new CompletedStep(1, 1, "B", 1.1, 2.1, null));
        assertThat(cp.isAllStepsDone()).isTrue();
    }

    @Test
    @DisplayName("isAllStepsDone: 未完成时 → false")
    void isAllStepsDone_notComplete_returnsFalse() {
        TaskCheckpoint cp = new TaskCheckpoint();
        PlanningConfig config = new PlanningConfig(1, 3, null, "driving"); // 3 steps
        cp.setPlanningConfig(config);
        cp.getCompletedSteps().add(new CompletedStep(0, 1, "A", 1.0, 2.0, null));
        assertThat(cp.isAllStepsDone()).isFalse();
    }

    @Test
    @DisplayName("totalPlannedSteps: totalDays=3, attractionsPerDay=3 → 9")
    void totalPlannedSteps_correctCalculation() {
        TaskCheckpoint cp = new TaskCheckpoint();
        cp.setPlanningConfig(new PlanningConfig(3, 3, null, "driving"));
        assertThat(cp.totalPlannedSteps()).isEqualTo(9);
    }

    // -----------------------------------------------------------------------
    // RetryState helpers
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("RetryState.isExhausted: count >= maxRetries → true")
    void retryState_isExhausted_whenCountReachesMax() {
        RetryState rs = new RetryState(3, 3);
        assertThat(rs.isExhausted()).isTrue();
    }

    @Test
    @DisplayName("RetryState.increment → 递增计数")
    void retryState_increment_increasesCount() {
        RetryState rs = new RetryState(0, 3);
        assertThat(rs.increment()).isEqualTo(1);
        assertThat(rs.increment()).isEqualTo(2);
        assertThat(rs.isExhausted()).isFalse();
    }

    @Test
    @DisplayName("RetryState.reset → 归零")
    void retryState_reset_setsCountToZero() {
        RetryState rs = new RetryState(2, 3);
        rs.reset();
        assertThat(rs.getCurrentStepRetryCount()).isZero();
    }

    // -----------------------------------------------------------------------
    // Helper builder
    // -----------------------------------------------------------------------

    private TaskCheckpoint buildFullCheckpoint() {
        TaskCheckpoint cp = new TaskCheckpoint();
        cp.setSchemaVersion("1.0");
        cp.setTaskId(42L);
        cp.setTaskUuid("test-uuid-1234");
        cp.setCurrentState("paused");
        cp.setRegion("西安市");
        cp.setUserIntent("3天西安历史文化游");

        PlanningConfig config = new PlanningConfig(3, 3, List.of("历史", "文化"), "driving");
        cp.setPlanningConfig(config);

        CompletedStep step = new CompletedStep(0, 1, "兵马俑", 34.384232, 109.278927,
            Map.of("geocode", Map.of("lat", 34.384232, "lng", 109.278927)));
        cp.getCompletedSteps().add(step);
        cp.setCurrentStepIndex(1);

        PendingToolCall pending = new PendingToolCall(
            "WeatherTool",
            Map.of("lat", 34.384232, "lng", 109.278927),
            "test-uuid-step1-weather"
        );
        cp.setPendingToolCall(pending);

        cp.getLlmConversationHistory().add(Map.of("role", "user", "content", "推荐下一个景点"));

        cp.setTokenBudgetSnapshot(new TokenBudgetSnapshot(1247L, 3800L, 10000L, "daily_quota_exhausted"));
        cp.setRetryState(new RetryState(0, 3));
        cp.setResumableAt(LocalDateTime.of(2026, 4, 17, 0, 0, 0));

        return cp;
    }
}
