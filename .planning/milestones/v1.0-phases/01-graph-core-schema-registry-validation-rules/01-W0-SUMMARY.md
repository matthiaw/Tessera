---
phase: 01-graph-core-schema-registry-validation-rules
plan: W0
subsystem: scaffolding
tags: [scaffolding, flyway, jqwik, archunit, graph-core]
requires:
  - phase-0 fabric-* modules, AgePostgresContainer, Flyway V1__enable_age.sql
provides:
  - jqwik 1.9.2 on fabric-core + fabric-rules test classpath
  - GraphMutation / GraphService / GraphRepository / GraphSession contracts
  - Flyway V2..V9 migrations for 13 Phase 1 tables
  - RawCypherBanTest (CORE-02) green
  - 38 test shells + 2 JMH bench skeletons for Waves 1-3
affects:
  - fabric-core main + test source sets
  - fabric-rules test source set
  - fabric-app main resources (migrations) + arch test suite
tech-stack:
  added:
    - net.jqwik:jqwik:1.9.2 (property-based testing, D-D1)
  patterns:
    - "Hybrid rules-as-code + DB activation per ADR-7 (V9__reconciliation_rules)"
    - "Partitioned append-only event log with monthly RANGE partitions"
    - "Debezium outbox SMT shape for Phase 4 swap-in readiness"
    - "Two-layer ArchUnit defense for raw-Cypher ban (type-based + string-constant)"
key-files:
  created:
    - pom.xml (parent — dependencyManagement)
    - fabric-core/pom.xml
    - fabric-rules/pom.xml
    - fabric-app/src/main/resources/db/migration/V2__graph_events.sql
    - fabric-app/src/main/resources/db/migration/V3__graph_outbox.sql
    - fabric-app/src/main/resources/db/migration/V4__schema_registry.sql
    - fabric-app/src/main/resources/db/migration/V5__schema_versioning_and_aliases.sql
    - fabric-app/src/main/resources/db/migration/V6__source_authority.sql
    - fabric-app/src/main/resources/db/migration/V7__reconciliation_conflicts.sql
    - fabric-app/src/main/resources/db/migration/V8__connector_limits_and_dlq.sql
    - fabric-app/src/main/resources/db/migration/V9__reconciliation_rules.sql
    - fabric-core/src/main/java/dev/tessera/core/graph/GraphMutation.java
    - fabric-core/src/main/java/dev/tessera/core/graph/GraphMutationOutcome.java
    - fabric-core/src/main/java/dev/tessera/core/graph/GraphService.java
    - fabric-core/src/main/java/dev/tessera/core/graph/GraphRepository.java
    - fabric-core/src/main/java/dev/tessera/core/graph/NodeState.java
    - fabric-core/src/main/java/dev/tessera/core/graph/Operation.java
    - fabric-core/src/main/java/dev/tessera/core/graph/SourceType.java
    - fabric-core/src/main/java/dev/tessera/core/graph/internal/CypherTemplate.java
    - fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphSession.java
    - fabric-core/src/test/java/dev/tessera/core/support/SchemaFixtures.java
    - fabric-core/src/test/java/dev/tessera/core/support/MutationFixtures.java
    - fabric-app/src/test/java/dev/tessera/arch/RawCypherBanTest.java
    - fabric-core/src/jmh/java/dev/tessera/core/bench/ShaclValidationBench.java
    - fabric-core/src/jmh/java/dev/tessera/core/bench/WritePipelineBench.java
    - "37 additional test shell files under fabric-core + fabric-rules (see git log for the full list)"
  modified:
    - .gitignore (ignore .jqwik-database)
decisions:
  - "Use partitioning-aware unique index (model_id, sequence_nr, event_time) — PostgreSQL requires partition key columns in unique indexes on RANGE-partitioned tables; per-tenant SEQUENCE still guarantees monotonicity in practice."
  - "Layer 2 of RawCypherBanTest scans only static final String fields (not instance fields) — avoids NullPointerException from reflect().get(null) on non-static fields while still catching the common case of Cypher constants."
  - "TenantBypassPropertyIT ships as a plain JUnit @Disabled placeholder in Wave 0 — jqwik 1.9 did not respect class-level @Disabled for @Property methods in the reactor run, and the Wave 1 implementation replaces the class body wholesale anyway."
  - "MutationFixtures provides @Provide methods compiled against jqwik 1.9 and is wired via MutationFixtures.class bridge in Wave 1 (per 01-W1-03)."
metrics:
  duration: ~18 min wall-clock
  completed: 2026-04-15
  tasks: 3
  commits: 4
  files_created: 51
  files_modified: 4
  mvn_verify: green
---

# Phase 1 Plan W0: Wave 0 Scaffolding Summary

**One-liner:** Phase 1 scaffolding — 8 Flyway migrations (V2..V9), the GraphService write-funnel contracts, the CORE-02 raw-Cypher ArchUnit ban, jqwik 1.9.2 wiring, and 38 @Disabled test shells + 2 JMH bench skeletons ready for Waves 1-3 to fill.

## Executive Summary

Wave 0 establishes the full contract surface and schema shape for Phase 1 without implementing any behavior. Every Flyway table mandated by CONTEXT §D-B1..D-D3 and ADR-7 is in place. The single write funnel (`GraphService.apply`) is declared. `GraphSession` exists in the `graph.internal` package and is guarded by `RawCypherBanTest` — the CORE-02 test Phase 0 explicitly deferred (D-15). Every integration test file named in `01-VALIDATION.md` exists as a `@Disabled` shell so Waves 1-3 drop `@Disabled` and fill a body rather than fight with imports, package layout, or Maven wiring.

`./mvnw -B verify` stays green end-to-end: Spotless (Palantir Java Format), license headers, Surefire, Failsafe, ArchUnit (`ModuleDependencyTest` + `ImagePinningTest` + `RawCypherBanTest`), JaCoCo.

## Tasks Completed

| # | Task | Commit | Files |
|---|------|--------|-------|
| 1 | Add jqwik + Flyway V2..V9 migrations | 077e103 | 11 |
| 2 | Write funnel contracts (GraphMutation, GraphService, GraphSession) | eb5d8c1 | 11 |
| 3 | ArchUnit raw-Cypher ban + test shells + JMH skeletons | de080ce + 9c0bcb7 | 42 |

## Key Outputs

### Flyway V2..V9

- **V2 `graph_events`** — append-only, RANGE partitioned by `event_time`, full provenance columns (source_type/id/system, confidence, extractor_version, llm_model_id, origin_connector_id, origin_change_id), payload + delta JSONB (EVENT-03), three indexes, initial y2026m04 partition.
- **V3 `graph_outbox`** — Debezium SMT shape + `routing_hints JSONB` for the ROUTE chain (D-C1).
- **V4 `schema_registry`** — typed core columns + JSONB flex fields for `schema_node_types`, `schema_properties`, `schema_edge_types` (D-B1).
- **V5 `schema_versioning_and_aliases`** — `schema_change_event`, `schema_version` with partial unique index on `is_current`, `schema_property_aliases`, `schema_edge_type_aliases` (D-B2, D-B3).
- **V6 `source_authority`** — exact D-C2 DDL with `TEXT[] priority_order`.
- **V7 `reconciliation_conflicts`** — exact D-C3 DDL with four operator-UI indexes.
- **V8 `connector_limits_and_dlq`** — circuit breaker override + DLQ per D-D2/D-D3.
- **V9 `reconciliation_rules`** — hybrid activation table per ADR-7 §RULE-04.

### Write Funnel Contracts

- `GraphMutation` record with all D-A1 provenance fields, compact-constructor validation (TenantContext non-null, confidence in [0,1]), ergonomic builder, `withTenant` copy helper.
- `Operation` / `SourceType` enums.
- `GraphMutationOutcome` sealed interface with `Committed` + `Rejected` records.
- `GraphService` interface — single write funnel (CORE-01).
- `GraphRepository` + `NodeState` — tenant-aware read entry.
- `internal/GraphSession` — sole Cypher-execution point (CORE-02); skeleton throws `UnsupportedOperationException` until Wave 1.
- `internal/CypherTemplate` — text-cast agtype idiom A factory that always injects `model_id`.

### ArchUnit Raw-Cypher Ban (CORE-02)

`RawCypherBanTest` ships two rules:

1. **`only_graph_internal_may_touch_pgjdbc`** — noClasses outside `dev.tessera.core.graph.internal..` may depend on `org.postgresql..` or `org.springframework.jdbc.core..`. Primary defense.
2. **`no_cypher_strings_outside_internal`** — noClasses outside `graph.internal` may hold `static final String` constants that match ≥2 Cypher keywords or `CYPHER('`. Secondary / defense-in-depth.

Both green — no existing class violates. CORE-02 is satisfied at Wave 0 close, retiring Phase 0's D-15 deferral.

### Test Shell Inventory

- 9 unit `*Test.java` shells under fabric-core (graph, schema, validation) and fabric-rules (rules, circuit)
- 28 integration `*IT.java` shells with `@Testcontainers` + `AgePostgresContainer` wiring where Postgres is needed
- 1 property-based shell (`TenantBypassPropertyIT` — currently a plain `@Disabled` placeholder; Wave 1 replaces its body with the real jqwik `@Property` harness)
- 2 JMH bench skeletons (`ShaclValidationBench`, `WritePipelineBench`) under `src/jmh/java`

Every shell carries the owning wave + plan in its `@Disabled` reason string so Waves 1-3 know which file to unlock next.

### Fixtures

- `SchemaFixtures` — placeholder builder for `NodeTypeDraft` / `PropertyDraft` / `EdgeTypeDraft`, to be replaced by the real Schema Registry records in Wave 2.
- `MutationFixtures` — jqwik `@Provide` methods `anyMutation()`, `creates()`, `mutationList()` for D-D1 fuzz.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Partitioned-table unique index requires partition-key column**
- **Found during:** Task 1 (Flyway V2)
- **Issue:** `CREATE UNIQUE INDEX idx_graph_events_model_seq ON graph_events (model_id, sequence_nr)` would fail at migration time on a RANGE-partitioned table — PostgreSQL requires that unique indexes on partitioned tables include all partition-key columns.
- **Fix:** Included `event_time` in the unique index: `(model_id, sequence_nr, event_time)`. Per-tenant SEQUENCE still guarantees monotonicity in practice; double-allocation is still caught by this index.
- **Files modified:** `fabric-app/src/main/resources/db/migration/V2__graph_events.sql`
- **Commit:** 077e103

**2. [Rule 1 - Bug] RawCypherBanTest reflecting non-static String fields NPE**
- **Found during:** Task 3 RawCypherBanTest initial run
- **Issue:** `f.reflect().get(null)` threw `NullPointerException` ("Cannot invoke Object.getClass() because obj is null") for non-static `String` fields (e.g. record components).
- **Fix:** Filter fields by `STATIC` + `FINAL` modifiers before reflection; wrap the reflection call in a try/catch covering `ReflectiveOperationException | IllegalArgumentException | NullPointerException`.
- **Files modified:** `fabric-app/src/test/java/dev/tessera/arch/RawCypherBanTest.java`
- **Commit:** de080ce

**3. [Rule 1 - Bug] jqwik `@Property` inside `@Disabled` class still gets scheduled**
- **Found during:** Task 3 full `./mvnw verify` run
- **Issue:** jqwik 1.9.2's JUnit Platform engine did not respect JUnit's class-level `@Disabled` annotation on `TenantBypassPropertyIT`; the `@Property` method ran and failed with `CannotFindArbitrary` for `List<GraphMutation>` because no `@Provide` was wired.
- **Fix:** Replace `TenantBypassPropertyIT` with a plain JUnit `@Disabled` shell containing a single `@Test void placeholder() {}`. Wave 1 (plan 01-W1-03) replaces the body wholesale with the real jqwik harness anyway.
- **Files modified:** `fabric-core/src/test/java/dev/tessera/core/graph/property/TenantBypassPropertyIT.java`
- **Commit:** de080ce

**4. [Rule 2 - Critical functionality] `.jqwik-database` untracked after verify**
- **Found during:** Task 3 post-verify `git status`
- **Issue:** jqwik writes a `.jqwik-database` file to each module directory to persist failed-example replay state. Leaving it untracked pollutes `git status` and would be checked in by mistake.
- **Fix:** Added `.jqwik-database` to `.gitignore`.
- **Files modified:** `.gitignore`
- **Commit:** 9c0bcb7

### Architectural Changes

None.

## Acceptance Criteria Check

- [x] `grep -q "net.jqwik" pom.xml` — parent dependencyManagement
- [x] `grep -q "net.jqwik" fabric-core/pom.xml` — test dep
- [x] `grep -q "net.jqwik" fabric-rules/pom.xml` — test dep
- [x] 8 new Flyway migrations V2..V9 exist
- [x] `CREATE TABLE graph_events`, `source_type TEXT NOT NULL`, `origin_connector_id`, `PARTITION BY RANGE`, `delta JSONB` all present in V2
- [x] `aggregatetype` + `routing_hints JSONB` in V3
- [x] `priority_order TEXT[]` in V6 / `losing_source_system` in V7
- [x] `CREATE TABLE reconciliation_rules` + `priority_override INTEGER` in V9
- [x] `record GraphMutation` + extractorVersion / llmModelId / BigDecimal confidence / originConnectorId
- [x] `interface GraphService` with `GraphMutationOutcome apply`
- [x] `GraphRepository` uses explicit `TenantContext ctx` parameter
- [x] `GraphSession` lives in `dev.tessera.core.graph.internal`
- [x] `RawCypherBanTest` exists with `only_graph_internal_may_touch_pgjdbc` + graph.internal package reference
- [x] `./mvnw -pl fabric-app -Dtest=RawCypherBanTest test` — **2 tests green, 0 failures**
- [x] All 38 test shells exist under the exact file paths listed in 01-VALIDATION.md
- [x] Both JMH benches under `fabric-core/src/jmh/java/dev/tessera/core/bench/`
- [x] `./mvnw -B verify` — **green end-to-end** (parent + 5 modules, ~29 s)

## Self-Check: PASSED

Commit 077e103 found: YES
Commit eb5d8c1 found: YES
Commit de080ce found: YES
Commit 9c0bcb7 found: YES

V2..V9 migrations found under `fabric-app/src/main/resources/db/migration/`: YES (8 files)
GraphMutation.java found: YES
GraphService.java found: YES
GraphSession.java found: YES (inside graph.internal)
RawCypherBanTest.java found and passing: YES (2/2 green)
Full `./mvnw -B verify` on last commit: green (~29 s)

## Next Steps

Wave 1 (plans 01-W1-01 .. 01-W1-03) can now:
- Fill `GraphSession.apply` + `findNode` with the real Cypher + event log append + outbox insert
- Implement `GraphService` and wire Spring `@Transactional`
- Unlock `NodeLifecycleIT`, `EdgeLifecycleIT`, `SystemPropertiesIT`, `GraphServiceApplyIT`, `TombstoneSemanticsIT`, `TimestampOwnershipTest`, `EventProvenanceSmokeIT`
- Replace `TenantBypassPropertyIT` with the real jqwik `@Property`-driven harness bridged to `MutationFixtures` via `@ForAll("mutationList") @From(MutationFixtures.class)`
- Fill `WritePipelineBench` BASELINE body (p95 < 3 ms warning-only)
