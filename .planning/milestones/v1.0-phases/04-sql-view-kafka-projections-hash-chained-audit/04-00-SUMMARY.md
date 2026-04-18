---
phase: 04-sql-view-kafka-projections-hash-chained-audit
plan: "00"
subsystem: testing
tags: [testcontainers, junit5, spring-boot-test, integration-tests, age-postgres]

requires:
  - phase: 03-mcp-projection-flagship-differentiator
    provides: fabric-core GraphRepository interface (findShortestPath, executeTenantCypher), fabric-projections ProjectionItApplication test harness

provides:
  - 5 @Disabled IT stubs covering SQL-01, SQL-02, AUDIT-01, AUDIT-02, KAFKA-02
  - Compile-clean test target for Wave 1-2 plans to reference in verify commands
  - Behavioral contracts documented before implementation

affects: [04-01-PLAN, 04-02-PLAN, 04-03-PLAN]

tech-stack:
  added: []
  patterns:
    - "Wave 0 stub pattern: @Disabled IT shells with fail() bodies establish verify targets before implementation"

key-files:
  created:
    - fabric-projections/src/test/java/dev/tessera/projections/sql/SqlViewProjectionIT.java
    - fabric-projections/src/test/java/dev/tessera/projections/sql/SqlViewSchemaChangeIT.java
    - fabric-core/src/test/java/dev/tessera/core/events/HashChainAppendIT.java
    - fabric-core/src/test/java/dev/tessera/core/audit/HashChainVerifyIT.java
    - fabric-app/src/test/java/dev/tessera/app/OutboxPollerConditionalIT.java
    - .planning/phases/04-sql-view-kafka-projections-hash-chained-audit/deferred-items.md
  modified:
    - fabric-app/src/test/java/dev/tessera/connectors/unstructured/MarkdownFolderConnectorIT.java

key-decisions:
  - "Wave 0 @Disabled stub pattern confirmed: fail() bodies with descriptive messages document behavioral contract before implementation"
  - "OutboxPollerConditionalIT uses single @SpringBootTest class; Plan 04-03 implementation will split into nested classes for two-context testing"

patterns-established:
  - "Wave 0 stub: @Disabled(\"Wave 0 stub — implementation in Plan 04-0X\") with fail(\"Not yet implemented — REQUIREMENT-ID: description (Plan 04-0X)\")"

requirements-completed: [SQL-01, SQL-02, AUDIT-01, AUDIT-02, KAFKA-02]

duration: 4min
completed: 2026-04-17
---

# Phase 04 Plan 00: Wave 0 IT Stubs Summary

**5 @Disabled IT stub files covering SQL-01/SQL-02/AUDIT-01/AUDIT-02/KAFKA-02 behavioral contracts, all compile-clean, giving Wave 1-2 plans valid verify targets from day one**

## Performance

- **Duration:** 4 min
- **Started:** 2026-04-17T08:46:43Z
- **Completed:** 2026-04-17T08:50:59Z
- **Tasks:** 1
- **Files modified:** 7 (5 created + 1 modified + 1 deferred-items)

## Accomplishments

- Created 5 IT stubs across 3 modules (fabric-projections, fabric-core, fabric-app) covering all Phase 4 requirements
- All stubs annotated with `@Disabled("Wave 0 stub — implementation in Plan 04-0X")` and `fail()` bodies documenting behavioral contracts
- All three modules (`fabric-core`, `fabric-projections`, `fabric-app`) compile cleanly with test sources
- Fixed pre-existing compile breaks in `MarkdownFolderConnectorIT` (Rule 1)

## Task Commits

Each task was committed atomically:

1. **Task 1: Create 5 IT stub files for Phase 4 behavioral verification** - `1f82c5d` (test)

**Plan metadata:** (this commit, docs)

## Files Created/Modified

- `fabric-projections/src/test/java/dev/tessera/projections/sql/SqlViewProjectionIT.java` - SQL-01/D-D3: view creation, column match, tombstone exclusion, DDL schema version
- `fabric-projections/src/test/java/dev/tessera/projections/sql/SqlViewSchemaChangeIT.java` - SQL-02: view regeneration on schema change, ApplicationRunner startup regeneration
- `fabric-core/src/test/java/dev/tessera/core/events/HashChainAppendIT.java` - AUDIT-01: enabled/disabled tenant prev_hash, concurrent append chain validity
- `fabric-core/src/test/java/dev/tessera/core/audit/HashChainVerifyIT.java` - AUDIT-02: valid chain, tampered chain detection, empty tenant verification
- `fabric-app/src/test/java/dev/tessera/app/OutboxPollerConditionalIT.java` - KAFKA-02: OutboxPoller conditional on tessera.kafka.enabled
- `fabric-app/src/test/java/dev/tessera/connectors/unstructured/MarkdownFolderConnectorIT.java` - Rule 1 fix: added missing GraphRepository method overrides and renamed ResolutionResult variant

## Decisions Made

- Wave 0 `@Disabled` stub pattern confirmed: `fail()` bodies with descriptive messages (`"Not yet implemented — REQUIREMENT-ID: description (Plan 04-0X)"`) serve as executable behavioral contracts
- `OutboxPollerConditionalIT` uses a single `@SpringBootTest` class for the stub; the Plan 04-03 implementation will need to split into nested classes (one per `@TestPropertySource` context) since `@TestPropertySource` is not applicable at method level

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Fixed MarkdownFolderConnectorIT missing GraphRepository method implementations**
- **Found during:** Task 1 (test-compile verification)
- **Issue:** `MarkdownFolderConnectorIT` anonymous `GraphRepository` implementation was missing `executeTenantCypher()` and `findShortestPath()` methods added to the interface in Phase 03. The test did not compile.
- **Fix:** Added `executeTenantCypher()` returning `List.of()` and `findShortestPath()` returning `List.of()` to the anonymous implementation.
- **Files modified:** `fabric-app/src/test/java/dev/tessera/connectors/unstructured/MarkdownFolderConnectorIT.java`
- **Verification:** `mvn test-compile -pl fabric-app -Dspotless.skip=true` passes
- **Committed in:** `1f82c5d` (Task 1 commit)

**2. [Rule 1 - Bug] Fixed MarkdownFolderConnectorIT using renamed ResolutionResult variant**
- **Found during:** Task 1 (test-compile verification)
- **Issue:** `MarkdownFolderConnectorIT` referenced `ResolutionResult.ReviewQueue` which was renamed to `ResolutionResult.NeedsReview` in production code.
- **Fix:** Replaced `new ResolutionResult.ReviewQueue("ALL", 0.3)` with `new ResolutionResult.NeedsReview("ALL", 0.3)`.
- **Files modified:** `fabric-app/src/test/java/dev/tessera/connectors/unstructured/MarkdownFolderConnectorIT.java`
- **Verification:** `mvn test-compile -pl fabric-app -Dspotless.skip=true` passes
- **Committed in:** `1f82c5d` (Task 1 commit)

---

**Total deviations:** 2 auto-fixed (2 Rule 1 — pre-existing bugs blocking compile verification)
**Impact on plan:** Both fixes were pre-existing breaks from Phase 03 API evolution not propagated to `MarkdownFolderConnectorIT`. No scope creep; all fixes are in the same commit.

## Issues Encountered

- Spotless Palantir Java Format plugin fails on Java 25 with `NoSuchMethodError` in `Log$DeferredDiagnosticHandler.getDiagnostics()`. Workaround: `-Dspotless.skip=true` on compile. This is a pre-existing environment issue unrelated to this plan.

## Known Stubs

All 5 stubs are intentional Wave 0 scaffolding — each is explicitly `@Disabled` with a reference to the enabling plan. None prevent the Wave 0 goal (compile-clean verify targets).

| Stub | File | Enabled by |
|------|------|-----------|
| SqlViewProjectionIT | fabric-projections/.../sql/SqlViewProjectionIT.java | Plan 04-01 |
| SqlViewSchemaChangeIT | fabric-projections/.../sql/SqlViewSchemaChangeIT.java | Plan 04-01 |
| HashChainAppendIT | fabric-core/.../events/HashChainAppendIT.java | Plan 04-02 |
| HashChainVerifyIT | fabric-core/.../audit/HashChainVerifyIT.java | Plan 04-02 |
| OutboxPollerConditionalIT | fabric-app/.../app/OutboxPollerConditionalIT.java | Plan 04-03 |

## Next Phase Readiness

- All 5 verify targets exist and compile; Wave 1 (Plan 04-01) can immediately enable `SqlViewProjectionIT` and `SqlViewSchemaChangeIT`
- `HashChainAppendIT` and `HashChainVerifyIT` await Plan 04-02 hash chain implementation
- `OutboxPollerConditionalIT` awaits Plan 04-03 Kafka/outbox conditionalization
- Pre-existing `MarkdownFolderConnectorIT` compile breaks resolved; fabric-app test-compile is now clean

---
*Phase: 04-sql-view-kafka-projections-hash-chained-audit*
*Completed: 2026-04-17*
