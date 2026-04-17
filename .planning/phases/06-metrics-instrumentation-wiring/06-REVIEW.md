---
phase: 06-metrics-instrumentation-wiring
reviewed: 2026-04-17T12:00:00Z
depth: standard
files_reviewed: 7
files_reviewed_list:
  - fabric-core/src/main/java/dev/tessera/core/metrics/MetricsPort.java
  - fabric-app/src/main/java/dev/tessera/app/metrics/TesseraMetricsAdapter.java
  - fabric-app/src/test/java/dev/tessera/app/metrics/TesseraMetricsAdapterTest.java
  - fabric-core/src/test/java/dev/tessera/core/validation/ShaclValidatorMetricsTest.java
  - fabric-core/src/main/java/dev/tessera/core/validation/ShaclValidator.java
  - fabric-rules/src/main/java/dev/tessera/rules/RuleEngine.java
  - fabric-connectors/src/main/java/dev/tessera/connectors/internal/ConnectorRunner.java
findings:
  critical: 0
  warning: 2
  info: 2
  total: 4
status: issues_found
---

# Phase 6: Code Review Report

**Reviewed:** 2026-04-17T12:00:00Z
**Depth:** standard
**Files Reviewed:** 7
**Status:** issues_found

## Summary

This phase introduces a `MetricsPort` SPI in fabric-core that decouples metrics emission from the Micrometer implementation in fabric-app. The architecture is clean -- the dependency direction is correct (fabric-app implements the port; core/rules/connectors only depend on the interface). The adapter is thin, the tests cover delegation and null-safety, and the null-guarding pattern at call sites prevents NPEs when the port is absent.

Two warnings were found: one potential NullPointerException in `RuleEngine.run()` when the mutation payload is null, and a missing `recordIngest()` call for the entity-resolution merge path in `ConnectorRunner`. Two info-level items note minor test and code quality observations.

## Warnings

### WR-01: RuleEngine.run() does not guard against null payload

**File:** `fabric-rules/src/main/java/dev/tessera/rules/RuleEngine.java:69`
**Issue:** Line 69 creates a `LinkedHashMap` from `ctx.mutation().payload()`. If `payload()` returns `null`, this throws a `NullPointerException`. The `GraphMutation` record allows null payloads (the `ShaclValidator.buildDataGraph` method explicitly null-checks `m.payload()` at line 107), so `RuleEngine` should do the same. This is a pre-existing bug that is not introduced by the metrics changes, but it is in a changed file and sits on the hot path right next to the new metrics call.
**Fix:**
```java
Map<String, Object> properties = new LinkedHashMap<>(
        ctx.mutation().payload() != null ? ctx.mutation().payload() : Map.of());
```

### WR-02: ConnectorRunner does not record ingest metric on entity-resolution merge path

**File:** `fabric-connectors/src/main/java/dev/tessera/connectors/internal/ConnectorRunner.java:268-289`
**Issue:** When an extraction candidate resolves to an existing node via `ResolutionResult.Match`, the `handleExtractionResolution` method calls `graphService.apply(mutation)` and checks for `Committed`, but never calls `metricsPort.recordIngest()`. The normal apply path at line 134 does record ingest. This means successfully merged entities from the entity-resolution path are silently uncounted in the `tessera.ingest.rate` metric, creating a blind spot in operational monitoring.
**Fix:** Add the ingest metric recording after the committed check inside `handleExtractionResolution`:
```java
if (outcome instanceof GraphMutationOutcome.Committed committed) {
    if (metricsPort != null) metricsPort.recordIngest();
    if (embeddingService != null) {
        storeEmbeddingSafe(committed.nodeUuid(), instance, candidate);
    }
}
```

## Info

### IN-01: Test uses NullPointerException-prone pattern on registry.find()

**File:** `fabric-app/src/test/java/dev/tessera/app/metrics/TesseraMetricsAdapterTest.java:46`
**Issue:** Each test method calls `registry.find("...").counter().count()` or `.timer().count()` without null-checking the intermediate result. If the metric name were misspelled or registration order changed, `counter()` / `timer()` would return null and the test would fail with an unhelpful NPE rather than a descriptive assertion error. This is acceptable for current test stability since the names are correct, but wrapping with `assertThat(registry.find(...).counter()).isNotNull()` as a precondition would improve debuggability if metric names drift.
**Fix:** Consider adding a precondition assertion or using `Objects.requireNonNull` with a message, e.g.:
```java
Counter counter = registry.find("tessera.ingest.rate").counter();
assertThat(counter).as("tessera.ingest.rate counter must be registered").isNotNull();
```

### IN-02: ShaclValidator metrics timing skips measurement when metricsPort is null

**File:** `fabric-core/src/main/java/dev/tessera/core/validation/ShaclValidator.java:79`
**Issue:** When `metricsPort` is null, `start` is set to `0L` and `System.nanoTime()` is never called. This is intentional and correct -- it avoids unnecessary `nanoTime` calls in non-instrumented contexts. Noting for documentation purposes that this means the timing overhead is truly zero when metrics are disabled, which is a good pattern.
**Fix:** No action needed. This is a positive observation.

---

_Reviewed: 2026-04-17T12:00:00Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
