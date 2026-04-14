---
phase: 01-graph-core-schema-registry-validation-rules
plan: W3
type: execute
wave: 4
depends_on: [W0, W1, W2]
files_modified:
  - fabric-core/src/main/java/dev/tessera/core/validation/ShaclValidator.java
  - fabric-core/src/main/java/dev/tessera/core/validation/ValidationReportFilter.java
  - fabric-core/src/main/java/dev/tessera/core/validation/internal/ShapeCompiler.java
  - fabric-core/src/main/java/dev/tessera/core/validation/internal/ShapeCache.java
  - fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphServiceImpl.java
  - fabric-rules/src/main/java/dev/tessera/rules/Rule.java
  - fabric-rules/src/main/java/dev/tessera/rules/RuleContext.java
  - fabric-rules/src/main/java/dev/tessera/rules/RuleOutcome.java
  - fabric-rules/src/main/java/dev/tessera/rules/Chain.java
  - fabric-rules/src/main/java/dev/tessera/rules/RuleEngine.java
  - fabric-rules/src/main/java/dev/tessera/rules/internal/ChainExecutor.java
  - fabric-rules/src/main/java/dev/tessera/rules/internal/RuleRepository.java
  - fabric-rules/src/main/java/dev/tessera/rules/admin/RuleAdminController.java
  - fabric-rules/src/main/java/dev/tessera/rules/authority/SourceAuthorityService.java
  - fabric-rules/src/main/java/dev/tessera/rules/authority/internal/SourceAuthorityRepository.java
  - fabric-rules/src/main/java/dev/tessera/rules/conflicts/ConflictRegister.java
  - fabric-rules/src/main/java/dev/tessera/rules/circuit/WriteRateCircuitBreaker.java
  - fabric-rules/src/main/java/dev/tessera/rules/circuit/CircuitBreakerAdminController.java
  - fabric-rules/src/main/java/dev/tessera/rules/chains/validate/SchemaRequiredFieldsRule.java
  - fabric-rules/src/main/java/dev/tessera/rules/chains/reconcile/SourceAuthorityRule.java
  - fabric-core/src/jmh/java/dev/tessera/core/bench/ShaclValidationBench.java
  - fabric-core/src/jmh/java/dev/tessera/core/bench/WritePipelineBench.java
  - fabric-core/src/test/java/dev/tessera/core/validation/ShaclPreCommitIT.java
  - fabric-core/src/test/java/dev/tessera/core/validation/ShapeCacheTest.java
  - fabric-core/src/test/java/dev/tessera/core/validation/TargetedValidationTest.java
  - fabric-core/src/test/java/dev/tessera/core/validation/ValidationReportFilterTest.java
  - fabric-core/src/test/java/dev/tessera/core/schema/SchemaToShaclIT.java
  - fabric-rules/src/test/java/dev/tessera/rules/BusinessRuleRejectIT.java
  - fabric-rules/src/test/java/dev/tessera/rules/ChainExecutorTest.java
  - fabric-rules/src/test/java/dev/tessera/rules/RuleInterfaceTest.java
  - fabric-rules/src/test/java/dev/tessera/rules/RuleOutcomeTest.java
  - fabric-rules/src/test/java/dev/tessera/rules/RuleRegistrationIT.java
  - fabric-rules/src/test/java/dev/tessera/rules/authority/SourceAuthorityIT.java
  - fabric-rules/src/test/java/dev/tessera/rules/conflicts/ConflictRegisterIT.java
  - fabric-rules/src/test/java/dev/tessera/rules/circuit/CircuitBreakerTest.java
  - fabric-rules/src/test/java/dev/tessera/rules/circuit/CircuitBreakerIT.java
  - fabric-rules/src/test/java/dev/tessera/rules/EchoLoopSuppressionIT.java
  - fabric-rules/src/test/java/dev/tessera/arch/RuleEngineHygieneTest.java
autonomous: true
requirements:
  - VALID-01
  - VALID-02
  - VALID-03
  - VALID-04
  - VALID-05
  - RULE-01
  - RULE-02
  - RULE-03
  - RULE-04
  - RULE-05
  - RULE-06
  - RULE-07
  - RULE-08
user_setup: []

must_haves:
  truths:
    - "Every mutation is SHACL-validated synchronously before GraphSession.apply"
    - "Compiled SHACL shapes are cached by (model_id, schema_version, type_slug); second validation against the same shape does not re-compile"
    - "SHACL validation targets only the mutated node — not the whole graph"
    - "A ValidationReport with violations never contains literal payload values or other tenants' data"
    - "A business rule returning REJECT blocks the commit"
    - "Rule engine runs four named chains in fixed order: VALIDATE → RECONCILE → ENRICH → ROUTE; REJECT in VALIDATE short-circuits (per ADR-7 §RULE-01..04)"
    - "All six rule outcomes (COMMIT/REJECT/MERGE/OVERRIDE/ADD/ROUTE) are implemented and tested"
    - "Rules are registered via Spring DI (code) + `reconciliation_rules` table (per-tenant activation + priority_override) per ADR-7 §RULE-04; internal `POST /admin/rules/reload/{model_id}` endpoint invalidates the per-tenant rule cache"
    - "Within a chain, rules sort by `priority()` DESC — higher runs first (ADR-7 §RULE-01)"
    - "source_authority table drives per-property reconciliation; two sources writing the same property yields the configured winner"
    - "A losing write lands in reconciliation_conflicts with rule_id + reason + both values"
    - "Write-amplification circuit breaker trips at 500 events/sec over 30 s rolling window; pauses connector, DLQs queued events, increments tessera.circuit.tripped Micrometer counter"
    - "POST /admin/connectors/{id}/reset clears the breaker (internal-only endpoint)"
    - "Every event carries origin_connector_id + origin_change_id; echo-loop suppression prevents a connector from re-applying its own events"
    - "ShaclValidationBench p95 < 2 ms (cached shapes, single-node)"
    - "WritePipelineBench FULL-PIPELINE p95 < 11 ms (Phase 0 point-lookup baseline × 2 + ~9 ms SHACL+rules budget). References the Wave-1 baseline captured in 01-W1-02; Wave 3 re-runs the bench with SHACL + rules in scope and gates the build."
  artifacts:
    - path: fabric-core/src/main/java/dev/tessera/core/validation/ShaclValidator.java
      provides: "Synchronous pre-commit SHACL validator (VALID-01)"
      contains: "class ShaclValidator"
    - path: fabric-rules/src/main/java/dev/tessera/rules/RuleEngine.java
      provides: "Four-chain pipeline executor"
      contains: "Chain.VALIDATE"
    - path: fabric-rules/src/main/java/dev/tessera/rules/circuit/WriteRateCircuitBreaker.java
      provides: "Rolling-window AtomicLongArray circuit breaker (D-D2/D-D3)"
      contains: "AtomicLongArray"
  key_links:
    - from: fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphServiceImpl.java
      to: fabric-core/src/main/java/dev/tessera/core/validation/ShaclValidator.java
      via: "shaclValidator.validate(ctx, descriptor, mutation) called AFTER rule engine RECONCILE+ENRICH, BEFORE graphSession.apply"
      pattern: "shaclValidator.validate"
    - from: fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphServiceImpl.java
      to: fabric-rules/src/main/java/dev/tessera/rules/RuleEngine.java
      via: "ruleEngine.run(Chain.VALIDATE|RECONCILE|ENRICH|ROUTE, ruleCtx) at the four pipeline steps"
      pattern: "ruleEngine.run"
---

<objective>
Land the validation + rule engine spine: Jena SHACL with cached shapes and tenant-filtered reports, the four-chain rule engine (VALIDATE → RECONCILE → ENRICH → ROUTE) with Spring DI registration, source authority matrix + conflict register, and the write-amplification circuit breaker with DLQ + Micrometer + admin reset. Wire all of this into `GraphServiceImpl.apply` at the Wave 1 TODO markers so the full Phase 1 pipeline per RESEARCH §"Pattern 1" is operational. Lock in the two perf budgets (SHACL < 2 ms p95, full pipeline < 11 ms p95).

Purpose: Close Phase 1. After this wave, Tessera has a trustworthy graph core — no projections, no connectors, but everything Phase 2+ needs to build projections is in place.
Output: All 13 remaining requirements (VALID-01..05, RULE-01..08) green.
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@.planning/adr/ADR-7-rule-engine-contract.md
@.planning/phases/01-graph-core-schema-registry-validation-rules/01-CONTEXT.md
@.planning/phases/01-graph-core-schema-registry-validation-rules/01-RESEARCH.md
@.planning/phases/01-graph-core-schema-registry-validation-rules/01-VALIDATION.md
@.planning/phases/01-graph-core-schema-registry-validation-rules/01-W1-PLAN.md
@.planning/phases/01-graph-core-schema-registry-validation-rules/01-W2-PLAN.md
@fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphServiceImpl.java
@fabric-core/src/main/java/dev/tessera/core/schema/SchemaRegistry.java
@fabric-app/src/main/resources/db/migration/V6__source_authority.sql
@fabric-app/src/main/resources/db/migration/V7__reconciliation_conflicts.sql
@fabric-app/src/main/resources/db/migration/V8__connector_limits_and_dlq.sql
</context>

<tasks>

<task type="auto" tdd="true">
  <name>Task 01-W3-01: Jena SHACL validator + shape cache + tenant-filtered ValidationReport (VALID-01..04) + JMH ShaclValidationBench + SchemaToShaclIT (SCHEMA-07)</name>
  <files>
    fabric-core/src/main/java/dev/tessera/core/validation/ShaclValidator.java,
    fabric-core/src/main/java/dev/tessera/core/validation/ValidationReportFilter.java,
    fabric-core/src/main/java/dev/tessera/core/validation/internal/ShapeCompiler.java,
    fabric-core/src/main/java/dev/tessera/core/validation/internal/ShapeCache.java,
    fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphServiceImpl.java,
    fabric-core/src/jmh/java/dev/tessera/core/bench/ShaclValidationBench.java,
    fabric-core/src/test/java/dev/tessera/core/validation/ShaclPreCommitIT.java,
    fabric-core/src/test/java/dev/tessera/core/validation/ShapeCacheTest.java,
    fabric-core/src/test/java/dev/tessera/core/validation/TargetedValidationTest.java,
    fabric-core/src/test/java/dev/tessera/core/validation/ValidationReportFilterTest.java,
    fabric-core/src/test/java/dev/tessera/core/schema/SchemaToShaclIT.java
  </files>
  <read_first>
    - .planning/phases/01-graph-core-schema-registry-validation-rules/01-RESEARCH.md §"SHACL Shape Caching" (copy Caffeine config verbatim), §"Jena SHACL Perf Envelope", §"SHACL Report Filtering" (copy redact() verbatim)
    - .planning/phases/01-graph-core-schema-registry-validation-rules/01-CONTEXT.md §D-C4
    - fabric-core/src/main/java/dev/tessera/core/schema/SchemaRegistry.java (Wave 2)
    - fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphServiceImpl.java (Wave 2 state)
  </read_first>
  <behavior>
    - Given a NodeTypeDescriptor with required property "email" and constraint sh:datatype xsd:string, a mutation missing "email" is rejected with a ValidationReport indicating sh:minCount violation on path email; the mutation never reaches GraphSession.apply
    - Second call with the same (modelId, schemaVersion, typeSlug) does NOT re-compile the Jena Shapes (cache hit observable via recordStats())
    - Validation runs ONLY on the mutated node — the in-memory RDF model has exactly one subject URI (the mutation's target), not the full graph
    - A violation triggered by tenant A's mutation produces a ValidationReport whose toString / logs / error response contain ZERO bytes of tenant B's data, no literal payload values, only shape IRI + focus UUID + constraint component
    - SchemaToShaclIT: create a type in the Schema Registry, flip a property from optional to required, apply a mutation that omits the new property → SHACL rejects it
    - ShaclValidationBench reports p95 < 2 ms on cached shapes for a 5-property type
  </behavior>
  <action>
    1. `ShapeCompiler.java` — takes a `NodeTypeDescriptor` from Wave 2 and builds a Jena `Shapes` object programmatically (NOT from Turtle files per RESEARCH §"Jena SHACL Perf Envelope"). For each `PropertyDescriptor`:
       - `sh:path <typeSlug/propertySlug>` (or a tenant-scoped IRI)
       - `sh:datatype` mapped from `PropertyDescriptor.dataType` (`"string"` → `xsd:string`, `"int"` → `xsd:integer`, `"boolean"` → `xsd:boolean`, `"instant"` → `xsd:dateTime`, `"uuid"` → a custom `tessera:uuid` IRI)
       - `sh:minCount 1` when `required`
       - `sh:pattern` / `sh:in` / `sh:class` from `validationRules` / `enumValues` / `referenceTarget` JSONB
       Uses only SHACL-Core constraints — NEVER sh:sparql (D-C4).
    2. `ShapeCache.java` — Caffeine cache per RESEARCH §"SHACL Shape Caching". Copy the configuration verbatim:
       ```java
       private final Cache<ShapeKey, Shapes> shapeCache = Caffeine.newBuilder()
           .maximumSize(10_000)
           .expireAfterAccess(Duration.ofHours(1))
           .recordStats()
           .build();
       record ShapeKey(UUID modelId, long schemaVersion, String typeSlug) {}
       ```
       Method `Shapes shapesFor(TenantContext ctx, String typeSlug, long schemaVersion)` calls `shapeCache.get(key, k -> compiler.compile(...))`.
    3. `ShaclValidator.java` — constructor takes `ShapeCache` + `SchemaRegistry` + `ValidationReportFilter`. Method `public void validate(TenantContext ctx, NodeTypeDescriptor descriptor, GraphMutation m) throws ShaclValidationException`:
       - Fetch `Shapes` via cache
       - Build an in-memory Jena `Graph` holding ONLY the mutated node's triples (single subject, `m.targetNodeUuid()` as URI, predicates from `m.payload()` entries, objects typed per Jena literals) — **targeted validation** per VALID-03
       - Call `org.apache.jena.shacl.ShaclValidator.get().validate(shapes, dataGraph)`
       - If `!report.conforms()`: filter via `ValidationReportFilter.redact(report, ctx)`, then throw `ShaclValidationException(redactedReport)`
    4. `ValidationReportFilter.java` — copy the `redact()` implementation from RESEARCH §"SHACL Report Filtering" verbatim. Strips literal values, keeps shape IRI + constraint component + Tessera UUID of focus node. Also: never pass the raw Jena report to any logger — the thrown exception carries only the redacted report. Add a `toSafeString(ValidationReport)` helper used in logs.
    5. Edit `GraphServiceImpl.java`: at the Wave 1/2 TODO marker inside `apply`, call `shaclValidator.validate(m.tenantContext(), descriptor, m)` AFTER the rule engine's RECONCILE + ENRICH chains (which Task 01-W3-02 wires) and BEFORE `graphSession.apply(...)`. For this task alone, if rule engine tasks land later in the wave, make the call sequence: `schemaRegistry.loadFor → shaclValidator.validate → graphSession.apply → eventLog.append → outbox.append`. The next task re-orders to include rules.
    6. `ShapeCacheTest.java` — unit test using mocked `ShapeCompiler`: call `shapesFor` twice with same key → compiler called once. Change schemaVersion → compiler called again. Assert cache stats `hitCount == 1`, `missCount == 2`. (VALID-02)
    7. `TargetedValidationTest.java` — unit test: seed a NodeTypeDescriptor; apply validator; inspect the Jena `Graph` passed to `ShaclValidator.get().validate(...)` via a spy on `ShaclValidator.get()` OR via a package-private helper `buildDataGraph(ctx, descriptor, m)` → assert the returned graph has exactly one subject resource and its triple count equals the payload entry count + system properties. **Full-graph validation must NOT occur**. (VALID-03)
    8. `ValidationReportFilterTest.java` — unit test: construct a synthetic Jena `ValidationReport` with literal values "tenant-a-secret@example.com" and "tenant-b-secret@example.com" in the messages; call `redact(report, ctxA)`; assert the redacted report's `toString()` contains NEITHER literal. (VALID-04, CRIT-6)
    9. `ShaclPreCommitIT.java` — integration test: register a type with required `email`; call `graphService.apply(mutation)` with missing email; assert `ShaclValidationException` thrown, no `graph_events` row, no `graph_outbox` row (rolled back). (VALID-01)
    10. `SchemaToShaclIT.java` — integration: create type v1 with optional email; apply mutation (succeeds); update schema to v2 with email required; cache must have been invalidated (via Wave 2 descriptor cache); apply new mutation missing email → rejected. Proves Schema Registry IS the SHACL source of truth. (SCHEMA-07)
    11. `ShaclValidationBench.java` — fill the Wave 0 skeleton: `@Setup(Level.Trial)` compiles a representative 5-property Shapes; `@Benchmark` calls `validator.validate(ctx, descriptor, sampleMutation)` against a cache-warm shape. `@BenchmarkMode(Mode.SampleTime)`, `@OutputTimeUnit(MILLISECONDS)`. Target: p95 < 2 ms. Fails the build if p95 > 2 ms when run via `./mvnw -pl fabric-core -Pjmh -Djmh.bench=ShaclValidationBench verify` with JMH assertion helpers (or a post-run awk on the JMH output log).
  </action>
  <verify>
    <automated>./mvnw -pl fabric-core -Dit.test='ShaclPreCommitIT,SchemaToShaclIT' -Dtest='ShapeCacheTest,TargetedValidationTest,ValidationReportFilterTest' verify</automated>
  </verify>
  <acceptance_criteria>
    - All 5 tests exit 0 (VALID-01..04, SCHEMA-07)
    - `grep -q "maximumSize(10_000)" fabric-core/src/main/java/dev/tessera/core/validation/internal/ShapeCache.java` succeeds
    - `grep -q "recordStats" fabric-core/src/main/java/dev/tessera/core/validation/internal/ShapeCache.java` succeeds
    - `grep -q "ShapeKey(UUID modelId, long schemaVersion, String typeSlug)" fabric-core/src/main/java/dev/tessera/core/validation/internal/ShapeCache.java` succeeds
    - `grep -q "shaclValidator.validate" fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphServiceImpl.java` succeeds
    - `grep -q "sh:sparql\|SHACL_SPARQL" fabric-core/src/main/java/dev/tessera/core/validation/internal/ShapeCompiler.java` returns ZERO hits (D-C4: SHACL-Core only)
    - `./mvnw -pl fabric-core -Pjmh -Djmh.bench=ShaclValidationBench verify` reports p95 < 2 ms (parse JMH output and fail if over)
  </acceptance_criteria>
  <done>
    SHACL validation runs synchronously in the pipeline, uses cached compiled shapes keyed by (modelId, schemaVersion, typeSlug), validates targeted single-node deltas, filters ValidationReport for tenant safety, and stays under the 2 ms p95 budget.
  </done>
</task>

<task type="auto" tdd="true">
  <name>Task 01-W3-02: Rule engine — four chains + Rule interface + Spring DI registration + source authority + conflict register + echo-loop suppression (RULE-01..06, RULE-08, VALID-05)</name>
  <files>
    fabric-rules/src/main/java/dev/tessera/rules/Rule.java,
    fabric-rules/src/main/java/dev/tessera/rules/RuleContext.java,
    fabric-rules/src/main/java/dev/tessera/rules/RuleOutcome.java,
    fabric-rules/src/main/java/dev/tessera/rules/Chain.java,
    fabric-rules/src/main/java/dev/tessera/rules/RuleEngine.java,
    fabric-rules/src/main/java/dev/tessera/rules/internal/ChainExecutor.java,
    fabric-rules/src/main/java/dev/tessera/rules/authority/SourceAuthorityService.java,
    fabric-rules/src/main/java/dev/tessera/rules/authority/internal/SourceAuthorityRepository.java,
    fabric-rules/src/main/java/dev/tessera/rules/conflicts/ConflictRegister.java,
    fabric-rules/src/main/java/dev/tessera/rules/chains/validate/SchemaRequiredFieldsRule.java,
    fabric-rules/src/main/java/dev/tessera/rules/chains/reconcile/SourceAuthorityRule.java,
    fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphServiceImpl.java,
    fabric-rules/src/test/java/dev/tessera/rules/ChainExecutorTest.java,
    fabric-rules/src/test/java/dev/tessera/rules/RuleInterfaceTest.java,
    fabric-rules/src/test/java/dev/tessera/rules/RuleOutcomeTest.java,
    fabric-rules/src/test/java/dev/tessera/rules/RuleRegistrationIT.java,
    fabric-rules/src/test/java/dev/tessera/rules/BusinessRuleRejectIT.java,
    fabric-rules/src/test/java/dev/tessera/rules/authority/SourceAuthorityIT.java,
    fabric-rules/src/test/java/dev/tessera/rules/conflicts/ConflictRegisterIT.java,
    fabric-rules/src/test/java/dev/tessera/rules/EchoLoopSuppressionIT.java
  </files>
  <read_first>
    - .planning/phases/01-graph-core-schema-registry-validation-rules/01-CONTEXT.md §D-C1 (six rule outcomes — COMMIT/REJECT/MERGE/OVERRIDE/ADD/ROUTE), §D-C2 (source_authority DDL), §D-C3 (reconciliation_conflicts DDL), §D-C4 (Rule interface)
    - .planning/phases/01-graph-core-schema-registry-validation-rules/01-RESEARCH.md §"Recommended Package Structure" (fabric-rules subtree), §"Authority Matrix Caching", §"Conflict Register"
    - fabric-app/src/main/resources/db/migration/V6__source_authority.sql
    - fabric-app/src/main/resources/db/migration/V7__reconciliation_conflicts.sql
  </read_first>
  <behavior>
    - Chain enum is `VALIDATE | RECONCILE | ENRICH | ROUTE`; RuleEngine runs them in that fixed compile-time order
    - REJECT in VALIDATE short-circuits — RECONCILE/ENRICH/ROUTE never run
    - Within a chain, rules sort by `priority()` **DESC** (higher runs first) per ADR-7 §RULE-01; `haltChain()` stops later rules in the same chain
    - Six outcomes: COMMIT (no-op), REJECT(reason), MERGE(propertySlug, value), OVERRIDE(propertySlug, value), ADD(propertySlug, value), ROUTE(target)
    - A business rule whose `evaluate` returns REJECT causes `GraphService.apply` to throw `RuleRejection` before anything is written (VALID-05)
    - Rules are Spring beans discovered via `List<Rule>` injection; filtered into chains by `Rule.chain()`
    - SourceAuthorityService reads `source_authority` rows into a Caffeine cache keyed by `(modelId, typeSlug, propertySlug)`, invalidated on updates
    - Two mutations on the same property from different sources → winner is determined by priority_order; loser row is inserted into reconciliation_conflicts with rule_id="SourceAuthorityRule", reason containing the priority_order
    - Every event written to graph_events carries `origin_connector_id` + `origin_change_id`; the echo-loop suppression rule rejects a mutation whose `(originConnectorId, originChangeId)` matches an already-committed event for the same `node_uuid`
  </behavior>
  <action>
    1. `Chain.java` — `public enum Chain { VALIDATE, RECONCILE, ENRICH, ROUTE }`.
    2. `Rule.java` — interface per **ADR-7 §RULE-02** verbatim (supersedes CONTEXT §D-C4 pre-ADR wording):
       ```java
       public interface Rule {
           String id();
           Chain chain();
           int priority();
           boolean applies(RuleContext ctx);
           RuleOutcome evaluate(RuleContext ctx);
       }
       ```
    3. `RuleOutcome.java` — `public sealed interface RuleOutcome permits Commit, Reject, Merge, Override, Add, Route` with record permits for each case per **ADR-7 §RULE-03** (COMMIT / REJECT / MERGE / OVERRIDE / ADD / ROUTE — no `FLAG_FOR_REVIEW`, no `DEFER` — those moved to Phase 2.5 per D-A2). Add a `halt()` default method returning `false` except for a `HaltingOutcome` wrapper.
    4. `RuleContext.java` — record `RuleContext(TenantContext tenant, GraphMutation mutation, NodeTypeDescriptor descriptor, GraphRepository reads, Map<String,Object> scratchpad)`. Rules MUST NOT perform I/O other than via `reads` (enforced by ArchUnit: no `java.net` / `HttpClient` / `WebClient` / `RestTemplate` imports in `dev.tessera.rules..`).
    5. `ChainExecutor.java` in `rules.internal` — takes a `RuleRepository` (see step 5b) at construction, resolves the active rule list per tenant, groups by `chain()`, sorts each chain by effective priority **DESC** (higher runs first) per ADR-7 §RULE-01. Method `List<RuleOutcome> run(Chain c, RuleContext ctx)` walks the sorted list, calling `applies`/`evaluate`; accumulates outcomes; on a `Reject` outcome returns early; on `halt()` truthy stops the chain but returns accumulated outcomes.
    5b. `RuleRepository.java` in `rules.internal` — per **ADR-7 §RULE-04** (hybrid Java-classes-plus-DB-activation model). Constructor takes `List<Rule>` (Spring DI) + `NamedParameterJdbcTemplate` + Caffeine. Core method `List<Rule> activeRulesFor(UUID modelId)`:
        - SELECT `rule_id, enabled, priority_override, parameters FROM reconciliation_rules WHERE model_id = :modelId`
        - Join against the in-memory `Map<String, Rule>` indexed by `Rule.id()`
        - Filter by `enabled = TRUE`
        - For each surviving `Rule`, if `priority_override IS NOT NULL`, wrap in a `PriorityOverrideRule` decorator that returns the override from `priority()`; else use the bean as-is
        - Sort DESC by effective priority per ADR-7 §RULE-01
        - Cache the resulting `List<Rule>` in Caffeine keyed by `modelId`, `maximumSize(10_000)`, `expireAfterAccess(Duration.ofHours(1))`, `recordStats()`
        - If the `reconciliation_rules` table has zero rows for a tenant (fresh tenant), default to ALL `List<Rule>` Spring beans enabled with bean-default priorities — this is the Phase 1 "no per-tenant activation configured yet" behavior
        - Method `void invalidate(UUID modelId)` clears the cache entry for a single tenant
    5c. `RuleAdminController.java` in `rules.admin` — `@RestController` with `@PostMapping("/admin/rules/reload/{modelId}")` that calls `ruleRepository.invalidate(modelId)`. Internal-only, protected identically to CircuitBreakerAdminController (`@PreAuthorize("hasRole('ADMIN')")` + `@ConditionalOnProperty("tessera.admin.internal-only")`). Phase 1 has no UI — the endpoint exists so Phase 2+ operator tooling can hot-reload rule activation per tenant without a process restart. Satisfies ADR-7 §RULE-04 "reloaded on change" requirement.
    6. `RuleEngine.java` — `@Service` facade around `ChainExecutor`; method `RuleOutcomes run(Chain c, RuleContext ctx)` delegates. Also provides a convenience `RuleOutcomes runAll(RuleContext ctx)` that runs all four chains in order and short-circuits on reject — this is the method called by GraphServiceImpl.
    7. `SourceAuthorityRepository.java` — JdbcTemplate wrapper around `source_authority`; methods `List<String> priorityFor(TenantContext, String typeSlug, String propertySlug)` and `void upsert(...)`.
    8. `SourceAuthorityService.java` — Caffeine-cached facade around the repository. Cache key `(modelId, typeSlug, propertySlug) → List<String>`, invalidated on upsert. Method `Optional<String> winner(TenantContext ctx, String typeSlug, String propertySlug, Collection<String> competingSourceSystems)` returns the source system that comes first in priority_order (or empty if none match).
    9. `ConflictRegister.java` — JdbcTemplate wrapper that INSERTs rows into `reconciliation_conflicts` with the D-C3 column set. Method `void record(TenantContext, UUID eventId, String typeSlug, UUID nodeUuid, String propertySlug, String winningSourceId, String winningSourceSystem, JsonNode winningValue, String losingSourceId, String losingSourceSystem, JsonNode losingValue, String ruleId, String reason)`. Inserts inside the caller's TX.
    10. Built-in rules shipped in Phase 1:
        - `SchemaRequiredFieldsRule` in `chains/validate` — Chain.VALIDATE, priority 100. `applies` always true. `evaluate` checks that every `descriptor.properties().stream().filter(PropertyDescriptor::required)` entry has a non-null payload value; returns REJECT on violation. (Overlaps SHACL sh:minCount — SHACL stays for shape, this catches Java-land issues pre-SHACL. Keep both: SHACL is the authoritative source.)
        - `SourceAuthorityRule` in `chains/reconcile` — Chain.RECONCILE, priority 100. For each payload property, looks up the current graph value's `_source` vs the mutation's `sourceSystem`, calls `sourceAuthorityService.winner(...)`. If the mutation is the winner → MERGE(propertySlug, newValue). If the mutation is the loser → call `conflictRegister.record(...)` with rule_id="SourceAuthorityRule" and reason="authority matrix override", and return OVERRIDE(propertySlug, existingValue) (meaning: overwrite the mutation's value with the existing one before it reaches GraphSession).
        - `EchoLoopSuppressionRule` in `chains/validate` — Chain.VALIDATE, priority 50 (runs before SchemaRequiredFieldsRule). For every mutation with `originConnectorId != null && originChangeId != null`, queries `graph_events` via GraphRepository: if there exists any event with matching origin pair and node_uuid → REJECT("echo loop suppressed"). (RULE-08)
    11. Edit `GraphServiceImpl.apply` — final pipeline:
        ```
        var descriptor = schemaRegistry.loadFor(m.tenantContext(), m.type());
        var ruleCtx = new RuleContext(m.tenantContext(), m, descriptor, graphRepository, new HashMap<>());
        var validate = ruleEngine.run(Chain.VALIDATE, ruleCtx);
        if (validate.hasReject()) throw new RuleRejection(validate.firstReject());
        var reconcile = ruleEngine.run(Chain.RECONCILE, ruleCtx);  // may MERGE/OVERRIDE/emit conflict
        var enrich = ruleEngine.run(Chain.ENRICH, ruleCtx);        // may ADD derived props
        GraphMutation finalMutation = ruleCtx.applyOutcomes(m, reconcile, enrich);  // fold outcomes into payload
        shaclValidator.validate(m.tenantContext(), descriptor, finalMutation);     // sync pre-commit
        var state = graphSession.apply(m.tenantContext(), finalMutation);
        var eventId = eventLog.append(m.tenantContext(), finalMutation, state, deriveEventType(finalMutation));
        outbox.append(m.tenantContext(), eventId, finalMutation.type(), state.uuid(), deriveEventType(finalMutation), state.properties(), routingHintsFrom(ruleEngine.run(Chain.ROUTE, ruleCtx.withEvent(eventId))));
        return GraphMutationOutcome.committed(state.uuid(), state.sequenceNr(), eventId);
        ```
        Whole method still `@Transactional(propagation = REQUIRED)` — single TX. Copy the outline from RESEARCH §"Pattern 1: Single Write Funnel".
    12. Tests (remove @Disabled on Wave 0 shells):
        - `ChainExecutorTest` (unit) — construct 4 fake rules spanning chains + priorities, assert execution order, assert REJECT short-circuits. (RULE-01)
        - `RuleInterfaceTest` (unit) — smoke test that a minimal `Rule` compiles and implements the interface. (RULE-02)
        - `RuleOutcomeTest` (unit) — each of 6 outcomes constructs and pattern-matches through a sealed switch. (RULE-03)
        - `RuleRegistrationIT` (integration) — boot Spring with the two built-in rules + one test rule; assert `List<Rule>` injection finds all three; assert they route to correct chains. **Additionally cover the ADR-7 §RULE-04 DB-activation path:** (a) empty `reconciliation_rules` table → all three rules active with default priorities; (b) INSERT a row with `enabled=false` for one rule_id → next `activeRulesFor(modelId)` call (after `invalidate(modelId)`) returns only the remaining two; (c) INSERT a row with `priority_override=999` for another rule_id → it sorts to the front of its chain; (d) hit `POST /admin/rules/reload/{modelId}` and assert the cache is re-read. (RULE-04)
        - `BusinessRuleRejectIT` — integration: register a rule returning REJECT; call `graphService.apply`; assert `RuleRejection` thrown, zero rows in graph_events. (VALID-05)
        - `SourceAuthorityIT` — integration: seed source_authority `(model_id, "Person", "email")` → `[crm, hr_system, obsidian]`; create Person with email from source `hr_system`; apply update from `obsidian` → conflict recorded, original email preserved; apply update from `crm` → email changes, previous (hr_system) value logged to conflicts. (RULE-05, RULE-06)
        - `ConflictRegisterIT` — integration: assert `reconciliation_conflicts` row shape matches D-C3; indexes work (`SELECT ... WHERE model_id=? AND node_uuid=?`). (RULE-06)
        - `EchoLoopSuppressionIT` — integration: apply mutation with origin_connector_id="c1", origin_change_id="x1"; apply again same origin pair → rejected. (RULE-08)
    13. Add ArchUnit rule in `fabric-rules/src/test/java/dev/tessera/arch/RuleEngineHygieneTest.java` (new file) forbidding `java.net`, `org.springframework.web.client`, `java.net.http.HttpClient`, `org.apache.jena` imports in `dev.tessera.rules..` — per RESEARCH §"Pattern 5: Hexagonal Write TX Boundary" (rules MUST NOT do I/O). **Additionally, add a second ArchRule in the same file that forbids any reference (field name, method name, string constant, or import) to the tokens `review_queue`, `FLAG_FOR_REVIEW`, or `DEFER` in any class under `dev.tessera.rules..`** — this structurally locks D-A2 (the review queue is a Phase 2.5 pre-funnel layer, NOT a rule-engine terminal outcome; per ADR-7 §RULE-03 the six legal outcomes are COMMIT/REJECT/MERGE/OVERRIDE/ADD/ROUTE). The rule uses ArchUnit's `containCypherStringConstant`-style custom `ArchCondition` pattern but searches field constants and class simple names for the banned tokens. W10 lock.
  </action>
  <verify>
    <automated>./mvnw -pl fabric-rules -am -Dit.test='RuleRegistrationIT,BusinessRuleRejectIT,SourceAuthorityIT,ConflictRegisterIT,EchoLoopSuppressionIT' -Dtest='ChainExecutorTest,RuleInterfaceTest,RuleOutcomeTest' verify</automated>
  </verify>
  <acceptance_criteria>
    - All 8 test commands exit 0 (ChainExecutorTest, RuleInterfaceTest, RuleOutcomeTest, RuleRegistrationIT, BusinessRuleRejectIT, SourceAuthorityIT, ConflictRegisterIT, EchoLoopSuppressionIT)
    - `grep -q "enum Chain { VALIDATE, RECONCILE, ENRICH, ROUTE }" fabric-rules/src/main/java/dev/tessera/rules/Chain.java` succeeds
    - `grep -q "sealed interface RuleOutcome" fabric-rules/src/main/java/dev/tessera/rules/RuleOutcome.java` succeeds
    - `grep -c "permits Commit, Reject, Merge, Override, Add, Route" fabric-rules/src/main/java/dev/tessera/rules/RuleOutcome.java` returns 1
    - `grep -q "ruleEngine.run(Chain.VALIDATE" fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphServiceImpl.java` succeeds
    - `grep -q "ruleEngine.run(Chain.RECONCILE" fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphServiceImpl.java` succeeds
    - `grep -q "shaclValidator.validate" fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphServiceImpl.java` succeeds (kept from Task 01-W3-01)
    - `grep -q "Caffeine" fabric-rules/src/main/java/dev/tessera/rules/authority/SourceAuthorityService.java` succeeds
    - `grep -q "reconciliation_rules" fabric-rules/src/main/java/dev/tessera/rules/internal/RuleRepository.java` succeeds (ADR-7 §RULE-04)
    - `grep -q "priority_override" fabric-rules/src/main/java/dev/tessera/rules/internal/RuleRepository.java` succeeds
    - ChainExecutor sorts DESC: `grep -Eq 'priority.*DESC|reversed\(\)|Comparator\.comparingInt.*reversed' fabric-rules/src/main/java/dev/tessera/rules/internal/ChainExecutor.java` succeeds (ADR-7 §RULE-01)
    - `grep -q "/admin/rules/reload" fabric-rules/src/main/java/dev/tessera/rules/admin/RuleAdminController.java` succeeds (ADR-7 §RULE-04 hot-reload)
    - `grep -q "@PreAuthorize" fabric-rules/src/main/java/dev/tessera/rules/admin/RuleAdminController.java` succeeds
    - `./mvnw -pl fabric-rules -Dtest=RuleEngineHygieneTest test` exits 0 (rules do no I/O AND no review_queue/FLAG_FOR_REVIEW/DEFER references — W10 structural lock on D-A2)
    - `grep -Erq 'review_queue|FLAG_FOR_REVIEW|DEFER' fabric-rules/src/main/java/dev/tessera/rules` returns ZERO hits (D-A2 structural lock)
    - Full `./mvnw -B verify` green end-to-end (Wave 1 + Wave 2 + Wave 3 all green together)
  </acceptance_criteria>
  <done>
    Rule engine with four named chains, six outcomes (ADR-7 §RULE-03), Spring DI + DB-backed activation (ADR-7 §RULE-04), DESC priority sort (ADR-7 §RULE-01), source authority matrix, conflict register, echo-loop suppression, and structural D-A2 lock (no review_queue references) are all operational. VALID-05 + RULE-01..06 + RULE-08 green.
  </done>
</task>

<task type="auto" tdd="true">
  <name>Task 01-W3-03: Write-amplification circuit breaker + DLQ + Micrometer + admin reset + WritePipelineBench gate (RULE-07)</name>
  <files>
    fabric-rules/src/main/java/dev/tessera/rules/circuit/WriteRateCircuitBreaker.java,
    fabric-rules/src/main/java/dev/tessera/rules/circuit/CircuitBreakerAdminController.java,
    fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphServiceImpl.java,
    fabric-core/src/jmh/java/dev/tessera/core/bench/WritePipelineBench.java,
    fabric-rules/src/test/java/dev/tessera/rules/circuit/CircuitBreakerTest.java,
    fabric-rules/src/test/java/dev/tessera/rules/circuit/CircuitBreakerIT.java
  </files>
  <read_first>
    - .planning/phases/01-graph-core-schema-registry-validation-rules/01-CONTEXT.md §D-D2, §D-D3 (copy exact parameters verbatim)
    - fabric-app/src/main/resources/db/migration/V8__connector_limits_and_dlq.sql
    - fabric-core/src/jmh/java/dev/tessera/core/bench/WritePipelineBench.java (Wave 1 baseline)
  </read_first>
  <behavior>
    - Circuit breaker holds an in-memory rolling 30-second window (AtomicLongArray of 30 slots of per-(connector,model) counters) per D-D2
    - Default threshold 500 events/sec per (connector_id, model_id); configurable via application.yml `tessera.circuit.threshold` and per-tenant override from `connector_limits` table
    - Startup grace window 60s per RESEARCH §"Open Questions Q5 RESOLVED" — during the first 60s of process uptime, samples are recorded but not evaluated. Configurable via `tessera.circuit.startup-grace` in `application.yml`, default `60000` ms
    - On trip: (1) mark connector halted in an in-memory Set<(connectorId, modelId)>, (2) GraphServiceImpl.apply throws `CircuitBreakerTrippedException` for any further mutation from that (connectorId, modelId), (3) queued-not-yet-applied events are routed to connector_dlq with `reason='circuit_breaker_tripped'` and raw payload preserved, (4) Micrometer counter `tessera.circuit.tripped{connector,model}` increments exactly once per trip, (5) events already committed BEFORE the trip remain committed (no rollback)
    - Admin reset: `POST /admin/connectors/{connectorId}/reset?modelId=...` clears the halted flag for that pair (internal-only — protected by a Spring Security config allowing only `ROLE_ADMIN` OR a feature flag `tessera.admin.internal-only=true`)
    - Full WritePipelineBench (SHACL + rules + Cypher + events + outbox) p95 < 11 ms on the Phase 0 100k dataset
  </behavior>
  <action>
    1. `WriteRateCircuitBreaker.java` in `rules.circuit` — per D-D2: `AtomicLongArray slots = new AtomicLongArray(30)` per `(connectorId, modelId)` key, held in `ConcurrentHashMap<BreakerKey, AtomicLongArray>`. On `record(connectorId, modelId)`: compute current slot = `(Instant.now().getEpochSecond() % 30)`, `slots.incrementAndGet(slot)`; on read: sum all 30 slots for events-in-last-30s; rate = sum/30. Method `boolean shouldTrip(connectorId, modelId)` compares against per-tenant threshold from `connector_limits` cache (default 500). **Startup grace per RESEARCH §Open Questions Q5 RESOLVED:** inject `@Value("${tessera.circuit.startup-grace:60000}")` long graceMs; track `startedAt = Instant.now()` at construction; `shouldTrip` returns `false` until `startedAt + graceMs`. During the grace window `record` still accumulates rate data (so the first post-grace evaluation has a warm window) but `shouldTrip` cannot trip. Add `application.yml` default `tessera.circuit.startup-grace: 60000`.
    2. `trip(connectorId, modelId, List<GraphMutation> queued)` method: (a) adds the key to a `Set<BreakerKey> halted` (ConcurrentHashMap.newKeySet); (b) for each queued mutation: INSERT into `connector_dlq` with `reason='circuit_breaker_tripped'`, `raw_payload=json(mutation)`, current timestamp; (c) increment `Counter.builder("tessera.circuit.tripped").tag("connector", connectorId).tag("model", modelId.toString()).register(meterRegistry).increment()`; (d) log a WARN with the (connectorId, modelId) pair.
    3. `GraphServiceImpl.apply`: at entry (BEFORE schema load), call `circuitBreaker.record(m.originConnectorId(), m.tenantContext().modelId())` and `if (circuitBreaker.isHalted(connectorId, modelId)) throw new CircuitBreakerTrippedException(connectorId)`. After successful apply, call `if (circuitBreaker.shouldTrip(connectorId, modelId)) circuitBreaker.trip(connectorId, modelId, List.of())` — in Phase 1 there is no queued-but-not-applied buffer (connectors land in Phase 2), so the queued list is always empty; the trip still halts subsequent apply() calls. Document this Phase-1 simplification in a TODO(phase2) comment.
    4. `CircuitBreakerAdminController.java` in `rules.circuit` — `@RestController` with `@PostMapping("/admin/connectors/{connectorId}/reset")` mapped to `breaker.reset(connectorId, modelId)`. Internal-only: add `@PreAuthorize("hasRole('ADMIN')")` AND a property check `@ConditionalOnProperty("tessera.admin.internal-only")`. The controller is registered only when this flag is true. Phase 1 default: enabled in dev profile, disabled elsewhere.
    5. `CircuitBreakerTest.java` (unit): construct breaker with threshold=10, grace=0 (override for test); record 11 events; `shouldTrip` returns true; record in two different (connector, model) keys — trip one, assert the other is NOT tripped. (Isolation)
    6. `CircuitBreakerIT.java` (integration with Testcontainers): boot Spring + breaker + Micrometer SimpleMeterRegistry; apply 501 mutations as connector "c1" to tenant A (serialized); assert 502nd throws `CircuitBreakerTrippedException`; assert `tessera.circuit.tripped{connector=c1,model=<A>}` counter == 1; assert connector_dlq table has 0 rows (Phase 1 has no queue buffer — documented); call admin `reset`; assert next mutation succeeds. (RULE-07)
    7. Re-run `WritePipelineBench.java` with the FULL pipeline in scope (SHACL + rules both wired in GraphServiceImpl by prior tasks in this wave). Update the JMH Javadoc: `"FULL-PIPELINE run — Phase 0 point-lookup baseline × 2 + ~9 ms SHACL+rules budget = 11 ms gate. Wave-1 BASELINE (no SHACL, no rules) captured in 01-W1-02 SUMMARY for comparison."` Set `@Fork(1) @Warmup(iterations = 5) @Measurement(iterations = 10)`. Parse JMH output and **fail the build** if p95 > 11 ms. Use the Phase 0 JMH harness (SeedGenerator from Phase 0 seeds a 100k dataset; bench calls `graphService.apply` with a random mutation per op). **Gate:** this full-pipeline JMH run must be green before Phase 1 is considered complete. The acceptance criterion compares the Wave-3 p95 against the 11 ms gate (NOT the Wave-1 3 ms baseline — those are two distinct numbers: the baseline measures cost of Cypher + events + outbox alone, the gate measures cost of the full pipeline including SHACL + rules).
  </action>
  <verify>
    <automated>./mvnw -pl fabric-rules -Dtest=CircuitBreakerTest -Dit.test=CircuitBreakerIT verify &amp;&amp; ./mvnw -pl fabric-core -Pjmh -Djmh.bench=WritePipelineBench -Djmh.dataset=100000 verify</automated>
  </verify>
  <acceptance_criteria>
    - `./mvnw -pl fabric-rules -Dtest=CircuitBreakerTest test` exits 0
    - `./mvnw -pl fabric-rules -Dit.test=CircuitBreakerIT verify` exits 0
    - `grep -q "AtomicLongArray" fabric-rules/src/main/java/dev/tessera/rules/circuit/WriteRateCircuitBreaker.java` succeeds (D-D2)
    - `grep -q "tessera.circuit.tripped" fabric-rules/src/main/java/dev/tessera/rules/circuit/WriteRateCircuitBreaker.java` succeeds (D-D3)
    - `grep -q "connector_dlq" fabric-rules/src/main/java/dev/tessera/rules/circuit/WriteRateCircuitBreaker.java` succeeds
    - `grep -q "/admin/connectors/{connectorId}/reset" fabric-rules/src/main/java/dev/tessera/rules/circuit/CircuitBreakerAdminController.java` succeeds (D-D3)
    - `grep -q "@PreAuthorize" fabric-rules/src/main/java/dev/tessera/rules/circuit/CircuitBreakerAdminController.java` succeeds
    - `./mvnw -pl fabric-core -Pjmh -Djmh.bench=WritePipelineBench -Djmh.dataset=100000 verify` reports **full-pipeline p95 < 11 ms** (distinct from the Wave-1 baseline captured in 01-W1-02 SUMMARY)
    - `grep -q "tessera.circuit.startup-grace" fabric-rules/src/main/java/dev/tessera/rules/circuit/WriteRateCircuitBreaker.java` succeeds (Q5 RESOLVED — configurable grace)
    - Full `./mvnw -B verify` green (Wave 1 + Wave 2 + Wave 3 all green together)
  </acceptance_criteria>
  <done>
    Circuit breaker trips at threshold, DLQs (empty in Phase 1 by design), increments Micrometer counter, is resettable via admin endpoint. Full WritePipelineBench with SHACL+rules < 11 ms p95. RULE-07 green. Phase 1 complete.
  </done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| mutation.payload → SHACL validator | Untrusted payload is evaluated against per-tenant shapes |
| rule engine → Cypher | Rules must not escape their sandbox (no I/O, no raw Cypher) |
| admin endpoint → circuit breaker state | Reset is an operator-only action |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-01-05 | Tampering | ShaclValidator synchronous pre-commit | mitigate | ShaclPreCommitIT asserts rollback atomicity when validation fails inside the @Transactional boundary (VALID-01) |
| T-01-06 | Information Disclosure | ValidationReport literal leaks | mitigate | ValidationReportFilter.redact strips literal values; ValidationReportFilterTest asserts no literal values leak; never pass raw Jena report to loggers (VALID-04) |
| T-01-08 | DoS | Write-amplification runaway | mitigate | WriteRateCircuitBreaker with 30s rolling window + 500/sec default threshold per D-D2; DLQ + Micrometer + admin reset per D-D3; CircuitBreakerIT proves trip + reset (RULE-07) |
| T-01-09 | Elevation of Privilege | Admin reset endpoint abuse | mitigate | `@PreAuthorize("hasRole('ADMIN')")` + `@ConditionalOnProperty("tessera.admin.internal-only")` disables controller outside internal profiles |
| T-01-10 | Tampering | Rule I/O escape | mitigate | RuleEngineHygieneTest ArchUnit rule forbids `java.net`, `HttpClient`, `org.apache.jena`, `org.springframework.web.client` imports under `dev.tessera.rules..` |
| T-01-19 | Information Disclosure | reconciliation_conflicts cross-tenant query | mitigate | All ConflictRegister reads WHERE model_id = ctx.modelId; ConflictRegisterIT seeds two tenants and asserts isolation |
| T-01-20 | Spoofing | Echo-loop replay | mitigate | EchoLoopSuppressionRule rejects mutations with matching (origin_connector_id, origin_change_id) for an existing event (RULE-08) |
</threat_model>

<verification>
`./mvnw -B verify` green. All 35 Phase 1 requirements validated by automated tests. ShaclValidationBench p95 < 2 ms. WritePipelineBench FULL-pipeline p95 < 11 ms (baseline from 01-W1-02 was < 3 ms). Phase 0 baseline benches still green (no regression).
</verification>

<success_criteria>
- VALID-01..05 all green
- RULE-01..08 all green
- Full GraphService.apply pipeline per RESEARCH §"Pattern 1" operational
- ShaclValidationBench p95 < 2 ms (cached, single-node)
- WritePipelineBench p95 < 11 ms (full pipeline, 100k dataset)
- Rule engine has zero I/O imports (ArchUnit-enforced)
- Circuit breaker admin reset endpoint protected by role + profile gate
</success_criteria>

<output>
After completion, create `.planning/phases/01-graph-core-schema-registry-validation-rules/01-W3-SUMMARY.md` — this SUMMARY marks Phase 1 complete and hands off to /gsd-plan-phase for Phase 2.
</output>
