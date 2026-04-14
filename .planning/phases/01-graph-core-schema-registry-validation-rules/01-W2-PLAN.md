---
phase: 01-graph-core-schema-registry-validation-rules
plan: W2
type: execute
wave: 3
depends_on: [W0, W1]
files_modified:
  - fabric-core/src/main/java/dev/tessera/core/schema/SchemaRegistry.java
  - fabric-core/src/main/java/dev/tessera/core/schema/SchemaVersionService.java
  - fabric-core/src/main/java/dev/tessera/core/schema/SchemaAliasService.java
  - fabric-core/src/main/java/dev/tessera/core/schema/NodeTypeDescriptor.java
  - fabric-core/src/main/java/dev/tessera/core/schema/PropertyDescriptor.java
  - fabric-core/src/main/java/dev/tessera/core/schema/EdgeTypeDescriptor.java
  - fabric-core/src/main/java/dev/tessera/core/schema/internal/SchemaRepository.java
  - fabric-core/src/main/java/dev/tessera/core/schema/internal/SchemaChangeReplayer.java
  - fabric-core/src/main/java/dev/tessera/core/schema/internal/SchemaDescriptorCache.java
  - fabric-core/src/main/java/dev/tessera/core/events/OutboxPoller.java
  - fabric-core/src/main/java/dev/tessera/core/events/GraphEventPublished.java
  - fabric-core/src/main/java/dev/tessera/core/events/internal/PartitionMaintenanceTask.java
  - fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphServiceImpl.java
  - fabric-core/src/test/java/dev/tessera/core/schema/SchemaNodeTypeCrudIT.java
  - fabric-core/src/test/java/dev/tessera/core/schema/SchemaPropertyCrudIT.java
  - fabric-core/src/test/java/dev/tessera/core/schema/SchemaEdgeTypeCrudIT.java
  - fabric-core/src/test/java/dev/tessera/core/schema/SchemaVersioningReplayIT.java
  - fabric-core/src/test/java/dev/tessera/core/schema/SchemaAliasIT.java
  - fabric-core/src/test/java/dev/tessera/core/schema/SchemaCacheInvalidationTest.java
  - fabric-core/src/test/java/dev/tessera/core/schema/SchemaBreakingChangeIT.java
  - fabric-core/src/test/java/dev/tessera/core/events/EventLogSchemaIT.java
  - fabric-core/src/test/java/dev/tessera/core/events/PerTenantSequenceIT.java
  - fabric-core/src/test/java/dev/tessera/core/events/EventProvenanceIT.java
  - fabric-core/src/test/java/dev/tessera/core/events/OutboxTransactionalIT.java
  - fabric-core/src/test/java/dev/tessera/core/events/OutboxPollerIT.java
  - fabric-core/src/test/java/dev/tessera/core/events/TemporalReplayIT.java
  - fabric-core/src/test/java/dev/tessera/core/events/AuditHistoryIT.java
autonomous: true
requirements:
  - SCHEMA-01
  - SCHEMA-02
  - SCHEMA-03
  - SCHEMA-04
  - SCHEMA-05
  - SCHEMA-06
  - SCHEMA-07
  - SCHEMA-08
  - EVENT-01
  - EVENT-02
  - EVENT-03
  - EVENT-04
  - EVENT-05
  - EVENT-06
  - EVENT-07

must_haves:
  truths:
    - "An operator (test harness) can CRUD schema_node_types, schema_properties, schema_edge_types"
    - "Every schema change appends a schema_change_event row and materializes a new schema_version snapshot"
    - "Renaming a property via alias preserves reads under the old slug; writes use the current slug"
    - "Schema descriptor lookups are Caffeine-cached and invalidate on any schema_change_event"
    - "Breaking changes (removing required field, narrowing cardinality, changing type) are rejected unless force=true"
    - "graph_events is monthly-partitioned and indexed on (model_id, sequence_nr) and (node_uuid)"
    - "sequence_nr comes from per-tenant SEQUENCE; two concurrent tenants never collide on the same number"
    - "Every event row carries full provenance (source_type, source_id, source_system, confidence, origin_connector_id, origin_change_id) AND both `payload` (full state) and `delta` (field-level diff) per EVENT-03"
    - "graph_outbox row is written in the same TX as graph_events and the Cypher mutation; rollback rolls all"
    - "OutboxPoller runs on @Scheduled(fixedDelay=500) with ShedLock + FOR UPDATE SKIP LOCKED; publishes via ApplicationEventPublisher"
    - "Given a node UUID and timestamp T, event replay reconstructs the node state at T"
    - "Given a node, full mutation history with cause attribution is retrievable"
  artifacts:
    - path: fabric-core/src/main/java/dev/tessera/core/schema/SchemaRegistry.java
      provides: "Schema Registry facade — SHACL/projections/MCP will consume this in Phase 2+"
      contains: "class SchemaRegistry"
    - path: fabric-core/src/main/java/dev/tessera/core/events/OutboxPoller.java
      provides: "In-process outbox poller with Spring ApplicationEventPublisher fan-out"
      contains: "@SchedulerLock"
    - path: fabric-core/src/main/java/dev/tessera/core/schema/internal/SchemaDescriptorCache.java
      provides: "Caffeine cache of compiled schema descriptors invalidated on schema change"
      contains: "Caffeine"
  key_links:
    - from: fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphServiceImpl.java
      to: fabric-core/src/main/java/dev/tessera/core/schema/SchemaRegistry.java
      via: "GraphService.apply calls schemaRegistry.loadFor(tenant, type) at step 2 of the pipeline"
      pattern: "schemaRegistry.loadFor"
    - from: fabric-core/src/main/java/dev/tessera/core/events/OutboxPoller.java
      to: org.springframework.context.ApplicationEventPublisher
      via: "publishEvent(new GraphEventPublished(...)) after UPDATE status=DELIVERED"
      pattern: "publishEvent"
---

<objective>
Stand up the two spines that Phase 1 exists to deliver: the **Schema Registry** (typed tables + event-sourced versioning + alias translation + Caffeine descriptor cache + breaking-change detector) and the **Event Log + Outbox** (the existing write path from Wave 1, now hardened with a working poller, provenance tests, partitioning proof, temporal replay, and audit history). Wire `SchemaRegistry.loadFor(...)` into `GraphServiceImpl.apply` at the TODO marker left by Wave 1 so future Waves can depend on schema being loaded before rules run.

Purpose: Make the graph knowable from its own schema store. Everything downstream (SHACL in Wave 3, projections in Phase 2, MCP in Phase 3) reads here.
Output: All 8 SCHEMA-* requirements and all 7 EVENT-* requirements green.
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
@.planning/phases/01-graph-core-schema-registry-validation-rules/01-W1-PLAN.md
@fabric-app/src/main/resources/db/migration/V2__graph_events.sql
@fabric-app/src/main/resources/db/migration/V3__graph_outbox.sql
@fabric-app/src/main/resources/db/migration/V4__schema_registry.sql
@fabric-app/src/main/resources/db/migration/V5__schema_versioning_and_aliases.sql
@fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphServiceImpl.java
</context>

<tasks>

<task type="auto" tdd="true">
  <name>Task 01-W2-01: Schema Registry — CRUD + versioning + aliases + Caffeine cache (SCHEMA-01..08)</name>
  <files>
    fabric-core/src/main/java/dev/tessera/core/schema/SchemaRegistry.java,
    fabric-core/src/main/java/dev/tessera/core/schema/SchemaVersionService.java,
    fabric-core/src/main/java/dev/tessera/core/schema/SchemaAliasService.java,
    fabric-core/src/main/java/dev/tessera/core/schema/NodeTypeDescriptor.java,
    fabric-core/src/main/java/dev/tessera/core/schema/PropertyDescriptor.java,
    fabric-core/src/main/java/dev/tessera/core/schema/EdgeTypeDescriptor.java,
    fabric-core/src/main/java/dev/tessera/core/schema/internal/SchemaRepository.java,
    fabric-core/src/main/java/dev/tessera/core/schema/internal/SchemaChangeReplayer.java,
    fabric-core/src/main/java/dev/tessera/core/schema/internal/SchemaDescriptorCache.java,
    fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphServiceImpl.java,
    fabric-core/src/test/java/dev/tessera/core/schema/SchemaNodeTypeCrudIT.java,
    fabric-core/src/test/java/dev/tessera/core/schema/SchemaPropertyCrudIT.java,
    fabric-core/src/test/java/dev/tessera/core/schema/SchemaEdgeTypeCrudIT.java,
    fabric-core/src/test/java/dev/tessera/core/schema/SchemaVersioningReplayIT.java,
    fabric-core/src/test/java/dev/tessera/core/schema/SchemaAliasIT.java,
    fabric-core/src/test/java/dev/tessera/core/schema/SchemaCacheInvalidationTest.java,
    fabric-core/src/test/java/dev/tessera/core/schema/SchemaBreakingChangeIT.java
  </files>
  <read_first>
    - .planning/phases/01-graph-core-schema-registry-validation-rules/01-CONTEXT.md §D-B1, §D-B2, §D-B3
    - fabric-app/src/main/resources/db/migration/V4__schema_registry.sql
    - fabric-app/src/main/resources/db/migration/V5__schema_versioning_and_aliases.sql
    - fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphServiceImpl.java (Wave 1 TODO marker for schema load)
  </read_first>
  <behavior>
    - createNodeType(ctx, spec) inserts schema_node_types row, appends schema_change_event, materializes new schema_version snapshot with is_current=true (old current → false)
    - listNodeTypes(ctx) returns live types for the tenant's current version
    - addProperty(ctx, typeSlug, prop) appends event, bumps version; old version still queryable via getAt(version_nr)
    - renameProperty(ctx, typeSlug, oldSlug, newSlug) inserts schema_property_aliases row; loadFor(ctx, typeSlug) still resolves old slug via alias
    - loadFor(ctx, typeSlug) is Caffeine-cached; first call hits DB, second call hits cache; any schema_change_event invalidates the cache key
    - Breaking change (remove required property, narrow cardinality "many→one", change data_type) — rejected with SchemaBreakingChangeException unless spec.force()==true
    - SchemaRegistry.loadFor is called from GraphServiceImpl.apply at the Wave 1 TODO marker between GraphSession.apply and EventLog.append (actually between authorize and any rule/validation — but Wave 3 wires rules); for Wave 2 just call loadFor BEFORE GraphSession.apply and pass the NodeTypeDescriptor as a parameter (or attach to RuleContext stub)
  </behavior>
  <action>
    1. `NodeTypeDescriptor.java`, `PropertyDescriptor.java`, `EdgeTypeDescriptor.java` — immutable records matching V4 columns. PropertyDescriptor carries `dataType`, `required`, `defaultValue` (JsonNode), `validationRules` (JsonNode), `enumValues`, `referenceTarget`. NodeTypeDescriptor has `List<PropertyDescriptor> properties()`, `long schemaVersion()`. Records are the cache values.
    2. `SchemaRepository.java` in `schema.internal` — `NamedParameterJdbcTemplate`-based CRUD for `schema_node_types`, `schema_properties`, `schema_edge_types`. Per research's D-B1 recommendation, use JdbcTemplate here (not JPA) because the tables mix typed columns with JSONB fields — JPA fights JSONB. RowMappers for each descriptor type.
    3. `SchemaVersionService.java` — handles `schema_change_event` append + `schema_version` snapshot materialization. Method `long applyChange(TenantContext ctx, SchemaChange change)`:
       - Serializes `change` to JSONB
       - INSERTs into `schema_change_event`
       - Reads current version's snapshot (or builds from scratch on first call)
       - Applies the change to build the new snapshot JSON
       - INSERTs new `schema_version` row with `is_current=true`, sets previous `is_current=false` in same TX
       - Returns new `version_nr`
       Runs inside caller's TX (propagation=REQUIRED).
    4. `SchemaAliasService.java` — CRUD for `schema_property_aliases` and `schema_edge_type_aliases`. Method `Optional<String> resolveCurrentSlug(TenantContext ctx, String typeSlug, String maybeOldSlug)` reads from `schema_property_aliases`. Called by `SchemaRegistry.loadFor` when a caller passes a slug that doesn't resolve directly.
    5. `SchemaDescriptorCache.java` — wraps Caffeine: `Cache<DescriptorKey, NodeTypeDescriptor>` where `DescriptorKey = (UUID modelId, String typeSlug, long schemaVersion)`. Configuration: `maximumSize(10_000)`, `expireAfterAccess(Duration.ofHours(1))`, `recordStats()`. Exposes `get(key, loader)` and `invalidateAll(modelId)` (called after any schema_change_event).
    6. `SchemaRegistry.java` — `@Service` facade, constructor takes Repository + VersionService + AliasService + DescriptorCache. Public API:
       - `NodeTypeDescriptor createNodeType(TenantContext ctx, CreateNodeTypeSpec spec)` (SCHEMA-01)
       - `PropertyDescriptor addProperty(TenantContext ctx, String typeSlug, AddPropertySpec spec)` (SCHEMA-02)
       - `EdgeTypeDescriptor createEdgeType(TenantContext ctx, CreateEdgeTypeSpec spec)` (SCHEMA-03)
       - `NodeTypeDescriptor loadFor(TenantContext ctx, String typeSlug)` — Caffeine-cached, resolves aliases (SCHEMA-05, SCHEMA-06, SCHEMA-07)
       - `NodeTypeDescriptor getAt(TenantContext ctx, String typeSlug, long versionNr)` — historical read via `schema_version` snapshot (SCHEMA-04)
       - `PropertyAlias renameProperty(TenantContext ctx, String typeSlug, String oldSlug, String newSlug)` (SCHEMA-05)
       - `void applyChangeOrReject(TenantContext ctx, SchemaChange change, boolean force)` — runs **breaking-change detector**: compare new snapshot to current; if removing a required property, narrowing edge cardinality, or changing `data_type`, throw `SchemaBreakingChangeException` unless `force=true` (SCHEMA-08).
       - After any successful mutation: invalidate `descriptorCache` for the affected `(modelId, typeSlug)`. (SCHEMA-06)
    7. Edit `GraphServiceImpl.java` to call `schemaRegistry.loadFor(m.tenantContext(), m.type())` BEFORE `graphSession.apply(...)`. Throw `UnknownTypeException` if `loadFor` returns null. This fulfills the "schema is loaded before mutation" half of the pipeline (SHACL/rules come in Wave 3). Descriptor is not yet used to validate fields — that's Wave 3 SHACL — but the call path must exist now.
    8. ITs (de-@Disable each Wave 0 shell and fill):
       - `SchemaNodeTypeCrudIT`: create → list → get → update description → deprecate — all roundtrip through SchemaRegistry (SCHEMA-01).
       - `SchemaPropertyCrudIT`: create type, add 3 properties of different dataType, list, deprecate one (SCHEMA-02).
       - `SchemaEdgeTypeCrudIT`: create source + target types, create edge type referencing them (SCHEMA-03).
       - `SchemaVersioningReplayIT`: seed type with 2 properties (version 1), add 3rd property (version 2), `getAt(ctx, slug, 1)` returns version 1 snapshot (2 props); `loadFor(ctx, slug)` returns version 2 (3 props) (SCHEMA-04).
       - `SchemaAliasIT`: create type with property `fullName`; rename to `name`; `loadFor` via alias resolution returns the descriptor; a schema lookup by old slug "fullName" still works (SCHEMA-05).
       - `SchemaCacheInvalidationTest` (unit, no DB — inject mock loader): cache hit on second call; after emitting a schema_change_event via the registry, next call misses (SCHEMA-06).
       - `SchemaBreakingChangeIT`: create type with required property `email`; attempt to remove `email` via `applyChangeOrReject(ctx, removeRequired, false)` → throws; repeat with `force=true` → succeeds (SCHEMA-08).
       - SCHEMA-07 (source of truth for SHACL) is validated by `SchemaToShaclIT` in Wave 3, not here — this plan's contribution is that `loadFor` returns a stable descriptor.
  </action>
  <verify>
    <automated>./mvnw -pl fabric-core -Dit.test='Schema*IT' -Dtest='Schema*Test' verify</automated>
  </verify>
  <acceptance_criteria>
    - All 7 schema IT/unit test commands exit 0: SchemaNodeTypeCrudIT, SchemaPropertyCrudIT, SchemaEdgeTypeCrudIT, SchemaVersioningReplayIT, SchemaAliasIT, SchemaCacheInvalidationTest, SchemaBreakingChangeIT
    - `grep -q "Caffeine" fabric-core/src/main/java/dev/tessera/core/schema/internal/SchemaDescriptorCache.java` succeeds
    - `grep -q "recordStats" fabric-core/src/main/java/dev/tessera/core/schema/internal/SchemaDescriptorCache.java` succeeds
    - `grep -q "schemaRegistry.loadFor" fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphServiceImpl.java` succeeds
    - `grep -q "SchemaBreakingChangeException" fabric-core/src/main/java/dev/tessera/core/schema/SchemaRegistry.java` succeeds
    - `grep -q "is_current" fabric-core/src/main/java/dev/tessera/core/schema/SchemaVersionService.java` succeeds
    - Full `./mvnw -B verify` green (Wave 1 ITs still pass — loadFor doesn't break existing flows; configure a default NodeTypeDescriptor for unregistered types OR make Wave 1 ITs register their types first; prefer the latter for correctness)
  </acceptance_criteria>
  <done>
    SchemaRegistry implements CRUD + versioning + aliases + cache + breaking-change detection per D-B1/B2/B3; GraphService.apply calls loadFor before GraphSession.apply; all 8 SCHEMA-* requirements green.
  </done>
</task>

<task type="auto" tdd="true">
  <name>Task 01-W2-02: Event Log hardening — provenance, partitioning, per-tenant sequence, temporal replay, audit history (EVENT-01, EVENT-02, EVENT-03, EVENT-06, EVENT-07)</name>
  <files>
    fabric-core/src/test/java/dev/tessera/core/events/EventLogSchemaIT.java,
    fabric-core/src/test/java/dev/tessera/core/events/PerTenantSequenceIT.java,
    fabric-core/src/test/java/dev/tessera/core/events/EventProvenanceIT.java,
    fabric-core/src/test/java/dev/tessera/core/events/TemporalReplayIT.java,
    fabric-core/src/test/java/dev/tessera/core/events/AuditHistoryIT.java,
    fabric-core/src/main/java/dev/tessera/core/events/EventLog.java
  </files>
  <read_first>
    - fabric-app/src/main/resources/db/migration/V2__graph_events.sql
    - fabric-core/src/main/java/dev/tessera/core/events/EventLog.java (Wave 1)
    - fabric-core/src/main/java/dev/tessera/core/events/internal/SequenceAllocator.java (Wave 1)
    - .planning/phases/01-graph-core-schema-registry-validation-rules/01-RESEARCH.md §"Event Log Schema", §"Per-Tenant Monotonic Sequence"
  </read_first>
  <behavior>
    - Event rows contain every column mandated by D-A1 / EVENT-03: source_type, source_id, source_system, confidence, extractor_version (nullable), llm_model_id (nullable), origin_connector_id, origin_change_id
    - `graph_events` table confirms partitioning (`PARTITION BY RANGE (event_time)`) and the three indexes (model_id,sequence_nr; node_uuid; model_id,type_slug,event_time)
    - Running 1000 mutations against tenant A and 1000 against tenant B in parallel produces 1000 rows each with sequence_nr 1..1000 (possibly with gaps due to CACHE 50, but always monotonic per tenant and never colliding across tenants)
    - EventLog.replayToState(ctx, nodeUuid, Instant T) returns the state of the node at time T by folding all events with event_time ≤ T
    - EventLog.history(ctx, nodeUuid) returns ordered List<EventRow> with full provenance fields populated
  </behavior>
  <action>
    1. Extend `EventLog.java`:
       - Add `public Map<String,Object> replayToState(TenantContext ctx, UUID nodeUuid, Instant at)` — SELECT all events where `model_id=$1 AND node_uuid=$2 AND event_time <= $3 ORDER BY sequence_nr`, fold `payload` JSONB into an accumulating map (UPDATE → merge delta, TOMBSTONE → add `_tombstoned=true`). Returns the final state. (EVENT-06)
       - Add `public List<EventRow> history(TenantContext ctx, UUID nodeUuid)` — SELECT all events ordered by sequence_nr; map to `EventRow` record with provenance fields. Caller can render the audit trail. (EVENT-07)
       - Confirm `append(...)` (from Wave 1) writes all 17 columns including `origin_connector_id` and `origin_change_id` from the mutation. (EVENT-03, RULE-08 partial — full RULE-08 test is in Wave 3)
    2. `EventLogSchemaIT.java`: boot the Testcontainer, run a `SELECT * FROM pg_indexes WHERE tablename = 'graph_events'` and assert the three required indexes exist by name; run `SELECT partition_bound FROM pg_catalog.pg_partitioned_table` to confirm partitioning; attempt `DELETE FROM graph_events` and assert it fails or at least that no row is removed — actually: assert `graph_events` has no trigger or policy preventing DELETE, but Tessera code never issues DELETE (append-only is a convention in code, not a DB constraint; document this in the test comment). The actual test: `SELECT count(*) FROM pg_indexes WHERE tablename LIKE 'graph_events%' AND indexname IN ('idx_graph_events_model_seq', 'idx_graph_events_node_uuid', 'idx_graph_events_model_type_time')` returns 3. (EVENT-01)
    3. `PerTenantSequenceIT.java`: Seed 200 mutations for tenant A and 200 for tenant B, serially (Wave 1 concurrency semantics are not guaranteed yet). Assert `SELECT model_id, array_agg(sequence_nr ORDER BY sequence_nr) FROM graph_events GROUP BY model_id` returns two rows, each with a monotonic list 1..200 (gaps allowed due to CACHE 50 but each tenant's list is strictly monotonic, no duplicates, min ≥ 1). Cross-tenant collision check: the `(model_id, sequence_nr)` unique index catches duplicates automatically. (EVENT-02)
    4. `EventProvenanceIT.java`: Apply a mutation with every D-A1 field populated (sourceType=EXTRACTION, extractorVersion="test-v1", llmModelId="claude-3", confidence=0.87, originConnectorId="obsidian-1", originChangeId="chunk-42"). Query `graph_events` directly and assert every column matches. **Also assert the `delta` JSONB column is populated correctly for each of CREATE/UPDATE/TOMBSTONE:** for CREATE the test row's delta equals the full payload; for UPDATE (apply a second mutation that changes one property) the delta contains only the changed key; for TOMBSTONE the delta contains `{"_tombstoned": true}`. (EVENT-03)
    5. `TemporalReplayIT.java`: Create Person with name=Alice at T0; update to name=Bob at T1; tombstone at T2. Call `EventLog.replayToState(ctx, uuid, T0+epsilon)` → `{name: Alice}`; replayToState(T1+epsilon) → `{name: Bob}`; replayToState(T2+epsilon) → contains `_tombstoned=true`. (EVENT-06)
    6. `AuditHistoryIT.java`: Same seed as TemporalReplayIT; call `EventLog.history(ctx, uuid)` and assert 3 rows returned in sequence_nr order with the correct event types CREATE_NODE, UPDATE_NODE, TOMBSTONE_NODE, and each row has the mutation's origin_connector_id / origin_change_id populated. (EVENT-07)
    7. `PartitionMaintenanceTask.java` in `events.internal` — per RESEARCH §Open Questions Q2 RESOLVED, a hand-rolled monthly partition creator (pg_partman deliberately avoided to contain CRIT-1/2 extension risk). `@Component` with a `@Scheduled(cron = "0 0 3 1 * *")` (3 AM on the 1st of each month) method that computes the next month's partition name (`graph_events_y<YYYY>m<MM>`) and runs `CREATE TABLE IF NOT EXISTS <name> PARTITION OF graph_events FOR VALUES FROM ('<first-of-month>') TO ('<first-of-next-month>')` via `NamedParameterJdbcTemplate`. Wrap in ShedLock (`@SchedulerLock(name = "partition-maintenance")`) to ensure single-runner across any future multi-instance deploys. A companion unit test is NOT required in Wave 2 — the V2 migration already ships the initial Apr 2026 partition; the scheduled task exists so production runs do not need a manual intervention at month rollover. Add a short Javadoc explaining this is the hand-rolled alternative to pg_partman per the ADR/research resolution.
    8. Wave 1's Outbox transactional atomicity test is already GREEN; this task re-asserts it in EVENT-04 coverage by running `OutboxTransactionalIT` which is filled in Task 01-W2-03 below.
  </action>
  <verify>
    <automated>./mvnw -pl fabric-core -Dit.test='EventLogSchemaIT,PerTenantSequenceIT,EventProvenanceIT,TemporalReplayIT,AuditHistoryIT' verify</automated>
  </verify>
  <acceptance_criteria>
    - All five IT tests exit 0
    - `grep -q "replayToState" fabric-core/src/main/java/dev/tessera/core/events/EventLog.java` succeeds
    - `grep -q "history" fabric-core/src/main/java/dev/tessera/core/events/EventLog.java` succeeds
    - `grep -q "origin_connector_id" fabric-core/src/test/java/dev/tessera/core/events/EventProvenanceIT.java` succeeds
    - `grep -q "delta" fabric-core/src/test/java/dev/tessera/core/events/EventProvenanceIT.java` succeeds (EVENT-03 delta assertion for CREATE/UPDATE/TOMBSTONE)
    - EVENT-01 index check passes (three named indexes present)
  </acceptance_criteria>
  <done>
    Event log is partitioned, indexed, per-tenant-sequenced, carries full provenance, supports temporal replay and audit history. EVENT-01, EVENT-02, EVENT-03, EVENT-06, EVENT-07 all green.
  </done>
</task>

<task type="auto" tdd="true">
  <name>Task 01-W2-03: Outbox poller — @Scheduled + ShedLock + SKIP LOCKED + ApplicationEventPublisher (EVENT-04, EVENT-05)</name>
  <files>
    fabric-core/src/main/java/dev/tessera/core/events/OutboxPoller.java,
    fabric-core/src/main/java/dev/tessera/core/events/GraphEventPublished.java,
    fabric-core/src/main/java/dev/tessera/core/events/Outbox.java,
    fabric-core/src/test/java/dev/tessera/core/events/OutboxTransactionalIT.java,
    fabric-core/src/test/java/dev/tessera/core/events/OutboxPollerIT.java
  </files>
  <read_first>
    - fabric-app/src/main/resources/db/migration/V3__graph_outbox.sql
    - .planning/phases/01-graph-core-schema-registry-validation-rules/01-RESEARCH.md §"Transactional Outbox" (copy poll block verbatim)
    - fabric-core/src/main/java/dev/tessera/core/events/Outbox.java (Wave 1)
  </read_first>
  <behavior>
    - Write path: a rollback inside GraphService.apply leaves both graph_events AND graph_outbox empty (rollback injection test from Wave 1 re-asserted here with explicit focus on the outbox table)
    - Poll path: @Scheduled(fixedDelay=500) picks up PENDING rows via `FOR UPDATE SKIP LOCKED LIMIT 100`, publishes `GraphEventPublished` on Spring's ApplicationEventPublisher, UPDATEs status='DELIVERED' with delivered_at=now()
    - ShedLock prevents double-processing across instances/restarts
    - A test listener (`@EventListener`) receives the published event; within 2 seconds of `GraphService.apply(...)` the listener callback has been invoked exactly once per mutation
  </behavior>
  <action>
    1. `GraphEventPublished.java` — `public record GraphEventPublished(UUID modelId, UUID eventId, String aggregateType, UUID aggregateId, String type, Map<String,Object> payload, Map<String,Object> routingHints, Instant deliveredAt)`. Immutable, Spring event payload.
    2. Confirm `Outbox.append(...)` from Wave 1 writes every column (id, model_id, event_id, aggregatetype, aggregateid, type, payload, status='PENDING', created_at). If Wave 1 skipped any, fill them here.
    3. `OutboxPoller.java` — `@Component`, constructor takes `NamedParameterJdbcTemplate` + `ApplicationEventPublisher` + Jackson `ObjectMapper`. Copy the poll block from RESEARCH §"Transactional Outbox" VERBATIM:
       ```java
       @Scheduled(fixedDelay = 500)
       @SchedulerLock(name = "outbox-poller", lockAtMostFor = "30s", lockAtLeastFor = "100ms")
       public void poll() {
           List<OutboxRow> pending = jdbc.query(
               "SELECT * FROM graph_outbox WHERE status='PENDING' ORDER BY created_at LIMIT 100 FOR UPDATE SKIP LOCKED",
               rowMapper
           );
           for (var row : pending) {
               publisher.publishEvent(new GraphEventPublished(row...));
               jdbc.update("UPDATE graph_outbox SET status='DELIVERED', delivered_at=clock_timestamp() WHERE id=:id",
                   Map.of("id", row.id()));
           }
       }
       ```
       Wrap the whole method in its own `@Transactional(propagation = REQUIRES_NEW)` so each poll batch is its own TX; ShedLock is configured via the existing Spring Boot auto-configuration (add `@EnableSchedulerLock(defaultLockAtMostFor = "PT30S")` in `fabric-app`'s main config class; add `net.javacrumbs.shedlock:shedlock-spring` + `shedlock-provider-jdbc-template` dependencies if not already via parent BOM — check parent pom.xml first; research says ShedLock 5.16.0 is on classpath).
       **IMPORTANT — routing_hints column mapping:** the `rowMapper` inside `pollBatch()` MUST explicitly read the `routing_hints` JSONB column from the ResultSet via `rs.getString("routing_hints")` (parsed through Jackson to `Map<String,Object>`, null-safe) and pass it to the `GraphEventPublished` constructor. The column is nullable; a null value becomes `Map.of()` in the published record. Without this mapping the poller silently drops the Phase 2+ routing plumbing.
    4. A `@TransactionalEventListener(phase = AFTER_COMMIT)` is **NOT** needed on the poller side — the poller runs AFTER commit by definition (it reads committed rows). However: document in a comment that Spring's `@TransactionalEventListener` would be an alternative to polling IF we didn't need Debezium-swap portability. We stick with polling.
    5. `OutboxTransactionalIT.java` (re-fills Wave 0 shell): success path asserts 1 row per mutation with status going from PENDING → DELIVERED within 2 s. Failure path: spy GraphSession to throw mid-apply, assert zero rows in `graph_outbox` AND zero in `graph_events` (rollback atomic — EVENT-04).
    6. `OutboxPollerIT.java`: set up a test `@EventListener(GraphEventPublished.class)` that records received events in a `CopyOnWriteArrayList`; apply 5 mutations; `Awaitility.await().atMost(3, SECONDS).until(() -> received.size() == 5)`; assert received events match applied mutations; assert all 5 outbox rows are status='DELIVERED' in DB. **Additionally, directly INSERT one `graph_outbox` row with a non-null `routing_hints` JSONB (e.g. `{"projection":"rest"}`) and assert the published `GraphEventPublished` record arrives at the listener with `routingHints` populated — proves the rowMapper plumbs the column through.** (EVENT-05)
  </action>
  <verify>
    <automated>./mvnw -pl fabric-core -Dit.test='OutboxTransactionalIT,OutboxPollerIT' verify</automated>
  </verify>
  <acceptance_criteria>
    - Both IT tests exit 0
    - `grep -q "@Scheduled(fixedDelay = 500)" fabric-core/src/main/java/dev/tessera/core/events/OutboxPoller.java` succeeds
    - `grep -q "FOR UPDATE SKIP LOCKED" fabric-core/src/main/java/dev/tessera/core/events/OutboxPoller.java` succeeds
    - `grep -q "@SchedulerLock" fabric-core/src/main/java/dev/tessera/core/events/OutboxPoller.java` succeeds
    - `grep -q "publishEvent" fabric-core/src/main/java/dev/tessera/core/events/OutboxPoller.java` succeeds
    - `grep -q "routing_hints" fabric-core/src/main/java/dev/tessera/core/events/OutboxPoller.java` succeeds (W8 — explicit column mapping)
    - Full `./mvnw -B verify` green
  </acceptance_criteria>
  <done>
    In-process outbox delivery works end-to-end: same-TX write, poller reads with SKIP LOCKED, publishes via Spring event bus, marks DELIVERED. Rollback atomicity proven. EVENT-04 and EVENT-05 green.
  </done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| Schema Registry API → DDL consumers (Wave 3 SHACL, Phase 2 projections) | A schema row is trusted shape metadata; a corrupted row is a cross-tenant contamination risk |
| graph_outbox row → ApplicationEventPublisher listeners | Events fan out to in-process listeners which may run with different privileges |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-01-07 | Tampering | graph_outbox rollback atomicity | mitigate | OutboxTransactionalIT asserts both tables empty on rollback; EVENT-04 |
| T-01-15 | Information Disclosure | Schema cache cross-tenant | mitigate | Cache key is `(modelId, typeSlug, schemaVersion)`; loadFor rejects a descriptor whose modelId != ctx.modelId; unit test in SchemaCacheInvalidationTest covers |
| T-01-16 | Tampering | Outbox double-delivery | mitigate | ShedLock + FOR UPDATE SKIP LOCKED + UPDATE status='DELIVERED' in the same TX as publishEvent; at-least-once semantics acknowledged (listeners must be idempotent — documented in Javadoc on GraphEventPublished) |
| T-01-17 | Tampering | Breaking schema change via force | accept | `force=true` is an explicit opt-in; Phase 2 will add ACL on the REST admin surface; Phase 1 is internal-only |
| T-01-18 | Information Disclosure | schema_version snapshot JSON leaks cross-tenant on query | mitigate | All snapshot queries WHERE model_id = ctx.modelId; SchemaVersioningReplayIT runs two tenants and asserts isolation |
</threat_model>

<verification>
`./mvnw -B verify` green. All 15 Wave-2 ITs pass. Wave 1's ITs still pass (TenantBypassPropertyIT especially, since schema lookup is now on the path).
</verification>

<success_criteria>
- SCHEMA-01..08 all covered by green ITs
- EVENT-01..07 all covered by green ITs
- SchemaRegistry.loadFor is called inside GraphServiceImpl.apply before GraphSession.apply
- Outbox poller publishes events via Spring ApplicationEventPublisher within 2 s of commit
- Caffeine descriptor cache hit rate observable via Micrometer
</success_criteria>

<output>
After completion, create `.planning/phases/01-graph-core-schema-registry-validation-rules/01-W2-SUMMARY.md`.
</output>