package com.travelagent.agent.validation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "agent.hallucination-detection")
public class HallucinationDetectionProperties {

    private boolean enabled = true;
    private int maxRetries = 3;
    private List<String> strictClaimTypes = new ArrayList<>(List.of(
            "phone", "url", "price", "address", "time", "weather", "traffic", "booking"
    ));

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = Math.max(0, maxRetries);
    }

    public List<String> getStrictClaimTypes() {
        return strictClaimTypes;
    }

    public void setStrictClaimTypes(List<String> strictClaimTypes) {
        this.strictClaimTypes = strictClaimTypes == null ? List.of() : List.copyOf(strictClaimTypes);
    }

    public boolean includes(String claimType) {
        if (claimType == null || claimType.isBlank()) {
            return false;
        }
        return strictClaimTypes.stream().anyMatch(item -> claimType.equalsIgnoreCase(item));
    }
}
