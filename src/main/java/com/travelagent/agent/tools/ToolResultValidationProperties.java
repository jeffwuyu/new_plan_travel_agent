package com.travelagent.agent.tools;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "agent.tool-result-validation")
public class ToolResultValidationProperties {

    private boolean enabled = true;
    private int maxSerializedChars = 65_536;
    private int maxStringChars = 8_000;
    private int maxCollectionItems = 50;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxSerializedChars() {
        return maxSerializedChars;
    }

    public void setMaxSerializedChars(int maxSerializedChars) {
        this.maxSerializedChars = Math.max(1_024, maxSerializedChars);
    }

    public int getMaxStringChars() {
        return maxStringChars;
    }

    public void setMaxStringChars(int maxStringChars) {
        this.maxStringChars = Math.max(256, maxStringChars);
    }

    public int getMaxCollectionItems() {
        return maxCollectionItems;
    }

    public void setMaxCollectionItems(int maxCollectionItems) {
        this.maxCollectionItems = Math.max(1, maxCollectionItems);
    }
}
