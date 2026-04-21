package com.travelagent.agent.statemachine;

public enum AgentEvent {
    SUBMIT,
    START_PLANNING,
    START_TOOL_CALL,
    TOOL_CALL_DONE,
    USER_INPUT_REQUIRED,
    USER_INPUT_RECEIVED,
    QUOTA_EXHAUSTED,
    RESUME,
    COMPLETE,
    FAIL,
    CANCEL
}
