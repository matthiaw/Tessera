---
phase: 09
slug: vault-dependency-health-indicator
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-17
---

# Phase 09 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Spring Boot Test + Testcontainers |
| **Config file** | `fabric-app/src/test/resources/application.yml` |
| **Quick run command** | `mvn test -pl fabric-app -Dtest=VaultHealthIndicatorTest` |
| **Full suite command** | `mvn verify -pl fabric-app` |
| **Estimated runtime** | ~45 seconds |

---

## Sampling Rate

- **After every task commit:** Run `mvn test -pl fabric-app -Dtest=VaultHealthIndicatorTest`
- **After every plan wave:** Run `mvn verify -pl fabric-app`
- **Before `/gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** 45 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 09-01-01 | 01 | 1 | SEC-02 | T-09-01 / — | Vault dependency at compile scope | unit | `mvn dependency:tree -pl fabric-app \| grep vault` | ❌ W0 | ⬜ pending |
| 09-01-02 | 01 | 1 | OPS-02 | — | Health indicator auto-configured | unit | `mvn test -pl fabric-app -Dtest=VaultHealthIndicatorTest` | ❌ W0 | ⬜ pending |
| 09-01-03 | 01 | 1 | SEC-02 | T-09-02 / — | Vault disabled in test profile | integration | `mvn verify -pl fabric-app` | ✅ | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `fabric-app/src/test/java/.../VaultHealthIndicatorTest.java` — unit test for health indicator behavior
- [ ] Verify `spring.cloud.vault.enabled=false` in test application.yml

*Existing test infrastructure (JUnit 5, Testcontainers, Spring Boot Test) covers all phase requirements.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| `spring.config.import=vault://` resolves at startup | SEC-02 | Requires running Vault instance | Start app with prod profile against Vault dev server; verify no startup errors |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 45s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
