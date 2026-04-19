package com.travelagent.agent.statemachine;

/**
 * Events that drive the agent task state machine.
 *
 * <pre>
 * SUBMIT          — task created (PENDING)
 * START_PLANNING  — dispatcher picks up PENDING/RESUMING task → PLANNING
 * START_TOOL_CALL — LLM decided to call a tool → TOOL_CALLING
 * TOOL_CALL_DONE  — tool returned a result, back to LLM loop → PLANNING
 * QUOTA_EXHAUSTED — token limit hit mid-execution → PAUSED
 * RESUME          — user requests resume of PAUSED task → RESUMING
 * COMPLETE        — all steps generated successfully → COMPLETED
 * FAIL            — unrecoverable error / retries exhausted → FAILED
 * CANCEL          — user explicitly cancels from any active state → CANCELLED
 * </pre>
 *
 * @see AgentStateMachine
 */

/**
 * 中文注释：状态机枚举，描述 Agent Event 对应的状态或状态流转规则。
 */

public enum AgentEvent {
    SUBMIT,
    START_PLANNING,
    START_TOOL_CALL,
    TOOL_CALL_DONE,
    QUOTA_EXHAUSTED,
    RESUME,
    COMPLETE,
    FAIL,
    CANCEL
}
