package com.travelagent.agent.tools;

import com.travelagent.agent.context.TaskCheckpoint;
import com.travelagent.model.entity.Task;

import java.util.Map;

public record ToolGuardContext(Task task,
                               TaskCheckpoint checkpoint,
                               AgentTool tool,
                               String toolName,
                               Map<String, Object> arguments,
                               boolean manualConfirmationApproved,
                               boolean replay) {

    public Map<String, Object> safeArguments() {
        return arguments == null ? Map.of() : arguments;
    }
}
