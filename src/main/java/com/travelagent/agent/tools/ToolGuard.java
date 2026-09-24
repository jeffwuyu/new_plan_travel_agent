package com.travelagent.agent.tools;

import com.travelagent.agent.safety.SensitiveInfoGuard;
import com.travelagent.mapper.UserMapper;
import com.travelagent.model.entity.Task;
import com.travelagent.model.entity.User;
import com.travelagent.model.enums.TaskStatus;
import com.travelagent.validation.JsonSchemaValidationService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class ToolGuard {

    private static final List<InjectionRule> INJECTION_RULES = List.of(
            new InjectionRule("ignore_previous_instructions", Pattern.compile("(?i)\\b(ignore|forget|discard)\\s+(all\\s+)?(previous|above|prior)\\s+(instructions?|messages?|context)\\b")),
            new InjectionRule("system_prompt_exfiltration", Pattern.compile("(?i)\\b(system\\s+prompt|developer\\s+message|hidden\\s+instructions?|reveal\\s+.*instructions?)\\b")),
            new InjectionRule("tool_json_forgery", Pattern.compile("(?i)\\b(function_call|tool_call|tools/call|\"toolName\"\\s*:|\"arguments\"\\s*:)\\b")),
            new InjectionRule("tool_policy_bypass", Pattern.compile("(?i)\\b(call|invoke|execute|use)\\s+.*\\b(tool|function|api)\\b.*\\b(without|bypass|ignore|skip)\\b")),
            new InjectionRule("script_or_html", Pattern.compile("(?i)<\\s*(script|iframe|object|embed|meta|link)\\b|javascript\\s*:"))
    );

    private final ToolGuardProperties properties;
    private final UserMapper userMapper;
    private final SensitiveInfoGuard sensitiveInfoGuard;
    private final JsonSchemaValidationService jsonSchemaValidationService;

    public ToolGuard(ToolGuardProperties properties,
                     UserMapper userMapper,
                     SensitiveInfoGuard sensitiveInfoGuard,
                     JsonSchemaValidationService jsonSchemaValidationService) {
        this.properties = properties;
        this.userMapper = userMapper;
        this.sensitiveInfoGuard = sensitiveInfoGuard;
        this.jsonSchemaValidationService = jsonSchemaValidationService;
    }

    public ToolGuardDecision evaluate(ToolGuardContext context) {
        if (context == null) {
            return ToolGuardDecision.deny("missing_context", "tool guard context is required");
        }
        if (!properties.isEnabled()) {
            validateArguments(context);
            return ToolGuardDecision.allow();
        }

        String toolName = normalizeToolName(context.toolName());
        ToolGuardProperties.ToolPolicy policy = properties.policyFor(toolName);
        if (policy == null) {
            return ToolGuardDecision.deny("tool_not_allowed", "Tool is not allowed: " + toolName);
        }

        User user = resolveActiveUser(context.task());
        if (user == null) {
            return ToolGuardDecision.deny("user_not_active", "Task user is missing or inactive");
        }
        int userLevel = user.getUserLevel() == null ? 0 : user.getUserLevel();
        if (userLevel < policy.getMinUserLevel()) {
            return ToolGuardDecision.deny("insufficient_user_level", "User level is not allowed for tool: " + toolName);
        }

        if (!stateAllowed(context, policy)) {
            return ToolGuardDecision.deny("state_not_allowed", "Current task state is not allowed for tool: " + toolName);
        }

        validateArguments(context);

        if (!context.manualConfirmationApproved()) {
            ToolGuardDecision sensitive = scanSensitiveInput(context, policy);
            if (!sensitive.allowed()) {
                return sensitive;
            }
            ToolGuardDecision injection = scanInjectionRisk(context, policy);
            if (!injection.allowed()) {
                return injection;
            }
        }

        return ToolGuardDecision.allow();
    }

    private void validateArguments(ToolGuardContext context) {
        AgentTool tool = context.tool();
        if (jsonSchemaValidationService == null || tool == null || tool.inputSchema().isEmpty()) {
            return;
        }
        jsonSchemaValidationService.validateOrThrow(
                "tool arguments for " + tool.getName(),
                context.safeArguments(),
                tool.inputSchema());
    }

    private User resolveActiveUser(Task task) {
        if (task == null || task.getUserId() == null || userMapper == null) {
            return null;
        }
        User user = userMapper.findById(task.getUserId());
        return user != null && user.isActive() ? user : null;
    }

    private boolean stateAllowed(ToolGuardContext context, ToolGuardProperties.ToolPolicy policy) {
        String taskState = context.task() == null ? null : context.task().getStatus();
        String checkpointState = context.checkpoint() == null ? null : context.checkpoint().getCurrentState();
        if (context.replay() && context.manualConfirmationApproved()) {
            return TaskStatus.PAUSED.getCode().equals(taskState)
                    || TaskStatus.PAUSED.getCode().equals(checkpointState)
                    || allowed(policy, taskState)
                    || allowed(policy, checkpointState);
        }
        return allowed(policy, taskState) || allowed(policy, checkpointState);
    }

    private boolean allowed(ToolGuardProperties.ToolPolicy policy, String state) {
        return state != null && policy.getAllowedStates().contains(state);
    }

    private ToolGuardDecision scanSensitiveInput(ToolGuardContext context, ToolGuardProperties.ToolPolicy policy) {
        if (!policy.isConfirmOnSensitiveInput() || sensitiveInfoGuard == null) {
            return ToolGuardDecision.allow();
        }
        List<Map<String, Object>> findings = new ArrayList<>();
        for (String text : flattenStrings(context.safeArguments())) {
            SensitiveInfoGuard.ScanResult scan = sensitiveInfoGuard.scan(text);
            if (scan.manualActionRequired()) {
                for (SensitiveInfoGuard.Finding finding : scan.findings()) {
                    findings.add(Map.of(
                            "type", finding.type(),
                            "maskedValue", finding.maskedValue(),
                            "highRisk", finding.highRisk()
                    ));
                }
            }
        }
        if (findings.isEmpty()) {
            return ToolGuardDecision.allow();
        }
        return ToolGuardDecision.requireConfirmation(new ToolGuardViolation(
                "sensitive_input",
                "Tool arguments contain sensitive information and require confirmation.",
                Map.of("findings", findings)
        ));
    }

    private ToolGuardDecision scanInjectionRisk(ToolGuardContext context, ToolGuardProperties.ToolPolicy policy) {
        if (!policy.isConfirmOnInjectionRisk()) {
            return ToolGuardDecision.allow();
        }
        List<Map<String, Object>> findings = new ArrayList<>();
        for (String text : flattenStrings(context.safeArguments())) {
            String value = text == null ? "" : text;
            for (InjectionRule rule : INJECTION_RULES) {
                if (rule.pattern().matcher(value).find()) {
                    findings.add(Map.of(
                            "type", rule.type(),
                            "sample", sample(value)
                    ));
                }
            }
        }
        if (findings.isEmpty()) {
            return ToolGuardDecision.allow();
        }
        return ToolGuardDecision.requireConfirmation(new ToolGuardViolation(
                "injection_risk",
                "Tool arguments contain prompt or tool injection risk and require confirmation.",
                Map.of("findings", findings)
        ));
    }

    private List<String> flattenStrings(Object value) {
        List<String> strings = new ArrayList<>();
        collectStrings(value, strings);
        return strings;
    }

    private void collectStrings(Object value, List<String> strings) {
        if (value == null) {
            return;
        }
        if (value instanceof String text) {
            if (!text.isBlank()) {
                strings.add(text);
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                collectStrings(entry.getKey(), strings);
                collectStrings(entry.getValue(), strings);
            }
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                collectStrings(item, strings);
            }
        }
    }

    private String sample(String value) {
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120);
    }

    private String normalizeToolName(String toolName) {
        return toolName == null ? "" : toolName.trim().toLowerCase(Locale.ROOT);
    }

    public Map<String, Object> violationPayload(ToolGuardDecision decision) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (decision == null || decision.violation() == null) {
            return payload;
        }
        payload.put("code", decision.violation().code());
        payload.put("message", decision.violation().message());
        payload.put("details", decision.violation().details());
        return payload;
    }

    private record InjectionRule(String type, Pattern pattern) {
    }
}
