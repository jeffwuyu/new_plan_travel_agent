package com.travelagent.service.notification;

public enum SseEvent {
    STATE_CHANGE,
    STEP_DONE,
    TOOL_RESULT,
    LLM_STREAM,
    USER_SELECTION_REQUIRED,
    USER_SELECTION_CONFIRMED,
    PAUSED,
    COMPLETED,
    ERROR,
    PROGRESS_SNAPSHOT,
    RETRY,
    REWIND
}
