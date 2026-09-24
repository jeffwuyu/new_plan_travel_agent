package com.travelagent.agent.tools;

import com.travelagent.model.enums.TaskStatus;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "agent.tool-guard")
public class ToolGuardProperties {

    private boolean enabled = true;
    private final Map<String, ToolPolicy> allowedTools = defaultPolicies();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Map<String, ToolPolicy> getAllowedTools() {
        return allowedTools;
    }

    public ToolPolicy policyFor(String toolName) {
        return allowedTools.get(toolName);
    }

    private static Map<String, ToolPolicy> defaultPolicies() {
        Map<String, ToolPolicy> policies = new LinkedHashMap<>();
        for (String toolName : List.of("geocode", "weather", "traffic_time", "web_search", "rag", "booking_query", "amap_map")) {
            policies.put(toolName, new ToolPolicy());
        }
        return policies;
    }

    public static class ToolPolicy {
        private int minUserLevel = 1;
        private List<String> allowedStates = new ArrayList<>(List.of(
                TaskStatus.PLANNING.getCode(),
                TaskStatus.TOOL_CALLING.getCode(),
                TaskStatus.RESUMING.getCode()
        ));
        private boolean confirmOnSensitiveInput = true;
        private boolean confirmOnInjectionRisk = true;

        public int getMinUserLevel() {
            return minUserLevel;
        }

        public void setMinUserLevel(int minUserLevel) {
            this.minUserLevel = Math.max(1, minUserLevel);
        }

        public List<String> getAllowedStates() {
            return allowedStates;
        }

        public void setAllowedStates(List<String> allowedStates) {
            this.allowedStates = allowedStates == null ? new ArrayList<>() : new ArrayList<>(allowedStates);
        }

        public boolean isConfirmOnSensitiveInput() {
            return confirmOnSensitiveInput;
        }

        public void setConfirmOnSensitiveInput(boolean confirmOnSensitiveInput) {
            this.confirmOnSensitiveInput = confirmOnSensitiveInput;
        }

        public boolean isConfirmOnInjectionRisk() {
            return confirmOnInjectionRisk;
        }

        public void setConfirmOnInjectionRisk(boolean confirmOnInjectionRisk) {
            this.confirmOnInjectionRisk = confirmOnInjectionRisk;
        }
    }
}
