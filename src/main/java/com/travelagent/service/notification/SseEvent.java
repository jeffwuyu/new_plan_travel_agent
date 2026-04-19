package com.travelagent.service.notification;

/**
 * SSE event type names pushed to the client via {@link SseNotificationService}.
 *
 * <p>Client-side usage:
 * <pre>
 *   const es = new EventSource('/api/tasks/{uuid}/stream');
 *   es.addEventListener('STATE_CHANGE', e => { ... });
 *   es.addEventListener('STEP_DONE',    e => { ... });
 * </pre>
 */

/**
 * 中文注释：服务枚举，定义 Sse Event 相关业务能力。
 */

public enum SseEvent {
    /** Agent state changed, e.g. PENDING→PLANNING. Payload: {@code {status, step?, totalSteps?}} */
    STATE_CHANGE,

    /** A planning step (attraction) was resolved. Payload: {@code {stepIndex, attractionName, trafficTimeMin}} */
    STEP_DONE,

    /** A tool returned a result. Payload: {@code {tool, result}} */
    TOOL_RESULT,

    /** A single streaming token from the LLM. Payload: {@code {token}} */
    LLM_STREAM,

    /** Task was paused due to quota exhaustion. Payload: {@code {reason, resumableAt}} */
    PAUSED,

    /** Task completed successfully. Payload: {@code {planId}} */
    COMPLETED,

    /** An error occurred. Payload: {@code {message}} */
    ERROR
}
