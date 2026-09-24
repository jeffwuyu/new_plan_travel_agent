package com.travelagent.agent.scratchpad;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "agent.scratchpad")
public class ScratchpadManagementProperties {

    private boolean enabled = true;
    private int keepRecentSteps = 6;
    private int maxObservationChars = 1_200;
    private int maxSummaryChars = 2_000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getKeepRecentSteps() {
        return keepRecentSteps;
    }

    public void setKeepRecentSteps(int keepRecentSteps) {
        this.keepRecentSteps = Math.max(1, keepRecentSteps);
    }

    public int getMaxObservationChars() {
        return maxObservationChars;
    }

    public void setMaxObservationChars(int maxObservationChars) {
        this.maxObservationChars = Math.max(256, maxObservationChars);
    }

    public int getMaxSummaryChars() {
        return maxSummaryChars;
    }

    public void setMaxSummaryChars(int maxSummaryChars) {
        this.maxSummaryChars = Math.max(512, maxSummaryChars);
    }
}
