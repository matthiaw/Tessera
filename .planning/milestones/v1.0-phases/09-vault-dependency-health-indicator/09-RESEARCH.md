# Phase 9: Vault Dependency & Health Indicator - Research

**Researched:** 2026-04-17
**Domain:** Spring Cloud Vault, Spring Boot Actuator health indicators, Maven dependency management
**Confidence:** HIGH

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| SEC-02 | Connector credentials and secrets live in HashiCorp Vault, loaded via Spring Cloud Vault Config Data API — never in repo, config files, or the fabric DB | `spring-cloud-starter-vault-config` must be a compile-scope dependency; `application-prod.yml` already has `spring.config.import=vault://` configured; the library auto-activates the Vault property source when present |
| OPS-02 | Spring Boot Actuator health endpoint exposes Postgres, AGE, Vault, and connector health | Postgres health is auto-provided by Spring Boot; AGE and connector health indicators already exist in `fabric-app`; Vault health indicator is auto-configured by Spring Cloud Vault when `spring-boot-starter-actuator` is on the classpath — this phase adds the missing Vault component |
</phase_requirements>

---

## Summary

Phase 9 is a targeted gap-closure phase with two interrelated tasks: (1) add `spring-cloud-starter-vault-config` as a compile-scope dependency to `fabric-app/pom.xml`, and (2) expose a Vault health component on `/actuator/health`. The audit found these missing despite `application-prod.yml` already containing a complete Vault configuration (`spring.config.import=vault://`, APPROLE auth, KV backend). The config exists; the library does not.

The good news: Spring Cloud Vault auto-configures a `VaultHealthIndicator` bean named `vaultHealthIndicator` automatically when `spring-boot-starter-actuator` is on the classpath. Since `fabric-app` already depends on `spring-boot-starter-actuator`, adding the vault starter is sufficient to bring the health indicator to life — no custom Java code is required for that part.

The only complication is test-time startup: adding `spring-cloud-starter-vault-config` to `fabric-app` means that any integration test that loads a full Spring context will attempt to connect to Vault. The standard mitigation is to set `spring.cloud.vault.enabled=false` in test application properties, or to rely on the `optional:vault://` import prefix already understood by Spring Cloud Vault. Since `application-prod.yml` (not `application.yml`) carries the `spring.config.import=vault://` line, tests that don't activate the `prod` profile are safe as long as the base `application.yml` omits the import.

**Primary recommendation:** Add `spring-cloud-starter-vault-config` to `fabric-app/pom.xml`. The Vault health indicator is then auto-configured automatically. Add `management.health.vault.enabled=true` (or omit, since it defaults to true) in `application.yml`. Add a unit test for the `vaultHealthIndicator` bean presence and a unit test covering UP/DOWN/UNKNOWN status scenarios via a mocked `VaultOperations`. Add `spring.cloud.vault.enabled=false` to the default `application.yml` so tests don't require a live Vault.

---

## Project Constraints (from CLAUDE.md)

| Directive | Applies to Phase 9 |
|-----------|-------------------|
| Java 21 + Spring Boot 3.5.13 | All new code |
| Spring Cloud 2024.0.x (Moorgate) — version `${spring-cloud.version}` already in parent POM | Version for `spring-cloud-starter-vault-config` comes from the imported BOM → resolves to 4.2.1 |
| `spring.config.import=vault://` (Config Data API), NOT `bootstrap.yml` | Already correct in `application-prod.yml`; do not introduce bootstrap.yml |
| Maven enforcer `dependencyConvergence` + `requireUpperBoundDeps` active | Adding vault starter pulls `httpclient5:5.4.2`; Spring Boot 3.5.13 manages `5.5.2` → Boot BOM wins, no conflict. Verify no new upper-bound violations. |
| ArchUnit module boundary enforcement | `VaultHealthIndicator` (auto-configured, no custom class needed) lives on the Spring Boot classpath; no module-boundary risk. If a custom class is written, it belongs in `fabric-app/src/main/java/dev/tessera/app/health/` (same package as AGE and connector indicators). |
| Apache 2.0 license headers on all new `.java` files | Any new test class needs the license header |
| `AbstractHealthIndicator` pattern established by `AgeGraphHealthIndicator` and `ConnectorHealthIndicator` | Unit test follows the same Mockito pattern |

---

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Vault secret loading at startup | API / Backend (Spring Boot app layer) | — | Config Data API runs before application context refresh; Vault is a configuration source, not a data store for graph nodes |
| Vault health monitoring | API / Backend (Actuator layer) | — | `VaultHealthIndicator` pings Vault's sys/health endpoint; lives in `fabric-app` alongside other health indicators |
| Vault credentials storage | External service (HashiCorp Vault) | — | Vault is the authoritative secrets store; Tessera is the consumer |

---

## Standard Stack

### Core Addition

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `spring-cloud-starter-vault-config` | 4.2.1 (from Spring Cloud 2024.0.1 BOM already imported) | Vault Config Data API + VaultOperations bean + auto-configured VaultHealthIndicator | The Spring Cloud Vault starter is the only supported path for `spring.config.import=vault://` in Spring Boot 3.x; matches locked decision; already managed by the parent POM's Spring Cloud BOM |

No new version property is needed. The parent POM already imports `spring-cloud-dependencies:2024.0.1` which resolves `spring-cloud-starter-vault-config` to `4.2.1`. [VERIFIED: Maven Central — spring-cloud-dependencies-2024.0.1.pom]

### Supporting Libraries (already present, no additions needed)

| Library | Why it already satisfies the requirement |
|---------|------------------------------------------|
| `spring-boot-starter-actuator` | Already in `fabric-app/pom.xml`; Spring Cloud Vault's `VaultHealthIndicatorConfiguration` is conditional on `spring-boot-starter-actuator` being present — `@ConditionalOnBean(VaultOperations.class)` + `@ConditionalOnMissingBean(name = "vaultHealthIndicator")` |
| `org.testcontainers:vault` | Already in `fabric-connectors/pom.xml` at test scope; can be added to `fabric-app` test scope for VaultAppRoleAuthIT |

### Installation

```xml
<!-- In fabric-app/pom.xml <dependencies> block -->
<!-- Version comes from spring-cloud-dependencies BOM imported in parent POM -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-vault-config</artifactId>
</dependency>
```

No version tag needed — the parent POM's `spring-cloud-dependencies:2024.0.1` BOM manages it to 4.2.1. [VERIFIED: Maven Central — spring-cloud-dependencies-2024.0.1.pom, spring-cloud-starter-vault-config-4.2.1.pom]

---

## Architecture Patterns

### System Architecture Diagram

```
[application-prod.yml]
  spring.config.import=vault://
          |
          v
[Spring Boot Config Data Loader] ──> [spring-cloud-starter-vault-config]
          |                                    |
          v                                    v
[Vault HTTP Client]                  [VaultOperations bean]
  APPROLE auth → Vault KV                     |
          |                                    v
          v                        [VaultHealthIndicatorConfiguration]
[Vault secret properties]           @ConditionalOnBean(VaultOperations)
  - tessera.auth.jwt-signing-key              |
  - connector credentials                      v
          |                        [vaultHealthIndicator bean]
          v                          name="vault" on /actuator/health
[RotatableJwtDecoder]
[ConnectorRegistry (credentials)]
```

### Recommended Project Structure (no new directories)

```
fabric-app/
├── pom.xml                           ← ADD spring-cloud-starter-vault-config
├── src/main/resources/
│   ├── application.yml               ← ADD spring.cloud.vault.enabled=false (dev default)
│   │                                     ADD management.health.vault.enabled=true
│   └── application-prod.yml          ← ALREADY has spring.config.import=vault://
└── src/test/java/dev/tessera/app/
    └── health/
        └── VaultHealthIndicatorTest.java  ← NEW: unit test via mocked VaultOperations
```

No new package is needed. The auto-configured `vaultHealthIndicator` bean lives in the Spring Cloud Vault auto-configuration, not in `dev.tessera.app`. If a custom indicator class is ever needed, it belongs in `dev/tessera/app/health/` matching the existing pattern.

### Pattern 1: Spring Cloud Vault Auto-Configured Health Indicator

**What:** When `spring-cloud-starter-vault-config` is on the classpath AND `spring-boot-starter-actuator` is present, Spring Cloud Vault auto-configures a `VaultHealthIndicator` bean named `vaultHealthIndicator`. It appears as the `vault` component in `/actuator/health`.

**When to use:** This is the standard path — no custom `@Component` class is needed. The indicator pings Vault's `sys/health` endpoint via `VaultOperations`.

**Example — how it appears in `/actuator/health` response:**
```json
{
  "status": "UP",
  "components": {
    "db": { "status": "UP" },
    "vault": { "status": "UP", "details": { "version": "1.x.y", "sealed": false } },
    "ageGraph": { "status": "UP", "details": { "graphs_count": 1 } },
    "connectors": { "status": "UP" }
  }
}
```

Source: [CITED: https://github.com/spring-cloud/spring-cloud-vault — VaultHealthIndicatorConfiguration.java, README.adoc]

### Pattern 2: Dev/Test Vault Disable Guard

**What:** `spring.cloud.vault.enabled=false` in `application.yml` (base profile) prevents Vault connection attempts during local development and unit/integration tests. The `prod` profile activates `application-prod.yml` which has the real `spring.config.import=vault://`.

**When to use:** Any environment without a live Vault instance. Tests, local dev, CI without Vault container.

```yaml
# application.yml (base) — safe default for tests and local dev
spring:
  cloud:
    vault:
      enabled: false    # Vault disabled by default; application-prod.yml enables it

management:
  health:
    vault:
      enabled: true     # Show the vault health component when Vault IS enabled
```

Source: [CITED: https://github.com/spring-cloud/spring-cloud-vault — config-data.adoc]

### Pattern 3: VaultHealthIndicator Unit Test via Mocked VaultOperations

**What:** Spring Cloud Vault's health indicator wraps `VaultOperations`. For unit tests, mock `VaultOperations.opsForSys().health()` to control UP/DOWN/UNKNOWN outcomes. This follows the exact same Mockito pattern as `AgeGraphHealthIndicatorTest` and `ConnectorHealthIndicatorTest`.

**When to use:** Unit test to verify the health component is present and correctly signals DOWN on Vault unavailability.

```java
// Source: established pattern from AgeGraphHealthIndicatorTest in this codebase
// VaultHealthIndicator is VaultHealthIndicator from spring-vault-core
// (transitive via spring-cloud-starter-vault-config)
@Test
void downWhenVaultThrowsException() {
    VaultOperations vaultOps = mock(VaultOperations.class);
    VaultSysOperations sys = mock(VaultSysOperations.class);
    when(vaultOps.opsForSys()).thenReturn(sys);
    when(sys.health()).thenThrow(new VaultException("Connection refused"));

    VaultHealthIndicator indicator = new VaultHealthIndicator(vaultOps);
    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
}
```

Note: `VaultHealthIndicator` is a class from `spring-vault-core` (transitive dependency of `spring-cloud-starter-vault-config`), not a custom class written for this phase. The test verifies its behavior. [VERIFIED: github.com/spring-cloud/spring-cloud-vault — VaultHealthIndicatorConfiguration.java]

### Anti-Patterns to Avoid

- **Writing a custom VaultHealthIndicator @Component:** Spring Cloud Vault already provides one. The auto-configured bean is conditional on `@ConditionalOnMissingBean(name="vaultHealthIndicator")` — if someone writes a custom class with `@Component("vault")`, it would suppress the auto-configured one. Don't do this.
- **Adding `spring.config.import=vault://` to the base `application.yml`:** This causes startup failure in test environments without Vault. The import belongs only in `application-prod.yml`.
- **Using `bootstrap.yml`:** Deprecated since Spring Cloud Vault 3.0 / Spring Boot 2.4. The Config Data API (`spring.config.import`) is the correct approach.
- **Adding vault dependency to `fabric-core` or `fabric-connectors`:** Those modules must not pull Vault into their compile scope — secrets management belongs at the application layer (`fabric-app`). `fabric-connectors` already has `testcontainers:vault` at test scope for integration tests; that's correct and separate.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Vault connectivity check | Custom HTTP ping to Vault's `/v1/sys/health` | Spring Cloud Vault auto-configured `VaultHealthIndicator` | Auto-configured indicator handles APPROLE token lifecycle, sealed-vs-uninitialized status codes, SSL/TLS config — not trivial to replicate |
| Vault property injection | Custom `@Value` + HTTP client | `spring.config.import=vault://` Config Data API | Config Data API handles refresh, failover, and `@RefreshScope` integration out of the box |
| Distributed locking for secrets rotation | Custom Postgres advisory lock | `RotatableJwtDecoder.rotateKey()` pattern already in codebase | Already solved in Phase 2 (Phase 2 tech debt: VaultAppRoleAuthIT was deferred, not the rotation mechanism) |

**Key insight:** Spring Cloud Vault's `VaultHealthIndicatorConfiguration` is already wired to fire automatically — adding the starter to `fabric-app/pom.xml` is the only non-trivial task. Everything else is configuration.

---

## Common Pitfalls

### Pitfall 1: Vault startup failure in tests

**What goes wrong:** Adding `spring-cloud-starter-vault-config` to `fabric-app` without disabling Vault for tests causes any `@SpringBootTest` or integration test that activates the full application context to fail with `VaultException: Connection refused` (Vault is not running).

**Why it happens:** The Config Data API processes `spring.config.import` entries before the application context refreshes. If `vault://` import is present and Vault is unavailable, startup fails unless the `optional:` prefix is used.

**How to avoid:** The existing `application-prod.yml` already uses `spring.config.import=vault://` (no `optional:` prefix — fail-fast in production is correct). The base `application.yml` should add `spring.cloud.vault.enabled=false` so that non-prod profiles never attempt a Vault connection. Tests that don't activate the `prod` profile are then safe.

**Warning signs:** `VaultException` or `IllegalStateException: Vault is not reachable` in test logs during context load.

### Pitfall 2: Maven enforcer `requireUpperBoundDeps` violation from `httpclient5`

**What goes wrong:** `spring-cloud-starter-vault-config:4.2.1` declares `httpclient5:5.4.2`. Spring Boot 3.5.13 manages `httpclient5:5.5.2`. Maven's `requireUpperBoundDeps` enforcer rule fires if the resolved version is *lower* than what something else needs.

**Why it happens:** The Spring Boot BOM version (5.5.2) is higher than what vault-config declares (5.4.2), so the resolved version is 5.5.2 (correct — Boot BOM wins). This is the safe direction. No enforcer violation is expected, but should be verified after adding the dependency. [VERIFIED: spring-cloud-dependencies-2024.0.1.pom, spring-boot-dependencies-3.5.13.pom]

**How to avoid:** Run `./mvnw verify -pl fabric-app -DskipTests` after the POM change to confirm the enforcer passes.

**Warning signs:** `[ERROR] Failed to execute goal ... Require upper bound dependencies error for ...httpclient5...` in the build output.

### Pitfall 3: `management.health.vault.enabled` not exposed in health endpoint

**What goes wrong:** The `vault` health component does not appear in `/actuator/health` even after adding the dependency.

**Why it happens:** Either (a) `spring.cloud.vault.enabled=false` in the base profile also suppresses `VaultOperations` bean creation, which means the `@ConditionalOnBean(VaultOperations.class)` in `VaultHealthIndicatorConfiguration` is not satisfied — health indicator is never registered; or (b) `management.endpoint.health.show-details=when-authorized` hides sub-components from unauthenticated callers.

**How to avoid:** (a) When testing the health endpoint in the `prod` profile, Vault must be reachable and `spring.cloud.vault.enabled=true`. (b) The `show-details=when-authorized` setting is intentional and correct — use an authenticated request or temporarily set `show-details=always` during development. When `spring.cloud.vault.enabled=false` is active (dev/test), the `vault` component will be absent from the health response; this is expected and correct behavior.

**Warning signs:** Health response shows `postgres`, `ageGraph`, `connectors` but not `vault`. Check whether `VaultOperations` bean exists in the context: `ApplicationContext.containsBean("vaultTemplate")`.

### Pitfall 4: `VaultHealthIndicator` class not importable in tests

**What goes wrong:** Test code tries to `import org.springframework.vault.actuate.health.VaultHealthIndicator` but the class is not found.

**Why it happens:** `VaultHealthIndicator` moved packages between versions. In spring-vault-core 3.x it is at `org.springframework.vault.actuate.health.VaultHealthIndicator`. In older versions it was elsewhere. The spring-vault-core version is managed by the Spring Cloud BOM.

**How to avoid:** Do not try to instantiate `VaultHealthIndicator` directly in unit tests — it requires a live `VaultOperations` and calls `sys.health()`. Instead, test via `ApplicationContext` slice with a mocked `VaultOperations` bean, or test the behaviour of the health response by verifying the `vault` key is present when `VaultOperations` is available. Alternatively, test only that the bean is conditionally absent when `spring.cloud.vault.enabled=false`.

**Warning signs:** `ClassNotFoundException: org.springframework.vault.actuate.health.VaultHealthIndicator` during test compilation.

---

## Code Examples

### Adding the dependency (fabric-app/pom.xml)

```xml
<!-- Source: spring-cloud-dependencies-2024.0.1.pom — version resolved by BOM, no explicit version needed -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-vault-config</artifactId>
</dependency>
```

### Dev/test vault disable (application.yml base profile)

```yaml
# Source: established pattern — spring.cloud.vault docs config-data.adoc
spring:
  cloud:
    vault:
      enabled: false   # Safe default for local dev and CI without Vault
```

### Prod vault config (application-prod.yml — already exists, shown for reference)

```yaml
# Source: current fabric-app/src/main/resources/application-prod.yml (already correct)
spring:
  config:
    import: "vault://secret/tessera/auth"
  cloud:
    vault:
      uri: ${VAULT_ADDR:http://localhost:8200}
      authentication: APPROLE
      app-role:
        role-id: ${VAULT_ROLE_ID:}
        secret-id: ${VAULT_SECRET_ID:}
      kv:
        enabled: true
        backend: secret
        default-context: tessera/auth
```

### management.health.vault enable (application.yml)

```yaml
# Source: Spring Cloud Vault README.adoc — management.health.vault.enabled defaults to true
management:
  health:
    vault:
      enabled: true   # Explicit for clarity; defaults to true when spring-cloud-vault is on classpath
```

### VaultHealthIndicator unit test (verify bean conditional behavior)

```java
// Source: pattern from AgeGraphHealthIndicatorTest in this codebase
// Tests that the vault health component is absent when Vault is disabled
// (can be done via @SpringBootTest with spring.cloud.vault.enabled=false)
@Test
void vaultHealthAbsentWhenVaultDisabled() {
    // When spring.cloud.vault.enabled=false, VaultOperations bean is not created,
    // so VaultHealthIndicatorConfiguration's @ConditionalOnBean(VaultOperations.class)
    // is not satisfied — no vaultHealthIndicator bean in context.
    assertThat(applicationContext.containsBean("vaultHealthIndicator")).isFalse();
}
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `bootstrap.yml` for Vault config | `spring.config.import=vault://` (Config Data API) | Spring Boot 2.4 / Spring Cloud Vault 3.0 | `bootstrap.yml` approach no longer needed; Config Data API is simpler and supported |
| Manual `VaultTemplate` health check | `VaultHealthIndicatorConfiguration` auto-configured by Spring Cloud Vault | Spring Cloud Vault 2.x+ | Zero-code health indicator when actuator is on classpath |

**Deprecated/outdated:**
- `bootstrap.yml` / `spring.cloud.bootstrap.enabled=true`: Do not use. `application-prod.yml` correctly uses `spring.config.import`.

---

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | `spring.cloud.vault.enabled=false` in base `application.yml` prevents `VaultOperations` bean creation and therefore also prevents `VaultHealthIndicator` from being registered, which is the intended behavior for dev/test | Common Pitfalls (Pitfall 3) | If wrong, `vault` health component appears as DOWN in dev, which pollutes health output; non-fatal but confusing |
| A2 | Existing integration tests in `fabric-app` do NOT activate the `prod` Spring profile, so they will not attempt Vault connection after the dependency is added | Standard Stack | If wrong, all ITs in fabric-app that load a Spring context will fail with VaultException; fix is `spring.cloud.vault.enabled=false` in test application.properties |

---

## Open Questions

1. **VaultAppRoleAuthIT — include or defer?**
   - What we know: Phase 2 tech debt item calls out "VaultAppRoleAuthIT deferred — Vault-to-app pipeline not integration-tested". Phase 9 brings Vault onto the classpath, making this IT feasible.
   - What's unclear: Is an integration test with a live Vault container (via `org.testcontainers:vault`) in scope for Phase 9 or deferred further?
   - Recommendation: Add `testcontainers:vault` to `fabric-app` test scope as part of this phase and implement `VaultAppRoleAuthIT` — it is the natural fit for this phase since the dependency gap is being closed here. Complexity is low (Testcontainers pattern already established; `testcontainers:vault` already in `fabric-connectors`).

2. **Should `spring.cloud.vault.enabled=false` go in `application.yml` or in a separate `application-test.yml`?**
   - What we know: The base `application.yml` affects all profiles unless overridden. A `test` profile property file is more surgical.
   - What's unclear: Whether existing tests that load a Spring Boot context explicitly activate the `test` profile.
   - Recommendation: Add `spring.cloud.vault.enabled=false` to the base `application.yml`. This is the simplest, safest path. Prod-profile `application-prod.yml` will be able to override with `spring.config.import=vault://` because the `prod` profile activates after the base.

---

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Docker (for testcontainers:vault in IT) | VaultAppRoleAuthIT | ✓ | 27.4 | Skip IT, unit test only |
| Maven 3.9 | Build | ✓ | 3.9 | N/A |
| Java 21 (Corretto) | Build + runtime | ✓ | 21 | N/A |

---

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 (via spring-boot-starter-test 3.5.13) |
| Config file | `fabric-app/pom.xml` surefire + failsafe plugins |
| Quick run command | `./mvnw test -pl fabric-app -Dtest=VaultHealthIndicatorTest` |
| Full suite command | `./mvnw verify -pl fabric-app` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| SEC-02 | `spring-cloud-starter-vault-config` on compile classpath | build verification | `./mvnw verify -pl fabric-app -DskipTests` | N/A (POM change) |
| SEC-02 | `spring.config.import=vault://` resolves when Vault available | integration | `./mvnw verify -pl fabric-app -Dtest=VaultAppRoleAuthIT` | ❌ Wave 0 |
| OPS-02 | `vault` component appears in `/actuator/health` when enabled | unit/slice | `./mvnw test -pl fabric-app -Dtest=VaultHealthIndicatorTest` | ❌ Wave 0 |
| OPS-02 | `vault` component absent when `spring.cloud.vault.enabled=false` | unit/slice | `./mvnw test -pl fabric-app -Dtest=VaultHealthIndicatorTest` | ❌ Wave 0 |

### Sampling Rate

- **Per task commit:** `./mvnw test -pl fabric-app -Dtest=VaultHealthIndicatorTest`
- **Per wave merge:** `./mvnw verify -pl fabric-app`
- **Phase gate:** Full suite green before `/gsd-verify-work`

### Wave 0 Gaps

- [ ] `fabric-app/src/test/java/dev/tessera/app/health/VaultHealthIndicatorTest.java` — covers OPS-02 (unit test, no Vault container required)
- [ ] `fabric-app/src/test/java/dev/tessera/app/VaultAppRoleAuthIT.java` — covers SEC-02 (Testcontainers vault container required)
- [ ] `testcontainers:vault` dependency at test scope in `fabric-app/pom.xml` — required for VaultAppRoleAuthIT

---

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | yes | APPROLE auth to Vault; no user passwords stored |
| V3 Session Management | no | N/A for secrets infrastructure |
| V4 Access Control | yes | Vault KV policy restricts Tessera to `secret/tessera/*` path only |
| V5 Input Validation | no | No user input reaches this phase's code path |
| V6 Cryptography | yes | Vault manages DEKs; Tessera never sees raw key material beyond HMAC signing key used by RotatableJwtDecoder |

### Known Threat Patterns for Spring Cloud Vault stack

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Vault token leakage via Actuator | Information Disclosure | `management.endpoint.health.show-details=when-authorized` already in `application.yml` — Vault status (UP/DOWN) visible to all; details (version, seal state) hidden from unauthenticated callers |
| AppRole secret-id in env vars | Information Disclosure | `VAULT_ROLE_ID` and `VAULT_SECRET_ID` injected at deploy time via Docker Compose env; never in git |
| Vault unavailability causes application startup failure | Denial of Service | `fail-fast=false` (default) in dev; prod can use `fail-fast=true` to fail early rather than serve with missing secrets; `spring.cloud.vault.enabled=false` ensures local dev is never affected |

---

## Sources

### Primary (HIGH confidence)

- `/spring-cloud/spring-cloud-vault` (Context7) — VaultHealthIndicatorConfiguration conditional annotations, auto-configuration behavior, `management.health.vault.enabled` property
- Maven Central: `spring-cloud-dependencies-2024.0.1.pom` — verified `spring-cloud-vault.version=4.2.1` [VERIFIED]
- Maven Central: `spring-cloud-starter-vault-config-4.2.1.pom` — verified artifact exists, transitive deps (httpclient5:5.4.2, spring-vault-core) [VERIFIED]
- Maven Central: `spring-boot-dependencies-3.5.13.pom` — verified `httpclient5.version=5.5.2` (higher than vault's 5.4.2, no enforcer conflict) [VERIFIED]
- Codebase: `fabric-app/src/main/resources/application-prod.yml` — confirmed `spring.config.import=vault://` already configured [VERIFIED]
- Codebase: `fabric-app/pom.xml` — confirmed `spring-boot-starter-actuator` already present [VERIFIED]
- Codebase: `fabric-app/src/main/java/dev/tessera/app/health/` — confirmed `AgeGraphHealthIndicator` and `ConnectorHealthIndicator` patterns [VERIFIED]
- Codebase: `.planning/v1.0-MILESTONE-AUDIT.md` — confirmed exact gap: "spring-cloud-starter-vault-config not in any pom.xml. No VaultHealthIndicator exists." [VERIFIED]

### Secondary (MEDIUM confidence)

- github.com/spring-cloud/spring-cloud-vault `VaultHealthIndicatorConfiguration.java` (via Context7 docs) — confirmed `@ConditionalOnBean(VaultOperations.class)` and `@ConditionalOnMissingBean(name="vaultHealthIndicator")` conditional logic [CITED]
- Spring Cloud Vault reference docs (Context7) — confirmed `optional:vault://` syntax and `spring.cloud.vault.enabled` disable pattern [CITED]

---

## Metadata

**Confidence breakdown:**
- Standard stack (library version, BOM resolution): HIGH — verified against Maven Central
- Architecture (auto-configuration behavior): HIGH — verified from Spring Cloud Vault source via Context7
- Pitfalls (test startup failure, enforcer): HIGH — derived from verified transitive dependency analysis
- Test patterns: HIGH — existing tests in codebase provide direct templates

**Research date:** 2026-04-17
**Valid until:** 2026-07-17 (stable library; 90-day validity)
