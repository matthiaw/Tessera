# Phase 9: Vault Dependency & Health Indicator - Pattern Map

**Mapped:** 2026-04-17
**Files analyzed:** 5 (2 new Java files, 2 config modifications, 1 POM modification)
**Analogs found:** 5 / 5

---

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|-------------------|------|-----------|----------------|---------------|
| `fabric-app/pom.xml` | config | N/A | `fabric-connectors/pom.xml` (Spring Cloud BOM pattern) | role-match |
| `fabric-app/src/main/resources/application.yml` | config | request-response | `application.yml` itself (existing management block) | self-extension |
| `fabric-app/src/test/java/dev/tessera/app/health/VaultHealthIndicatorTest.java` | test | request-response | `fabric-app/src/test/java/dev/tessera/app/health/AgeGraphHealthIndicatorTest.java` | exact |
| `fabric-app/src/test/java/dev/tessera/app/VaultAppRoleAuthIT.java` | test | request-response | `fabric-app/src/test/java/dev/tessera/app/OutboxPollerConditionalIT.java` | role-match |
| *(no custom indicator class — auto-configured)* | — | — | `fabric-app/src/main/java/dev/tessera/app/health/AgeGraphHealthIndicator.java` | reference only |

---

## Pattern Assignments

### `fabric-app/pom.xml` (config — add dependency)

**Analog:** `fabric-app/pom.xml` itself, pattern consistent with existing Spring Cloud dependency blocks in `fabric-connectors/pom.xml`

**Dependency block pattern** — insert after the last `spring-boot-starter-*` dependency block (before the `<build>` block, lines 57-77 are the test-scope deps):

```xml
<!-- Version resolved by spring-cloud-dependencies:2024.0.1 BOM in parent POM → 4.2.1 -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-vault-config</artifactId>
</dependency>
```

**Testcontainers vault test-scope pattern** — add alongside existing `testcontainers:postgresql` (line 69-72 of current pom.xml):

```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>vault</artifactId>
    <scope>test</scope>
</dependency>
```

No `<version>` tags are needed for either entry — both are managed by the parent BOM (`spring-cloud-dependencies` for vault-config, `testcontainers` BOM for vault container).

---

### `fabric-app/src/main/resources/application.yml` (config — extend existing file)

**Analog:** The file itself (`fabric-app/src/main/resources/application.yml`). The existing `management:` block at lines 80-89 shows the exact indentation and structure to extend.

**Existing `management:` block to extend** (lines 80-89):

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  endpoint:
    health:
      show-details: when-authorized
    prometheus:
      access: unrestricted
```

**Add `spring.cloud.vault` disable guard** — insert into the existing `spring:` block (as a new top-level key under `spring:`). The base `application.yml` has no existing `spring.cloud` section, so this is a new sub-key:

```yaml
spring:
  cloud:
    vault:
      enabled: false    # Vault disabled by default; application-prod.yml activates it via spring.config.import
```

**Add vault health enable** — extend the existing `management.health` block (after `show-details: when-authorized`):

```yaml
management:
  endpoint:
    health:
      show-details: when-authorized
      # vault health component: present only when spring.cloud.vault.enabled=true (prod profile)
  health:
    vault:
      enabled: true   # Explicit for clarity; defaults to true when spring-cloud-vault is on classpath
```

**Note:** `application-prod.yml` already contains the correct `spring.config.import=vault://` and full Vault config block. Do NOT touch it.

---

### `fabric-app/src/test/java/dev/tessera/app/health/VaultHealthIndicatorTest.java` (test, request-response)

**Analog:** `fabric-app/src/test/java/dev/tessera/app/health/AgeGraphHealthIndicatorTest.java`

**License header pattern** (lines 1-15 of AgeGraphHealthIndicatorTest.java):

```java
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
```

**Package declaration** (line 16):

```java
package dev.tessera.app.health;
```

**Imports pattern** (lines 18-30 of AgeGraphHealthIndicatorTest.java — adapt for Vault):

```java
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
```

**Class Javadoc pattern** (lines 32-36 of AgeGraphHealthIndicatorTest.java):

```java
/**
 * OPS-02: Unit tests for auto-configured VaultHealthIndicator — Vault availability health check.
 *
 * <p>Uses {@link ApplicationContextRunner} to verify the {@code vaultHealthIndicator} bean
 * is absent when {@code spring.cloud.vault.enabled=false} (default dev/test profile).
 */
```

**Core test pattern using ApplicationContextRunner** (from `OutboxPollerConditionalIT.java` lines 62-95 — adapt for vault bean presence):

```java
// Uses ApplicationContextRunner — NOT @SpringBootTest — so no Flyway, no DB, no Security needed.
// withUserConfiguration ensures @Conditional annotations are evaluated during context refresh.
private final ApplicationContextRunner runner =
        new ApplicationContextRunner()
                .withPropertyValues("spring.cloud.vault.enabled=false");

@Test
void vaultHealthAbsentWhenVaultDisabled() {
    runner.run(ctx ->
        assertThat(ctx).doesNotHaveBean("vaultHealthIndicator"));
}
```

**UP/DOWN test pattern using Mockito** (from `AgeGraphHealthIndicatorTest.java` lines 38-88):

```java
// Pattern for testing VaultHealthIndicator behavior via mocked VaultOperations:
// - @BeforeEach: mock(VaultOperations.class) and mock(VaultSysOperations.class)
// - stub vaultOps.opsForSys() → sys
// - stub sys.health() → VaultHealth UP response or throw VaultException
// - assertThat(health.getStatus()).isEqualTo(Status.UP / Status.DOWN)

@BeforeEach
void setUp() {
    // Note: VaultHealthIndicator is from spring-vault-core (transitive).
    // Direct instantiation: new VaultHealthIndicator(vaultOps)
}

@Test
void downWhenVaultThrowsException() {
    // stub sys.health() to throw VaultException("Connection refused")
    // assert status == DOWN
}
```

**IMPORTANT — avoid direct instantiation:** Per RESEARCH.md Pitfall 4, `VaultHealthIndicator` class location can vary by version. Prefer `ApplicationContextRunner`-based bean-presence tests over direct instantiation. If direct instantiation is used, verify the import: `org.springframework.vault.actuate.health.VaultHealthIndicator` (spring-vault-core 3.x).

---

### `fabric-app/src/test/java/dev/tessera/app/VaultAppRoleAuthIT.java` (test — integration, request-response)

**Analog:** `fabric-app/src/test/java/dev/tessera/app/OutboxPollerConditionalIT.java`

**License header pattern** — identical to all other test files (lines 1-15).

**Package declaration:**

```java
package dev.tessera.app;
```

**Imports pattern** (adapt from OutboxPollerConditionalIT.java lines 18-27):

```java
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.vault.VaultContainer;
```

**Testcontainers setup pattern** (based on existing Testcontainers usage in `fabric-app` for PostgreSQL and established project convention):

```java
@Testcontainers
class VaultAppRoleAuthIT {

    @Container
    static VaultContainer<?> vault = new VaultContainer<>("hashicorp/vault:1.15")
            .withVaultToken("root-token");

    @DynamicPropertySource
    static void vaultProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.vault.uri", vault::getHttpHostAddress);
        registry.add("spring.cloud.vault.token", () -> "root-token");
        registry.add("spring.cloud.vault.enabled", () -> "true");
    }
}
```

**Class Javadoc pattern** (from OutboxPollerConditionalIT.java lines 37-42):

```java
/**
 * SEC-02: Integration test verifying Vault AppRole authentication and secret loading.
 *
 * <p>Uses {@link VaultContainer} (Testcontainers) to start a real Vault instance.
 * Verifies that {@code spring.config.import=vault://} resolves secrets when Vault is available.
 */
```

---

## Shared Patterns

### Apache 2.0 License Header
**Source:** `fabric-app/src/test/java/dev/tessera/app/health/AgeGraphHealthIndicatorTest.java` lines 1-15
**Apply to:** All new `.java` files in this phase (`VaultHealthIndicatorTest.java`, `VaultAppRoleAuthIT.java`)

```java
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
```

### Health Test Structure (Mockito + no-DB pattern)
**Source:** `fabric-app/src/test/java/dev/tessera/app/health/AgeGraphHealthIndicatorTest.java` lines 37-88
**Apply to:** `VaultHealthIndicatorTest.java`

Key structural traits to replicate:
- No `@SpringBootTest` — plain JUnit 5 class, no annotations
- `@BeforeEach` wires mocks manually via `mock(...)` and instantiates the indicator
- Each `@Test` stubs one scenario, calls `indicator.health()`, asserts on `Health.getStatus()` and `Health.getDetails()`
- Class-level Javadoc cites the requirement ID (`OPS-02`) and describes what is being tested

### Bean-Presence Test Structure (ApplicationContextRunner pattern)
**Source:** `fabric-app/src/test/java/dev/tessera/app/OutboxPollerConditionalIT.java` lines 43-96
**Apply to:** `VaultHealthIndicatorTest.java` (for the `vaultHealthIndicator` absent-when-disabled scenario)

Key structural traits to replicate:
- Use `ApplicationContextRunner` (not `@SpringBootTest`) for lightweight conditional verification
- Use `withPropertyValues(...)` to inject the disabling property
- Assert with `assertThat(ctx).doesNotHaveBean("vaultHealthIndicator")`
- Class-level Javadoc explains WHY `withUserConfiguration` is used instead of `withBean`

### POM Dependency Block (no-version, BOM-managed)
**Source:** `fabric-app/pom.xml` lines 41-48 (existing spring-boot-starter-actuator block)
**Apply to:** New `spring-cloud-starter-vault-config` and `testcontainers:vault` entries

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

Convention: no `<version>` tag when the artifact is managed by a BOM imported in the parent POM. The Spring Cloud BOM (`spring-cloud-dependencies:2024.0.1`) manages `spring-cloud-starter-vault-config`; the Testcontainers BOM manages `testcontainers:vault`.

---

## No Analog Found

All files have analogs. No entries in this section.

---

## Metadata

**Analog search scope:** `fabric-app/src/`, `fabric-connectors/src/`, `fabric-app/pom.xml`
**Files scanned:** 8 source files + 2 POM files + 2 YAML config files
**Pattern extraction date:** 2026-04-17
