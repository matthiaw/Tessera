---
phase: 10
slug: field-level-access-control-security-docs
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-17
---

# Phase 10 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Spring Boot Test + Testcontainers |
| **Config file** | `fabric-core/pom.xml`, `fabric-projections/pom.xml` |
| **Quick run command** | `mvn test -pl fabric-core -Dtest=AclFilterServiceTest` |
| **Full suite command** | `mvn test -pl fabric-core,fabric-projections` |
| **Estimated runtime** | ~90 seconds |

---

## Sampling Rate

- **After every task commit:** Run `mvn test -pl fabric-core -Dtest=AclFilterServiceTest`
- **After every plan wave:** Run `mvn test -pl fabric-core,fabric-projections`
- **Before `/gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** 90 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 10-01-01 | 01 | 1 | SEC-05 | T-10-01 | Properties with read_roles filtered from response when caller lacks role | unit | `mvn test -pl fabric-core -Dtest=AclFilterServiceTest` | ❌ W0 | ⬜ pending |
| 10-01-02 | 01 | 1 | SEC-04 | T-10-02 | Node types with read_roles return 404 when caller lacks role | integration | `mvn test -pl fabric-projections -Dtest=TypeLevelAclIT` | ❌ W0 | ⬜ pending |
| 10-02-01 | 02 | 1 | REST-07 | T-10-03 | REST responses filtered by caller role | integration | `mvn test -pl fabric-projections -Dtest=AclRestFilterIT` | ❌ W0 | ⬜ pending |
| 10-02-02 | 02 | 1 | SEC-05 | T-10-04 | MCP responses filtered by caller role | integration | `mvn test -pl fabric-projections -Dtest=AclMcpFilterIT` | ❌ W0 | ⬜ pending |
| 10-03-01 | 03 | 2 | SEC-03 | — | TDE runbook exists and covers key rotation | manual | N/A | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `AclFilterServiceTest.java` — unit tests for property filtering by role
- [ ] `TypeLevelAclIT.java` — integration test for node type visibility by role
- [ ] `AclRestFilterIT.java` — REST endpoint ACL filtering integration test
- [ ] `AclMcpFilterIT.java` — MCP tool ACL filtering integration test

*Existing test infrastructure (Testcontainers, Spring Boot Test) covers framework needs.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| TDE runbook completeness | SEC-03 | Operational documentation review | Read `docs/ops/tde-deployment-runbook.md`, verify sections: LUKS setup, key rotation, backup encryption, DR restore, monitoring |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 90s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
