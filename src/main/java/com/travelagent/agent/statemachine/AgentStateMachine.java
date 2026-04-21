package com.travelagent.agent.statemachine;

import com.travelagent.exception.BusinessException;
import com.travelagent.model.enums.TaskStatus;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

@Component
public class AgentStateMachine {

    private static final Map<TaskStatus, Set<AgentEvent>> ALLOWED;
    private static final Map<AgentEvent, TaskStatus> TARGET_STATE;

    static {
        ALLOWED = new EnumMap<>(TaskStatus.class);
        ALLOWED.put(TaskStatus.PENDING, EnumSet.of(AgentEvent.START_PLANNING, AgentEvent.CANCEL, AgentEvent.FAIL));
        ALLOWED.put(TaskStatus.PLANNING, EnumSet.of(
                AgentEvent.START_TOOL_CALL,
                AgentEvent.USER_INPUT_REQUIRED,
                AgentEvent.QUOTA_EXHAUSTED,
                AgentEvent.COMPLETE,
                AgentEvent.FAIL,
                AgentEvent.CANCEL));
        ALLOWED.put(TaskStatus.TOOL_CALLING, EnumSet.of(
                AgentEvent.TOOL_CALL_DONE,
                AgentEvent.QUOTA_EXHAUSTED,
                AgentEvent.FAIL,
                AgentEvent.CANCEL));
        ALLOWED.put(TaskStatus.AWAITING_USER_INPUT, EnumSet.of(
                AgentEvent.USER_INPUT_RECEIVED,
                AgentEvent.CANCEL));
        ALLOWED.put(TaskStatus.PAUSED, EnumSet.of(AgentEvent.RESUME, AgentEvent.CANCEL));
        ALLOWED.put(TaskStatus.RESUMING, EnumSet.of(AgentEvent.START_PLANNING, AgentEvent.FAIL, AgentEvent.CANCEL));
        ALLOWED.put(TaskStatus.COMPLETED, EnumSet.noneOf(AgentEvent.class));
        ALLOWED.put(TaskStatus.FAILED, EnumSet.noneOf(AgentEvent.class));
        ALLOWED.put(TaskStatus.CANCELLED, EnumSet.noneOf(AgentEvent.class));

        TARGET_STATE = new EnumMap<>(AgentEvent.class);
        TARGET_STATE.put(AgentEvent.SUBMIT, TaskStatus.PENDING);
        TARGET_STATE.put(AgentEvent.START_PLANNING, TaskStatus.PLANNING);
        TARGET_STATE.put(AgentEvent.START_TOOL_CALL, TaskStatus.TOOL_CALLING);
        TARGET_STATE.put(AgentEvent.TOOL_CALL_DONE, TaskStatus.PLANNING);
        TARGET_STATE.put(AgentEvent.USER_INPUT_REQUIRED, TaskStatus.AWAITING_USER_INPUT);
        TARGET_STATE.put(AgentEvent.USER_INPUT_RECEIVED, TaskStatus.RESUMING);
        TARGET_STATE.put(AgentEvent.QUOTA_EXHAUSTED, TaskStatus.PAUSED);
        TARGET_STATE.put(AgentEvent.RESUME, TaskStatus.RESUMING);
        TARGET_STATE.put(AgentEvent.COMPLETE, TaskStatus.COMPLETED);
        TARGET_STATE.put(AgentEvent.FAIL, TaskStatus.FAILED);
        TARGET_STATE.put(AgentEvent.CANCEL, TaskStatus.CANCELLED);
    }

    public TaskStatus transition(TaskStatus fromStatus, AgentEvent event) {
        Set<AgentEvent> allowed = ALLOWED.getOrDefault(fromStatus, EnumSet.noneOf(AgentEvent.class));
        if (!allowed.contains(event)) {
            throw new BusinessException(409,
                    String.format("task status [%s] does not allow event [%s]", fromStatus.getCode(), event.name()));
        }
        return TARGET_STATE.get(event);
    }

    public boolean canTransition(TaskStatus fromStatus, AgentEvent event) {
        return ALLOWED.getOrDefault(fromStatus, EnumSet.noneOf(AgentEvent.class)).contains(event);
    }
}
