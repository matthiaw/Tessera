---
phase: 6
slug: metrics-instrumentation-wiring
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-17
---

# Phase 6 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Spring Boot Test + Testcontainers |
| **Config file** | `pom.xml` (surefire/failsafe plugins) |
| **Quick run command** | `mvn test -pl tessera-app -Dtest=TesseraMetrics*` |
| **Full suite command** | `mvn verify` |
| **Estimated runtime** | ~60 seconds |

---

## Sampling Rate

- **After every task commit:** Run `mvn test -pl tessera-app -Dtest=TesseraMetrics*`
- **After every plan wave:** Run `mvn verify`
- **Before `/gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** 60 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 06-01-01 | 01 | 1 | OPS-01 | — | N/A | unit | `mvn test -Dtest=MetricsPortTest` | ❌ W0 | ⬜ pending |
| 06-01-02 | 01 | 1 | OPS-01 | — | N/A | unit | `mvn test -Dtest=TesseraMetricsAdapterTest` | ❌ W0 | ⬜ pending |
| 06-02-01 | 02 | 2 | OPS-01 | — | N/A | integration | `mvn test -Dtest=ConnectorRunnerMetricsTest` | ❌ W0 | ⬜ pending |
| 06-02-02 | 02 | 2 | OPS-01 | — | N/A | integration | `mvn test -Dtest=RuleEngineMetricsTest` | ❌ W0 | ⬜ pending |
| 06-02-03 | 02 | 2 | OPS-01 | — | N/A | integration | `mvn test -Dtest=ShaclValidatorMetricsTest` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] Test stubs for MetricsPort and TesseraMetricsAdapter
- [ ] Test stubs for ConnectorRunner metrics wiring
- [ ] Test stubs for RuleEngine metrics wiring
- [ ] Test stubs for ShaclValidator metrics timing

*Existing TesseraMetricsTest covers meter registration; new tests needed for wiring.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Prometheus scrape endpoint shows real counters | OPS-01 | Requires running app + Prometheus | Start app, trigger connector sync, check /actuator/prometheus |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 60s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
