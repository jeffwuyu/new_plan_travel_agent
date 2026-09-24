package com.travelagent.agent.tools;

public record ToolGuardDecision(DecisionType type,
                                ToolGuardViolation violation) {

    public static ToolGuardDecision allow() {
        return new ToolGuardDecision(DecisionType.ALLOW, null);
    }

    public static ToolGuardDecision deny(String code, String message) {
        return new ToolGuardDecision(DecisionType.DENY, new ToolGuardViolation(code, message));
    }

    public static ToolGuardDecision requireConfirmation(ToolGuardViolation violation) {
        return new ToolGuardDecision(DecisionType.REQUIRE_CONFIRMATION, violation);
    }

    public boolean allowed() {
        return type == DecisionType.ALLOW;
    }

    public boolean denied() {
        return type == DecisionType.DENY;
    }

    public boolean requiresConfirmation() {
        return type == DecisionType.REQUIRE_CONFIRMATION;
    }

    public enum DecisionType {
        ALLOW,
        DENY,
        REQUIRE_CONFIRMATION
    }
}
