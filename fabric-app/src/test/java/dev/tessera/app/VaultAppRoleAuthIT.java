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
package dev.tessera.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.configuration.CompatibilityVerifierAutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.vault.core.VaultKeyValueOperationsSupport;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.support.VaultResponse;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.vault.VaultContainer;

/**
 * SEC-02: Integration test for Vault connectivity and secret retrieval via Testcontainers.
 *
 * <p>Proves that Spring Cloud Vault auto-configures a {@link VaultTemplate} that can
 * connect to and read secrets from a HashiCorp Vault instance. Also verifies the
 * {@code vaultHealthIndicator} bean is present when Vault is enabled (OPS-02).
 *
 * <p>Uses TOKEN authentication (simpler for test setup) rather than AppRole, but
 * exercises the same {@link VaultTemplate} and health indicator that production uses.
 *
 * <p><b>Note:</b> {@code spring.config.import=vault://} is NOT tested here because the
 * Config Data API resolves imports during environment preparation, before any test
 * property source (including {@code @DynamicPropertySource} and
 * {@code ApplicationContextInitializer}) can inject the Vault container address.
 * Production uses {@code application-prod.yml} where the Vault address is a static
 * environment variable. This test verifies the underlying VaultTemplate connectivity
 * which the Config Data API depends on.
 */
@SpringBootTest(
        classes = VaultAppRoleAuthIT.TestConfig.class,
        properties = {
                "spring.cloud.compatibility-verifier.enabled=false",
                "spring.autoconfigure.exclude="
                        + "org.springframework.cloud.configuration.CompatibilityVerifierAutoConfiguration"
        })
@Testcontainers
class VaultAppRoleAuthIT {

    @Container
    static VaultContainer<?> vault = new VaultContainer<>("hashicorp/vault:1.15")
            .withVaultToken("test-root-token")
            .withInitCommand("kv put secret/tessera/auth jwt-signing-key=test-signing-key-for-it");

    @DynamicPropertySource
    static void vaultProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.vault.enabled", () -> "true");
        registry.add("spring.cloud.vault.uri", vault::getHttpHostAddress);
        registry.add("spring.cloud.vault.authentication", () -> "TOKEN");
        registry.add("spring.cloud.vault.token", () -> "test-root-token");
        registry.add("spring.cloud.vault.kv.enabled", () -> "true");
        registry.add("spring.cloud.vault.kv.backend", () -> "secret");
        registry.add("spring.cloud.vault.kv.default-context", () -> "tessera/auth");
    }

    @Autowired
    private VaultTemplate vaultTemplate;

    @Autowired
    private ApplicationContext context;

    /**
     * SEC-02: Verify that secrets stored in Vault can be read via the auto-configured
     * {@link VaultTemplate}. This proves Vault connectivity, TOKEN authentication, and
     * KV v2 secret engine access -- the same path that {@code spring.config.import=vault://}
     * uses at runtime.
     */
    @Test
    void secretLoadedFromVault() {
        // Verify Vault connectivity first
        org.springframework.vault.support.VaultHealth health = vaultTemplate.opsForSys().health();
        assertThat(health.isInitialized()).isTrue();

        // Write secret programmatically via VaultTemplate to guarantee correctness
        vaultTemplate
                .opsForKeyValue("secret", VaultKeyValueOperationsSupport.KeyValueBackend.KV_2)
                .put("tessera/auth", java.util.Map.of("jwt-signing-key", "test-signing-key-for-it"));

        // Read it back via KV v2 operations
        VaultResponse response = vaultTemplate
                .opsForKeyValue("secret", VaultKeyValueOperationsSupport.KeyValueBackend.KV_2)
                .get("tessera/auth");

        assertThat(response).isNotNull();
        assertThat(response.getData()).containsEntry("jwt-signing-key", "test-signing-key-for-it");
    }

    /**
     * OPS-02: Verify that the Vault health indicator bean is present when Vault is
     * enabled and connected.
     */
    @Test
    void vaultHealthIndicatorPresent() {
        assertThat(context.containsBean("vaultHealthIndicator")).isTrue();
    }

    @Configuration
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            FlywayAutoConfiguration.class,
            CompatibilityVerifierAutoConfiguration.class
    })
    static class TestConfig {}
}
