package com.travelagent.agent.statemachine;

import com.travelagent.exception.BusinessException;
import com.travelagent.model.enums.TaskStatus;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Pure state-machine validator for agent task lifecycle transitions.
 *
 * <p>This component has no dependencies — it is a stateless transition table.
 * Callers are responsible for persisting the resulting status to the database
 * after receiving the return value from {@link #transition}.
 *
 * <p>Invalid transition attempts throw {@link BusinessException} with HTTP 409
 * (Conflict), which maps to a consistent REST error response.
 *
 * <p>Transition table:
 * <pre>
 * PENDING       → START_PLANNING  → PLANNING
 * PENDING       → CANCEL          → CANCELLED
 * PENDING       → FAIL            → FAILED
 *
 * PLANNING      → START_TOOL_CALL → TOOL_CALLING
 * PLANNING      → QUOTA_EXHAUSTED → PAUSED
 * PLANNING      → COMPLETE        → COMPLETED
 * PLANNING      → FAIL            → FAILED
 * PLANNING      → CANCEL          → CANCELLED
 *
 * TOOL_CALLING  → TOOL_CALL_DONE  → PLANNING
 * TOOL_CALLING  → QUOTA_EXHAUSTED → PAUSED
 * TOOL_CALLING  → FAIL            → FAILED
 * TOOL_CALLING  → CANCEL          → CANCELLED
 *
 * PAUSED        → RESUME          → RESUMING
 * PAUSED        → CANCEL          → CANCELLED
 *
 * RESUMING      → START_PLANNING  → PLANNING
 * RESUMING      → FAIL            → FAILED
 * RESUMING      → CANCEL          → CANCELLED
 *
 * COMPLETED / FAILED / CANCELLED → (no transitions — terminal states)
 * </pre>
 */

/**
 * 中文注释：状态机类，描述 Agent State Machine 对应的状态或状态流转规则。
 */

@Component
public class AgentStateMachine {

    /** Allowed events from each state. */
    private static final Map<TaskStatus, Set<AgentEvent>> ALLOWED;

    /** Target state for each event (event is globally deterministic). */
    private static final Map<AgentEvent, TaskStatus> TARGET_STATE;

    static {
        ALLOWED = new EnumMap<>(TaskStatus.class);
        ALLOWED.put(TaskStatus.PENDING,       EnumSet.of(AgentEvent.START_PLANNING,
                                                         AgentEvent.CANCEL,
                                                         AgentEvent.FAIL));
        ALLOWED.put(TaskStatus.PLANNING,      EnumSet.of(AgentEvent.START_TOOL_CALL,
                                                         AgentEvent.QUOTA_EXHAUSTED,
                                                         AgentEvent.COMPLETE,
                                                         AgentEvent.FAIL,
                                                         AgentEvent.CANCEL));
        ALLOWED.put(TaskStatus.TOOL_CALLING,  EnumSet.of(AgentEvent.TOOL_CALL_DONE,
                                                         AgentEvent.QUOTA_EXHAUSTED,
                                                         AgentEvent.FAIL,
                                                         AgentEvent.CANCEL));
        ALLOWED.put(TaskStatus.PAUSED,        EnumSet.of(AgentEvent.RESUME,
                                                         AgentEvent.CANCEL));
        ALLOWED.put(TaskStatus.RESUMING,      EnumSet.of(AgentEvent.START_PLANNING,
                                                         AgentEvent.FAIL,
                                                         AgentEvent.CANCEL));
        // Terminal states — no outgoing transitions
        ALLOWED.put(TaskStatus.COMPLETED,     EnumSet.noneOf(AgentEvent.class));
        ALLOWED.put(TaskStatus.FAILED,        EnumSet.noneOf(AgentEvent.class));
        ALLOWED.put(TaskStatus.CANCELLED,     EnumSet.noneOf(AgentEvent.class));

        TARGET_STATE = new EnumMap<>(AgentEvent.class);
        TARGET_STATE.put(AgentEvent.SUBMIT,          TaskStatus.PENDING);
        TARGET_STATE.put(AgentEvent.START_PLANNING,  TaskStatus.PLANNING);
        TARGET_STATE.put(AgentEvent.START_TOOL_CALL, TaskStatus.TOOL_CALLING);
        TARGET_STATE.put(AgentEvent.TOOL_CALL_DONE,  TaskStatus.PLANNING);
        TARGET_STATE.put(AgentEvent.QUOTA_EXHAUSTED, TaskStatus.PAUSED);
        TARGET_STATE.put(AgentEvent.RESUME,          TaskStatus.RESUMING);
        TARGET_STATE.put(AgentEvent.COMPLETE,        TaskStatus.COMPLETED);
        TARGET_STATE.put(AgentEvent.FAIL,            TaskStatus.FAILED);
        TARGET_STATE.put(AgentEvent.CANCEL,          TaskStatus.CANCELLED);
    }

    /**
     * Validates that {@code event} is legal from {@code fromStatus} and returns
     * the resulting {@link TaskStatus}.
     *
     * @throws BusinessException HTTP 409 if the transition is not allowed.
     */
    public TaskStatus transition(TaskStatus fromStatus, AgentEvent event) {
        Set<AgentEvent> allowed = ALLOWED.getOrDefault(fromStatus, EnumSet.noneOf(AgentEvent.class));
        if (!allowed.contains(event)) {
            throw new BusinessException(409,
                String.format("任务状态[%s]不允许事件[%s]", fromStatus.getCode(), event.name()));
        }
        return TARGET_STATE.get(event);
    }

    /**
     * Returns {@code true} if the given event is allowed from {@code fromStatus},
     * without throwing. Useful for conditional checks before committing a transition.
     */
    public boolean canTransition(TaskStatus fromStatus, AgentEvent event) {
        return ALLOWED.getOrDefault(fromStatus, EnumSet.noneOf(AgentEvent.class))
                      .contains(event);
    }
}
