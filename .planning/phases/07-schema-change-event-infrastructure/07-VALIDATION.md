---
phase: 7
slug: schema-change-event-infrastructure
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-17
---

# Phase 7 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Spring Boot Test + Testcontainers |
| **Config file** | `fabric-projections/src/test/resources/application-test.yml` |
| **Quick run command** | `mvn test -pl fabric-core -Dtest=SchemaChangeEventTest` |
| **Full suite command** | `mvn test -pl fabric-core,fabric-projections` |
| **Estimated runtime** | ~45 seconds |

---

## Sampling Rate

- **After every task commit:** Run `mvn test -pl fabric-core -Dtest=SchemaChangeEventTest`
- **After every plan wave:** Run `mvn test -pl fabric-core,fabric-projections`
- **Before `/gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** 45 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 7-01-01 | 01 | 1 | SQL-02 | — | N/A | unit | `mvn test -pl fabric-core -Dtest=SchemaChangeEventTest` | ❌ W0 | ⬜ pending |
| 7-01-02 | 01 | 1 | SQL-02 | — | N/A | integration | `mvn test -pl fabric-projections -Dtest=SqlViewSchemaChangeIT` | ✅ | ⬜ pending |
| 7-02-01 | 02 | 2 | MCP-08 | — | N/A | integration | `mvn test -pl fabric-projections -Dtest=McpSchemaChangeIT` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `SchemaChangeEventTest.java` — unit tests for event record construction and payload
- [ ] Enable `SqlViewSchemaChangeIT` — remove @Disabled, implement test methods

*If none: "Existing infrastructure covers all phase requirements."*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| MCP client receives notification | MCP-08 | Requires connected MCP client | Start MCP client, change schema, verify notification received |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 45s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
