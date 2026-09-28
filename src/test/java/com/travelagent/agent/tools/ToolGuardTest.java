package com.travelagent.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.agent.safety.SensitiveInfoGuard;
import com.travelagent.mapper.UserMapper;
import com.travelagent.model.entity.Task;
import com.travelagent.model.entity.User;
import com.travelagent.model.enums.TaskStatus;
import com.travelagent.validation.JsonSchemaValidationService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolGuardTest {

    @Test
    void defaultPropertiesContainExistingToolAllowlist() {
        ToolGuardProperties properties = new ToolGuardProperties();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getAllowedTools().keySet()).contains(
                "geocode", "weather", "traffic_time", "web_search", "rag", "booking_query", "amap_map");
    }

    @Test
    void missingAllowlistEntryFailsClosed() {
        ToolGuardProperties properties = new ToolGuardProperties();
        properties.getAllowedTools().clear();
        UserMapper userMapper = mock(UserMapper.class);
        when(userMapper.findById(1L)).thenReturn(activeUser());
        ToolGuard guard = new ToolGuard(
                properties,
                userMapper,
                new SensitiveInfoGuard(),
                new JsonSchemaValidationService(new ObjectMapper().findAndRegisterModules()));

        ToolGuardDecision decision = guard.evaluate(new ToolGuardContext(
                task(),
                checkpoint(),
                new SimpleTool("unlisted_tool"),
                "unlisted_tool",
                Map.of("query", "Forbidden City"),
                false,
                false));

        assertThat(decision.denied()).isTrue();
        assertThat(decision.violation().code()).isEqualTo("tool_not_allowed");
    }

    @Test
    void sensitiveInputRequiresConfirmation() {
        ToolGuardProperties properties = new ToolGuardProperties();
        UserMapper userMapper = mock(UserMapper.class);
        when(userMapper.findById(1L)).thenReturn(activeUser());
        ToolGuard guard = new ToolGuard(
                properties,
                userMapper,
                new SensitiveInfoGuard(),
                new JsonSchemaValidationService(new ObjectMapper().findAndRegisterModules()));

        ToolGuardDecision decision = guard.evaluate(new ToolGuardContext(
                task(),
                checkpoint(),
                new SimpleTool("web_search"),
                "web_search",
                Map.of("query", "contact 13800138000"),
                false,
                false));

        assertThat(decision.requiresConfirmation()).isTrue();
        assertThat(decision.violation().code()).isEqualTo("sensitive_input");
    }

    private Task task() {
        Task task = new Task();
        task.setId(1L);
        task.setTaskUuid("task-uuid");
        task.setUserId(1L);
        task.setStatus(TaskStatus.PLANNING.getCode());
        return task;
    }

    private TaskCheckpoint checkpoint() {
        TaskCheckpoint checkpoint = new TaskCheckpoint();
        checkpoint.setCurrentState(TaskStatus.PLANNING.getCode());
        return checkpoint;
    }

    private User activeUser() {
        User user = new User();
        user.setId(1L);
        user.setStatus(1);
        user.setUserLevel(1);
        return user;
    }

    private record SimpleTool(String name) implements AgentTool {
        @Override public String getName() {
            return name;
        }

        @Override public Map<String, Object> execute(Map<String, Object> arguments, String idempotencyKey) {
            return Map.of("ok", true);
        }
    }
}
