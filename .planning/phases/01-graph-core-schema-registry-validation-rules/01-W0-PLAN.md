---
phase: 01-graph-core-schema-registry-validation-rules
plan: W0
type: execute
wave: 1
depends_on: []
files_modified:
  - pom.xml
  - fabric-core/pom.xml
  - fabric-rules/pom.xml
  - fabric-core/src/main/java/dev/tessera/core/graph/GraphMutation.java
  - fabric-core/src/main/java/dev/tessera/core/graph/GraphMutationOutcome.java
  - fabric-core/src/main/java/dev/tessera/core/graph/GraphService.java
  - fabric-core/src/main/java/dev/tessera/core/graph/GraphRepository.java
  - fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphSession.java
  - fabric-core/src/main/java/dev/tessera/core/graph/internal/CypherTemplate.java
  - fabric-core/src/main/java/dev/tessera/core/tenant/TenantContext.java
  - fabric-app/src/main/resources/db/migration/V2__graph_events.sql
  - fabric-app/src/main/resources/db/migration/V3__graph_outbox.sql
  - fabric-app/src/main/resources/db/migration/V4__schema_registry.sql
  - fabric-app/src/main/resources/db/migration/V5__schema_versioning_and_aliases.sql
  - fabric-app/src/main/resources/db/migration/V6__source_authority.sql
  - fabric-app/src/main/resources/db/migration/V7__reconciliation_conflicts.sql
  - fabric-app/src/main/resources/db/migration/V8__connector_limits_and_dlq.sql
  - fabric-app/src/main/resources/db/migration/V9__reconciliation_rules.sql
  - fabric-core/src/test/java/dev/tessera/core/support/SchemaFixtures.java
  - fabric-core/src/test/java/dev/tessera/core/support/MutationFixtures.java
  - fabric-app/src/test/java/dev/tessera/arch/RawCypherBanTest.java
  - fabric-core/src/jmh/java/dev/tessera/core/bench/ShaclValidationBench.java
  - fabric-core/src/jmh/java/dev/tessera/core/bench/WritePipelineBench.java
autonomous: true
requirements:
  - CORE-02
  - CORE-06
  - EVENT-01
  - EVENT-02
  - EVENT-03
  - EVENT-04
  - RULE-08
user_setup: []

must_haves:
  truths:
    - "jqwik 1.9.2 is available as test dependency in fabric-core and fabric-rules"
    - "GraphMutation record exists with all provenance fields from D-A1"
    - "GraphService interface exists as sole write entrypoint in dev.tessera.core.graph"
    - "GraphSession skeleton exists in dev.tessera.core.graph.internal (sole raw-Cypher package)"
    - "All Phase 1 Flyway migrations V2..V9 exist and run clean on Testcontainers startup"
    - "RawCypherBanTest exists in fabric-app arch suite and is GREEN"
    - "All integration test shells named in 01-VALIDATION.md exist as empty/skipped classes so Wave 1-3 can fill them"
  artifacts:
    - path: fabric-core/src/main/java/dev/tessera/core/graph/GraphMutation.java
      provides: "Write funnel DTO with full provenance (D-A1)"
      contains: "record GraphMutation"
    - path: fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphSession.java
      provides: "Only class allowed to execute raw Cypher (CORE-02)"
      contains: "class GraphSession"
    - path: fabric-app/src/main/resources/db/migration/V2__graph_events.sql
      provides: "Event log table, indexes, initial partition (EVENT-01..03, RULE-08)"
      contains: "CREATE TABLE graph_events"
    - path: fabric-app/src/test/java/dev/tessera/arch/RawCypherBanTest.java
      provides: "CORE-02 ArchUnit ban on raw Cypher outside graph.internal"
      contains: "noClasses"
  key_links:
    - from: fabric-core/pom.xml
      to: net.jqwik:jqwik:1.9.2
      via: test dependency
      pattern: "jqwik"
    - from: fabric-app/src/test/java/dev/tessera/arch/RawCypherBanTest.java
      to: dev.tessera.core.graph.internal
      via: "ArchUnit package restriction"
      pattern: "graph\\.internal"
---

<objective>
Seed every piece of Phase 1 scaffolding so Waves 2-4 can land implementation code without any "where does this go" guesswork. This plan delivers the full Flyway DDL for the 13 new tables, the write-funnel type contracts (GraphMutation, GraphService, GraphRepository, GraphSession), the jqwik test dependency, the ArchUnit raw-Cypher ban (CORE-02 — deferred from Phase 0 D-15), test fixtures, JMH bench skeletons, and thin shells for every integration test named in 01-VALIDATION.md so downstream waves merely fill them.

Purpose: Interface-first task ordering. Wave 0 publishes the contracts and the schema; Waves 1-3 implement against them.
Output: 13 Flyway migrations, the Java contract files, the ArchUnit rule green, jqwik wired in, all test-file shells present and @Disabled.
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@.planning/PROJECT.md
@.planning/ROADMAP.md
@.planning/phases/01-graph-core-schema-registry-validation-rules/01-CONTEXT.md
@.planning/phases/01-graph-core-schema-registry-validation-rules/01-RESEARCH.md
@.planning/phases/01-graph-core-schema-registry-validation-rules/01-VALIDATION.md
@.planning/phases/00-foundations-risk-burndown/00-CONTEXT.md
@fabric-core/src/main/java/dev/tessera/core/tenant/TenantContext.java
@fabric-core/src/test/java/dev/tessera/core/support/AgePostgresContainer.java
@fabric-app/src/test/java/dev/tessera/arch/ModuleDependencyTest.java
@fabric-app/src/main/resources/db/migration/V1__enable_age.sql
@pom.xml

<interfaces>
From fabric-core/src/main/java/dev/tessera/core/tenant/TenantContext.java:
```java
public record TenantContext(UUID modelId) {
    public static TenantContext of(UUID modelId);
}
```

GraphMutation contract from CONTEXT §D-A1 (what this plan must create):
```java
public record GraphMutation(
    TenantContext tenantContext,
    Operation operation,                 // CREATE | UPDATE | TOMBSTONE
    String type,                         // type_slug
    UUID targetNodeUuid,                 // nullable on CREATE
    Map<String, Object> payload,
    SourceType sourceType,               // STRUCTURED | EXTRACTION | MANUAL | SYSTEM
    String sourceId,
    String sourceSystem,
    BigDecimal confidence,               // 0.0..1.0, default 1.0 for STRUCTURED/MANUAL/SYSTEM
    String extractorVersion,             // nullable (Phase 2.5 populates)
    String llmModelId,                   // nullable (Phase 2.5 populates)
    String originConnectorId,            // RULE-08
    String originChangeId                // RULE-08
) { ... }
```

System properties required on every node/edge (CORE-06):
`uuid`, `model_id`, `_type`, `_created_at`, `_updated_at`, `_created_by`, `_source`, `_source_id`
</interfaces>
</context>

<tasks>

<task type="auto" tdd="false">
  <name>Task 01-W0-01: Add jqwik + write the 13 Flyway migrations for Phase 1 tables</name>
  <files>
    pom.xml,
    fabric-core/pom.xml,
    fabric-rules/pom.xml,
    fabric-app/src/main/resources/db/migration/V2__graph_events.sql,
    fabric-app/src/main/resources/db/migration/V3__graph_outbox.sql,
    fabric-app/src/main/resources/db/migration/V4__schema_registry.sql,
    fabric-app/src/main/resources/db/migration/V5__schema_versioning_and_aliases.sql,
    fabric-app/src/main/resources/db/migration/V6__source_authority.sql,
    fabric-app/src/main/resources/db/migration/V7__reconciliation_conflicts.sql,
    fabric-app/src/main/resources/db/migration/V8__connector_limits_and_dlq.sql,
    fabric-app/src/main/resources/db/migration/V9__reconciliation_rules.sql
  </files>
  <read_first>
    - pom.xml (parent — see existing dependencyManagement + enforcer config)
    - fabric-core/pom.xml
    - fabric-rules/pom.xml
    - fabric-app/src/main/resources/db/migration/V1__enable_age.sql
    - .planning/phases/01-graph-core-schema-registry-validation-rules/01-CONTEXT.md §D-C2, §D-C3 (exact DDL)
    - .planning/phases/01-graph-core-schema-registry-validation-rules/01-RESEARCH.md §"Event Log Schema" (exact `graph_events` DDL, copy verbatim)
  </read_first>
  <action>
    1. In parent `pom.xml` `<dependencyManagement>`: add `net.jqwik:jqwik:1.9.2` (scope `test`). Do not touch any other dependency. Do NOT add jqwik-bom — use the direct `net.jqwik:jqwik` aggregator per 01-RESEARCH.md §Standard Stack.
    2. In `fabric-core/pom.xml` and `fabric-rules/pom.xml`: add `<dependency><groupId>net.jqwik</groupId><artifactId>jqwik</artifactId><scope>test</scope></dependency>` (version comes from parent dependencyManagement). No surefire/failsafe config changes — jqwik's JUnit5 engine is auto-discovered.
    3. `V2__graph_events.sql` — copy the full DDL from 01-RESEARCH.md §"Event Log Schema" VERBATIM (table, three indexes, one initial monthly partition `graph_events_y2026m04`). Include all provenance columns (`source_type`, `source_id`, `source_system`, `confidence NUMERIC(4,3) NOT NULL DEFAULT 1.0`, `extractor_version`, `llm_model_id`, `origin_connector_id`, `origin_change_id`) and `event_time TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()` (CORE-08 — Tessera-owned timestamp). **Must include both `payload JSONB NOT NULL` (full post-mutation state) AND `delta JSONB NOT NULL` (field-level diff: on CREATE, delta == payload; on UPDATE, delta is the changed-fields-only diff; on TOMBSTONE, delta == `{"_tombstoned": true}`). Both columns are mandated by EVENT-03.** Partition key: `RANGE (event_time)`.
    4. `V3__graph_outbox.sql` — create `graph_outbox (id UUID PRIMARY KEY DEFAULT gen_random_uuid(), model_id UUID NOT NULL, event_id UUID NOT NULL REFERENCES graph_events(id), aggregatetype TEXT NOT NULL, aggregateid TEXT NOT NULL, type TEXT NOT NULL, payload JSONB NOT NULL, routing_hints JSONB, status TEXT NOT NULL DEFAULT 'PENDING', created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), delivered_at TIMESTAMPTZ)`. Columns match Debezium Outbox Event Router SMT shape per 01-RESEARCH.md §"Transactional Outbox". Index: `(status, created_at)` for poll, `(model_id, created_at DESC)` for per-tenant queries.
    5. `V4__schema_registry.sql` — create `schema_node_types (id UUID PRIMARY KEY, model_id UUID NOT NULL, name TEXT NOT NULL, slug TEXT NOT NULL, label TEXT NOT NULL, description TEXT, builtin BOOLEAN NOT NULL DEFAULT false, source_system TEXT, deprecated_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), UNIQUE (model_id, slug))`, `schema_properties (id UUID PRIMARY KEY, model_id UUID NOT NULL, type_slug TEXT NOT NULL, name TEXT NOT NULL, slug TEXT NOT NULL, data_type TEXT NOT NULL, required BOOLEAN NOT NULL DEFAULT false, default_value JSONB, validation_rules JSONB, enum_values JSONB, reference_target TEXT, source_path TEXT, deprecated_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), UNIQUE (model_id, type_slug, slug))`, and `schema_edge_types (id UUID PRIMARY KEY, model_id UUID NOT NULL, name TEXT NOT NULL, slug TEXT NOT NULL, edge_label TEXT NOT NULL, inverse_name TEXT, source_type_slug TEXT NOT NULL, target_type_slug TEXT NOT NULL, cardinality TEXT NOT NULL, properties_schema JSONB, deprecated_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), UNIQUE (model_id, slug))` per D-B1.
    6. `V5__schema_versioning_and_aliases.sql` — create `schema_change_event (id UUID PRIMARY KEY, model_id UUID NOT NULL, change_type TEXT NOT NULL, payload JSONB NOT NULL, caused_by TEXT NOT NULL, event_time TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp())`, `schema_version (model_id UUID NOT NULL, version_nr BIGINT NOT NULL, snapshot JSONB NOT NULL, is_current BOOLEAN NOT NULL DEFAULT false, created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), PRIMARY KEY (model_id, version_nr))`, a partial unique index `CREATE UNIQUE INDEX idx_schema_version_current ON schema_version (model_id) WHERE is_current`, plus `schema_property_aliases (model_id UUID NOT NULL, type_slug TEXT NOT NULL, old_slug TEXT NOT NULL, current_slug TEXT NOT NULL, retired_at TIMESTAMPTZ, PRIMARY KEY (model_id, type_slug, old_slug))` and `schema_edge_type_aliases (model_id UUID NOT NULL, old_slug TEXT NOT NULL, current_slug TEXT NOT NULL, retired_at TIMESTAMPTZ, PRIMARY KEY (model_id, old_slug))` per D-B2, D-B3.
    7. `V6__source_authority.sql` — copy the exact DDL from CONTEXT §D-C2 (`source_authority` table with `priority_order TEXT[]`, PK `(model_id, type_slug, property_slug)`, `updated_at`, `updated_by`).
    8. `V7__reconciliation_conflicts.sql` — copy exact DDL from CONTEXT §D-C3 including all four indexes: `(model_id, node_uuid)`, `(model_id, type_slug, property_slug)`, `(model_id, losing_source_system)`, `(model_id, created_at DESC)`.
    9. `V8__connector_limits_and_dlq.sql` — `connector_limits (model_id UUID NOT NULL, connector_id TEXT NOT NULL, window_seconds INT NOT NULL DEFAULT 30, threshold INT NOT NULL DEFAULT 500, PRIMARY KEY (model_id, connector_id))` and `connector_dlq (id UUID PRIMARY KEY DEFAULT gen_random_uuid(), model_id UUID NOT NULL, connector_id TEXT NOT NULL, reason TEXT NOT NULL, raw_payload JSONB NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp())`. Index on `(model_id, connector_id, created_at DESC)`.
    10. `V9__reconciliation_rules.sql` — per **ADR-7 §RULE-04** (hybrid Java-classes-plus-DB-activation model), copy the exact DDL:
        ```sql
        CREATE TABLE reconciliation_rules (
            id UUID PRIMARY KEY,
            model_id UUID NOT NULL,
            rule_id TEXT NOT NULL,              -- matches Rule.id() in Java
            enabled BOOLEAN NOT NULL DEFAULT TRUE,
            priority_override INTEGER,          -- NULL = use Rule.priority() default
            parameters JSONB,                   -- rule-specific per-tenant config
            updated_at TIMESTAMPTZ NOT NULL,
            updated_by TEXT NOT NULL,
            UNIQUE (model_id, rule_id)
        );
        CREATE INDEX idx_reconciliation_rules_model ON reconciliation_rules (model_id);
        ```
        This table satisfies REQUIREMENTS.md RULE-04 (per ADR-7). `RuleRepository` (Wave 3, plan 01-W3-02) reads this table and joins against `List<Rule>` Spring beans by `rule_id`.
    11. Every migration file starts with a 1-line comment `-- Phase 1 / Wave 0 / <table>` for traceability. No SQL DDL for any table NOT listed here — Wave 0 is scaffolding only, per-tenant `graph_events_seq_*` SEQUENCE objects are created at runtime by SequenceAllocator (Wave 1).
  </action>
  <verify>
    <automated>./mvnw -pl fabric-app -Dit.test=FlywayIT verify</automated>
  </verify>
  <acceptance_criteria>
    - `grep -q "net.jqwik" pom.xml` succeeds
    - `grep -q "net.jqwik" fabric-core/pom.xml` and `grep -q "net.jqwik" fabric-rules/pom.xml` both succeed
    - All 7 new migration files exist under `fabric-app/src/main/resources/db/migration/V2__*.sql` through `V8__*.sql`
    - `grep -q "CREATE TABLE graph_events" fabric-app/src/main/resources/db/migration/V2__graph_events.sql` succeeds
    - `grep -q "source_type TEXT NOT NULL" fabric-app/src/main/resources/db/migration/V2__graph_events.sql` succeeds
    - `grep -q "origin_connector_id" fabric-app/src/main/resources/db/migration/V2__graph_events.sql` succeeds
    - `grep -q "PARTITION BY RANGE" fabric-app/src/main/resources/db/migration/V2__graph_events.sql` succeeds
    - `grep -q "aggregatetype" fabric-app/src/main/resources/db/migration/V3__graph_outbox.sql` succeeds
    - `grep -q "priority_order TEXT\[\]" fabric-app/src/main/resources/db/migration/V6__source_authority.sql` succeeds
    - `grep -q "losing_source_system" fabric-app/src/main/resources/db/migration/V7__reconciliation_conflicts.sql` succeeds
    - `grep -q "delta JSONB" fabric-app/src/main/resources/db/migration/V2__graph_events.sql` succeeds (EVENT-03)
    - `grep -q "routing_hints JSONB" fabric-app/src/main/resources/db/migration/V3__graph_outbox.sql` succeeds
    - `grep -q "CREATE TABLE reconciliation_rules" fabric-app/src/main/resources/db/migration/V9__reconciliation_rules.sql` succeeds (ADR-7 / RULE-04)
    - `grep -q "priority_override INTEGER" fabric-app/src/main/resources/db/migration/V9__reconciliation_rules.sql` succeeds
    - Existing Phase 0 `FlywayIT` or equivalent boot test runs clean through V9 (Flyway reports migration `9` as current)
  </acceptance_criteria>
  <done>
    jqwik is on the test classpath in both modules; all 8 migration files (V2..V9) exist with the exact table shapes mandated by CONTEXT §D-B1..D-C3 and RESEARCH §"Event Log Schema"; Phase 0 FlywayIT still green after applying V2..V9. V9 provides the RULE-04 activation table per ADR-7.
  </done>
</task>

<task type="auto" tdd="false">
  <name>Task 01-W0-02: Write GraphMutation, GraphService, GraphRepository, GraphSession skeletons + fixtures</name>
  <files>
    fabric-core/src/main/java/dev/tessera/core/graph/GraphMutation.java,
    fabric-core/src/main/java/dev/tessera/core/graph/GraphMutationOutcome.java,
    fabric-core/src/main/java/dev/tessera/core/graph/Operation.java,
    fabric-core/src/main/java/dev/tessera/core/graph/SourceType.java,
    fabric-core/src/main/java/dev/tessera/core/graph/GraphService.java,
    fabric-core/src/main/java/dev/tessera/core/graph/GraphRepository.java,
    fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphSession.java,
    fabric-core/src/main/java/dev/tessera/core/graph/internal/CypherTemplate.java,
    fabric-core/src/test/java/dev/tessera/core/support/SchemaFixtures.java,
    fabric-core/src/test/java/dev/tessera/core/support/MutationFixtures.java
  </files>
  <read_first>
    - .planning/phases/01-graph-core-schema-registry-validation-rules/01-CONTEXT.md §D-A1, §D-A2, §D-A3, §D-C4
    - .planning/phases/01-graph-core-schema-registry-validation-rules/01-RESEARCH.md §"Recommended Package Structure", §"Pattern 1: Single Write Funnel", §"Agtype Parameter Binding"
    - fabric-core/src/main/java/dev/tessera/core/tenant/TenantContext.java
  </read_first>
  <action>
    1. `Operation.java` — `public enum Operation { CREATE, UPDATE, TOMBSTONE }`.
    2. `SourceType.java` — `public enum SourceType { STRUCTURED, EXTRACTION, MANUAL, SYSTEM }`.
    3. `GraphMutation.java` — `public record GraphMutation(TenantContext tenantContext, Operation operation, String type, UUID targetNodeUuid, Map<String,Object> payload, SourceType sourceType, String sourceId, String sourceSystem, BigDecimal confidence, String extractorVersion, String llmModelId, String originConnectorId, String originChangeId)` with compact constructor validating: tenantContext non-null (CORE-03), operation non-null, type non-null, sourceType non-null, sourceId non-null, sourceSystem non-null, confidence in `[BigDecimal.ZERO, BigDecimal.ONE]`. Also add a static `builder()` for ergonomic construction from tests and a `withTenant(TenantContext)` copy helper (used by jqwik fuzz). Fields `extractorVersion` and `llmModelId` are explicitly nullable — D-A1.
    4. `GraphMutationOutcome.java` — `public sealed interface GraphMutationOutcome permits Committed, Rejected { }` with `record Committed(UUID nodeUuid, long sequenceNr, UUID eventId) implements GraphMutationOutcome` and `record Rejected(String ruleId, String reason) implements GraphMutationOutcome`.
    5. `GraphService.java` — `public interface GraphService { GraphMutationOutcome apply(GraphMutation mutation); }`. Apache 2.0 header. Single method is the sole write entrypoint (CORE-01). Javadoc cites CORE-01 and marks this as the `@Transactional` boundary.
    6. `GraphRepository.java` — `public interface GraphRepository { Optional<NodeState> findNode(TenantContext ctx, String typeSlug, UUID nodeUuid); List<NodeState> queryAll(TenantContext ctx, String typeSlug); }` — each method takes `TenantContext` as first explicit parameter (CORE-03). Also define `public record NodeState(UUID uuid, String typeSlug, Map<String,Object> properties, Instant createdAt, Instant updatedAt)` in the same package.
    7. `internal/GraphSession.java` — class skeleton with a private constructor package-scoped `GraphSession(NamedParameterJdbcTemplate jdbc)`. Add method stubs `public NodeState apply(TenantContext ctx, GraphMutation m)` and `public Optional<NodeState> findNode(TenantContext ctx, String typeSlug, UUID nodeUuid)` throwing `UnsupportedOperationException("Wave 1 fills this")`. This is the ONLY class permitted raw Cypher by CORE-02.
    8. `internal/CypherTemplate.java` — record `public record CypherTemplate(String graphName, String cypher, Map<String,Object> params)` with a static `forTenant(TenantContext ctx, String cypher, Map<String,Object> extraParams)` factory that always adds `model_id` to params. Documented idiom A (text-cast agtype) per RESEARCH §"Agtype Parameter Binding".
    9. `test/support/SchemaFixtures.java` — static builder methods `nodeType(String slug)`, `property(String typeSlug, String slug, String dataType)`, `edgeType(String slug, String from, String to)`. Returns DTOs matching the Wave 2 schema registry shape (plain Java records — Wave 2 may refactor).
    10. `test/support/MutationFixtures.java` — static `jqwik` `@Provide` methods `Arbitrary<GraphMutation> anyMutation()` and `Arbitrary<GraphMutation> creates()` using `Arbitraries.create(UUID::randomUUID)` seeds, `Arbitraries.of(Operation.values())`, etc. Also an `Arbitrary<List<GraphMutation>> mutationList()` of size 1..20. Used by TenantBypassPropertyIT in Wave 1.
    11. Apache 2.0 header on every `.java` file (license-maven-plugin hard-fail). Palantir Java Format (Spotless) clean.
  </action>
  <verify>
    <automated>./mvnw -pl fabric-core -am compile test-compile &amp;&amp; ./mvnw -pl fabric-core spotless:check license:check</automated>
  </verify>
  <acceptance_criteria>
    - File `fabric-core/src/main/java/dev/tessera/core/graph/GraphMutation.java` exists and `grep -q "record GraphMutation" fabric-core/src/main/java/dev/tessera/core/graph/GraphMutation.java` succeeds
    - `grep -q "extractorVersion" fabric-core/src/main/java/dev/tessera/core/graph/GraphMutation.java` succeeds
    - `grep -q "llmModelId" fabric-core/src/main/java/dev/tessera/core/graph/GraphMutation.java` succeeds
    - `grep -q "BigDecimal confidence" fabric-core/src/main/java/dev/tessera/core/graph/GraphMutation.java` succeeds
    - `grep -q "originConnectorId" fabric-core/src/main/java/dev/tessera/core/graph/GraphMutation.java` succeeds (RULE-08)
    - `grep -q "interface GraphService" fabric-core/src/main/java/dev/tessera/core/graph/GraphService.java` succeeds
    - `grep -q "GraphMutationOutcome apply" fabric-core/src/main/java/dev/tessera/core/graph/GraphService.java` succeeds
    - `grep -q "TenantContext ctx" fabric-core/src/main/java/dev/tessera/core/graph/GraphRepository.java` succeeds (explicit tenant param CORE-03)
    - File `fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphSession.java` exists in the `graph.internal` package
    - `./mvnw -pl fabric-core -am compile test-compile` exits 0
    - `./mvnw -pl fabric-core spotless:check license:check` exits 0
  </acceptance_criteria>
  <done>
    The five write-funnel contract types compile, are under the Apache header, are Spotless-clean, and expose the exact record shape mandated by D-A1. SchemaFixtures and MutationFixtures exist so Wave 1 tests can @ForAll against them.
  </done>
</task>

<task type="auto" tdd="false">
  <name>Task 01-W0-03: ArchUnit raw-Cypher ban + integration test shells + JMH bench skeletons</name>
  <files>
    fabric-app/src/test/java/dev/tessera/arch/RawCypherBanTest.java,
    fabric-core/src/test/java/dev/tessera/core/graph/GraphServiceApplyIT.java,
    fabric-core/src/test/java/dev/tessera/core/graph/NodeLifecycleIT.java,
    fabric-core/src/test/java/dev/tessera/core/graph/EdgeLifecycleIT.java,
    fabric-core/src/test/java/dev/tessera/core/graph/SystemPropertiesIT.java,
    fabric-core/src/test/java/dev/tessera/core/graph/TombstoneSemanticsIT.java,
    fabric-core/src/test/java/dev/tessera/core/graph/TimestampOwnershipTest.java,
    fabric-core/src/test/java/dev/tessera/core/graph/property/TenantBypassPropertyIT.java,
    fabric-core/src/test/java/dev/tessera/core/schema/SchemaNodeTypeCrudIT.java,
    fabric-core/src/test/java/dev/tessera/core/schema/SchemaPropertyCrudIT.java,
    fabric-core/src/test/java/dev/tessera/core/schema/SchemaEdgeTypeCrudIT.java,
    fabric-core/src/test/java/dev/tessera/core/schema/SchemaVersioningReplayIT.java,
    fabric-core/src/test/java/dev/tessera/core/schema/SchemaAliasIT.java,
    fabric-core/src/test/java/dev/tessera/core/schema/SchemaCacheInvalidationTest.java,
    fabric-core/src/test/java/dev/tessera/core/schema/SchemaToShaclIT.java,
    fabric-core/src/test/java/dev/tessera/core/schema/SchemaBreakingChangeIT.java,
    fabric-core/src/test/java/dev/tessera/core/validation/ShaclPreCommitIT.java,
    fabric-core/src/test/java/dev/tessera/core/validation/ShapeCacheTest.java,
    fabric-core/src/test/java/dev/tessera/core/validation/TargetedValidationTest.java,
    fabric-core/src/test/java/dev/tessera/core/validation/ValidationReportFilterTest.java,
    fabric-core/src/test/java/dev/tessera/core/events/EventLogSchemaIT.java,
    fabric-core/src/test/java/dev/tessera/core/events/PerTenantSequenceIT.java,
    fabric-core/src/test/java/dev/tessera/core/events/EventProvenanceIT.java,
    fabric-core/src/test/java/dev/tessera/core/events/OutboxTransactionalIT.java,
    fabric-core/src/test/java/dev/tessera/core/events/OutboxPollerIT.java,
    fabric-core/src/test/java/dev/tessera/core/events/TemporalReplayIT.java,
    fabric-core/src/test/java/dev/tessera/core/events/AuditHistoryIT.java,
    fabric-rules/src/test/java/dev/tessera/rules/BusinessRuleRejectIT.java,
    fabric-rules/src/test/java/dev/tessera/rules/ChainExecutorTest.java,
    fabric-rules/src/test/java/dev/tessera/rules/RuleInterfaceTest.java,
    fabric-rules/src/test/java/dev/tessera/rules/RuleOutcomeTest.java,
    fabric-rules/src/test/java/dev/tessera/rules/RuleRegistrationIT.java,
    fabric-rules/src/test/java/dev/tessera/rules/authority/SourceAuthorityIT.java,
    fabric-rules/src/test/java/dev/tessera/rules/conflicts/ConflictRegisterIT.java,
    fabric-rules/src/test/java/dev/tessera/rules/circuit/CircuitBreakerTest.java,
    fabric-rules/src/test/java/dev/tessera/rules/circuit/CircuitBreakerIT.java,
    fabric-rules/src/test/java/dev/tessera/rules/EchoLoopSuppressionIT.java,
    fabric-core/src/jmh/java/dev/tessera/core/bench/ShaclValidationBench.java,
    fabric-core/src/jmh/java/dev/tessera/core/bench/WritePipelineBench.java
  </files>
  <read_first>
    - fabric-app/src/test/java/dev/tessera/arch/ModuleDependencyTest.java (extend pattern, don't fork)
    - .planning/phases/01-graph-core-schema-registry-validation-rules/01-RESEARCH.md §"ArchUnit Raw-Cypher Ban" (copy both layer-1 and layer-2 rules verbatim)
    - .planning/phases/01-graph-core-schema-registry-validation-rules/01-VALIDATION.md (per-task verification map — every IT file listed here must exist as a shell)
  </read_first>
  <action>
    1. `RawCypherBanTest.java` — sibling to `ModuleDependencyTest.java` in `fabric-app/src/test/java/dev/tessera/arch/`. Copy both ArchRules VERBATIM from 01-RESEARCH.md §"ArchUnit Raw-Cypher Ban":
       - Layer 1 `only_graph_internal_may_touch_pgjdbc` — `noClasses().that().resideOutsideOfPackage("dev.tessera.core.graph.internal..").should().dependOnClassesThat().resideInAnyPackage("org.postgresql..", "org.springframework.jdbc.core..").because("CORE-02: only graph.internal may execute raw Cypher or touch pgJDBC directly")`
       - Layer 2 `no_cypher_strings_outside_internal` — `noClasses().that().resideOutsideOfPackage("dev.tessera.core.graph.internal..").should(containCypherStringConstant())` with the inner `ArchCondition` that checks field constant values for `MATCH`/`CREATE`/`MERGE`/`RETURN`/`SET` co-occurrence OR literal `CYPHER('`.
       - Must be GREEN immediately (no other class currently references pgJDBC or holds Cypher constants). CORE-02 validated.
    2. For every integration test shell file listed in <files>: create a minimal JUnit 5 class with an Apache header, `@Testcontainers` (where IT), `@Disabled("Wave N — filled by plan 01-WN")` with N set per 01-VALIDATION.md wave column, and at least one empty `@Test void placeholder() { }` body. IT files live under `fabric-*/src/test/java/...` and use `*IT.java` suffix so Failsafe picks them up; unit tests use `*Test.java` for Surefire. Every IT that needs Postgres uses `@Container static final PostgreSQLContainer<?> PG = AgePostgresContainer.create();`. `TenantBypassPropertyIT.java` is a shell using `@Property(tries = 10)` (low try count while disabled) to prove jqwik annotations compile — copy the skeleton from 01-RESEARCH.md §"Jqwik Tenant-Bypass Fuzz" but keep @Disabled.
    3. `ShaclValidationBench.java` — JMH benchmark skeleton with `@BenchmarkMode(Mode.SampleTime)`, `@OutputTimeUnit(MILLISECONDS)`, `@State(Scope.Benchmark)` setup that is `@Setup(Level.Trial)` loading `AgePostgresContainer`, and one `@Benchmark void validate() { /* filled by Wave 3 */ }` method. Include `@Fork(1) @Warmup(iterations = 3) @Measurement(iterations = 5)` annotations. Purpose: establish the JMH file so Wave 3 only has to fill the body, not restructure the harness.
    4. `WritePipelineBench.java` — identical JMH skeleton, targeting `GraphService.apply` (body `// Wave 1 fills`).
    5. `./mvnw -B verify` must stay green: all shells are `@Disabled` and do not execute their body at build time; the ArchUnit test IS executed and IS green because no production class currently violates.
  </action>
  <verify>
    <automated>./mvnw -pl fabric-app -Dtest=RawCypherBanTest test &amp;&amp; ./mvnw -B -DskipITs=false -Dtest='!*' -Dit.test='!*' verify</automated>
  </verify>
  <acceptance_criteria>
    - File `fabric-app/src/test/java/dev/tessera/arch/RawCypherBanTest.java` exists
    - `grep -q "only_graph_internal_may_touch_pgjdbc" fabric-app/src/test/java/dev/tessera/arch/RawCypherBanTest.java` succeeds
    - `grep -q "resideOutsideOfPackage.*graph.internal" fabric-app/src/test/java/dev/tessera/arch/RawCypherBanTest.java` succeeds
    - `./mvnw -pl fabric-app -Dtest=RawCypherBanTest test` passes (green — proves CORE-02)
    - All 38 test-shell files listed in <files> exist — verify via `find fabric-core/src/test/java fabric-rules/src/test/java fabric-app/src/test/java -name '*IT.java' -o -name '*Test.java' | wc -l` is ≥ 39 (the 38 shells + RawCypherBanTest + ModuleDependencyTest + ImagePinningTest)
    - Both JMH bench files exist under `fabric-core/src/jmh/java/dev/tessera/core/bench/`
    - Full `./mvnw -B verify` on this commit is green (Spotless + license + Surefire + Failsafe + ArchUnit)
  </acceptance_criteria>
  <done>
    RawCypherBanTest GREEN (CORE-02 satisfied for Phase 1), every integration test file named in 01-VALIDATION.md exists as a @Disabled shell so Waves 1-3 simply remove @Disabled and fill bodies, JMH benches compile against the Phase 0 JMH harness, and the full build stays green.
  </done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| build → classpath | A contributor can add a dependency; ArchUnit type-based rules must catch raw-Cypher escape |
| schema DDL → runtime | A Flyway migration becomes the shape of every future TX; malformed columns are permanent |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-01-02 | Tampering | graph.internal raw-Cypher boundary | mitigate | RawCypherBanTest Layer 1 (type-based pgJDBC ban) + Layer 2 (string-constant Cypher detection) in fabric-app arch suite; GREEN at Wave 0 close |
| T-01-04 | Tampering | graph_events.event_time | mitigate | `V2__graph_events.sql` defines `event_time TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()` — payload-supplied timestamps cannot land in the column; Wave 1 TimestampOwnershipTest enforces at the Java layer |
| T-01-11 | Information Disclosure | reconciliation_conflicts cross-tenant | mitigate | Table PK/indexes scoped to `(model_id, ...)` from Wave 0 DDL; Wave 3 enforces WHERE filter at read path |
| T-01-12 | Elevation of Privilege | Flyway DDL | accept | Flyway runs as the `tessera` DB user (non-superuser except for `CREATE EXTENSION age` already in V1); no new privileged operations in V2..V8 |
</threat_model>

<verification>
Run `./mvnw -B verify` — must be green. Phase 0 FlywayIT reports schema at version 9. `./mvnw -pl fabric-app -Dtest=RawCypherBanTest test` green (CORE-02). All test shells compile.
</verification>

<success_criteria>
- jqwik 1.9.2 on test classpath in fabric-core + fabric-rules
- 8 new Flyway migrations (V2..V9) applied cleanly (V9 = reconciliation_rules per ADR-7)
- GraphMutation record matches D-A1 field-for-field
- GraphService / GraphRepository / GraphSession skeletons exist
- RawCypherBanTest green (CORE-02 validated at Wave 0 close)
- All 38 integration test shells exist under the exact file paths listed in 01-VALIDATION.md
- `./mvnw -B verify` green end-to-end
</success_criteria>

<output>
After completion, create `.planning/phases/01-graph-core-schema-registry-validation-rules/01-W0-SUMMARY.md` and flip `wave_0_complete: true` in `01-VALIDATION.md` frontmatter.
</output>