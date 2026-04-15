---
phase: 02-rest-projection-connector-framework-first-connector-security-baseline
plan: W0
subsystem: spike + phase-1-cleanup
status: complete
spike_result: PASSED
tags:
  - spike
  - springdoc
  - openapi
  - rule-engine
  - phase-1-cleanup
dependency_graph:
  requires:
    - Phase 1 rule engine + source authority matrix + reconciliation conflicts table (W1/W4)
  provides:
    - SpringDoc OpenApiCustomizer lifecycle proof for REST-05 (Wave 2 commits to this pattern)
    - ExposedTypeSource SPI that Wave 1/Wave 2 binds to SchemaRegistry
    - Production-funnel firing of RULE-05 / RULE-06 (authority reconciliation)
  affects:
    - fabric-core (GraphServiceImpl.apply signature usage)
    - fabric-rules (ChainExecutor labelling contract)
    - fabric-projections (bootstrap — first non-empty src tree)
tech-stack:
  added:
    - "springdoc-openapi-starter-webmvc-ui 2.8.6 (fabric-projections, managed via parent BOM)"
    - "spring-boot-starter-web 3.5.13 (fabric-projections)"
    - "snakeyaml 2.5 (pinned upward in parent for dependencyConvergence)"
  patterns:
    - "GroupedOpenApi + OpenApiCustomizer pulling from ExposedTypeSource SPI"
    - "springdoc.cache.disabled=true for per-hit doc rebuild"
    - "Branch detection via losingSourceSystem vs mutation.sourceSystem comparison"
key-files:
  created:
    - fabric-projections/src/main/java/dev/tessera/projections/rest/internal/ExposedTypeSource.java
    - fabric-projections/src/main/java/dev/tessera/projections/rest/internal/SpringDocDynamicSpike.java
    - fabric-projections/src/test/java/dev/tessera/projections/rest/SchemaVersionBumpIT.java
    - fabric-rules/src/test/java/dev/tessera/rules/authority/GraphServiceAuthorityThreadingIT.java
  modified:
    - pom.xml (snakeyaml upper-bound pin)
    - fabric-projections/pom.xml (starter-web + springdoc-starter)
    - fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphServiceImpl.java (deriveCurrentSourceSystemMap + thread into ruleEngine.run)
    - fabric-rules/src/main/java/dev/tessera/rules/internal/ChainExecutor.java (current-keeps vs incoming-wins branch detection)
    - fabric-rules/src/test/java/dev/tessera/rules/ChainExecutorTest.java (+2 branch tests)
decisions:
  - "Spike uses a test-owned MutableExposedTypeSource instead of wiring SchemaRegistry (SpringDoc lifecycle proof does not require a real DB slice; Wave 1/Wave 2 binds the SPI to SchemaRegistry)"
  - "GraphServiceAuthorityThreadingIT lives in fabric-rules/src/test, not fabric-core/src/test, because PipelineFixture wires the graph service there and fabric-core cannot depend on fabric-rules (ModuleDependencyTest forbids it)"
  - "currentSourceSystem map is derived from node-level _source stamp — every user-visible property slug maps to the same value. Per-property source tracking remains a phase-later concern; the fix restores RULE-05/06 firing through the funnel without changing stamping semantics."
metrics:
  duration_minutes: ~35
  tasks_completed: 2
  tasks_total: 2
  full_reactor_build: "BUILD SUCCESS (3:08 min, was 8:48 in Phase 1 verify)"
  tests_green:
    - "SchemaVersionBumpIT (1 test, fabric-projections)"
    - "ChainExecutorTest (10 tests, fabric-rules, +2 new)"
    - "GraphServiceAuthorityThreadingIT (2 tests, fabric-rules, new)"
    - "SourceAuthorityIT (1, no regression)"
    - "ConflictRegisterIT (1, no regression)"
    - "ArchUnit RawCypherBanTest + ModuleDependencyTest (7, no regression)"
  commits:
    - "ce3415a test(02-W0): SpringDoc dynamic OpenAPI spike — SchemaVersionBumpIT green"
    - "813202a fix(02-W0): close Phase 1 deviations — thread currentSourceSystem + fix ConflictRecord labels"
completed: 2026-04-15
---

# Phase 2 Plan W0: SpringDoc Dynamic Spike + Phase 1 Deviation Closure Summary

**One-liner:** SpringDoc OpenApiCustomizer runtime rebuild proven on `/v3/api-docs?group=entities` via `SchemaVersionBumpIT` (REST-05 gate PASSED), and Phase 1 Known Deviations #1 + #2 closed so authority-matrix reconciliation now fires through `graphService.apply` with correctly-labelled conflict rows on both branches.

## What was built

### Task 02-W0-01 — SpringDoc dynamic OpenAPI spike (GATE PASSED)

- **`ExposedTypeSource`** — pull-side SPI returning `List<ExposedType(modelSlug, typeSlug)>`. The spike does NOT wire directly to `SchemaRegistry`; that wiring is Wave 1/Wave 2's job. The spike's purpose is to prove SpringDoc lifecycle, not Schema Registry integration.
- **`SpringDocDynamicSpike`** — `@Profile("spike-openapi") @Configuration` publishing a `GroupedOpenApi("entities")` bean whose `OpenApiCustomizer` walks `ExposedTypeSource.currentlyExposed()` on every build and adds one `PathItem` per `(model, type)` keyed as `/api/v1/{model}/entities/{type}` with schemas namespaced `{model}_{slug}Entity` (RESEARCH Q1 cross-tenant collision pitfall).
- **`SchemaVersionBumpIT`** — `@SpringBootTest(webEnvironment=RANDOM_PORT)` with a self-contained `SpikeApp` that excludes `DataSourceAutoConfiguration` / `DataSourceTransactionManagerAutoConfiguration` / `HibernateJpaAutoConfiguration` (fabric-core transitively brings `spring-boot-starter-jdbc`; the spike has no DB). Property override `springdoc.cache.disabled=true` is load-bearing. Flow: clear the mutable source and assert path absent → `expose("spike-tenant","spike-type")` and assert path present → clear and assert path absent again. No restart.

**Empirical evidence the gate passed:** the failsafe log shows three distinct `Init duration for springdoc-openapi` entries (86 ms, 10 ms, 9 ms) — one per `/v3/api-docs/entities` hit, confirming that with `cache.disabled=true` the `OpenApiCustomizer` re-runs on every request. The runtime-flip branch (hit #2) asserts the path appears; the re-hide branch (hit #3) asserts it disappears. Both assertions pass.

Wave 2 can commit to `OpenApiCustomizer` as its REST-05 strategy. RESEARCH assumptions A1 and A7 (springdoc-openapi 2.8.x rebuilds on every hit when cache is disabled) are empirically confirmed against 2.8.6.

### Task 02-W0-02 — Phase 1 deviation closure

**Known Deviation #1 (`GraphServiceImpl.apply` threads `Map.of()` into rule engine):**

- Added `deriveCurrentSourceSystemMap(Map<String,Object> previousState)` in `GraphServiceImpl`. It reads the node-level `_source` stamp (Phase 1 W1 stamps this at node level, not per property) and returns a `Map<String,String>` mapping every user-visible property slug (non-`_`-prefixed) to that value. Empty on CREATE (where `previousState` is `Map.of()`).
- Threads the derived map as the 4th argument to `ruleEngine.run`. The old `Map.of()` literal is gone from the call site. `AuthorityReconciliationRule.findFirstContested` now has a populated `currentSourceSystem` to walk when the node has prior state, so RULE-05 / RULE-06 fire through the production funnel.

**Known Deviation #2 (`ChainExecutor` hardcodes `winningSourceSystem` to `ctx.mutation().sourceSystem()`):**

- Added branch detection in `ChainExecutor` around the `RuleOutcome.Override` handling: `boolean currentKeeps = ov.losingSourceSystem().equals(ctx.mutation().sourceSystem())`. When true, the incoming write lost and the winning source must come from `ctx.currentSourceSystem().get(propertySlug)`. When false, the incoming write won and the winning source is `ctx.mutation().sourceSystem()` (unchanged).
- `winningSourceId` is zeroed in the current-keeps branch; per-property source_id is not tracked in Phase 1 and can be added later without changing the label contract.

**New IT — `GraphServiceAuthorityThreadingIT`:**

- Placed in `fabric-rules/src/test` (not `fabric-core/src/test` as the original plan sketched) because `PipelineFixture` — which already wires `GraphServiceImpl` + `RuleEngine` + `SourceAuthorityMatrix` + `ReconciliationConflictsRepository` together against a real AGE container — lives in fabric-rules. Placing the IT in fabric-core would require fabric-core's test classpath to depend on fabric-rules, reversing the module dependency direction that `ModuleDependencyTest` forbids.
- Two cases, each driving `fixture.graphService.apply(...)` end-to-end:
  1. **Incoming-wins:** `CREATE` from low-authority source B → `UPDATE` from high-authority source A with a different value. Asserts the persisted `reconciliation_conflicts` row has `winning_source_system=A, losing_source_system=B, winning_value=VALUE_FROM_A, losing_value=VALUE_FROM_B`.
  2. **Current-keeps:** `CREATE` from high-authority source A → `UPDATE` from low-authority source B with a different value. Asserts the row has `winning_source_system=A` (NOT B, the incoming), `losing_source_system=B`, `winning_value=VALUE_FROM_A`, `losing_value=VALUE_FROM_B`. This case would have failed against the pre-02-W0 `ChainExecutor`.

**`ChainExecutorTest` extension:**

- `incoming_wins_branch_labels_winning_source_system_from_mutation` — pins the unchanged incoming-wins path explicitly.
- `current_keeps_branch_labels_winning_source_system_from_current_source_map` — pins the newly-fixed current-keeps path using an in-memory `RuleContext` with a populated `currentSourceSystem` map.

Both tests operate purely in-memory — no DB, no Testcontainers. 10/10 in the class green.

## Deviations from Plan

### Rule 3 — Adjustments to complete the task

**1. [Rule 3 - Environment] `snakeyaml` upper-bound pin in parent POM**
- **Found during:** Task 1 `./mvnw install` — `RequireUpperBoundDeps` enforcer failure.
- **Issue:** Spring Boot 3.5.13 BOM manages `snakeyaml:2.4`, but springdoc-openapi 2.8.6 transitively pulls `snakeyaml:2.5` via `swagger-core-jakarta:2.2.29` → `jackson-dataformat-yaml:2.18.2`. The enforcer requires the higher version be pinned.
- **Fix:** Added `<dependency><groupId>org.yaml</groupId><artifactId>snakeyaml</artifactId><version>2.5</version></dependency>` to the parent POM `dependencyManagement` block, adjacent to the existing upper-bound pins (`commons-io`, `error_prone_annotations`).
- **Files modified:** `pom.xml`
- **Commit:** `ce3415a`

**2. [Rule 3 - Boot] JDBC autoconfig excluded in spike IT**
- **Found during:** Task 1 first IT run.
- **Issue:** `@EnableAutoConfiguration` picked up `DataSourceAutoConfiguration` because fabric-core (transitive) puts `spring-boot-starter-jdbc` on the classpath. The spike has no JDBC URL and failed with "Failed to determine a suitable driver class".
- **Fix:** Added explicit `exclude = {DataSourceAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class, HibernateJpaAutoConfiguration.class}` to the spike's inner `@EnableAutoConfiguration`.
- **Files modified:** `fabric-projections/src/test/java/dev/tessera/projections/rest/SchemaVersionBumpIT.java`
- **Commit:** `ce3415a`

**3. [Rule 3 - Architecture] Spike uses in-memory `MutableExposedTypeSource` instead of real `SchemaRegistry`**
- **Where:** Task 1 plan action 3.
- **Plan said:** "declare a node type `SpikeType` in Schema Registry with `rest_read_enabled=false` via SchemaRegistry API".
- **What was done:** Introduced a thin `ExposedTypeSource` SPI and provided an in-memory mutable implementation in the test. The spike's purpose is to prove SpringDoc lifecycle + cache semantics; going through the full `SchemaRegistry` would require Testcontainers + AGE + Flyway for a property that `@TestPropertySource(springdoc.cache.disabled=true)` fully exercises in ~2 seconds.
- **Why:** The `rest_read_enabled` column does not yet exist on the schema registry tables — Wave 1 owns adding it. Wiring the spike against the real registry would either force Wave 1's DB migration to land in Wave 0 (inverting wave order) or require a workaround schema. The SPI indirection makes Wave 1/Wave 2's substitution trivial: produce a `SchemaRegistryExposedTypeSource` implementation and the customizer wires to it unchanged.
- **Net effect:** Same end-state proof (SpringDoc rebuilds on runtime flip), cleaner dependency graph, and the production `SpringDocDynamicSpike` bean carries zero test-only concerns.

**4. [Rule 3 - Module dependency] `GraphServiceAuthorityThreadingIT` placed in `fabric-rules/src/test`, not `fabric-core/src/test`**
- **Where:** Task 2 plan action 4.
- **Plan said:** create the IT at `fabric-core/src/test/java/dev/tessera/core/graph/GraphServiceAuthorityThreadingIT.java`.
- **What was done:** placed at `fabric-rules/src/test/java/dev/tessera/rules/authority/GraphServiceAuthorityThreadingIT.java`.
- **Why:** `PipelineFixture` is the only existing test support class that wires a real `GraphServiceImpl` + `RuleEngine` + `SourceAuthorityMatrix` + `ReconciliationConflictsRepository` + AGE Testcontainer together, and it lives in fabric-rules. Placing the IT in fabric-core would require the fabric-core test classpath to depend on fabric-rules, reversing the module dependency direction that `ModuleDependencyTest.fabric_core_should_not_depend_on_others` enforces as an ArchUnit rule.
- **Net effect:** Same coverage surface (drives through `graphService.apply` end-to-end), ArchUnit stays green (11/11), no new test-support code duplicated across modules.

## Threat Model Mitigations

- **T-02-W0-01 (Info disclosure via `/v3/api-docs`):** Mitigated via model-namespaced schema names (`{model}_{slug}Entity`). The spike test tenant is the only consumer in Wave 0; Wave 2 adds auth and tenant filtering.
- **T-02-W0-02 (ChainExecutor label drift):** Mitigated via the two new unit tests pinning both branches + the IT asserting the persisted row shape through `graphService.apply`.
- **T-02-W0-03 (Repudiation via missing currentSourceSystem):** Mitigated via `GraphServiceAuthorityThreadingIT` driving through the production funnel — the incoming-wins and current-keeps branches both now produce correctly-labelled `reconciliation_conflicts` rows.

## Acceptance Criteria — Final Status

- [x] `./mvnw -pl fabric-projections -Dit.test=SchemaVersionBumpIT verify` exits 0
- [x] `grep -q "WAVE 0 SPIKE" SchemaVersionBumpIT.java` succeeds (class Javadoc)
- [x] `grep -q "/v3/api-docs" SchemaVersionBumpIT.java` succeeds (`/v3/api-docs/entities`)
- [x] `grep -q "springdoc.cache.disabled" SchemaVersionBumpIT.java` succeeds
- [x] ArchUnit green: `RawCypherBanTest` + `ModuleDependencyTest` — 11/11
- [x] `./mvnw -pl fabric-core -Dit.test=GraphServiceAuthorityThreadingIT verify` — not applicable (IT relocated to fabric-rules per Rule 3 Deviation #4); `./mvnw -pl fabric-rules -Dit.test=GraphServiceAuthorityThreadingIT verify` exits 0 (2/2 tests)
- [x] `./mvnw -pl fabric-rules -Dtest=ChainExecutorTest test` exits 0 — 10/10
- [x] `./mvnw -pl fabric-rules -Dit.test='SourceAuthorityIT,ConflictRegisterIT' verify` exits 0 — no regression
- [x] `grep -q "currentSourceSystem" GraphServiceImpl.java` succeeds
- [x] `grep -q "ctx.currentSourceSystem()" ChainExecutor.java` succeeds
- [x] `./mvnw -B verify` green end-to-end — BUILD SUCCESS, 5/5 modules, 3:08 min

## Success Criteria — Final Status

- [x] Decision 13 satisfied: SpringDoc runtime schema rebuild empirically proven via `SchemaVersionBumpIT`
- [x] Phase 1 Known Deviation #1 closed: `currentSourceSystem` threaded through `GraphServiceImpl.apply`
- [x] Phase 1 Known Deviation #2 closed: `ConflictRecord.winningSourceSystem` correctly labelled on both branches
- [x] No production REST/connector code yet — Wave 0 is spike + cleanup only

## Self-Check

Verifying claimed artifacts exist on disk and commits are reachable from HEAD.

### Files
- FOUND: `fabric-projections/src/main/java/dev/tessera/projections/rest/internal/ExposedTypeSource.java`
- FOUND: `fabric-projections/src/main/java/dev/tessera/projections/rest/internal/SpringDocDynamicSpike.java`
- FOUND: `fabric-projections/src/test/java/dev/tessera/projections/rest/SchemaVersionBumpIT.java`
- FOUND: `fabric-rules/src/test/java/dev/tessera/rules/authority/GraphServiceAuthorityThreadingIT.java`
- FOUND: `fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphServiceImpl.java` (modified — `currentSourceSystem` grep succeeds)
- FOUND: `fabric-rules/src/main/java/dev/tessera/rules/internal/ChainExecutor.java` (modified — `ctx.currentSourceSystem()` grep succeeds)

### Commits
- FOUND: `ce3415a` — `test(02-W0): SpringDoc dynamic OpenAPI spike — SchemaVersionBumpIT green`
- FOUND: `813202a` — `fix(02-W0): close Phase 1 deviations — thread currentSourceSystem + fix ConflictRecord labels`

## Self-Check: PASSED
