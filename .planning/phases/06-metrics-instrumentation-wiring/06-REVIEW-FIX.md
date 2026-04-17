---
phase: 06-metrics-instrumentation-wiring
fixed_at: 2026-04-17T12:10:00Z
review_path: .planning/phases/06-metrics-instrumentation-wiring/06-REVIEW.md
iteration: 1
findings_in_scope: 2
fixed: 2
skipped: 0
status: all_fixed
---

# Phase 6: Code Review Fix Report

**Fixed at:** 2026-04-17T12:10:00Z
**Source review:** .planning/phases/06-metrics-instrumentation-wiring/06-REVIEW.md
**Iteration:** 1

**Summary:**
- Findings in scope: 2
- Fixed: 2
- Skipped: 0

## Fixed Issues

### WR-01: RuleEngine.run() does not guard against null payload

**Files modified:** `fabric-rules/src/main/java/dev/tessera/rules/RuleEngine.java`
**Commit:** 20fe238
**Applied fix:** Wrapped `ctx.mutation().payload()` in a null-coalescing ternary that falls back to `Map.of()` when payload is null. This prevents a NullPointerException when `GraphMutation` carries a null payload, consistent with the null-check pattern already used in `ShaclValidator.buildDataGraph`.

### WR-02: ConnectorRunner does not record ingest metric on entity-resolution merge path

**Files modified:** `fabric-connectors/src/main/java/dev/tessera/connectors/internal/ConnectorRunner.java`
**Commit:** ef7db27
**Applied fix:** Added `metricsPort.recordIngest()` call (with null guard) inside the `handleExtractionResolution` method when the outcome is `Committed`. The embedding service check was also separated into its own `if` block to keep the metric recording unconditional for all committed outcomes on the merge path. This ensures entity-resolution merges are counted in the `tessera.ingest.rate` metric alongside normal applies.

---

_Fixed: 2026-04-17T12:10:00Z_
_Fixer: Claude (gsd-code-fixer)_
_Iteration: 1_
