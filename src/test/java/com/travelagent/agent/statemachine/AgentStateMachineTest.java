package com.travelagent.agent.statemachine;

import com.travelagent.exception.BusinessException;
import com.travelagent.model.enums.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for AgentStateMachine.
 * No Spring context — pure logic verification.
 */

/**
 * 中文注释：测试类，用于验证 Agent State Machine Test 相关行为是否符合预期。
 */

@DisplayName("AgentStateMachine Tests")
class AgentStateMachineTest {

    private AgentStateMachine stateMachine;

    @BeforeEach
    void setUp() {
        stateMachine = new AgentStateMachine();
    }

    // -----------------------------------------------------------------------
    // Valid transitions
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("PENDING → START_PLANNING → PLANNING")
    void transition_pendingToPlanning_succeeds() {
        TaskStatus result = stateMachine.transition(TaskStatus.PENDING, AgentEvent.START_PLANNING);
        assertThat(result).isEqualTo(TaskStatus.PLANNING);
    }

    @Test
    @DisplayName("PENDING → CANCEL → CANCELLED")
    void transition_pendingToCancel_succeeds() {
        TaskStatus result = stateMachine.transition(TaskStatus.PENDING, AgentEvent.CANCEL);
        assertThat(result).isEqualTo(TaskStatus.CANCELLED);
    }

    @Test
    @DisplayName("PENDING → FAIL → FAILED")
    void transition_pendingToFail_succeeds() {
        TaskStatus result = stateMachine.transition(TaskStatus.PENDING, AgentEvent.FAIL);
        assertThat(result).isEqualTo(TaskStatus.FAILED);
    }

    @Test
    @DisplayName("PLANNING → START_TOOL_CALL → TOOL_CALLING")
    void transition_planningToToolCalling_succeeds() {
        TaskStatus result = stateMachine.transition(TaskStatus.PLANNING, AgentEvent.START_TOOL_CALL);
        assertThat(result).isEqualTo(TaskStatus.TOOL_CALLING);
    }

    @Test
    @DisplayName("PLANNING → QUOTA_EXHAUSTED → PAUSED")
    void transition_planningToPaused_viaQuotaExhausted_succeeds() {
        TaskStatus result = stateMachine.transition(TaskStatus.PLANNING, AgentEvent.QUOTA_EXHAUSTED);
        assertThat(result).isEqualTo(TaskStatus.PAUSED);
    }

    @Test
    @DisplayName("PLANNING → COMPLETE → COMPLETED")
    void transition_planningToCompleted_succeeds() {
        TaskStatus result = stateMachine.transition(TaskStatus.PLANNING, AgentEvent.COMPLETE);
        assertThat(result).isEqualTo(TaskStatus.COMPLETED);
    }

    @Test
    @DisplayName("PLANNING → FAIL → FAILED")
    void transition_planningToFailed_succeeds() {
        TaskStatus result = stateMachine.transition(TaskStatus.PLANNING, AgentEvent.FAIL);
        assertThat(result).isEqualTo(TaskStatus.FAILED);
    }

    @Test
    @DisplayName("PLANNING → CANCEL → CANCELLED")
    void transition_planningToCancelled_succeeds() {
        TaskStatus result = stateMachine.transition(TaskStatus.PLANNING, AgentEvent.CANCEL);
        assertThat(result).isEqualTo(TaskStatus.CANCELLED);
    }

    @Test
    @DisplayName("TOOL_CALLING → TOOL_CALL_DONE → PLANNING")
    void transition_toolCallingToPlanning_viaToolCallDone_succeeds() {
        TaskStatus result = stateMachine.transition(TaskStatus.TOOL_CALLING, AgentEvent.TOOL_CALL_DONE);
        assertThat(result).isEqualTo(TaskStatus.PLANNING);
    }

    @Test
    @DisplayName("TOOL_CALLING → QUOTA_EXHAUSTED → PAUSED")
    void transition_toolCallingToPaused_viaQuotaExhausted_succeeds() {
        TaskStatus result = stateMachine.transition(TaskStatus.TOOL_CALLING, AgentEvent.QUOTA_EXHAUSTED);
        assertThat(result).isEqualTo(TaskStatus.PAUSED);
    }

    @Test
    @DisplayName("TOOL_CALLING → FAIL → FAILED")
    void transition_toolCallingToFailed_succeeds() {
        TaskStatus result = stateMachine.transition(TaskStatus.TOOL_CALLING, AgentEvent.FAIL);
        assertThat(result).isEqualTo(TaskStatus.FAILED);
    }

    @Test
    @DisplayName("TOOL_CALLING → CANCEL → CANCELLED")
    void transition_toolCallingToCancelled_succeeds() {
        TaskStatus result = stateMachine.transition(TaskStatus.TOOL_CALLING, AgentEvent.CANCEL);
        assertThat(result).isEqualTo(TaskStatus.CANCELLED);
    }

    @Test
    @DisplayName("PAUSED → RESUME → RESUMING")
    void transition_pausedToResuming_succeeds() {
        TaskStatus result = stateMachine.transition(TaskStatus.PAUSED, AgentEvent.RESUME);
        assertThat(result).isEqualTo(TaskStatus.RESUMING);
    }

    @Test
    @DisplayName("PAUSED → CANCEL → CANCELLED")
    void transition_pausedToCancelled_succeeds() {
        TaskStatus result = stateMachine.transition(TaskStatus.PAUSED, AgentEvent.CANCEL);
        assertThat(result).isEqualTo(TaskStatus.CANCELLED);
    }

    @Test
    @DisplayName("RESUMING → START_PLANNING → PLANNING")
    void transition_resumingToPlanning_succeeds() {
        TaskStatus result = stateMachine.transition(TaskStatus.RESUMING, AgentEvent.START_PLANNING);
        assertThat(result).isEqualTo(TaskStatus.PLANNING);
    }

    @Test
    @DisplayName("RESUMING → FAIL → FAILED")
    void transition_resumingToFailed_succeeds() {
        TaskStatus result = stateMachine.transition(TaskStatus.RESUMING, AgentEvent.FAIL);
        assertThat(result).isEqualTo(TaskStatus.FAILED);
    }

    @Test
    @DisplayName("RESUMING → CANCEL → CANCELLED")
    void transition_resumingToCancelled_succeeds() {
        TaskStatus result = stateMachine.transition(TaskStatus.RESUMING, AgentEvent.CANCEL);
        assertThat(result).isEqualTo(TaskStatus.CANCELLED);
    }

    // -----------------------------------------------------------------------
    // Terminal states — no transitions allowed
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("COMPLETED → 任意事件 → 抛 BusinessException 409")
    void transition_completed_anyEvent_throwsBusinessException() {
        for (AgentEvent event : AgentEvent.values()) {
            assertThatThrownBy(() -> stateMachine.transition(TaskStatus.COMPLETED, event))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getHttpStatus()).isEqualTo(409));
        }
    }

    @Test
    @DisplayName("FAILED → 任意事件 → 抛 BusinessException 409")
    void transition_failed_anyEvent_throwsBusinessException() {
        for (AgentEvent event : AgentEvent.values()) {
            assertThatThrownBy(() -> stateMachine.transition(TaskStatus.FAILED, event))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getHttpStatus()).isEqualTo(409));
        }
    }

    @Test
    @DisplayName("CANCELLED → 任意事件 → 抛 BusinessException 409")
    void transition_cancelled_anyEvent_throwsBusinessException() {
        for (AgentEvent event : AgentEvent.values()) {
            assertThatThrownBy(() -> stateMachine.transition(TaskStatus.CANCELLED, event))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getHttpStatus()).isEqualTo(409));
        }
    }

    // -----------------------------------------------------------------------
    // Invalid transitions on active states
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("PENDING → TOOL_CALL_DONE → 非法，抛 BusinessException 409")
    void transition_pending_toolCallDone_throwsBusinessException() {
        assertThatThrownBy(() -> stateMachine.transition(TaskStatus.PENDING, AgentEvent.TOOL_CALL_DONE))
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> assertThat(((BusinessException) ex).getHttpStatus()).isEqualTo(409));
    }

    @Test
    @DisplayName("PENDING → RESUME → 非法，抛 BusinessException 409")
    void transition_pending_resume_throwsBusinessException() {
        assertThatThrownBy(() -> stateMachine.transition(TaskStatus.PENDING, AgentEvent.RESUME))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("PAUSED → START_PLANNING → 非法，抛 BusinessException 409")
    void transition_paused_startPlanning_throwsBusinessException() {
        assertThatThrownBy(() -> stateMachine.transition(TaskStatus.PAUSED, AgentEvent.START_PLANNING))
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> assertThat(((BusinessException) ex).getHttpStatus()).isEqualTo(409));
    }

    // -----------------------------------------------------------------------
    // canTransition
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("canTransition: PENDING + START_PLANNING → true")
    void canTransition_validTransition_returnsTrue() {
        assertThat(stateMachine.canTransition(TaskStatus.PENDING, AgentEvent.START_PLANNING)).isTrue();
    }

    @Test
    @DisplayName("canTransition: COMPLETED + 任意事件 → false")
    void canTransition_fromTerminalState_returnsFalse() {
        for (AgentEvent event : AgentEvent.values()) {
            assertThat(stateMachine.canTransition(TaskStatus.COMPLETED, event)).isFalse();
        }
    }

    @Test
    @DisplayName("canTransition: PLANNING + RESUME → false")
    void canTransition_invalidEvent_returnsFalse() {
        assertThat(stateMachine.canTransition(TaskStatus.PLANNING, AgentEvent.RESUME)).isFalse();
    }
}
