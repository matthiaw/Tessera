---
phase: 01-graph-core-schema-registry-validation-rules
plan: W1
type: execute
wave: 2
depends_on: [W0]
files_modified:
  - fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphSession.java
  - fabric-core/src/main/java/dev/tessera/core/graph/internal/AgtypeBinder.java
  - fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphServiceImpl.java
  - fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphRepositoryImpl.java
  - fabric-core/src/main/java/dev/tessera/core/events/EventLog.java
  - fabric-core/src/main/java/dev/tessera/core/events/Outbox.java
  - fabric-core/src/main/java/dev/tessera/core/events/internal/SequenceAllocator.java
  - fabric-core/src/test/java/dev/tessera/core/graph/GraphServiceApplyIT.java
  - fabric-core/src/test/java/dev/tessera/core/graph/NodeLifecycleIT.java
  - fabric-core/src/test/java/dev/tessera/core/graph/EdgeLifecycleIT.java
  - fabric-core/src/test/java/dev/tessera/core/graph/SystemPropertiesIT.java
  - fabric-core/src/test/java/dev/tessera/core/graph/TombstoneSemanticsIT.java
  - fabric-core/src/test/java/dev/tessera/core/graph/TimestampOwnershipTest.java
  - fabric-core/src/test/java/dev/tessera/core/graph/property/TenantBypassPropertyIT.java
  - fabric-core/src/jmh/java/dev/tessera/core/bench/WritePipelineBench.java
  - fabric-core/src/test/java/dev/tessera/core/events/EventProvenanceSmokeIT.java
autonomous: true
requirements:
  - CORE-01
  - CORE-03
  - CORE-04
  - CORE-05
  - CORE-06
  - CORE-07
  - CORE-08

must_haves:
  truths:
    - "Every write goes through GraphService.apply() inside a single @Transactional boundary"
    - "Given tenant A and tenant B seeded in the same DB, no read or write operation as tenant A returns tenant B data (proven by jqwik fuzz across 7 ops)"
    - "Given a node UUID and tenant, the node has system properties uuid, model_id, _type, _created_at, _updated_at, _created_by, _source, _source_id"
    - "Given DELETE without opt-in, the node exists with a tombstone flag; given DELETE with explicit hard opt-in, the node is removed"
    - "WritePipelineBench p95 baseline (no SHACL, no rules — just Cypher + event append + outbox append) stays under 3 ms (Phase 0 point-lookup baseline × 3); Wave 3 re-runs with full pipeline and gates at 11 ms"
  artifacts:
    - path: fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphServiceImpl.java
      provides: "The single @Transactional write funnel for Phase 1"
      contains: "class GraphServiceImpl implements GraphService"
    - path: fabric-core/src/main/java/dev/tessera/core/events/internal/SequenceAllocator.java
      provides: "Per-tenant Postgres SEQUENCE allocation (EVENT-02)"
      contains: "nextval"
  key_links:
    - from: fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphServiceImpl.java
      to: fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphSession.java
      via: "@Transactional method calls GraphSession.apply + EventLog.append + Outbox.append"
      pattern: "@Transactional"
    - from: fabric-core/src/test/java/dev/tessera/core/graph/property/TenantBypassPropertyIT.java
      to: GraphService.apply
      via: "jqwik @Property seeds tenant A + B, runs ops as tenant A, asserts zero B uuids"
      pattern: "@Property"
---

<objective>
Implement the single write funnel (`GraphService.apply`) with its in-TX collaborators: `GraphSession` (raw-Cypher executor), `GraphRepository` (read path), `SequenceAllocator` (per-tenant SEQUENCE), `EventLog` (append-only writer), `Outbox` (same-TX outbox writer). Seed graph operations for nodes and edges (CREATE/UPDATE/TOMBSTONE), system properties (CORE-06), Tessera-owned timestamps (CORE-08), tombstone-default deletes (CORE-07). Prove tenant isolation via jqwik property tests (CORE-03) and capture a WritePipelineBench p95 baseline.

Purpose: Lock down the write path and tenant safety BEFORE Schema Registry and SHACL land, so everything downstream can assume a working, leak-proof funnel.
Output: A green GraphService that wires Cypher through the event log + outbox in one TX, with jqwik proving the leak surface is empty.
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@.planning/phases/01-graph-core-schema-registry-validation-rules/01-CONTEXT.md
@.planning/phases/01-graph-core-schema-registry-validation-rules/01-RESEARCH.md
@.planning/phases/01-graph-core-schema-registry-validation-rules/01-VALIDATION.md
@.planning/phases/01-graph-core-schema-registry-validation-rules/01-W0-PLAN.md
@fabric-core/src/main/java/dev/tessera/core/graph/GraphMutation.java
@fabric-core/src/main/java/dev/tessera/core/graph/GraphService.java
@fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphSession.java
@fabric-core/src/main/java/dev/tessera/core/tenant/TenantContext.java
@fabric-core/src/test/java/dev/tessera/core/support/AgePostgresContainer.java
@.planning/phases/00-foundations-risk-burndown/00-VERIFICATION.md
</context>

<tasks>

<task type="auto" tdd="true">
  <name>Task 01-W1-01: GraphSession + AgtypeBinder + GraphRepositoryImpl — raw-Cypher execution (CORE-04, CORE-05)</name>
  <files>
    fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphSession.java,
    fabric-core/src/main/java/dev/tessera/core/graph/internal/AgtypeBinder.java,
    fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphRepositoryImpl.java,
    fabric-core/src/test/java/dev/tessera/core/graph/NodeLifecycleIT.java,
    fabric-core/src/test/java/dev/tessera/core/graph/EdgeLifecycleIT.java,
    fabric-core/src/test/java/dev/tessera/core/graph/SystemPropertiesIT.java
  </files>
  <read_first>
    - fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphSession.java (Wave 0 skeleton)
    - .planning/phases/01-graph-core-schema-registry-validation-rules/01-RESEARCH.md §"Agtype Parameter Binding" (Idiom A — copy verbatim)
    - .planning/research/PITFALLS.md MIN-1, MIN-2
    - fabric-core/src/test/java/dev/tessera/core/support/AgePostgresContainer.java
  </read_first>
  <behavior>
    - GraphSession.apply(CREATE, "Person", payload={name:"Alice"}) creates node with system props (uuid auto, model_id from ctx, _type="Person", _created_at=Instant.now(), _created_by="system" or sourceId, _source=sourceSystem, _source_id=sourceId), returns NodeState
    - GraphSession.apply(UPDATE, node) leaves uuid and _created_at unchanged, updates _updated_at
    - GraphSession.apply(TOMBSTONE, node) sets _tombstoned=true and _tombstoned_at, does not DELETE from AGE
    - GraphSession.hardDelete(ctx, typeSlug, nodeUuid) — explicit opt-in, actually removes (CORE-07)
    - Edge CREATE via dedicated method apply(TenantContext, GraphMutation) when operation yields edge (sourceUuid+targetUuid in payload) — CORE-05
    - GraphRepositoryImpl.findNode returns Optional.empty when model_id does not match
    - GraphRepositoryImpl.queryAll returns only rows where n.model_id = ctx.modelId()
    - Edge case: payload supplies `_created_at` → IGNORED, Tessera value wins (CORE-08)
  </behavior>
  <action>
    1. `AgtypeBinder.java` (package `dev.tessera.core.graph.internal`): helper that takes a `Map<String,Object>` and serializes to a JSON string suitable for `::agtype` text-cast. Uses Jackson (already on classpath via spring-boot-starter-jpa). Static method `public static String toAgtypeJson(Map<String,Object>)`. Escapes UUIDs as strings, numbers as numbers, booleans as booleans. Rejects nested Collection values that aren't `Map` or `List<primitive>`.
    2. `GraphSession.java` (replace Wave 0 skeleton): constructor `GraphSession(NamedParameterJdbcTemplate jdbc)` (package-private — only the Spring config in `graph` package instantiates it). Public method `NodeState apply(TenantContext ctx, GraphMutation m)`:
       - Stamp Tessera-owned system properties: `_created_at` = `Instant.now()` on CREATE, `_updated_at` = `Instant.now()` on every op, `_created_by` = `m.sourceId()`, `_source` = `m.sourceSystem()`, `_source_id` = `m.sourceId()`, `model_id` = `ctx.modelId()`, `_type` = `m.type()`. Any payload key starting with `_created_at`/`_updated_at` is STRIPPED before merge — payload-supplied timestamps are rejected (CORE-08).
       - For CREATE: generate UUID, build Cypher per idiom A: `SELECT * FROM cypher('tessera_main', $$CREATE (n:%s $props) RETURN n$$, $1) AS (n agtype)` where `$1` is the agtype-cast JSON blob from AgtypeBinder (includes `model_id`, `uuid`, and all props) — label name is `m.type()` (validated against a `[A-Za-z_][A-Za-z0-9_]*` regex to prevent Cypher injection).
       - For UPDATE: MATCH by `(n:%s {model_id: $model_id, uuid: $uuid})` SET each property.
       - For TOMBSTONE: MATCH as UPDATE, SET `_tombstoned=true`, `_tombstoned_at=<timestamp>`.
       - Edge operations (when `m.type()` is an edge type, determined by a `#edge/` prefix or explicit `EDGE_TYPE` marker in the mutation — Wave 2 refines via schema lookup, Wave 1 uses a sentinel): MATCH source/target nodes by uuid + model_id, CREATE edge with properties including `model_id` on the edge. CORE-05.
       - `public void hardDelete(TenantContext ctx, String typeSlug, UUID uuid)` — opt-in `DETACH DELETE` path. Separate public method so the default `apply(TOMBSTONE)` never hard-deletes (CORE-07).
    3. `GraphRepositoryImpl.java` implementing `GraphRepository` — constructor takes `GraphSession`. Every query Cypher string includes `WHERE n.model_id = $model_id` and is executed via `GraphSession.query(ctx, cypher, params)`. Returns `NodeState` records mapped from agtype result.
    4. `NodeLifecycleIT.java`: @Testcontainers using AgePostgresContainer; `@Autowired GraphSession graphSession;` (via a minimal `@SpringBootTest(classes = ...)` slice) — apply CREATE Person name=Alice, assert find returns it with uuid non-null, _type="Person"; apply UPDATE changing name; assert find returns new name. 1000-line ceiling.
    5. `EdgeLifecycleIT.java`: create two Person nodes, create a KNOWS edge, assert edge is readable via a Cypher MATCH, apply TOMBSTONE, assert edge is marked tombstoned. (CORE-05)
    6. `SystemPropertiesIT.java`: create a node with minimal payload; assert ALL 8 system props present in the returned NodeState (uuid, model_id matches ctx, _type, _created_at within last 5s, _updated_at == _created_at on CREATE, _created_by, _source, _source_id). (CORE-06)
    7. TDD order: write failing test first, then implement GraphSession method, then green. Commit per RED/GREEN per project convention.
  </action>
  <verify>
    <automated>./mvnw -pl fabric-core -Dit.test='NodeLifecycleIT,EdgeLifecycleIT,SystemPropertiesIT' verify</automated>
  </verify>
  <acceptance_criteria>
    - `./mvnw -pl fabric-core -Dit.test=NodeLifecycleIT verify` exits 0 (CORE-04)
    - `./mvnw -pl fabric-core -Dit.test=EdgeLifecycleIT verify` exits 0 (CORE-05)
    - `./mvnw -pl fabric-core -Dit.test=SystemPropertiesIT verify` exits 0 (CORE-06)
    - `grep -q "model_id" fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphSession.java` succeeds
    - `grep -q "Instant.now" fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphSession.java` succeeds (Tessera-owned timestamps)
    - `./mvnw -pl fabric-app -Dtest=RawCypherBanTest test` remains green (no raw Cypher leaked outside graph.internal)
  </acceptance_criteria>
  <done>
    GraphSession implements CRUD for nodes + edges, stamps system properties, and is the only raw-Cypher class. The three IT tests pass.
  </done>
</task>

<task type="auto" tdd="true">
  <name>Task 01-W1-02: GraphServiceImpl + SequenceAllocator + EventLog + Outbox — single-TX write funnel (CORE-01, CORE-07, CORE-08, EVENT-02 partial)</name>
  <files>
    fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphServiceImpl.java,
    fabric-core/src/main/java/dev/tessera/core/events/EventLog.java,
    fabric-core/src/main/java/dev/tessera/core/events/Outbox.java,
    fabric-core/src/main/java/dev/tessera/core/events/internal/SequenceAllocator.java,
    fabric-core/src/test/java/dev/tessera/core/graph/GraphServiceApplyIT.java,
    fabric-core/src/test/java/dev/tessera/core/graph/TombstoneSemanticsIT.java,
    fabric-core/src/test/java/dev/tessera/core/graph/TimestampOwnershipTest.java,
    fabric-core/src/jmh/java/dev/tessera/core/bench/WritePipelineBench.java
  </files>
  <read_first>
    - .planning/phases/01-graph-core-schema-registry-validation-rules/01-RESEARCH.md §"Pattern 1: Single Write Funnel" (copy outline verbatim), §"Per-Tenant Monotonic Sequence"
    - .planning/phases/01-graph-core-schema-registry-validation-rules/01-CONTEXT.md §D-A1
    - fabric-app/src/main/resources/db/migration/V2__graph_events.sql
    - fabric-app/src/main/resources/db/migration/V3__graph_outbox.sql
    - fabric-core/src/jmh/java/dev/tessera/core/bench/WritePipelineBench.java (Wave 0 skeleton)
  </read_first>
  <behavior>
    - GraphServiceImpl.apply(mutation) runs inside a single @Transactional; if GraphSession throws, neither graph_events nor graph_outbox rows persist (rollback atomic)
    - Every successful apply() inserts exactly one graph_events row and one graph_outbox row
    - graph_events.sequence_nr is monotonic per model_id; two concurrent writes to different tenants NEVER share a sequence_nr within the same tenant, and a crash does not reuse sequence_nr
    - TimestampOwnershipTest: apply() ignores payload-supplied `_created_at`, stamps Tessera's Instant.now
    - TombstoneSemanticsIT: apply(TOMBSTONE) leaves node present with `_tombstoned=true`; after calling graphSession.hardDelete() the node is gone
    - WritePipelineBench (Wave-1 baseline — no SHACL, no rules, just Cypher + event append + outbox append) reports a p95 number; target < 3 ms (Phase 0 point-lookup baseline × 3). This is the baseline used to measure the cost added by Wave 2 (schema hook) and Wave 3 (SHACL + rules). Wave 3 re-runs the bench with the full pipeline and gates at p95 < 11 ms.
    - EventLog.append computes and persists the `delta` JSONB column on every row: on CREATE delta == full payload; on UPDATE delta == field-level diff of new state vs previous node state; on TOMBSTONE delta == `{"_tombstoned": true}` (EVENT-03)
    - EventLog.append writes `origin_connector_id` and `origin_change_id` from the incoming GraphMutation (proven by an EventProvenanceSmokeIT single-write assertion in this wave; full provenance IT lives in Wave 2)
  </behavior>
  <action>
    1. `SequenceAllocator.java` (package `dev.tessera.core.events.internal`): `public long nextSequenceNr(TenantContext ctx)`. Computes sequence name as `graph_events_seq_<hex-of-modelId-no-dashes>`. On first call per tenant, executes `CREATE SEQUENCE IF NOT EXISTS <name> AS BIGINT MINVALUE 1 CACHE 50` (wrapped in its own autonomous propagation? NO — keep in the current TX; sequence creation is idempotent and safe). Then `SELECT nextval('<name>')`. Uses `NamedParameterJdbcTemplate` injected by Spring. Per RESEARCH §"Per-Tenant Monotonic Sequence", this is the recommended allocation strategy. Cache the "already created" set in an in-memory `ConcurrentHashMap.KeySet` so the CREATE is skipped on subsequent calls.
    2. `EventLog.java` — `@Component` with constructor `EventLog(NamedParameterJdbcTemplate jdbc, SequenceAllocator allocator)`. Method `public UUID append(TenantContext ctx, GraphMutation m, NodeState state, String eventType, Map<String,Object> previousState)`: allocates sequence_nr, computes the `delta` JSONB payload per EVENT-03 rules (on CREATE delta == full payload; on UPDATE compute field-level diff between `state.properties()` and `previousState` — keys changed or added; on TOMBSTONE delta == `{"_tombstoned": true}`), INSERTs into `graph_events` with all 18 columns from V2 including both `payload` (full state) and `delta`. Every insert MUST persist `origin_connector_id` and `origin_change_id` from `m` — these are load-bearing for RULE-08 echo-loop suppression and for EVENT-03 provenance. Returns the new event id. Uses `NamedParameterJdbcTemplate.update`. The connection is shared via Spring TX synchronization — no `DataSource.getConnection()`. Add a small package-private helper `computeDelta(Operation op, Map<String,Object> newState, Map<String,Object> previousState)` that is directly testable by TimestampOwnershipTest's sister unit test if needed.
    3. `Outbox.java` — `@Component` with constructor `Outbox(NamedParameterJdbcTemplate jdbc)`. Method `public void append(TenantContext ctx, UUID eventId, String aggregateType, UUID aggregateId, String type, Map<String,Object> payload, Map<String,Object> routingHints)`: INSERTs one row into `graph_outbox` with status='PENDING' in the CURRENT TX (no new connection). Phase 4 Debezium swap will replace only the poller, not this writer.
    4. `GraphServiceImpl.java` — `@Service`, constructor takes `GraphSession`, `EventLog`, `Outbox`. The `apply(GraphMutation)` method is annotated `@Transactional(propagation = REQUIRED)` and implements the pipeline from RESEARCH §"Pattern 1: Single Write Funnel":
       ```
       authorize(m);                           // stub for Phase 2 — Phase 1 is a no-op
       // Wave 2 will inject SchemaRegistry lookup here
       // Wave 3 will inject ruleEngine.run + shaclValidator.validate here
       var state = graphSession.apply(m.tenantContext(), m);
       var eventId = eventLog.append(m.tenantContext(), m, state, deriveEventType(m));
       outbox.append(m.tenantContext(), eventId, m.type(), state.uuid(), deriveEventType(m), state.properties(), Map.of());
       return GraphMutationOutcome.committed(state.uuid(), /*seq*/ -1, eventId);
       ```
       Leave TODO(wave2/wave3) comments at the exact places where schema lookup and rule engine + SHACL will slot in so Waves 2 and 3 know where to land. The single `@Transactional` boundary wraps the entire method — this is the CORE-01 promise.
    5. `GraphServiceApplyIT.java`: Testcontainers. Wire a minimal Spring slice (`@SpringBootTest(classes={GraphServiceImpl.class, GraphSession.class, EventLog.class, Outbox.class, SequenceAllocator.class, ...})` or a real `@SpringBootTest` using the existing `fabric-app` boot class). Test cases: (a) success path — 1 event row + 1 outbox row present; (b) rollback injection — throw from inside GraphSession via a mocked/spied session, assert zero rows persisted in either table; (c) CORE-01 full atomicity.
    6. `TombstoneSemanticsIT.java`: CREATE node, apply TOMBSTONE, assert `_tombstoned=true` AND row still readable; call `graphSession.hardDelete` explicitly, assert row absent. (CORE-07)
    7. `TimestampOwnershipTest.java` (unit — no DB needed, mock GraphSession): construct a mutation with payload `{_created_at: "1999-01-01T00:00:00Z"}`; call GraphSession's payload-stripper helper (or extract the sanitize method and test it directly); assert the stripped payload has no `_created_at` key. (CORE-08)
    8. `WritePipelineBench.java`: fill the Wave 0 skeleton — `@Benchmark` calls `graphService.apply(MutationFixtures.sampleCreate())` against an AgePostgresContainer started in `@Setup`. Captures a SampleTime p95. **Wave 1 target: p95 < 3 ms (Phase 0 point-lookup baseline × 3)** — this is a *baseline* measurement with no SHACL, no rules, just Cypher + event append + outbox append. Mark the JMH class Javadoc with: `"BASELINE — used to measure cost added by Wave 2 (schema hook) and Wave 3 (SHACL + rules). Wave 3 re-runs with the full pipeline and gates at p95 < 11 ms."` The bench runs via `./mvnw -pl fabric-core -Pjmh -Djmh.bench=WritePipelineBench verify`; in Wave 1 the 3 ms target emits a console WARNING on breach but does not fail the build (gate lives in Wave 3).
    9. `EventProvenanceSmokeIT.java` (Wave-1 smoke): a single-write integration test — apply a mutation with `originConnectorId="smoke-conn"`, `originChangeId="smoke-chg-1"`; SELECT the single graph_events row via `NamedParameterJdbcTemplate`; assert `origin_connector_id='smoke-conn'` AND `origin_change_id='smoke-chg-1'` AND `delta IS NOT NULL`. Closes the Wave-1 gap where these columns could otherwise be silently skipped (full provenance IT is `EventProvenanceIT` in Wave 2).
  </action>
  <verify>
    <automated>./mvnw -pl fabric-core -Dit.test='GraphServiceApplyIT,TombstoneSemanticsIT' -Dtest=TimestampOwnershipTest verify</automated>
  </verify>
  <acceptance_criteria>
    - `./mvnw -pl fabric-core -Dit.test=GraphServiceApplyIT verify` passes (CORE-01)
    - `./mvnw -pl fabric-core -Dit.test=TombstoneSemanticsIT verify` passes (CORE-07)
    - `./mvnw -pl fabric-core -Dtest=TimestampOwnershipTest test` passes (CORE-08)
    - `grep -q "@Transactional" fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphServiceImpl.java` succeeds
    - `grep -q "nextval" fabric-core/src/main/java/dev/tessera/core/events/internal/SequenceAllocator.java` succeeds (EVENT-02 — no MAX()+1)
    - `grep -q "CACHE 50" fabric-core/src/main/java/dev/tessera/core/events/internal/SequenceAllocator.java` succeeds
    - grep -v "MAX(sequence_nr)" returns 0 hits in fabric-core/src/main (no anti-pattern)
    - `grep -q "origin_connector_id" fabric-core/src/main/java/dev/tessera/core/events/EventLog.java` succeeds (EVENT-03 provenance written on every append)
    - `grep -q "origin_change_id" fabric-core/src/main/java/dev/tessera/core/events/EventLog.java` succeeds
    - `grep -q "computeDelta\|delta" fabric-core/src/main/java/dev/tessera/core/events/EventLog.java` succeeds (EVENT-03 delta)
    - `./mvnw -pl fabric-core -Dit.test=EventProvenanceSmokeIT verify` exits 0 (Wave-1 smoke — full provenance IT in Wave 2)
    - `./mvnw -pl fabric-core -Pjmh -Djmh.bench=WritePipelineBench verify` produces a p95 measurement in the JMH output; Wave-1 BASELINE target < 3 ms (warning on breach, not build fail — gate lives in Wave 3 full-pipeline run)
    - `./mvnw -pl fabric-app -Dtest=RawCypherBanTest test` still green
  </acceptance_criteria>
  <done>
    GraphService.apply runs the full Wave-1 subset of the pipeline in a single TX with rollback atomicity; sequence_nr comes from per-tenant SEQUENCE; every event row carries `origin_connector_id`, `origin_change_id`, and computed `delta`; WritePipelineBench BASELINE p95 captured against the 3 ms Wave-1 target.
  </done>
</task>

<task type="auto" tdd="true">
  <name>Task 01-W1-03: TenantBypassPropertyIT — jqwik red-team fuzz across 7 ops (CORE-03)</name>
  <files>
    fabric-core/src/test/java/dev/tessera/core/graph/property/TenantBypassPropertyIT.java,
    fabric-core/src/test/java/dev/tessera/core/support/MutationFixtures.java
  </files>
  <read_first>
    - .planning/phases/01-graph-core-schema-registry-validation-rules/01-RESEARCH.md §"Jqwik Tenant-Bypass Fuzz" (copy skeleton verbatim)
    - .planning/phases/01-graph-core-schema-registry-validation-rules/01-CONTEXT.md §D-D1
    - fabric-core/src/test/java/dev/tessera/core/support/MutationFixtures.java (Wave 0)
    - fabric-core/src/test/java/dev/tessera/core/support/AgePostgresContainer.java
  </read_first>
  <behavior>
    - For each of 7 operations (create, get, query, update, tombstone, traverse, find_path): seed tenant A with N mutations, tenant B with M mutations, run op as tenant A, assert ZERO tenant-B uuids appear in any result. Minimum 1000 tries per op. Total wall-clock < 60s.
    - Shrinking produces minimal reproducer; failure prints the seed.
  </behavior>
  <action>
    1. Extend `MutationFixtures.java` with a full jqwik provider suite per RESEARCH §"Jqwik Tenant-Bypass Fuzz":
       - `@Provide Arbitrary<Pair<UUID,UUID>> tenantPair()` — two distinct random UUIDs.
       - `@Provide Arbitrary<GraphMutation> anyCreateFor(TenantContext ctx)` — wraps `Arbitraries.create(UUID::randomUUID)` + random type (`Arbitraries.of("Person","Role","Circle","Activity")`) + random payload (`@ForAll` string values).
       - `@Provide Arbitrary<List<GraphMutation>> mutationsFor(TenantContext ctx)` — list of 1..20 creates.
    2. `TenantBypassPropertyIT.java`: remove `@Disabled` from Wave 0 shell. Enable Testcontainers with `AgePostgresContainer`. Wire GraphService, GraphRepository, GraphSession via a `@SpringBootTest` slice (reuse the slice from GraphServiceApplyIT). Implement **seven** `@Property(tries=1000) @Report(Reporting.GENERATED)` methods, one per operation:
       - `queryCannotSeeOtherTenantNodes(...)` — seeds A + B with creates, runs `graphRepository.queryAll(ctxA, typeSlug)`, asserts `.extracting(NodeState::uuid).doesNotContainAnyElementsOf(bUuids)`.
       - `getCannotFetchOtherTenantNode(...)` — after seeding, calls `graphRepository.findNode(ctxA, typeSlug, bNodeUuid)` and asserts `Optional.empty()`.
       - `createCannotTouchOtherTenantNode(...)` — ctx=A, target uuid belongs to B; assert apply either creates a new node under A (different uuid) or rejects, but NEVER updates B's row.
       - `updateCannotTouchOtherTenantNode(...)`, `tombstoneCannotTouchOtherTenantNode(...)`, `traverseCannotSeeOtherTenantNodes(...)`, `findPathCannotSeeOtherTenantNodes(...)` — analogous.
       Each @Property uses `@Container static final PostgreSQLContainer<?> PG = AgePostgresContainer.create().withReuse(true)`. Before-each: truncate tables so each try starts clean (or use two random tenants per try — cleaner, faster).
    3. Wall-clock budget: run locally first to confirm < 60 s. If over, reduce `tries` per op to 200 until performance allows 1000 — target is 1000 per op (7000 total scenarios).
    4. When (not if) a property fails: print the seed via `@Report(Reporting.GENERATED)`, pick the shrunk counter-example, add an `@Example` regression method in the same class pinning that exact scenario, then fix the bypass in GraphSession/GraphRepository. This is the feedback loop per the manual-verification row in 01-VALIDATION.md.
    5. Add a class-level Javadoc comment to TenantBypassPropertyIT documenting the jqwik seed policy (RESEARCH §Open Questions Q4 RESOLVED): "CI runs jqwik with its default random seed (unseeded). Failing seeds are permanently captured as `@Example` regression tests in this class. Developers can locally re-run a specific failure with `@Seed(...)` from the jqwik console output."
  </action>
  <verify>
    <automated>./mvnw -pl fabric-core -Dit.test=TenantBypassPropertyIT verify</automated>
  </verify>
  <acceptance_criteria>
    - File `fabric-core/src/test/java/dev/tessera/core/graph/property/TenantBypassPropertyIT.java` exists WITHOUT `@Disabled`
    - `grep -c "@Property" fabric-core/src/test/java/dev/tessera/core/graph/property/TenantBypassPropertyIT.java` returns 7 or more (one per op)
    - `grep -q "tries = 1000" fabric-core/src/test/java/dev/tessera/core/graph/property/TenantBypassPropertyIT.java` succeeds (or a documented reduction with TODO link)
    - `grep -q "doesNotContainAnyElementsOf" fabric-core/src/test/java/dev/tessera/core/graph/property/TenantBypassPropertyIT.java` succeeds
    - `./mvnw -pl fabric-core -Dit.test=TenantBypassPropertyIT verify` exits 0 (all 7 properties green) within 60 seconds wall-clock
  </acceptance_criteria>
  <done>
    Jqwik exercises every read/write op across two random tenants 1000+ times each; CORE-03 "tenant bypass impossible" is empirically established; any failure has been minimized, converted to an @Example regression, and fixed.
  </done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| caller → GraphService.apply | Every Phase 2+ module enters the graph only through this method |
| GraphMutation payload → Cypher | Untrusted property keys and values flow into `agtype` JSON parameters |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-01-01 | Tampering | GraphService.apply atomicity | mitigate | Single `@Transactional(propagation=REQUIRED)` covering Session + EventLog + Outbox; GraphServiceApplyIT injects a rollback at GraphSession level and asserts zero rows in both tables |
| T-01-03 | Information Disclosure | Cross-tenant read/write | mitigate | Mandatory `TenantContext` parameter on every GraphSession / GraphRepository method; jqwik TenantBypassPropertyIT runs 7 properties × 1000 tries each; ArchUnit raw-Cypher ban (Wave 0) forces all Cypher through GraphSession where `WHERE n.model_id = $model_id` is enforced |
| T-01-04 | Tampering | Payload-supplied timestamps | mitigate | GraphSession strips `_created_at` / `_updated_at` from payload before merge; `event_time` column in V2 has `DEFAULT clock_timestamp()`; TimestampOwnershipTest asserts |
| T-01-13 | Tampering | Cypher injection via type_slug / label | mitigate | GraphSession validates label against `[A-Za-z_][A-Za-z0-9_]*` before string interpolation; parameters always travel as agtype JSON, never string-concatenated |
| T-01-14 | DoS | Sequence allocator lock contention | mitigate | Per-tenant `CREATE SEQUENCE ... CACHE 50` is lock-free (nextval never takes row locks); MOD-5 anti-pattern explicitly avoided |
</threat_model>

<verification>
`./mvnw -B verify` green. All CORE-01..08 ITs green. WritePipelineBench p95 captured (threshold enforced in Wave 3).
</verification>

<success_criteria>
- GraphServiceImpl implements single-TX write funnel (CORE-01)
- GraphSession is the only raw-Cypher class and still passes RawCypherBanTest (CORE-02)
- TenantBypassPropertyIT green with 7 × 1000 = 7000 scenarios (CORE-03)
- Node / edge / system-property / tombstone / timestamp ITs all green (CORE-04..08)
- SequenceAllocator uses per-tenant Postgres SEQUENCE with CACHE 50 (EVENT-02 — fully landed here for the allocator, event log/outbox writer already landed in this wave)
- WritePipelineBench Wave-1 BASELINE p95 captured against < 3 ms target (no SHACL, no rules); Wave 3 re-runs with full pipeline at < 11 ms
- EventLog writes `origin_connector_id`, `origin_change_id`, and `delta` on every row (EVENT-03)
- EventProvenanceSmokeIT green (Wave-1 gap closed)
</success_criteria>

<output>
After completion, create `.planning/phases/01-graph-core-schema-registry-validation-rules/01-W1-SUMMARY.md`.
</output>