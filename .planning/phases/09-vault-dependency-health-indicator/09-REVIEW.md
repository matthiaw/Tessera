---
phase: 09-vault-dependency-health-indicator
reviewed: 2026-04-17T12:00:00Z
depth: standard
files_reviewed: 4
files_reviewed_list:
  - fabric-app/src/test/java/dev/tessera/app/health/VaultHealthIndicatorTest.java
  - fabric-app/src/test/java/dev/tessera/app/VaultAppRoleAuthIT.java
  - fabric-app/pom.xml
  - fabric-app/src/main/resources/application.yml
findings:
  critical: 0
  warning: 2
  info: 2
  total: 4
status: issues_found
---

# Phase 09: Code Review Report

**Reviewed:** 2026-04-17T12:00:00Z
**Depth:** standard
**Files Reviewed:** 4
**Status:** issues_found

## Summary

Reviewed the Vault dependency, health indicator configuration, unit tests, and integration test. The implementation is solid overall: Vault is correctly disabled by default, the health indicator conditional behavior is properly tested, and the integration test uses Testcontainers with dynamic properties. Two warnings relate to a hardcoded test token in the integration test and a potentially misleading test class name. Two informational items note minor improvements.

## Warnings

### WR-01: Hardcoded Vault root token in integration test

**File:** `fabric-app/src/test/java/dev/tessera/app/VaultAppRoleAuthIT.java:69-70`
**Issue:** The Vault container is initialized with a hardcoded root token `"test-root-token"` which is also repeated on line 77. While this is a test-only token for a Testcontainers ephemeral container (not a production secret), the pattern of hardcoding tokens inline can lead to copy-paste into non-test contexts. Extract to a constant to make the intent explicit and reduce repetition.
**Fix:**
```java
private static final String TEST_ROOT_TOKEN = "test-root-token";

@Container
static VaultContainer<?> vault = new VaultContainer<>("hashicorp/vault:1.15")
        .withVaultToken(TEST_ROOT_TOKEN)
        .withInitCommand("kv put secret/tessera/auth jwt-signing-key=test-signing-key-for-it");
```
Then reference `TEST_ROOT_TOKEN` on line 77 as well.

### WR-02: Test class named VaultAppRoleAuthIT but uses TOKEN authentication

**File:** `fabric-app/src/test/java/dev/tessera/app/VaultAppRoleAuthIT.java:65`
**Issue:** The class is named `VaultAppRoleAuthIT` but the test uses TOKEN authentication (line 76: `"spring.cloud.vault.authentication", () -> "TOKEN"`). The Javadoc on line 48 acknowledges this ("Uses TOKEN authentication...rather than AppRole") but the class name is misleading. A developer searching for AppRole integration tests will find this class and be confused. This could also mask a missing test -- there is no actual AppRole authentication coverage.
**Fix:** Rename to `VaultConnectivityIT` or `VaultIntegrationIT` to accurately reflect what is tested. If AppRole testing is planned for later, add a TODO or tracked issue.

## Info

### IN-01: Vault Docker image tag should be pinned to a patch version

**File:** `fabric-app/src/test/java/dev/tessera/app/VaultAppRoleAuthIT.java:68`
**Issue:** The Vault container uses `hashicorp/vault:1.15` which resolves to the latest 1.15.x patch. For reproducible test builds, pin to a specific patch (e.g., `hashicorp/vault:1.15.6`). Minor version drift can introduce behavioral changes in the KV engine that cause flaky tests.
**Fix:** Pin to a specific patch version, e.g., `new VaultContainer<>("hashicorp/vault:1.15.6")`.

### IN-02: management.health.vault.enabled is redundant when spring.cloud.vault.enabled=false

**File:** `fabric-app/src/main/resources/application.yml:97`
**Issue:** The property `management.health.vault.enabled: true` is set, but in the default profile `spring.cloud.vault.enabled` is `false`. When Vault is disabled, the `VaultHealthIndicatorAutoConfiguration` does not create the health indicator bean regardless of this management property (as correctly verified by the unit test on line 55 of `VaultHealthIndicatorTest.java`). The comment "Show vault health component when spring-cloud-vault is active" is accurate but the setting is a no-op in the default profile. Consider adding a brief comment clarifying it takes effect only when the prod profile activates Vault.
**Fix:** Add clarifying comment:
```yaml
management:
  health:
    vault:
      enabled: true  # Effective only when spring.cloud.vault.enabled=true (prod profile)
```

---

_Reviewed: 2026-04-17T12:00:00Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
