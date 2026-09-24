package com.travelagent.agent.planner;

import lombok.Getter;

@Getter
public enum PlanTaskStatus {
    PENDING("pending"),
    RUNNING("running"),
    SUCCESS("success"),
    FAILED("failed"),
    SKIPPED("skipped");

    private final String code;

    PlanTaskStatus(String code) {
        this.code = code;
    }

    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == SKIPPED;
    }

    public boolean satisfiesDependency() {
        return this == SUCCESS || this == SKIPPED;
    }
}
