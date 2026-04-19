package com.travelagent.model.enums;

import lombok.Getter;

/**
 * Agent task lifecycle states.
 * Matches the state machine transitions in AgentStateMachine.
 */

/**
 * 中文注释：枚举枚举，用于声明 Task Status 的可选取值集合。
 */

@Getter
public enum TaskStatus {
    PENDING("pending", "待执行"),
    PLANNING("planning", "规划中"),
    TOOL_CALLING("tool_calling", "工具调用中"),
    PAUSED("paused", "已暂停(配额耗尽)"),
    RESUMING("resuming", "恢复中"),
    COMPLETED("completed", "已完成"),
    FAILED("failed", "失败"),
    CANCELLED("cancelled", "已取消");

    private final String code;
    private final String label;

    TaskStatus(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public static TaskStatus fromCode(String code) {
        for (TaskStatus status : values()) {
            if (status.code.equals(code)) return status;
        }
        throw new IllegalArgumentException("Unknown task status: " + code);
    }

    /** Returns true if the task can accept a resume request. */
    public boolean isResumable() {
        return this == PAUSED;
    }

    /** Returns true if the task is in an active (running) state. */
    public boolean isActive() {
        return this == PLANNING || this == TOOL_CALLING || this == RESUMING;
    }

    /** Returns true if the task has reached a terminal state. */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
