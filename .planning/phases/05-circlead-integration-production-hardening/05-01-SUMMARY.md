---
phase: 05-circlead-integration-production-hardening
plan: "01"
subsystem: observability
tags: [metrics, micrometer, prometheus, health-indicators, actuator, tdd]
dependency_graph:
  requires:
    - micrometer-prometheus-dependency
    - prometheus-actuator-endpoint
    - nyquist-test-stubs-phase5
  provides:
    - tessera-metrics-bean
    - age-graph-health-indicator
    - connector-health-indicator
  affects:
    - fabric-app
tech_stack:
  added: []
  patterns:
    - Micrometer Counter/Gauge/Timer registration via constructor injection (MeterRegistry)
    - Gauge DB-polling lambda with null-guard for unit-test safety (no DB required)
    - AbstractHealthIndicator with @Component name for /actuator/health sub-key
    - TDD RED->GREEN->REFACTOR with SimpleMeterRegistry (no Spring context)
key_files:
  created:
    - fabric-app/src/main/java/dev/tessera/app/metrics/TesseraMetrics.java
    - fabric-app/src/main/java/dev/tessera/app/health/AgeGraphHealthIndicator.java
    - fabric-app/src/main/java/dev/tessera/app/health/ConnectorHealthIndicator.java
  modified:
    - fabric-app/src/test/java/dev/tessera/app/metrics/TesseraMetricsTest.java
    - fabric-app/src/test/java/dev/tessera/app/health/AgeGraphHealthIndicatorTest.java
    - fabric-app/src/test/java/dev/tessera/app/health/ConnectorHealthIndicatorTest.java
decisions:
  - Gauge lambdas take NamedParameterJdbcTemplate as the state object (not a captured field) so Micrometer holds a weak reference to the template; null-guard returns 0/-1 in unit tests without DB
  - AgeGraphHealthIndicator returns UP for empty ag_catalog.ag_graph (AGE loaded but no graphs is not a failure); exception path is DOWN (AGE extension not loaded)
  - ConnectorHealthIndicator aggregates any FAILED outcome to DOWN regardless of other connectors; empty result set is UP with no details
  - Spotless applied after each Write (Google Java Format enforced by pre-existing spotless-maven-plugin)
metrics:
  duration_seconds: 389
  completed_date: "2026-04-17"
  tasks_completed: 2
  files_created: 3
  files_modified: 3
---

# Phase 5 Plan 01: Observability — Prometheus Metrics and Health Indicators

**One-liner:** TesseraMetrics registers 6 Micrometer meters (3 counters, 2 DB-polling gauges, 1 SHACL timer); AgeGraphHealthIndicator and ConnectorHealthIndicator expose AGE and connector sync status under /actuator/health with UP/DOWN aggregation.

## Tasks Completed

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 | TesseraMetrics — 6 Micrometer meters | 4c3ac6c | TesseraMetrics.java, TesseraMetricsTest.java |
| 2 | AgeGraphHealthIndicator + ConnectorHealthIndicator | 5baacd0 | AgeGraphHealthIndicator.java, ConnectorHealthIndicator.java, AgeGraphHealthIndicatorTest.java, ConnectorHealthIndicatorTest.java |

## Verification Results

- `./mvnw test -pl fabric-app -Dtest="TesseraMetricsTest,AgeGraphHealthIndicatorTest,ConnectorHealthIndicatorTest"` — 16 tests run, 0 failures, 0 errors
- TesseraMetrics registers 6 meters: tessera.ingest.rate (Counter), tessera.rules.evaluations (Counter), tessera.conflicts.count (Counter), tessera.outbox.lag (Gauge), tessera.replication.slot.lag (Gauge), tessera.shacl.validation.time (Timer)
- AgeGraphHealthIndicator extends AbstractHealthIndicator, @Component("ageGraph"), queries ag_catalog.ag_graph
- ConnectorHealthIndicator extends AbstractHealthIndicator, @Component("connectors"), queries connector_sync_status JOIN connectors WHERE enabled=TRUE
- No @Disabled annotations remaining in any of the 3 test files

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Spotless formatting applied twice (after test write and after production class write)**

- **Found during:** Task 1 and Task 2
- **Issue:** The spotless-maven-plugin (Google Java Format) failed the build when new Java files were written with line breaks that differed from the formatter's canonical output. This is expected Spotless behavior, not a code bug.
- **Fix:** Ran `./mvnw spotless:apply -pl fabric-app` after each Write to normalize formatting before running tests.
- **Files modified:** TesseraMetrics.java, AgeGraphHealthIndicatorTest.java, ConnectorHealthIndicatorTest.java (minor line-break normalizations only)
- **Commit:** Formatting normalization included in the respective task commits

## Known Stubs

None — all test stubs from Plan 00 that were assigned to this plan have been fully implemented and activated (no @Disabled remaining).

The remaining Phase 5 stubs (EventRetentionJobTest, EventSnapshotServiceTest, CircleadConnectorIT) are in fabric-core and fabric-connectors and will be implemented in plans 02–04.

## Threat Surface

No new network endpoints introduced. The /actuator/health sub-keys (ageGraph, connectors) are protected by the existing `show-details: when-authorized` configuration in application.yml. Threat mitigations T-05-01-02 (ConnectorHealthIndicator details gated by auth) and T-05-01-04 (Gauge DB queries bounded by HikariCP) are implemented as designed.

## Self-Check: PASSED
