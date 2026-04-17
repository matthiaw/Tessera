---
phase: 5
slug: circlead-integration-production-hardening
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-17
---

# Phase 5 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Spring Boot Test + Testcontainers + WireMock |
| **Config file** | `fabric-app/src/test/resources/application-test.yml` |
| **Quick run command** | `mvn test -pl fabric-core,fabric-projections,fabric-app -Dtest=CircleadMapping*,EventRetention*,Snapshot*,*HealthIndicator* -Dspotless.check.skip=true` |
| **Full suite command** | `mvn verify -Dspotless.check.skip=true` |
| **Estimated runtime** | ~90 seconds |

---

## Sampling Rate

- **After every task commit:** Run quick test command
- **After every plan wave:** Run full suite command
- **Before `/gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** 90 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|-----------|-------------------|-------------|--------|
| 05-01-01 | 01 | 1 | CIRC-01 | integration | `mvn test -Dtest=CircleadMappingIT` | ❌ W0 | ⬜ pending |
| 05-01-02 | 01 | 1 | CIRC-02, CIRC-03 | integration | `mvn test -Dtest=CircleadGracefulDegradationIT` | ❌ W0 | ⬜ pending |
| 05-02-01 | 02 | 1 | OPS-01 | unit | `mvn test -Dtest=*MetricsTest` | ❌ W0 | ⬜ pending |
| 05-02-02 | 02 | 1 | OPS-02 | integration | `mvn test -Dtest=*HealthIndicatorIT` | ❌ W0 | ⬜ pending |
| 05-03-01 | 03 | 2 | OPS-03 | integration | `mvn test -Dtest=EventRetentionIT` | ❌ W0 | ⬜ pending |
| 05-03-02 | 03 | 2 | OPS-04 | integration | `mvn test -Dtest=SnapshotCompactionIT` | ❌ W0 | ⬜ pending |
| 05-04-01 | 04 | 2 | OPS-05 | script | `bash scripts/dr_drill.sh --dry-run` | N/A | ⬜ pending |

---

## Wave 0 Requirements

- [ ] `CircleadMappingIT.java` — stubs for CIRC-01 mapping round-trip
- [ ] `CircleadGracefulDegradationIT.java` — stubs for CIRC-02/03 degradation
- [ ] `EventRetentionIT.java` — stubs for OPS-03 retention
- [ ] `SnapshotCompactionIT.java` — stubs for OPS-04 snapshot

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Live circlead integration | CIRC-01 | Requires running circlead instance | Point connector at live circlead, verify Role/Circle/Activity data in graph |
| DR drill end-to-end | OPS-05 | Requires pg_dump/restore cycle | Run scripts/dr_drill.sh against IONOS VPS |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] Feedback latency < 90s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
