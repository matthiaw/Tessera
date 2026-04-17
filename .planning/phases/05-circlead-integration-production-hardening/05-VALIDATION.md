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
| **Quick run command** | `mvn test -pl fabric-core,fabric-projections,fabric-app -Dtest=CircleadConnector*,EventRetention*,EventSnapshot*,*HealthIndicator*,TesseraMetrics* -Dspotless.check.skip=true` |
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
| 05-02-01 | 02 | 1 | CIRC-01 | integration | `mvn test -Dtest=CircleadConnectorIT` | ❌ W0 | ⬜ pending |
| 05-02-02 | 02 | 1 | CIRC-02, CIRC-03 | integration | `mvn test -Dtest=CircleadConnectorIT` | ❌ W0 | ⬜ pending |
| 05-01-01 | 01 | 1 | OPS-01 | unit | `mvn test -Dtest=TesseraMetricsTest` | ❌ W0 | ⬜ pending |
| 05-01-02 | 01 | 1 | OPS-02 | integration | `mvn test -Dtest=AgeGraphHealthIndicatorIT,ConnectorHealthIndicatorIT` | ❌ W0 | ⬜ pending |
| 05-03-01 | 03 | 2 | OPS-03 | integration | `mvn test -Dtest=EventRetentionJobTest` | ❌ W0 | ⬜ pending |
| 05-03-02 | 03 | 2 | OPS-04 | integration | `mvn test -Dtest=EventSnapshotServiceTest` | ❌ W0 | ⬜ pending |
| 05-04-01 | 04 | 3 | OPS-05 | script | `grep -q 'pg_dump\|pg_restore\|flyway' scripts/dr_drill.sh` | N/A | ⬜ pending |

*Note: CIRC-03 (graceful degradation) is circlead-side responsibility. Tessera delivers: documented circuit breaker pattern + connector disconnect test in CircleadConnectorIT. See D-A3 scope note.*

*Note: OPS-05 DR drill API smoke test runs in IONOS manual drill only (not CI). CI job validates DB-layer: dump → restore → Flyway → psql queries. See D-D1 scope note.*

---

## Wave 0 Requirements

- [ ] `CircleadConnectorIT.java` — stubs for CIRC-01 mapping + connector disconnect
- [ ] `TesseraMetricsTest.java` — stubs for OPS-01 metrics registration
- [ ] `AgeGraphHealthIndicatorIT.java` — stubs for OPS-02 health
- [ ] `ConnectorHealthIndicatorIT.java` — stubs for OPS-02 connector health
- [ ] `EventRetentionJobTest.java` — stubs for OPS-03 retention
- [ ] `EventSnapshotServiceTest.java` — stubs for OPS-04 snapshot

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Live circlead integration | CIRC-01 | Requires running circlead instance | Point connector at live circlead, verify Role/Circle/Activity data in graph |
| Circlead graceful degradation | CIRC-03 | Requires circlead-side circuit breaker | Implement circuit breaker in circlead per docs/circlead-mapping.md, verify fallback |
| DR drill API smoke test | OPS-05 | Requires full Tessera on IONOS VPS | Run scripts/dr_drill.sh on VPS, verify /actuator/health + GET /api/v1/{model}/entities/role |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] Feedback latency < 90s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
