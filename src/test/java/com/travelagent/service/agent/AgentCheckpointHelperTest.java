package com.travelagent.service.agent;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.travelagent.agent.context.AgentSessionState;
import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.agent.planner.PlanTaskType;
import com.travelagent.agent.planner.PlannerToolType;
import com.travelagent.agent.planner.TravelPlan;
import com.travelagent.agent.planner.TravelPlanTask;
import com.travelagent.mapper.TaskCheckpointArtifactMapper;
import com.travelagent.mapper.TaskMapper;
import com.travelagent.mapper.UserMapper;
import com.travelagent.model.entity.Task;
import com.travelagent.service.agent.impl.AgentCheckpointHelper;
import com.travelagent.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentCheckpointHelper Session State Tests")
class AgentCheckpointHelperTest {

    @Mock private TaskMapper taskMapper;
    @Mock private UserMapper userMapper;
    @Mock private TaskCheckpointArtifactMapper artifactMapper;

    private AgentCheckpointHelper helper;
    private Task task;

    @BeforeEach
    void setUp() {
        helper = new AgentCheckpointHelper();
        JsonUtil jsonUtil = new JsonUtil();
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        ReflectionTestUtils.setField(jsonUtil, "objectMapper", mapper);
        ReflectionTestUtils.setField(helper, "taskMapper", taskMapper);
        ReflectionTestUtils.setField(helper, "userMapper", userMapper);
        ReflectionTestUtils.setField(helper, "artifactMapper", artifactMapper);
        ReflectionTestUtils.setField(helper, "jsonUtil", jsonUtil);

        task = new Task();
        task.setId(9L);
        task.setUserId(3L);
        task.setTaskUuid("session-task-001");
        task.setStatus("planning");
        task.setRegion("西安市");
        task.setSchemaVersion("1.0");
        task.setTotalTokensUsed(12);
    }

    @Test
    void saveAndLoadSessionState_persistsInTaskCheckpointJson() {
        AgentSessionState state = buildSessionState();

        AgentSessionState saved = helper.saveSessionState(task, state);

        ArgumentCaptor<Task> captor = ArgumentCaptor.forClass(Task.class);
        verify(taskMapper).updateCheckpoint(captor.capture());
        Task persisted = captor.getValue();
        assertThat(persisted.getSchemaVersion()).isEqualTo(TaskCheckpoint.CURRENT_SCHEMA_VERSION);
        assertThat(persisted.getCheckpointJson()).contains("西安三日低强度历史游");
        assertThat(saved.getTaskUuid()).isEqualTo("session-task-001");
        assertThat(saved.getSubtaskStates()).singleElement()
                .satisfies(subtask -> assertThat(subtask.getTaskType()).isEqualTo("weather_query"));

        task.setCheckpointJson(persisted.getCheckpointJson());
        AgentSessionState loaded = helper.loadSessionState(task);

        assertThat(loaded.getCurrentState()).isEqualTo("planning");
        assertThat(loaded.getToolResults()).containsKey("weather");
        assertThat(loaded.getRagResults()).singleElement()
                .satisfies(result -> assertThat(result).containsEntry("query", "西安历史景点"));
        assertThat(loaded.getValidatorResults()).singleElement()
                .satisfies(result -> assertThat(result).containsEntry("valid", true));
        assertThat(loaded.getUserFeedback()).singleElement()
                .satisfies(feedback -> assertThat(feedback).containsEntry("message", "第二天少走路"));
        assertThat(loaded.getFinalItinerary()).isEqualTo("西安三日低强度历史游");
    }

    @Test
    void loadCheckpoint_migratesLegacySchemaAndDefaultsCollections() {
        task.setSchemaVersion("1.0");
        task.setCheckpointJson("""
                {"schemaVersion":"1.0","taskUuid":"legacy-task","currentState":"planning"}
                """);

        TaskCheckpoint checkpoint = helper.loadCheckpoint(task);

        assertThat(checkpoint.getSchemaVersion()).isEqualTo(TaskCheckpoint.CURRENT_SCHEMA_VERSION);
        assertThat(checkpoint.getCompletedSteps()).isNotNull().isEmpty();
        assertThat(checkpoint.getToolResults()).isNotNull().isEmpty();
        assertThat(checkpoint.getCurrentContext()).isNotNull().isEmpty();
    }

    @Test
    void saveCheckpoint_compactsLargeHistoryListsBeforePersisting() {
        TaskCheckpoint checkpoint = new TaskCheckpoint();
        checkpoint.setCurrentState("planning");
        for (int i = 0; i < 25; i++) {
            checkpoint.getLlmConversationHistory().add(Map.of("index", i));
            checkpoint.recordRagResult(Map.of("index", i));
            checkpoint.recordIntermediateSummary(Map.of("index", i));
            checkpoint.recordValidatorResult(Map.of("index", i));
            checkpoint.recordUserFeedback("test", "feedback-" + i, Map.of());
            checkpoint.recordFailure("failure-" + i);
        }

        helper.saveCheckpoint(task, checkpoint);

        ArgumentCaptor<Task> captor = ArgumentCaptor.forClass(Task.class);
        verify(taskMapper).updateCheckpoint(captor.capture());
        Task persisted = captor.getValue();
        TaskCheckpoint restored = helper.loadCheckpoint(persisted);
        assertThat(restored.getLlmConversationHistory()).hasSize(20);
        assertThat(restored.getRagResults()).hasSize(20);
        assertThat(restored.getIntermediateSummaries()).hasSize(20);
        assertThat(restored.getValidatorResults()).hasSize(20);
        assertThat(restored.getUserFeedback()).hasSize(20);
        assertThat(restored.getFailureReasons()).hasSize(20);
        assertThat(restored.getFailureReasons().get(0)).isEqualTo("failure-5");

        ArgumentCaptor<String> artifactType = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payloadJson = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> itemCount = ArgumentCaptor.forClass(Integer.class);
        verify(artifactMapper, times(6)).upsertArtifact(
                anyString(),
                any(),
                artifactType.capture(),
                payloadJson.capture(),
                itemCount.capture(),
                anyString());
        assertThat(artifactType.getAllValues()).containsExactlyInAnyOrder(
                "llm_history",
                "rag_results",
                "summaries",
                "validator_results",
                "user_feedback",
                "failure_reasons");
        assertThat(itemCount.getAllValues()).allMatch(count -> count == 25);
        assertThat(payloadJson.getAllValues()).anySatisfy(payload -> assertThat(payload).contains("failure-0"));
    }

    @Test
    void updateAndRecoverSessionState_keepsTaskMetadataAndDerivedSubtasks() {
        AgentSessionState state = helper.updateSessionState(task, session -> {
            session.setCurrentState("tool_calling");
            session.setCurrentPlan(buildPlan());
            session.getFailureReasons().add("weather: timeout");
        });
        task.setCheckpointJson(task.getCheckpointJson());

        assertThat(state.getTaskId()).isEqualTo(9L);
        assertThat(state.getTaskUuid()).isEqualTo("session-task-001");
        assertThat(state.getSubtaskStates()).singleElement()
                .satisfies(subtask -> assertThat(subtask.getStatus()).isEqualTo("pending"));

        AgentSessionState recovered = helper.recoverSessionState(task);

        assertThat(recovered.getTaskId()).isEqualTo(9L);
        assertThat(recovered.getTaskUuid()).isEqualTo("session-task-001");
        assertThat(recovered.getSubtaskStates()).singleElement()
                .satisfies(subtask -> assertThat(subtask.getTaskId()).isEqualTo("weather-1"));
    }

    private AgentSessionState buildSessionState() {
        AgentSessionState state = new AgentSessionState();
        state.setCurrentState("planning");
        state.setUserIntent("想去西安玩三天");
        state.setCurrentPlan(buildPlan());
        state.getToolResults().put("weather", Map.of("status", "success", "temperature", "26C"));
        state.getRagResults().add(Map.of("query", "西安历史景点", "sourceType", "static_knowledge"));
        state.getIntermediateSummaries().add(Map.of("type", "observe", "summary", "天气良好"));
        state.getValidatorResults().add(Map.of("valid", true, "issues", List.of()));
        state.getRetryCounts().put("weather", 0);
        state.getUserFeedback().add(Map.of("source", "node_chat", "message", "第二天少走路"));
        state.setFinalItinerary("西安三日低强度历史游");
        return state;
    }

    private TravelPlan buildPlan() {
        TravelPlan plan = new TravelPlan();
        plan.setGoal("西安三日游");
        plan.setTasks(List.of(new TravelPlanTask(
                "weather-1",
                PlanTaskType.WEATHER_QUERY,
                PlannerToolType.WEATHER,
                Map.of("city", "西安"),
                List.of(),
                List.of("天气可用"))));
        return plan;
    }
}
