---
phase: 08-circlead-production-wiring-dr-drill-fix
plan: "01"
subsystem: fabric-connectors
tags: [circlead, connectors, placeholder-resolution, db-registration, scheduler-wiring]
dependency_graph:
  requires: []
  provides: [CIRC-01, CIRC-02]
  affects: [ConnectorRegistry, ConnectorScheduler, CircleadConnectorConfig]
tech_stack:
  added: []
  patterns:
    - "Environment.resolvePlaceholders() to fix Spring ${...} in @Bean values"
    - "@PostConstruct with loadAndResolveMappings() helper to avoid circular-bean-creation"
    - "@DependsOn for deterministic @PostConstruct ordering across Spring components"
    - "ApplicationContextRunner with mocked NamedParameterJdbcTemplate for integration tests"
key_files:
  created:
    - fabric-connectors/src/test/java/dev/tessera/connectors/circlead/CircleadPlaceholderResolutionTest.java
    - fabric-connectors/src/test/java/dev/tessera/connectors/circlead/CircleadSchedulerWiringIT.java
  modified:
    - fabric-connectors/src/main/java/dev/tessera/connectors/circlead/CircleadConnectorConfig.java
    - fabric-connectors/src/main/java/dev/tessera/connectors/internal/ConnectorRegistry.java
    - fabric-app/src/main/resources/application.yml
decisions:
  - "loadAndResolveMappings() private helper instead of calling @Bean methods in @PostConstruct — avoids 'bean currently in creation' circular reference error that occurs when Spring proxies intercept self-invocation of @Bean methods on a @Configuration class during its own initialization"
  - "@DependsOn on ConnectorRegistry using the string bean name 'circleadConnectorConfig' rather than a typed dependency — ConnectorRegistry lives in fabric-connectors.internal, CircleadConnectorConfig in .circlead; avoids a compile-time coupling"
  - "ON CONFLICT DO NOTHING without a named UNIQUE constraint — connectors table has no natural unique key on (model_id, type, sourceEntityType); idempotence is achieved by never inserting duplicates rather than by an upsert key"
metrics:
  duration_minutes: 30
  completed_date: "2026-04-17"
  tasks_completed: 3
  files_changed: 5
---

# Phase 08 Plan 01: CircleadConnectorConfig Placeholder Resolution + DB Registration Summary

**One-liner:** Spring `${...}` placeholder resolution via `Environment.resolvePlaceholders()` + `@PostConstruct` DB upsert with `@DependsOn` startup ordering guarantee for circlead connectors.

## What Was Built

Fixed two production bugs in the circlead connector wiring path:

1. **CIRC-02 — Placeholder resolution:** `CircleadConnectorConfig` now injects `Environment` and calls `env.resolvePlaceholders(raw.sourceUrl())` before returning each `MappingDefinition` bean. `URI.create()` in `GenericRestPollerConnector.poll()` will never receive a raw `${...}` string.

2. **CIRC-01 — DB registration:** A `@PostConstruct` method (`registerCircleadConnectors`) upserts the three circlead connector rows into the `connectors` table using `ON CONFLICT DO NOTHING`. `ConnectorRegistry.loadAll()` now finds these rows.

3. **Startup ordering:** `ConnectorRegistry` gained `@DependsOn("circleadConnectorConfig")` to guarantee the upsert completes before `loadAll()` queries the table.

4. **Config:** `tessera.connectors.circlead.*` keys added to `application.yml` with env-var overrides (`TESSERA_CIRCLEAD_BASE_URL`, `TESSERA_CIRCLEAD_MODEL_ID`, `TESSERA_CIRCLEAD_CREDENTIALS_REF`).

## Commits

| Hash | Type | Description |
|------|------|-------------|
| f59c43e | feat | CircleadConnectorConfig placeholder resolution + DB registration |
| 9927027 | test | Unit test: CircleadPlaceholderResolutionTest |
| 1e4320d | test | Integration test: CircleadSchedulerWiringIT (CIRC-01) |

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Circular bean creation in @PostConstruct**

- **Found during:** Task 3 (integration test execution)
- **Issue:** `registerCircleadConnectors()` called `circleadRoleMapping()` / `circleadCircleMapping()` / `circleadActivityMapping()` — the Spring CGLIB proxy on `@Configuration` classes intercepts self-invocation to enforce singleton semantics. During `@PostConstruct`, the bean is still "in creation", so Spring threw: `Error creating bean with name 'circleadConnectorConfig': Requested bean is currently in creation`.
- **Fix:** Extracted `loadAndResolveMappings()` private helper that reads the three classpath resources and calls `withResolvedUrl()` directly (bypassing the Spring bean factory). The `@Bean` methods continue to use the same logic; the `@PostConstruct` uses the helper.
- **Files modified:** `CircleadConnectorConfig.java`
- **Commit:** 1e4320d

## Threat Surface Scan

The `@PostConstruct` upsert inserts rows with `credentialsRef` sourced from `TESSERA_CIRCLEAD_CREDENTIALS_REF` env var (defaults to `vault:secret/tessera/circlead/api-token`). This is consistent with the existing connector credential model (T-08-02 already accepted in the threat model — env-var override prevents hardcoding).

No new trust boundaries introduced beyond those in the plan's threat model.

## Self-Check: PASSED

| Item | Status |
|------|--------|
| CircleadPlaceholderResolutionTest.java | FOUND |
| CircleadSchedulerWiringIT.java | FOUND |
| 08-01-SUMMARY.md | FOUND |
| Commit f59c43e (feat) | FOUND |
| Commit 9927027 (test unit) | FOUND |
| Commit 1e4320d (test IT) | FOUND |
