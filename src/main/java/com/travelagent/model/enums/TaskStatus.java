package com.travelagent.model.enums;

import lombok.Getter;

@Getter
public enum TaskStatus {
    PENDING("pending", "等待中"),
    PLANNING("planning", "规划中"),
    TOOL_CALLING("tool_calling", "工具调用中"),
    AWAITING_USER_INPUT("awaiting_user_input", "等待选择起点"),
    PAUSED("paused", "已暂停"),
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
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown task status: " + code);
    }

    public boolean isResumable() {
        return this == PAUSED;
    }

    public boolean isAwaitingUserInput() {
        return this == AWAITING_USER_INPUT;
    }

    public boolean isActive() {
        return this == PLANNING || this == TOOL_CALLING || this == RESUMING || this == AWAITING_USER_INPUT;
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
