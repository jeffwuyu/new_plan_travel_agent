package com.travelagent.config;

import jakarta.annotation.PostConstruct;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component
public class ProductionSafetyGuard {

    public static final String DEFAULT_JWT_SECRET = "change-me-in-production-min-32-chars";

    private final Environment environment;

    public ProductionSafetyGuard(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    public void validate() {
        validateProductionSecrets();
    }

    void validateProductionSecrets() {
        if (!isProductionProfile()) {
            return;
        }
        String jwtSecret = environment.getProperty("jwt.secret", "");
        if (jwtSecret == null || jwtSecret.isBlank() || DEFAULT_JWT_SECRET.equals(jwtSecret.trim())) {
            throw new IllegalStateException("Production profile requires a non-default jwt.secret/JWT_SECRET");
        }
        if (jwtSecret.trim().length() < 32) {
            throw new IllegalStateException("Production profile requires jwt.secret to be at least 32 characters");
        }
    }

    private boolean isProductionProfile() {
        return Arrays.stream(environment.getActiveProfiles())
                .anyMatch(profile -> "prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile));
    }
}
