---
phase: 02-rest-projection-connector-framework-first-connector-security-baseline
plan: W0
type: execute
wave: 1
depends_on: []
files_modified:
  - fabric-projections/pom.xml
  - fabric-projections/src/main/java/dev/tessera/projections/rest/internal/SpringDocDynamicSpike.java
  - fabric-projections/src/test/java/dev/tessera/projections/rest/SchemaVersionBumpIT.java
  - fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphServiceImpl.java
  - fabric-rules/src/main/java/dev/tessera/rules/internal/ChainExecutor.java
  - fabric-rules/src/test/java/dev/tessera/rules/internal/ChainExecutorTest.java
  - fabric-core/src/test/java/dev/tessera/core/graph/GraphServiceAuthorityThreadingIT.java
autonomous: true
requirements:
  - REST-05
  - RULE-05
  - RULE-06

must_haves:
  truths:
    - "SchemaVersionBumpIT proves /v3/api-docs reflects a runtime schema flip without redeploy (Decision 13)"
    - "GraphServiceImpl.apply threads currentSourceSystem map from previous state into ruleEngine.run — AuthorityReconciliationRule actually fires through the write funnel"
    - "ChainExecutor.ConflictRecord.winningSourceSystem is labelled correctly in both the incoming-wins and current-keeps branches"
  artifacts:
    - path: fabric-projections/src/test/java/dev/tessera/projections/rest/SchemaVersionBumpIT.java
      provides: "Wave 0 spike that de-risks REST-05 before Wave 2 commits to OpenApiCustomizer"
      contains: "/v3/api-docs"
    - path: fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphServiceImpl.java
      provides: "currentSourceSystem threading into ruleEngine.run"
      contains: "currentSourceSystem"
  key_links:
    - from: fabric-projections/src/test/java/dev/tessera/projections/rest/SchemaVersionBumpIT.java
      to: fabric-projections/src/main/java/dev/tessera/projections/rest/internal/SpringDocDynamicSpike.java
      via: "Spike registers OpenApiCustomizer that reads SchemaRegistry, IT asserts path appears after flip"
      pattern: "OpenApiCustomizer"
---

<objective>
Land the Wave 0 spike and Phase 1 deviation closures BEFORE any production REST/connector code is written. Two concerns, one wave:

1. **SpringDoc dynamic lifecycle spike (Decision 13 / RESEARCH Q1 / assumption A1+A7).** Build the minimum viable SpringDoc `OpenApiCustomizer` wired to the Schema Registry, then an IT that (a) declares a node type with `rest_read_enabled=false`, hits `/v3/api-docs`, asserts the path is absent; (b) flips `rest_read_enabled=true`; (c) hits `/v3/api-docs` a second time and asserts the new path appears — WITHOUT an application restart. If the spike fails, STOP and return to the orchestrator for a fallback discussion before Wave 1 production code lands.

2. **Phase 1 deviation closure (known-deviations from 01-VERIFICATION.md).** Thread `currentSourceSystem` from graph previous-state into `ruleEngine.run` inside `GraphServiceImpl.apply`, and fix `ChainExecutor.ConflictRecord.winningSourceSystem` labelling on the current-keeps branch. Both are tiny production-code changes with existing Phase 1 ITs ready to flip to driving through `graphService.apply`.

Purpose: de-risk the two load-bearing research assumptions for Phase 2 (A1, A7 — OpenApiCustomizer runtime rebuild) and pay down the Phase 1 gaps that would otherwise silently hide rule-engine behaviour in Wave 2+ integration tests.

Output: a single committed spike IT (red→green) + two targeted production fixes with updated ITs. No REST controllers, no connector SPI, no Flyway changes.
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@.planning/phases/02-rest-projection-connector-framework-first-connector-security-baseline/02-CONTEXT.md
@.planning/phases/02-rest-projection-connector-framework-first-connector-security-baseline/02-RESEARCH.md
@.planning/phases/01-graph-core-schema-registry-validation-rules/01-VERIFICATION.md
@.planning/phases/01-graph-core-schema-registry-validation-rules/01-W1-PLAN.md
@.planning/phases/01-graph-core-schema-registry-validation-rules/01-W3-PLAN.md
@fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphServiceImpl.java
@fabric-rules/src/main/java/dev/tessera/rules/internal/ChainExecutor.java
@fabric-core/src/main/java/dev/tessera/core/schema/SchemaRegistry.java
@fabric-rules/src/test/java/dev/tessera/rules/authority/SourceAuthorityIT.java
@fabric-rules/src/test/java/dev/tessera/rules/conflicts/ConflictRegisterIT.java
</context>

<interfaces>
From fabric-rules ChainExecutor (Phase 1 line 88 quirk — see 01-VERIFICATION.md Known Deviation #2):
```java
// Today (buggy current-keeps branch):
new ConflictRecord(..., /*winningSourceSystem*/ ctx.mutation().sourceSystem(), ...)
// Must become:
new ConflictRecord(..., override.winningSourceSystem() /* or ctx.currentSourceSystem().get(property) */, ...)
```

From fabric-core GraphServiceImpl (Phase 1 line 120 — see 01-VERIFICATION.md Known Deviation #1):
```java
// Today:
ruleEngine.run(mutation.tenantContext(), resolvedDescriptor, previousState, Map.of(), mutation);
// Must become:
Map<String,String> currentSourceSystem = capturePreviousSourceSystemMap(previousState);
ruleEngine.run(mutation.tenantContext(), resolvedDescriptor, previousState, currentSourceSystem, mutation);
```

From RESEARCH Q1 (SpringDoc):
```java
GroupedOpenApi.builder()
    .group("entities")
    .pathsToMatch("/api/v1/*/entities/**")
    .addOpenApiCustomizer(openApi -> { /* iterate SchemaRegistry.exposedTypes(model) */ })
    .build();
```
</interfaces>

<tasks>

<task type="auto" tdd="true">
  <name>Task 02-W0-01: SpringDoc dynamic OpenAPI spike (SchemaVersionBumpIT) — REST-05 de-risk</name>
  <files>
    fabric-projections/pom.xml,
    fabric-projections/src/main/java/dev/tessera/projections/rest/internal/SpringDocDynamicSpike.java,
    fabric-projections/src/test/java/dev/tessera/projections/rest/SchemaVersionBumpIT.java
  </files>
  <read_first>
    - .planning/phases/02-.../02-RESEARCH.md §Q1 (SpringDoc lifecycle, verbatim skeleton)
    - .planning/phases/02-.../02-CONTEXT.md Decision 13
    - fabric-core/src/main/java/dev/tessera/core/schema/SchemaRegistry.java (Phase 1 cache semantics)
  </read_first>
  <behavior>
    - A `@TestConfiguration` inside `SchemaVersionBumpIT` registers a `GroupedOpenApi("entities")` with an `OpenApiCustomizer` that walks `SchemaRegistry.exposedTypes(modelId)` and adds one `PathItem` per `(model,type)` under `/api/v1/{model}/entities/{typeSlug}` (GET list only — spike does NOT need full CRUD).
    - Test flow: (1) declare node type `SpikeType` in Schema Registry with `rest_read_enabled=false` via SchemaRegistry API; (2) GET `/v3/api-docs?group=entities`, assert JSON does NOT contain `/api/v1/spike-tenant/entities/spike-type`; (3) flip `rest_read_enabled=true` via SchemaRegistry; (4) GET `/v3/api-docs?group=entities` again WITHOUT restart, assert path now present; (5) flip back to false and assert path disappears again.
    - Test uses Testcontainers `AgePostgresContainer` (Phase 1 helper), `@SpringBootTest(webEnvironment=RANDOM_PORT)`, and sets `springdoc.cache.disabled=true`.
    - **If the second hit does NOT reflect the flip**, the task fails with a message directing the operator to `/gsd-plan-phase 2 --revision` and the fallback discussion. Do NOT attempt to work around silently.
  </behavior>
  <action>
    1. Add dependencies to `fabric-projections/pom.xml`: `org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.x` (version from parent BOM; add to parent BOM if missing), `org.springframework.boot:spring-boot-starter-web`, `org.springframework.boot:spring-boot-starter-test` (test scope). Do NOT yet add Spring Security — that lands in Wave 2.
    2. Create `SpringDocDynamicSpike.java` package `dev.tessera.projections.rest.internal` — a minimal `@Configuration` (conditional on profile `spike-openapi`) that publishes a `GroupedOpenApi("entities")` bean whose `OpenApiCustomizer` iterates `SchemaRegistry.allModels()` + `SchemaRegistry.exposedTypes(model)` (NB: if those helpers don't exist yet, add them as read-only methods on `SchemaRegistry` — pure query, no migration needed). The customizer adds a `PathItem` with a single `GET` operation per `(model,type)`, schema name `{model}_{slug}Entity` to avoid collisions (RESEARCH Q1 pitfall). Use `SpringDocConfigProperties` cache disabled via property, not via bean override.
    3. Create `SchemaVersionBumpIT.java` in `fabric-projections/src/test/java`:
       - `@Testcontainers`, `@SpringBootTest(webEnvironment=RANDOM_PORT, classes={TesseraApp.class, SpringDocDynamicSpike.class})`, `@ActiveProfiles({"test","spike-openapi"})`.
       - Property override: `springdoc.cache.disabled=true`.
       - `@Autowired SchemaRegistry registry; @LocalServerPort int port;` — use RestAssured or `WebTestClient` for `/v3/api-docs?group=entities`.
       - Test method `openApiDocReflectsRuntimeSchemaFlip()`: declare type, assert absent; flip; assert present; flip back; assert absent. Each assertion uses JSONPath against the doc.
       - Class-level Javadoc: `"WAVE 0 SPIKE — de-risks REST-05 / CONTEXT Decision 13 / RESEARCH assumption A1+A7. Wave 2 OpenApiCustomizer production code depends on this test staying green. If it fails, STOP and escalate to the orchestrator."`
    4. TDD order: write the test first and run it (expect RED because the customizer doesn't exist), then implement the customizer, then GREEN.
    5. If the test is GREEN on the first-flip branch but stale on the second-flip branch, document the observed SpringDoc cache behaviour in the SUMMARY and route to orchestrator with a concrete fallback recommendation (e.g. a manual `springDocProviders.getWebMvcProvider().getActualGroups()` invalidation, or the restart-on-schema-change fallback).
  </action>
  <verify>
    <automated>./mvnw -pl fabric-projections -Dit.test=SchemaVersionBumpIT verify</automated>
  </verify>
  <acceptance_criteria>
    - `./mvnw -pl fabric-projections -Dit.test=SchemaVersionBumpIT verify` exits 0
    - `grep -q "WAVE 0 SPIKE" fabric-projections/src/test/java/dev/tessera/projections/rest/SchemaVersionBumpIT.java` succeeds
    - `grep -q "/v3/api-docs" fabric-projections/src/test/java/dev/tessera/projections/rest/SchemaVersionBumpIT.java` succeeds
    - `grep -q "springdoc.cache.disabled" fabric-projections/src/test/java/dev/tessera/projections/rest/SchemaVersionBumpIT.java` succeeds
    - ArchUnit remains green: `./mvnw -pl fabric-app -Dtest=RawCypherBanTest test`
  </acceptance_criteria>
  <done>
    SpringDoc's runtime schema rebuild is empirically proven (or conclusively disproven with a documented fallback). Wave 2 can commit to `OpenApiCustomizer` as its REST-05 strategy.
  </done>
</task>

<task type="auto" tdd="true">
  <name>Task 02-W0-02: Close Phase 1 deviations — thread currentSourceSystem + fix ConflictRecord labels</name>
  <files>
    fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphServiceImpl.java,
    fabric-core/src/test/java/dev/tessera/core/graph/GraphServiceAuthorityThreadingIT.java,
    fabric-rules/src/main/java/dev/tessera/rules/internal/ChainExecutor.java,
    fabric-rules/src/test/java/dev/tessera/rules/internal/ChainExecutorTest.java
  </files>
  <read_first>
    - .planning/phases/01-graph-core-schema-registry-validation-rules/01-VERIFICATION.md §Known Deviations #1 and #2 (verbatim)
    - fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphServiceImpl.java line 120
    - fabric-rules/src/main/java/dev/tessera/rules/internal/ChainExecutor.java line 88
    - fabric-rules/src/test/java/dev/tessera/rules/authority/SourceAuthorityIT.java (Case 1 — current-keeps branch)
    - fabric-rules/src/test/java/dev/tessera/rules/conflicts/ConflictRegisterIT.java
  </read_first>
  <behavior>
    - `GraphServiceImpl.apply` reads the pre-mutation node state (already captured for rule engine input) and derives a `Map<String,String>` keyed by property slug → `_source` label for each currently-set property. That map replaces the `Map.of()` argument on `ruleEngine.run(...)`.
    - If no previous state exists (CREATE path), the map is empty — unchanged from today for new-node flows.
    - `ChainExecutor` current-keeps branch populates `ConflictRecord.winningSourceSystem` from `ctx.currentSourceSystem().get(propertySlug)` (never from `ctx.mutation().sourceSystem()`). Incoming-wins branch unchanged.
    - New IT `GraphServiceAuthorityThreadingIT` drives `graphService.apply` (NOT `ruleEngine.run` directly) with two sequential mutations from different source systems on the same property, and asserts:
      (a) the final node state reflects the higher-authority winner regardless of arrival order;
      (b) a `reconciliation_conflicts` row exists with `winning_source_system` correctly labelled on BOTH the current-keeps and incoming-wins branches;
      (c) equivalent to the two passing IT cases in `SourceAuthorityIT` + `ConflictRegisterIT` but via the production write funnel, closing the Phase 1 Known Deviation #1 gap at the commit surface.
    - Extend `ChainExecutorTest` with a unit case that pins both branches' `winningSourceSystem` labels.
  </behavior>
  <action>
    1. `GraphServiceImpl.java`: add a private helper `Map<String,String> deriveCurrentSourceSystemMap(NodeState previousState)` that walks `previousState.propertySourceSystems()` (or equivalent accessor — add a read-only accessor on `NodeState` if not already present; Phase 1 W1 stamps `_source` per node but per-property tracking may need a tiny additive accessor). Pass the result as the 4th argument to `ruleEngine.run`. Retain `Map.of()` for the create path where `previousState` is null. Leave the `// TODO(W2): Spring Security authorize()` in place — that's Wave 2's concern.
    2. `ChainExecutor.java` line 88: change the `ConflictRecord` construction in the current-keeps branch to pull from `ctx.currentSourceSystem().get(propertySlug)`, falling back to the override's `winningSourceSystem` if populated. Keep the incoming-wins branch unchanged. Add a Javadoc note referencing 01-VERIFICATION Known Deviation #2.
    3. `ChainExecutorTest.java`: add `currentKeepsBranchLabelsWinningSourceSystemFromCurrent()` and `incomingWinsBranchLabelsWinningSourceSystemFromMutation()` unit tests. Both operate on in-memory `RuleContext` fixtures — no DB needed.
    4. `GraphServiceAuthorityThreadingIT.java`: Testcontainers AGE, full Spring slice wiring `GraphServiceImpl` + `RuleEngine` + `SourceAuthorityMatrix` + `ReconciliationConflictsRepository` (reuse the Phase 1 slice from `GraphServiceApplyIT`). Seed V6/V7 tables with an authority matrix (one source higher than the other). Apply mutation M1 from source LOW, then M2 from source HIGH on same property — assert final state is HIGH's value and the conflict row labels `winning=HIGH, losing=LOW`. Apply M3 from source LOW again — assert current-keeps branch fires AND the row labels `winning=HIGH, losing=LOW` (NOT `winning=LOW`). Run in both arrival orders.
    5. TDD order: write the new IT first (RED against Phase 1's buggy behaviour), then fix ChainExecutor + GraphServiceImpl, then GREEN.
    6. Do NOT touch `SourceAuthorityIT` / `ConflictRegisterIT` in this task — they stay as rule-engine-direct ITs. The new IT is the funnel-level gate.
  </action>
  <verify>
    <automated>./mvnw -pl fabric-core,fabric-rules -Dit.test='GraphServiceAuthorityThreadingIT,SourceAuthorityIT,ConflictRegisterIT' -Dtest=ChainExecutorTest verify</automated>
  </verify>
  <acceptance_criteria>
    - `./mvnw -pl fabric-core -Dit.test=GraphServiceAuthorityThreadingIT verify` exits 0
    - `./mvnw -pl fabric-rules -Dtest=ChainExecutorTest test` exits 0 (both new branches + existing cases)
    - `./mvnw -pl fabric-rules -Dit.test='SourceAuthorityIT,ConflictRegisterIT' verify` still exits 0 (no regression on the rule-engine-direct path)
    - `grep -q "currentSourceSystem" fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphServiceImpl.java` succeeds AND `grep -n "Map.of()" fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphServiceImpl.java` shows Map.of() ONLY in the create-path branch
    - `grep -q "ctx.currentSourceSystem()" fabric-rules/src/main/java/dev/tessera/rules/internal/ChainExecutor.java` succeeds
    - `./mvnw -B verify` green end-to-end (no Phase 1 regression)
  </acceptance_criteria>
  <done>
    Phase 1 Known Deviations #1 and #2 closed. `reconciliation_conflicts` rows are correctly labelled in both branches. Rule engine authority matrix now fires through `graphService.apply` end-to-end.
  </done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| SchemaRegistry → OpenApiCustomizer | Schema descriptors flow into a public doc endpoint; descriptor content must not leak across tenants |
| GraphServiceImpl → ruleEngine | Per-property source system labels are now load-bearing for RULE-05/06 |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-02-W0-01 | Information Disclosure | `/v3/api-docs` exposing cross-tenant types | mitigate | OpenApiCustomizer namespaces schemas as `{model}_{slug}Entity` and iterates per-model — Wave 2 adds auth and tenant filtering; Wave 0 spike uses a single test tenant only |
| T-02-W0-02 | Tampering | ChainExecutor label drift on current-keeps branch | mitigate | Unit test pins both branches; IT asserts row shape through the funnel |
| T-02-W0-03 | Repudiation | Missing currentSourceSystem means authority winner not recorded through funnel | mitigate | New IT drives through `graphService.apply` end-to-end |
</threat_model>

<verification>
`./mvnw -B verify` green. `SchemaVersionBumpIT`, `GraphServiceAuthorityThreadingIT`, `ChainExecutorTest` all green. Phase 1 suites remain green (no regressions).
</verification>

<success_criteria>
- Decision 13 satisfied: SpringDoc runtime schema rebuild empirically proven via `SchemaVersionBumpIT`
- Phase 1 Known Deviation #1 closed: `currentSourceSystem` threaded through `GraphServiceImpl.apply`
- Phase 1 Known Deviation #2 closed: `ConflictRecord.winningSourceSystem` correctly labelled on both branches
- No production REST/connector code yet — Wave 0 is spike + cleanup only
</success_criteria>

<output>
After completion, create `.planning/phases/02-rest-projection-connector-framework-first-connector-security-baseline/02-W0-SUMMARY.md`.
</output>
