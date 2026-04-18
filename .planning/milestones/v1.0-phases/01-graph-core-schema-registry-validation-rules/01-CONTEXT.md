# Phase 1: Graph Core, Schema Registry, Validation, Rules — Context

**Gathered:** 2026-04-14
**Status:** Ready for planning

<domain>
## Phase Boundary

Deliver the two spines of Tessera — the **Schema Registry** and the **Event Log + Outbox** — wrapped by a single transactional write funnel (`GraphService.apply`) that enforces tenant isolation, synchronous SHACL validation, and priority-based reconciliation rules. No projections, no connectors, no HTTP surface, no MCP — just a trustworthy graph core that everything in Phase 2+ can build on.

Scope anchor: CORE-01..08, SCHEMA-01..08, VALID-01..05, EVENT-01..07, RULE-01..08 — 35 requirements across six subsystems.

**Explicit non-goals (out of Phase 1):** REST projection (Phase 2), connector framework + first connector (Phase 2), unstructured ingestion + extraction (Phase 2.5), MCP projection (Phase 3), SQL view projection + Kafka (Phase 4), circlead integration + DR drill (Phase 5). Phase 1 ships zero user-facing surface; its consumers are all Phase 2+ modules within Tessera itself.

</domain>

<decisions>
## Implementation Decisions

Decisions are grouped by subsystem. Every decision is load-bearing for downstream planning and is locked — change requires an explicit revisit of this document.

### A. GraphMutation Contract & Phase 2.5 Forward-Hook

- **D-A1:** `GraphMutation` carries the **full provenance set** from Phase 1, not a minimal subset that grows later. Fields:
  - `sourceType` (enum: `STRUCTURED | EXTRACTION | MANUAL | SYSTEM`)
  - `sourceId` (String — connector-assigned identifier of the originating record)
  - `sourceSystem` (String — logical system name, e.g. `crm`, `obsidian`, `hr_system`)
  - `confidence` (BigDecimal, 0.0–1.0, default `1.0` for `STRUCTURED`/`MANUAL`/`SYSTEM`)
  - `extractorVersion` (String, nullable — populated only by Phase 2.5 extraction path)
  - `llmModelId` (String, nullable — populated only by Phase 2.5 extraction path)
  - Plus standard mutation fields: `tenantContext`, `operation` (CREATE/UPDATE/TOMBSTONE), `type`, `payload`, `targetNodeUuid?`.
  - **Rationale:** Phase 2.5 populates the nullable fields without any schema migration or refactor. Matches the forward-commitment note in ROADMAP.md.

- **D-A2:** The **review queue is a Phase 2.5 layer on top** — NOT a rule engine terminal outcome. The rule engine in Phase 1 stays pure: a candidate is either COMMIT-ed or REJECT-ed. Phase 2.5 will add a pre-funnel filter (`if confidence < threshold: route to review_queue before GraphService.apply()`). The rule engine must not grow a `ROUTE` outcome just for Phase 2.5's benefit.

- **D-A3:** The rule engine treats **all candidates uniformly** regardless of `sourceType`. Rules that want to behave differently inspect `sourceType` and `sourceSystem` from the mutation context — a well-designed authority-matrix rule reads the right priorities from its own data, it does not branch on source kind. No extraction-specific "trust" chain.

### B. Schema Registry — Storage, Versioning, Aliases

- **D-B1:** Schema is stored in **typed Postgres tables** (`schema_node_types`, `schema_properties`, `schema_edge_types`) with **JSONB columns** for flexible-shape fields (validation_rules, default_value, enum_values, reference_target). Core queryable attributes (name, slug, data_type, cardinality, required, deprecated_at, created_at) are real columns with indexes. Best of both worlds — queryable core + extensible detail without Flyway migrations for every new validation rule type.

- **D-B2:** Schema versioning is **event-sourced with materialized snapshots**. A `schema_change_event` table appends every schema mutation (identical shape to `graph_events` but scoped to schema, not data). A `schema_version` row is materialized per `(model_id, version_nr)` as a snapshot for fast reads. Querying an old version reads the snapshot row; introducing a new version appends change events and writes a new snapshot row. Matches research/ARCHITECTURE.md §2.2 "emits schema-change events". A single boolean `is_current` flag on `schema_version` marks the active version per model.

- **D-B3:** Property aliases are implemented via a **translation table** `schema_property_aliases (model_id, type_slug, old_slug, current_slug, retired_at)`. The Projection Engine and graph read path check aliases on unknown-slug lookups. Writes always use the current slug. Aliases accumulate until explicitly retired; a retention window for pruning is a Phase 3+ concern. `schema_edge_type_aliases` follows the same shape for edge renames.

### C. Rule Engine, Authority Matrix, Conflict Register

- **D-C1:** The rule engine uses **multiple named chains in a fixed pipeline order**:
  1. **VALIDATE** — rules that can REJECT a mutation (business constraints beyond SHACL shape: cross-entity referential checks, time windows, domain-specific invariants)
  2. **RECONCILE** — rules that decide property winners when multiple sources disagree; can MERGE, OVERRIDE, or emit conflict records
  3. **ENRICH** — rules that ADD derived properties (computed fields, provenance tags, lineage hints)
  4. **ROUTE** — rules that decide downstream consumer routing (which outbox topic to tag, which tenants to notify); NOT the same as Phase 2.5's review queue (see D-A2).

  Each chain runs independently; a REJECT in VALIDATE short-circuits the pipeline before RECONCILE sees anything. Priority within a chain is numeric (lower = earlier); rules can explicitly `haltChain()` to stop later rules in the same chain. Rule outcomes are the union `{ COMMIT, REJECT(reason), MERGE(value), OVERRIDE(value), ADD(property, value), ROUTE(target) }`.

- **D-C2:** The **authority matrix** is stored in a `source_authority` table, runtime-editable per tenant:
  ```
  source_authority (
    model_id UUID NOT NULL,
    type_slug TEXT NOT NULL,
    property_slug TEXT NOT NULL,
    priority_order TEXT[] NOT NULL,          -- e.g. ['crm', 'hr_system', 'obsidian']
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by TEXT NOT NULL,
    PRIMARY KEY (model_id, type_slug, property_slug)
  )
  ```
  An admin API can edit rows at runtime. A Caffeine cache keyed by `(model_id, type_slug, property_slug)` is invalidated on row update. Per-property granularity is D14 from FEATURES.md — "HR owns name, CRM owns phone, ERP owns cost center."

- **D-C3:** The **conflict register** is a dedicated relational table:
  ```
  reconciliation_conflicts (
    id UUID PRIMARY KEY,
    model_id UUID NOT NULL,
    event_id UUID NOT NULL REFERENCES graph_events(id),
    type_slug TEXT NOT NULL,
    node_uuid UUID NOT NULL,
    property_slug TEXT NOT NULL,
    losing_source_id TEXT NOT NULL,
    losing_source_system TEXT NOT NULL,
    losing_value JSONB NOT NULL,
    winning_source_id TEXT NOT NULL,
    winning_source_system TEXT NOT NULL,
    winning_value JSONB NOT NULL,
    rule_id TEXT NOT NULL,                   -- which rule made the decision
    reason TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT conflict_tenant_check CHECK (model_id IS NOT NULL)
  )
  ```
  Indexes on `(model_id, node_uuid)`, `(model_id, type_slug, property_slug)`, `(model_id, losing_source_system)`, `(model_id, created_at DESC)`. This table becomes the data source for the v2+ operator UI.

- **D-C4:** Business-rule validation language is **Java `Rule` classes**, NOT SHACL-SPARQL. SHACL-Core shapes (via Apache Jena 5.2.0) handle data-shape validation (types, cardinalities, regex, required, enum). Business rules are Java classes implementing a `Rule` interface, discovered via Spring DI and registered per chain. Justified by research/PITFALLS.md: Jena SHACL-SPARQL constraints are a measurable perf cliff vs SHACL-Core, and cross-entity rules are clumsy to express in SPARQL. Java gives full language features + speed.

  Rule interface sketch:
  ```java
  public interface Rule {
      String id();
      Chain chain();                   // VALIDATE | RECONCILE | ENRICH | ROUTE
      int priority();                  // lower = earlier
      boolean applies(RuleContext ctx);
      RuleOutcome evaluate(RuleContext ctx);
  }
  ```

### D. Tenant Safety + Write-Amplification Circuit Breaker

- **D-D1:** Tenant-bypass tests use **jqwik property-based testing** (`net.jqwik:jqwik` 1.9+ under JUnit5) covering **all read and write operations**: `create`, `get`, `query`, `update`, `tombstone`, `traverse`, `find_path`. Each property seeds data for tenant A and tenant B, runs the operation as tenant A, and asserts **zero rows** from tenant B's data appear in any result. Minimum 1000 randomized scenarios per operation. A separate curated regression test is added every time a tenant-leak bug is found. This is how CORE-03 is validated — not by hand-written tests alone.

- **D-D2:** The write-amplification circuit breaker trips on **events-per-second per `(connector_id, model_id)` over a rolling window**. Default window = 30 seconds; default threshold = 500 events/sec per `(connector_id, model_id)`; both configurable in `application.yml` and per-tenant override in a `connector_limits` table. The sliding window is maintained in-memory (plain `AtomicLongArray`, no extra infra). Window + threshold are both observable via Micrometer metrics (`tessera.writes.rate{connector,model}` and `tessera.circuit.threshold{connector,model}`).

- **D-D3:** When the circuit breaker trips, the action is **pause connector + DLQ + alert**:
  1. The connector's `sync()` loop is halted (Spring scheduler removes the trigger until manual reset).
  2. Any events already accepted by `GraphService.apply()` in the tripping window are committed normally — we do not roll back accepted writes.
  3. Any events that were queued for this connector but not yet applied are routed to `connector_dlq` with `reason='circuit_breaker_tripped'` and the raw payload preserved for re-drive.
  4. A Micrometer counter `tessera.circuit.tripped{connector,model}` increments.
  5. An operator re-drives the connector via an admin endpoint (`POST /admin/connectors/{id}/reset`) after investigation. In Phase 1 the admin endpoint is internal-only; it becomes a first-class admin surface in Phase 2.

### Claude's Discretion

The planner / researcher / executor have freedom on:
- **Exact Flyway migration sequence** for the 15+ new tables — just keep them small, one subsystem per migration file.
- **Spring Data JPA vs plain JdbcTemplate** for each table — JPA for CRUD-heavy tables (schema registry, authority matrix), JdbcTemplate for append-only/high-write tables (`graph_events`, `graph_outbox`, `reconciliation_conflicts`), but the planner may deviate with a brief justification.
- **Caffeine cache tuning** (size, TTL, refresh policy) — use sensible defaults; revisit if SHACL or schema-lookup perf shows up in the JMH baseline from Phase 0.
- **Sequence generation for `graph_events.sequence_nr` per `model_id`** — Postgres advisory locks, `SELECT FOR UPDATE` on a per-tenant counter row, or a Postgres sequence per tenant. Planner picks, documents the trade-off.
- **Exact names of test support classes** — e.g. `TenantBypassPropertyTest`, `ReconciliationConflictHarness`, `SchemaVersioningReplayTest` — naming is a local concern.
- **Whether `GraphService` is a single bean or a facade over specialized beans** (`EntityService`, `EdgeService`, `SchemaService`, etc.) as long as `GraphService.apply(GraphMutation)` remains THE single write entrypoint from outside `fabric-core`. Internal composition is free.
- **Packaging** of the rule engine inside `fabric-rules`: single `rules` package or split into `rules.chain`, `rules.authority`, `rules.registry`. Prefer small packages.
- **Jqwik property generators** — seed strategy, value space, entity-graph shape. Just keep the total test suite under 60 s wall-clock.
- **Whether the Phase 2 admin API for `source_authority` edits lands in Phase 1 as a bare REST endpoint or stays as a `JdbcTemplate`-backed service for Phase 1** — but the underlying table and caching must be complete in Phase 1.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Project-level specs
- `.planning/PROJECT.md` — Vision, constraints, ADRs 1–6, OSS posture, tenant isolation rules
- `.planning/REQUIREMENTS.md` §Graph Core / §Schema Registry / §Validation / §Event Log & Outbox / §Rule Engine — the 35 requirements Phase 1 must satisfy
- `.planning/ROADMAP.md` §"Phase 1: Graph Core, Schema Registry, Validation, Rules" — Goal, depends, success criteria, Phase 1 forward-commitment note for Phase 2.5

### Research (authoritative for Phase 1 technical approach)
- `.planning/research/ARCHITECTURE.md` §2.2 (Component Boundaries), §2.3 (Write Pipeline authoritative flow), §3.3 (Event log + outbox two-table pattern), §3.4 (Multi-tenancy + `TenantContext` defenses), §3.5 (Synchronous SHACL pre-commit), §4 Patterns 1–6, §5 Anti-Patterns 1–8, §8 Build Order Steps 1–4
- `.planning/research/STACK.md` — Full version locks (Spring Boot 3.5.13, Jena 5.2.0, ArchUnit 1.3, Caffeine 3.1.8, Flyway 10, Testcontainers 1.20.4); `jqwik` 1.9+ is the property-based test framework added by this CONTEXT
- `.planning/research/PITFALLS.md` — CRIT-3 (aggregation cliff — not a Phase 1 blocker but shape of rule engine should not depend on aggregate queries), MIN-1 (agtype parameter binding — relevant for every `GraphSession` template), MIN-2 (no default indexes — Phase 1 must create indexes for `(model_id, type, uuid)` and `(model_id, sequence_nr)`)
- `.planning/research/SUMMARY.md` §"Phase 1" — Rationale for the two-spine ordering and why Schema Registry lands before projections

### Prior phase context
- `.planning/phases/00-foundations-risk-burndown/00-CONTEXT.md` — Phase 0 locked decisions D-01..D-16. Most relevant for Phase 1:
  - **D-14:** Five-module Maven layout (`fabric-core`, `fabric-rules`, `fabric-projections`, `fabric-connectors`, `fabric-app`) with strict upward dependency direction. Phase 1 lives in `fabric-core` (graph core, schema registry, event log, SHACL, GraphService) and `fabric-rules` (rule engine + authority matrix).
  - **D-15:** ArchUnit module-direction was scoped to Phase 0; the **raw-Cypher ban outside `graph.internal`** (CORE-02) is Phase 1's to add. The test class name from Phase 0's ArchUnit suite should be extended, not replaced.
  - **D-16:** `TenantContext` record shipped in Phase 0 as a structural primitive with `modelId` (UUID) + helpers. Phase 1 makes it a mandatory method parameter across `GraphService`, `GraphSession`, `GraphRepository`, every rule, and every schema registry operation.

### Phase 0 baseline reference
- `.planning/phases/00-foundations-risk-burndown/00-VERIFICATION.md` — Live JMH p95 numbers for point-lookup (~1 ms), traversal (1-hop ~13 ms), aggregate (~330 ms), pagination (~56 ms) against 100k nodes. Phase 1's rule engine + SHACL budget must not blow past these by more than 2× on the same shape of query, or the JMH nightly will regress.
- `fabric-core/src/test/java/dev/tessera/core/support/AgePostgresContainer.java` — digest-pinned Testcontainers helper built in plan 00-03. Phase 1 integration tests reuse this.
- `fabric-core/src/test/java/dev/tessera/core/tenant/TenantContextTest.java` — baseline shape of how TenantContext is tested; extend, don't fork.

### External (read before planning Phase 1)
- Apache Jena 5.2.0 SHACL docs — `jena-shacl` module usage, `ShapesGraph`, `ValidationReport` shape
- Apache AGE 1.6 Cypher docs — confirm `agtype` parameter binding idioms for `GraphSession` templates (MIN-1)
- Spring Data JPA + JdbcTemplate reference — Phase 1 uses both; know when to pick each
- jqwik user guide — property-based test structure, generators, `@Property` / `@ForAll` idioms

No Tessera ADR files exist as standalone docs yet — ADR-1..6 are summarized in `PROJECT.md`. Phase 1 should land `.planning/adr/` as first-class ADR documents (ADR-7 "GraphMutation Provenance Contract", ADR-8 "Schema Versioning via Events + Snapshots", ADR-9 "Rule Engine Chain Structure") as the decisions crystallize in implementation.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets (from Phase 0)

- **`dev.tessera.core.tenant.TenantContext`** — record with `UUID modelId` and static `of(UUID)` factory. Phase 1 adds methods: `requireNonNull`, `matches(UUID other)`, equality contract already provided by record.
- **`dev.tessera.core.support.AgePostgresContainer`** — digest-pinned Testcontainers wrapper around AGE 1.6 (`apache/age@sha256:16aa423d20a31aed36a3313244bf7aa00731325862f20ed584510e381f2feaed`). Every Phase 1 integration test reuses this — do not fork the container setup.
- **Parent POM `pom.xml`** — already has Spotless (Palantir), license-plugin (Apache 2.0 headers), JaCoCo 0.8.12+, maven-enforcer with `banCircularDependencies` (extra-enforcer-rules 1.9.0), Surefire, Failsafe. Phase 1 adds `jqwik` under `<dependencyManagement>` and enables it in `fabric-core` + `fabric-rules` test scopes.
- **`fabric-app/src/main/resources/application.yml`** — HikariCP `connection-init-sql` already runs `LOAD 'age'; SET search_path = ag_catalog, "$user", public;` per-pooled-connection. Phase 1 does not need to touch this.
- **Flyway V1** — `V1__enable_age.sql` runs `CREATE EXTENSION IF NOT EXISTS age`. Phase 1 appends V2..Vn migrations for: `graph_events`, `graph_outbox`, `schema_node_types`, `schema_properties`, `schema_edge_types`, `schema_change_event`, `schema_version`, `schema_property_aliases`, `schema_edge_type_aliases`, `source_authority`, `reconciliation_conflicts`, `connector_limits`, `connector_dlq`. Keep one migration file per table (or one per subsystem if that stays small).
- **ArchUnit `ModuleDependencyTest`** in `fabric-app/src/test/java/dev/tessera/arch/` — module-direction rules. Phase 1 extends it (or adds a sibling class) with the raw-Cypher ban: nothing in the codebase may reference `org.apache.age` or execute Cypher strings outside `dev.tessera.core.graph.internal`.
- **ArchUnit `ImagePinningTest`** — digest enforcement across three sites. Phase 1 integration tests use `AgePostgresContainer`, which already goes through this gate.
- **JMH harness + `BenchHarness` + `SeedGenerator`** — deterministic graph seed. Phase 1 may reuse `SeedGenerator` for property-based fuzz data seeds where helpful.

### Established Patterns (must follow)

- **`TenantContext` as mandatory method parameter** — never `ThreadLocal`, never static. Every `GraphService`, `GraphSession`, rule, schema operation takes it explicitly. A compile error is better than a silent leak.
- **Testcontainers for integration tests**, JUnit5 Surefire for pure unit tests. `*IT.java` suffix for integration, plain `*Test.java` for unit.
- **Spring DI + constructor injection** — no field injection, no static lookups, no manual new for Spring beans.
- **Apache 2.0 header on every `.java` file** — `license-maven-plugin` hard-fails the build if missing.
- **Palantir Java Format via Spotless** — `./mvnw spotless:check` is in the default verify lifecycle.

### Integration Points

- **`GraphService`** in `dev.tessera.core.graph` — the new write funnel entry point; single public method `apply(GraphMutation mutation)`. All Phase 2+ modules call this.
- **`GraphSession`** in `dev.tessera.core.graph.internal` — the only class allowed to execute raw Cypher. Template-based, `TenantContext`-aware, parameter-binding-aware per MIN-1. The raw-Cypher ArchUnit rule pins the allowed package.
- **`GraphRepository`** in `dev.tessera.core.graph` — the read-side entry point; every read path calls `GraphRepository.query(TenantContext, CypherTemplate, Map<String,Object>)` which internally delegates to `GraphSession`.
- **`SchemaRegistry`** in `dev.tessera.core.schema` — CRUD on schema tables + versioning + alias lookup. Source of truth for SHACL shape compilation, Projection Engine type descriptors (Phase 2), and MCP tool dispatch (Phase 3).
- **`RuleEngine`** in `dev.tessera.rules` — registers rules into named chains, exposes `evaluate(Chain, RuleContext) -> List<RuleOutcome>`. Called by `GraphService.apply` between schema load and SHACL validation.
- **`EventLog` / `Outbox`** in `dev.tessera.core.events` — append-only, both written by `GraphService.apply` in the same Postgres TX. Outbox poller runs on a Spring `@Scheduled` task with ShedLock.

</code_context>

<specifics>
## Specific Ideas

- **"The graph is the truth; everything else is a projection"** (PROJECT.md Core Value) — Phase 1 IS the truth. No part of Phase 1 ships a projection, but every decision in Phase 1 must make projection-building in Phase 2+ trivial.
- **Generic `GraphMutation` contract is the single point of extensibility** between structured connectors (Phase 2) and extraction connectors (Phase 2.5). Get this wrong and Phase 2.5 has to refactor; get it right and Phase 2.5 just populates two more fields.
- **Rule engine chain order (VALIDATE → RECONCILE → ENRICH → ROUTE) is structural, not convention.** A REJECT in VALIDATE must short-circuit before RECONCILE sees anything; an enrichment rule must not be able to run before authority resolution has happened. The planner should encode this as a typed pipeline, not a `List<Rule>` that relies on priority numbers alone.
- **`reconciliation_conflicts` becomes operator-UI ground truth in v2+** — design it now as if an operator will query it by tenant/entity/property/source. Indexes reflect that.
- **`source_authority` is runtime-editable per tenant.** Every edit logs to `schema_change_event` alongside schema mutations — authority IS schema metadata from a governance standpoint.
- **Raw-Cypher ArchUnit ban is the single most important CORE test** and the Phase 0 deferral (D-15) makes Phase 1 its owner. It must be green before any Cypher-writing code lands in the phase.

</specifics>

<deferred>
## Deferred Ideas

Ideas surfaced during discussion that are explicitly NOT part of Phase 1:

- **Review queue for low-confidence extractions** — Phase 2.5 (EXTR-07). The rule engine in Phase 1 does not grow a ROUTE-to-review outcome.
- **pgvector extension for embedding-based entity resolution** — Phase 2.5 (EXTR-05, EXTR-08). Phase 1 does not install pgvector; Phase 1 reconciliation is exact-match + authority-matrix only.
- **Admin UI for editing `source_authority`** — Phase 2+ (REST endpoint possibly, full UI v2+). Phase 1 provides the DB table + caching + in-code service; editing is via `JdbcTemplate` or test harness.
- **Operator UI for the conflict register** — v2+, per FEATURES.md anti-feature A14. Phase 1 designs the table to make the eventual UI trivial but ships no UI.
- **LLM-assisted schema evolution** (v2+ LLM-01..03) — not Phase 1 work; Phase 1 ships the Schema Registry that future LLM-proposal flows will call.
- **Drools migration** (ADR-3 v2+) — custom engine in Phase 1 only.
- **OWL reasoning** (ADR-2 v2+ opt-in) — SHACL only in Phase 1.
- **Postgres RLS as belt-and-braces on AGE label tables** — research flag; Phase 1 validates the `model_id` filter via ArchUnit + jqwik fuzz + repository-layer guard. RLS is a Phase 2+ hardening.
- **Schema-registry-driven OpenAPI generation** — Phase 2 (REST projection). Phase 1 only stores the schema.
- **MCP tool list from schema registry** — Phase 3. Phase 1 only stores the schema.
- **Kafka topic generation per type** — Phase 4. Phase 1 writes the outbox rows, that's all.
- **Full temporal query surface (`get_state_at`)** — Phase 3 (MCP). Phase 1 ensures the event log is complete and replayable, which is the prerequisite.
- **Self-built `apache/age` image** — still deferred indefinitely per Phase 0 D-09.

</deferred>

---

*Phase: 01-graph-core-schema-registry-validation-rules*
*Context gathered: 2026-04-14 via /gsd-discuss-phase (interactive)*
*All four gray-area buckets (A, B, C, D) discussed; every question answered with the recommended option.*
