/*
 * Copyright 2026 Tessera Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.tessera.app.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.vault.config.VaultAutoConfiguration;
import org.springframework.cloud.vault.config.VaultHealthIndicator;
import org.springframework.cloud.vault.config.VaultHealthIndicatorAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.vault.VaultException;
import org.springframework.vault.core.VaultOperations;
import org.springframework.vault.core.VaultSysOperations;

/**
 * OPS-02: Unit tests for Vault health indicator conditional behavior.
 *
 * <p>Verifies that the {@code vaultHealthIndicator} bean is absent when Vault is disabled
 * (dev/test default) and present when a {@link VaultOperations} bean exists. Uses
 * {@link ApplicationContextRunner} for lightweight context bootstrap.
 */
class VaultHealthIndicatorTest {

    /**
     * OPS-02: When {@code spring.cloud.vault.enabled=false} (dev/test default), the
     * {@code vaultHealthIndicator} bean must not be present in the application context.
     * Uses VaultAutoConfiguration which conditionally creates VaultOperations only when
     * vault is enabled.
     */
    @Test
    void vaultHealthAbsentWhenVaultDisabled() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        VaultAutoConfiguration.class, VaultHealthIndicatorAutoConfiguration.class))
                .withPropertyValues("spring.cloud.vault.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean("vaultHealthIndicator"));
    }

    /**
     * OPS-02: When a {@link VaultOperations} bean is available (Vault enabled and
     * connected), the auto-configured {@code vaultHealthIndicator} bean must be present.
     * Provides a mock VaultOperations directly (without VaultAutoConfiguration) to avoid
     * bean definition conflicts.
     */
    @Test
    void vaultHealthPresentWhenVaultOperationsAvailable() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(VaultHealthIndicatorAutoConfiguration.class))
                .withUserConfiguration(MockVaultConfig.class)
                .run(ctx -> assertThat(ctx).hasBean("vaultHealthIndicator"));
    }

    /**
     * OPS-02: When Vault throws an exception during health check, the indicator reports
     * DOWN status.
     */
    @Test
    void downWhenVaultThrowsException() {
        VaultOperations vaultOps = mock(VaultOperations.class);
        VaultSysOperations sys = mock(VaultSysOperations.class);
        when(vaultOps.opsForSys()).thenReturn(sys);
        when(sys.health()).thenThrow(new VaultException("Connection refused"));

        VaultHealthIndicator indicator = new VaultHealthIndicator(vaultOps);
        org.springframework.boot.actuate.health.Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(org.springframework.boot.actuate.health.Status.DOWN);
    }

    @Configuration
    static class MockVaultConfig {

        @Bean
        VaultOperations vaultTemplate() {
            return mock(VaultOperations.class);
        }
    }
}
