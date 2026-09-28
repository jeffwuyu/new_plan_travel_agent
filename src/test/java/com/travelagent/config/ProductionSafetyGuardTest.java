package com.travelagent.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ProductionSafetyGuard Tests")
class ProductionSafetyGuardTest {

    @Test
    @DisplayName("prod profile rejects default JWT secret")
    void prodProfileRejectsDefaultJwtSecret() {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod"});
        when(environment.getProperty("jwt.secret", "")).thenReturn(ProductionSafetyGuard.DEFAULT_JWT_SECRET);

        ProductionSafetyGuard guard = new ProductionSafetyGuard(environment);

        assertThatThrownBy(guard::validateProductionSecrets)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-default jwt.secret");
    }

    @Test
    @DisplayName("prod profile accepts strong custom JWT secret")
    void prodProfileAcceptsStrongJwtSecret() {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[]{"production"});
        when(environment.getProperty("jwt.secret", "")).thenReturn("travel-agent-production-secret-32chars-plus");

        ProductionSafetyGuard guard = new ProductionSafetyGuard(environment);

        assertThatCode(guard::validateProductionSecrets).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("dev profile does not enforce production secret rule")
    void devProfileDoesNotEnforceProductionSecretRule() {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[]{"dev"});
        when(environment.getProperty("jwt.secret", "")).thenReturn(ProductionSafetyGuard.DEFAULT_JWT_SECRET);

        ProductionSafetyGuard guard = new ProductionSafetyGuard(environment);

        assertThatCode(guard::validateProductionSecrets).doesNotThrowAnyException();
    }
}
