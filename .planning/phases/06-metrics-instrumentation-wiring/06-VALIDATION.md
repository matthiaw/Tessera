---
phase: 6
slug: metrics-instrumentation-wiring
status: complete
nyquist_compliant: true
wave_0_complete: true
created: 2026-04-17
updated: 2026-04-17
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
| 06-01-01 | 01 | 1 | OPS-01 | — | N/A | manual-only | — (pure interface, contract tested transitively via TesseraMetricsAdapterTest and ShaclValidatorMetricsTest) | — | ✅ green |
| 06-01-02 | 01 | 1 | OPS-01 | — | N/A | unit | `./mvnw test -pl fabric-app -Dtest="TesseraMetricsAdapterTest" -DfailIfNoTests=false` | ✅ | ✅ green |
| 06-02-01 | 02 | 2 | OPS-01 | — | N/A | unit | `./mvnw test -pl fabric-connectors -Dtest="ConnectorRunnerMetricsTest" -DfailIfNoTests=false` | ✅ | ✅ green |
| 06-02-02 | 02 | 2 | OPS-01 | — | N/A | unit | `./mvnw test -pl fabric-rules -Dtest="RuleEngineMetricsTest" -DfailIfNoTests=false` | ✅ | ✅ green |
| 06-02-03 | 02 | 2 | OPS-01 | — | N/A | unit | `./mvnw test -pl fabric-core -Dtest="ShaclValidatorMetricsTest" -DfailIfNoTests=false` | ✅ | ✅ green |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [x] Test stubs for MetricsPort and TesseraMetricsAdapter
- [x] Test stubs for ConnectorRunner metrics wiring
- [x] Test stubs for RuleEngine metrics wiring
- [x] Test stubs for ShaclValidator metrics timing

*All wiring tests created and passing. TesseraMetricsTest covers meter registration; new tests cover call-site wiring.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Prometheus scrape endpoint shows real counters | OPS-01 | Requires running app + Prometheus | Start app, trigger connector sync, check /actuator/prometheus |
| MetricsPort interface contract | OPS-01 | Pure interface — no standalone test possible | Contract tested transitively via TesseraMetricsAdapterTest (all 4 methods) and ShaclValidatorMetricsTest (RecordingMetricsPort stub) |

---

## Validation Sign-Off

- [x] All tasks have `<automated>` verify or Wave 0 dependencies
- [x] Sampling continuity: no 3 consecutive tasks without automated verify
- [x] Wave 0 covers all MISSING references
- [x] No watch-mode flags
- [x] Feedback latency < 60s
- [x] `nyquist_compliant: true` set in frontmatter

**Approval:** 2026-04-17 — all gaps filled by gsd-nyquist-auditor
