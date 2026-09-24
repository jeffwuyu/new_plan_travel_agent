package com.travelagent.agent.observation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "agent.observation-cleaning")
public class ObservationCleaningProperties {

    private boolean enabled = true;
    private int maxInputChars = 60_000;
    private int maxCleanedChars = 8_000;
    private double maxSummaryRatio = 0.40d;
    private int maxSummaryChars = 2_000;
    private int maxEvidenceItems = 5;
    private boolean rawArtifactEnabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxInputChars() {
        return maxInputChars;
    }

    public void setMaxInputChars(int maxInputChars) {
        this.maxInputChars = Math.max(1_024, maxInputChars);
    }

    public int getMaxCleanedChars() {
        return maxCleanedChars;
    }

    public void setMaxCleanedChars(int maxCleanedChars) {
        this.maxCleanedChars = Math.max(512, maxCleanedChars);
    }

    public double getMaxSummaryRatio() {
        return maxSummaryRatio;
    }

    public void setMaxSummaryRatio(double maxSummaryRatio) {
        this.maxSummaryRatio = Math.max(0.05d, Math.min(1.0d, maxSummaryRatio));
    }

    public int getMaxSummaryChars() {
        return maxSummaryChars;
    }

    public void setMaxSummaryChars(int maxSummaryChars) {
        this.maxSummaryChars = Math.max(128, maxSummaryChars);
    }

    public int getMaxEvidenceItems() {
        return maxEvidenceItems;
    }

    public void setMaxEvidenceItems(int maxEvidenceItems) {
        this.maxEvidenceItems = Math.max(1, maxEvidenceItems);
    }

    public boolean isRawArtifactEnabled() {
        return rawArtifactEnabled;
    }

    public void setRawArtifactEnabled(boolean rawArtifactEnabled) {
        this.rawArtifactEnabled = rawArtifactEnabled;
    }
}
