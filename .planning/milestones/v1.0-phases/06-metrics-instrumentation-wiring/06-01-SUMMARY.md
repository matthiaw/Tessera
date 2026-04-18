---
phase: 06-metrics-instrumentation-wiring
plan: "01"
subsystem: metrics
tags: [metrics, observability, prometheus, micrometer, spi]
dependency_graph:
  requires: [05-01]
  provides: [OPS-01-wired]
  affects: [fabric-core, fabric-rules, fabric-connectors, fabric-app]
tech_stack:
  added: []
  patterns: [SPI-port-adapter, optional-field-injection, null-guard]
key_files:
  created:
    - fabric-core/src/main/java/dev/tessera/core/metrics/MetricsPort.java
    - fabric-app/src/main/java/dev/tessera/app/metrics/TesseraMetricsAdapter.java
    - fabric-app/src/test/java/dev/tessera/app/metrics/TesseraMetricsAdapterTest.java
    - fabric-core/src/test/java/dev/tessera/core/validation/ShaclValidatorMetricsTest.java
  modified:
    - fabric-core/src/main/java/dev/tessera/core/validation/ShaclValidator.java
    - fabric-rules/src/main/java/dev/tessera/rules/RuleEngine.java
    - fabric-connectors/src/main/java/dev/tessera/connectors/internal/ConnectorRunner.java
decisions:
  - MetricsPort uses field injection (@Autowired required=false) in ShaclValidator and RuleEngine to preserve 2-arg and 3-arg constructors used by test fixtures
  - ConnectorRunner uses constructor parameter injection (consistent with existing optional bean pattern for EntityResolutionService/EmbeddingService)
  - recordRuleEvaluation fires once per pipeline invocation at top of run(RuleContext) — fires even on early VALIDATE reject so all evaluations are counted
  - recordConflict iterates EngineResult.conflicts() just before return — single counting point, no double-counting risk
  - SHACL timer records even when validation fails (before the conforms check) to capture full validation cost
metrics:
  duration_minutes: 14
  completed_date: "2026-04-17"
  tasks_completed: 2
  tasks_total: 2
  files_created: 4
  files_modified: 3
requirements:
  - OPS-01
---

# Phase 06 Plan 01: Metrics Instrumentation Wiring Summary

## One-liner

MetricsPort SPI in fabric-core wired into ShaclValidator, RuleEngine, and ConnectorRunner via null-guarded optional injection, with TesseraMetricsAdapter delegating to Micrometer in fabric-app.

## What Was Built

### MetricsPort SPI (fabric-core)

New interface `dev.tessera.core.metrics.MetricsPort` in fabric-core with four Micrometer-free methods:
- `recordIngest()` — tessera.ingest.rate counter
- `recordRuleEvaluation()` — tessera.rules.evaluations counter
- `recordConflict()` — tessera.conflicts.count counter
- `recordShaclValidationNanos(long nanos)` — tessera.shacl.validation.time timer

Follows the same structural pattern as `RuleEnginePort` and `CircuitBreakerPort` — fabric-core defines the SPI, fabric-app (or the app assembly) provides the implementation.

### TesseraMetricsAdapter (fabric-app)

Spring `@Component` implementing `MetricsPort` via constructor injection of `TesseraMetrics`. Thin pass-throughs:
- `recordIngest()` → `metrics.recordIngest()`
- `recordRuleEvaluation()` → `metrics.recordRuleEvaluation()`
- `recordConflict()` → `metrics.recordConflict()`
- `recordShaclValidationNanos(nanos)` → `metrics.shaclTimer().record(nanos, TimeUnit.NANOSECONDS)`

### Wiring in Production Code Paths

**ShaclValidator** — field injection `@Autowired(required = false) private MetricsPort metricsPort`. Timer wraps the Jena validation call using `System.nanoTime()`. Records even when validation fails. 2-arg constructor unchanged.

**RuleEngine** — field injection `@Autowired(required = false) private MetricsPort metricsPort`. `recordRuleEvaluation()` fires at the top of `run(RuleContext)` (before chain execution). `recordConflict()` iterates `EngineResult.conflicts()` just before return. 3-arg constructor unchanged.

**ConnectorRunner** — constructor parameter `@Autowired(required = false) MetricsPort metricsPort` added as last parameter (consistent with existing optional bean pattern). `recordIngest()` fires immediately after `successCount++` inside the `Committed` branch.

### Tests

- `TesseraMetricsAdapterTest` — 4 tests verifying all delegation methods using `SimpleMeterRegistry`. All pass.
- `ShaclValidatorMetricsTest` — 2 tests: timer recording with `RecordingMetricsPort` stub and null-safety. Both pass after Task 2 wiring.
- `TargetedValidationTest` — pre-existing SHACL test unchanged and passing (null metricsPort path).

## Deviations from Plan

None — plan executed exactly as written.

## Verification Results

All acceptance criteria met:

1. `MetricsPort.java` exists in `fabric-core` with 4 methods, zero Micrometer imports
2. `TesseraMetricsAdapter.java` has `@Component` and `implements MetricsPort`
3. `TesseraMetricsAdapterTest` — 4 tests, all pass
4. `ShaclValidatorMetricsTest` — 2 tests, both pass
5. `TargetedValidationTest` — passes unchanged
6. 3 wiring sites confirmed via grep
7. No fabric-app imports in fabric-core/fabric-rules/fabric-connectors main sources

Pre-existing failures (not caused by this plan):
- `FindShortestPathSpikeIT` — requires live PostgreSQL+AGE; fails with Connection refused in offline CI
- `ImagePinningTest` — Docker image digest drift; pre-existing before this plan

## Known Stubs

None.

## Threat Flags

None — all threat model entries accepted per plan (T-06-01, T-06-02, T-06-03). No new security surface introduced.

## Self-Check: PASSED

All 4 created files exist on disk. Both task commits (0539df2, b08275a) found in git log.
