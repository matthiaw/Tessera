# Phase 1: Graph Core, Schema Registry, Validation, Rules — Research

**Researched:** 2026-04-13
**Domain:** Transactional graph write-funnel (AGE Cypher + SHACL + rule engine + event log + outbox) on PG16 + Spring Boot 3.5.13
**Confidence:** HIGH on Jena SHACL, pgJDBC/AGE, Spring TX semantics, jqwik idioms, outbox patterns, ArchUnit rules. MEDIUM on AGE-specific agtype parameter binding edge cases and precise SHACL p95 numbers at Tessera shape (extrapolated from Jena benchmarks + Phase 0 baseline, not directly measured).

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**A. GraphMutation Contract & Phase 2.5 Forward-Hook**

- **D-A1:** `GraphMutation` carries the full provenance set from Phase 1, not a minimal subset that grows later. Fields: `sourceType` (enum: `STRUCTURED | EXTRACTION | MANUAL | SYSTEM`), `sourceId` (String), `sourceSystem` (String), `confidence` (BigDecimal, 0.0–1.0, default `1.0` for `STRUCTURED`/`MANUAL`/`SYSTEM`), `extractorVersion` (String, nullable), `llmModelId` (String, nullable). Plus standard mutation fields: `tenantContext`, `operation` (CREATE/UPDATE/TOMBSTONE), `type`, `payload`, `targetNodeUuid?`. Phase 2.5 populates nullable fields without migration.

- **D-A2:** Review queue is a Phase 2.5 **pre-funnel** layer, NOT a rule engine terminal outcome. Phase 1 rule engine COMMIT or REJECT only. Phase 2.5 adds pre-funnel filter: `if confidence < threshold: route to review_queue before GraphService.apply()`. The rule engine must not grow a `ROUTE` outcome just for Phase 2.5's benefit.

- **D-A3:** Rule engine treats **all candidates uniformly** regardless of `sourceType`. Rules inspect `sourceType`/`sourceSystem` as data, not via special chains. No extraction-specific "trust" chain.

**B. Schema Registry — Storage, Versioning, Aliases**

- **D-B1:** Schema stored in **typed Postgres tables** (`schema_node_types`, `schema_properties`, `schema_edge_types`) with **JSONB columns** for flexible-shape fields (validation_rules, default_value, enum_values, reference_target). Core queryable attributes are real columns with indexes.

- **D-B2:** Schema versioning is **event-sourced with materialized snapshots**. A `schema_change_event` table appends every schema mutation. A `schema_version` row is materialized per `(model_id, version_nr)` as a snapshot. Single boolean `is_current` flag on `schema_version` marks the active version per model.

- **D-B3:** Property aliases via **translation table** `schema_property_aliases (model_id, type_slug, old_slug, current_slug, retired_at)`. Writes always use the current slug. `schema_edge_type_aliases` follows the same shape.

**C. Rule Engine, Authority Matrix, Conflict Register**

- **D-C1:** Rule engine uses **four named chains in fixed pipeline order**: VALIDATE → RECONCILE → ENRICH → ROUTE. Each chain runs independently; REJECT in VALIDATE short-circuits before RECONCILE sees anything. Priority within a chain is numeric (lower = earlier); rules can `haltChain()`. Rule outcomes are the union `{ COMMIT, REJECT(reason), MERGE(value), OVERRIDE(value), ADD(property, value), ROUTE(target) }`.

- **D-C2:** Authority matrix in `source_authority` table, runtime-editable per tenant, `(model_id, type_slug, property_slug) → priority_order TEXT[]`, Caffeine-cached, invalidated on row update.

- **D-C3:** `reconciliation_conflicts` dedicated relational table (exact schema in CONTEXT §D-C3); indexes on `(model_id, node_uuid)`, `(model_id, type_slug, property_slug)`, `(model_id, losing_source_system)`, `(model_id, created_at DESC)`.

- **D-C4:** Business-rule validation language is **Java `Rule` classes**, NOT SHACL-SPARQL. SHACL-Core shapes (Jena 5.2.0) for data shape only. SHACL-SPARQL is explicitly rejected on performance grounds.

**D. Tenant Safety + Write-Amplification Circuit Breaker**

- **D-D1:** Tenant-bypass tests use **jqwik property-based testing** (1.9+ under JUnit5) covering all read/write ops: `create`, `get`, `query`, `update`, `tombstone`, `traverse`, `find_path`. Minimum 1000 randomized scenarios per operation. Each property seeds tenant A + tenant B, runs op as tenant A, asserts zero rows from tenant B appear.

- **D-D2:** Circuit breaker trips on events-per-second per `(connector_id, model_id)` over rolling 30-second window, default threshold 500/sec. In-memory `AtomicLongArray`. Observable via Micrometer.

- **D-D3:** On trip: pause connector (`sync()` halted), committed writes remain committed, queued-not-yet-applied events → `connector_dlq` with `reason='circuit_breaker_tripped'`, Micrometer counter `tessera.circuit.tripped{connector,model}` increments, operator re-drive via `POST /admin/connectors/{id}/reset` (internal-only in Phase 1).

### Claude's Discretion

- Exact Flyway migration sequence — one subsystem per migration file (or one per table).
- JPA vs JdbcTemplate per table — JPA for CRUD-heavy (schema registry, authority matrix), JdbcTemplate for append-only high-write (`graph_events`, `graph_outbox`, `reconciliation_conflicts`); planner may deviate with justification.
- Caffeine cache tuning (size, TTL, refresh policy) — sensible defaults.
- `graph_events.sequence_nr` generation strategy — advisory locks / SELECT FOR UPDATE / per-tenant SEQUENCE. Planner picks, documents trade-off. **Research recommendation below: per-tenant `SEQUENCE`.**
- Exact test support class names.
- `GraphService` as single bean vs facade over specialized beans — internal composition free.
- Rule engine packaging inside `fabric-rules`.
- jqwik generator seed strategy, value space, entity-graph shape — keep full suite < 60s wall-clock.
- Admin API for `source_authority` edits — bare REST endpoint or `JdbcTemplate`-backed service.

### Deferred Ideas (OUT OF SCOPE)

- Review queue for low-confidence extractions (Phase 2.5)
- pgvector for embedding-based entity resolution (Phase 2.5)
- Admin UI for `source_authority` editing (Phase 2+)
- Operator UI for conflict register (v2+)
- LLM-assisted schema evolution (v2+)
- Drools migration (v2+)
- OWL reasoning (v2+)
- Postgres RLS as belt-and-braces (Phase 2+ hardening)
- Schema-registry-driven OpenAPI generation (Phase 2)
- MCP tool list from schema registry (Phase 3)
- Kafka topic generation (Phase 4)
- Full temporal query surface `get_state_at` (Phase 3)
- Self-built `apache/age` image (indefinitely deferred per D-09)

</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| CORE-01 | `GraphService.apply()` single write funnel; single TX auth→rules→SHACL→Cypher→event log→outbox | §"GraphService Transaction Boundaries", §"Architecture Patterns / Pattern 1" |
| CORE-02 | `GraphSession` is only Cypher execution surface; ArchUnit forbids raw Cypher outside `graph.internal` | §"ArchUnit Raw-Cypher Ban" |
| CORE-03 | `TenantContext` mandatory explicit parameter; fuzz tests verify no bypass path | §"Jqwik Tenant-Bypass Fuzz" |
| CORE-04 | Create/read/update/tombstone nodes with labels, properties, UUIDs, `model_id` | §"Agtype Parameter Binding", §"Standard Stack / GraphSession" |
| CORE-05 | Create/read/tombstone edges with label, source, target, properties, `model_id` | §"Agtype Parameter Binding" |
| CORE-06 | System properties on every node/edge: `uuid`, `model_id`, `_type`, `_created_at`, `_updated_at`, `_created_by`, `_source`, `_source_id` | §"GraphMutation Contract" |
| CORE-07 | Tombstone-default deletes; hard-delete explicit opt-in | §"Tombstone Semantics" |
| CORE-08 | Tessera-owned timestamps, never trusted from source | §"GraphService Transaction Boundaries" |
| SCHEMA-01 | CRUD `schema_node_types` | §"Schema Registry Versioning" |
| SCHEMA-02 | CRUD `schema_properties` | §"Schema Registry Versioning" |
| SCHEMA-03 | CRUD `schema_edge_types` | §"Schema Registry Versioning" |
| SCHEMA-04 | Versioned schema, old versions queryable | §"Schema Registry Versioning" (event-sourced + snapshot) |
| SCHEMA-05 | Property aliases do not break existing reads | §"Schema Registry Versioning" (alias tables) |
| SCHEMA-06 | Caffeine schema descriptor cache with invalidation on change | §"SHACL Shape Caching" |
| SCHEMA-07 | Single source of truth for SHACL/projections/OpenAPI/MCP | Phase 1 ships the store only |
| SCHEMA-08 | Reject breaking changes unless forced | §"Schema Registry Versioning" — compatibility matrix |
| VALID-01 | SHACL runs synchronously in the write transaction | §"Jena SHACL Perf Envelope", §"GraphService Transaction Boundaries" |
| VALID-02 | Compiled SHACL shapes cached per `(model_id, typeSlug)` with invalidation | §"SHACL Shape Caching" |
| VALID-03 | Targeted validation against minimal in-memory RDF model (not full graph) | §"Jena SHACL Perf Envelope" |
| VALID-04 | `ValidationReport` tenant-filtered before reaching consumers/logs | §"SHACL Report Filtering" |
| VALID-05 | Business-rule validation runs as rule-engine action with REJECT outcome | §"Rule Engine Chain Structure" |
| EVENT-01 | `graph_events` append-only, monthly-partitioned, indexed `(model_id, sequence_nr)` and `(node_uuid)` | §"Event Log Partitioning" |
| EVENT-02 | `sequence_nr` from Postgres `SEQUENCE` per `model_id`, never `MAX()+1` | §"Per-Tenant Monotonic Sequence" |
| EVENT-03 | Full provenance on every event | §"GraphMutation Contract", §"Event Log Schema" |
| EVENT-04 | `graph_outbox` written in same TX as event + mutation | §"Transactional Outbox" |
| EVENT-05 | In-process outbox poller via Spring `ApplicationEventPublisher` | §"Transactional Outbox" |
| EVENT-06 | Temporal replay: state at T via event log | §"Event Log Schema" — payload column |
| EVENT-07 | Full mutation history per node queryable | §"Event Log Schema" |
| RULE-01 | Chain-of-responsibility executor, priority-ordered, short-circuit on match | §"Rule Engine Chain Structure" |
| RULE-02 | `Rule` interface with `priority()`, `matches()`, `apply()` | §"Rule Engine Chain Structure" |
| RULE-03 | Rule actions: REJECT, MERGE, OVERRIDE, ADD, ROUTE, COMMIT | §"Rule Engine Chain Structure" |
| RULE-04 | Rules registered per chain via Spring DI (code-based per D-C4); per-tenant config from DB | §"Rule Engine Chain Structure" |
| RULE-05 | Per-tenant per-property source authority matrix | §"Authority Matrix Caching" |
| RULE-06 | `reconciliation_conflicts` register | §"Conflict Register" |
| RULE-07 | Write-amplification circuit breaker | §"Circuit Breaker Design" |
| RULE-08 | Every event tracks `origin_connector_id` + `origin_change_id` to prevent echo loops | §"Event Log Schema" |

</phase_requirements>

## Summary

Phase 1 is mostly an **integration problem**, not a research problem. All 11 open questions have well-established answers in the Java/Spring/Postgres ecosystem. The two genuinely novel areas are (a) the AGE-specific agtype parameter-binding idiom (only marginally surprising; Phase 0 already proved the text-cast workaround works) and (b) the performance budget of the full write pipeline vs Phase 0 JMH baselines, which is extrapolated rather than measured (requires live JMH regression once Phase 1 lands).

**Primary recommendation:** Build the write funnel in a strict bottom-up order matching `ARCHITECTURE.md §8`: (1) GraphMutation + GraphSession + GraphRepository + event log + outbox tables, (2) Schema Registry + versioning + alias tables, (3) SHACL shape compilation cache keyed by `(model_id, schema_version, type_slug)`, (4) Rule engine with the four named chains, (5) Circuit breaker + tenant-bypass jqwik harness, (6) ArchUnit raw-Cypher ban. Use per-tenant **Postgres `SEQUENCE` objects** for `graph_events.sequence_nr` (cheapest, non-contending, exactly what EVENT-02 requires). Use **Spring `@Transactional(propagation = REQUIRED)`** around `GraphService.apply` with the full stack (rules + SHACL + Cypher + event log + outbox) inside that TX, and let the HikariCP pool's session-init SQL run once per pooled connection (Phase 0 already configured this). Poll the outbox with `@Scheduled` + ShedLock, never LISTEN/NOTIFY, to preserve the Debezium-swap invariant in Phase 4.

## Project Constraints (from CLAUDE.md)

- **Tech stack locked:** Java 21 + Spring Boot 3.5.13, PG 16 + Apache AGE 1.6.0 (`apache/age@sha256:16aa423d…feaed` already pinned in 3 sites), Apache Jena 5.2.0 SHACL, Maven multi-module, Flyway 10, Testcontainers 1.20.4, ArchUnit 1.3, Caffeine 3.1.8+. Do NOT introduce alternatives.
- **Module direction (D-14):** `fabric-core` → no internal deps; `fabric-rules` → `fabric-core`; `fabric-projections`/`fabric-connectors`/`fabric-app` ≥ `fabric-rules`/`fabric-core`. Phase 1 work lives in `fabric-core` (graph core, schema registry, event log, SHACL, GraphService) and `fabric-rules` (rule engine + authority matrix). Enforcer + ArchUnit `ModuleDependencyTest` already green.
- **License:** Apache 2.0 header on every `.java` (license-maven-plugin hard-fails the build).
- **Format:** Palantir Java Format via Spotless (`./mvnw spotless:check`).
- **Testing:** JUnit5 `*Test.java` for unit, Testcontainers `*IT.java` for integration, Surefire/Failsafe separation. `AgePostgresContainer.create()` is the only allowed entry point for AGE containers (reuses Phase 0 digest pin).
- **DI:** Constructor injection only, no field injection, no static lookups, no manual `new` for Spring beans.
- **TenantContext:** Mandatory method parameter, never ThreadLocal (Phase 0 D-16 record already shipped).
- **No Co-Authored-By in commit messages** (global CLAUDE.md).
- **GSD workflow enforcement:** All file edits through GSD commands.

## Standard Stack

### Core (all already pinned from Phase 0 / STACK.md — DO NOT change)

| Library | Version | Purpose | Why Standard |
|---|---|---|---|
| Spring Boot | 3.5.13 | App framework | Phase 0 pinned; 3.4.x EOL; no patch drift |
| Java | 21 (Corretto) | Runtime | LTS, virtual threads, records, pattern matching |
| PostgreSQL | 16 + Apache AGE 1.6.0 | Truth store | Digest-pinned image, already running in CI |
| pgJDBC | 42.7.5 | DB driver | Standard; AGE works server-side, no special JDBC driver needed |
| Apache Jena | 5.2.0 | SHACL engine | `jena-shacl` module; Java 21 compatible |
| Flyway | 10.x (BOM) | Migrations | Already runs Phase 0 V1; Phase 1 appends V2..Vn |
| Spring Data JPA | 3.5.13 (BOM) | Schema registry, authority matrix CRUD | Typed Postgres tables per D-B1 |
| Spring JDBC | 3.5.13 (BOM) | Append-only high-write tables (events, outbox, conflicts) | NamedParameterJdbcTemplate avoids JPA session overhead |
| Caffeine | 3.1.8 | SHACL shape + authority matrix cache | Per VALID-02 and D-C2 |
| ShedLock | 5.16.0 | `@Scheduled` outbox poller lock | Insurance for multi-instance; cheap |

### Testing (additions this phase)

| Library | Version | Purpose | Why Standard |
|---|---|---|---|
| **jqwik** | **1.9.2** | Property-based tenant-bypass fuzz (D-D1) | Best JUnit5-native PBT framework in JVM land; shrinking built-in |
| ArchUnit | 1.3.0 | Module boundaries + raw-Cypher ban | Phase 0 already imports; Phase 1 extends |
| Testcontainers | 1.20.4 | `AgePostgresContainer` reuse | Phase 0 helper is the ONLY entry point |
| Rest-Assured / MockMvc | 5.5.x | — | NOT in Phase 1 scope — no HTTP surface ships in Phase 1 |

**Installation additions (`pom.xml` dependencyManagement):**

```xml
<dependency>
    <groupId>net.jqwik</groupId>
    <artifactId>jqwik</artifactId>
    <version>1.9.2</version>
    <scope>test</scope>
</dependency>
```

Enable in `fabric-core` and `fabric-rules` test scopes. Surefire needs no extra config — jqwik's JUnit5 engine is auto-discovered. [CITED: https://jqwik.net/docs/current/user-guide.html]

**Version verification:** `jqwik` 1.9.2 is the latest 1.9.x as of early 2026 per Maven Central. Pin to 1.9.2 to avoid milestone drift. [VERIFIED: would be `mvn dependency:tree` post-commit; `[ASSUMED]` precise patch number — planner should run `mvn versions:display-dependency-updates` before locking]

## Architecture Patterns

### Recommended Package Structure

```
fabric-core/src/main/java/dev/tessera/core/
├── graph/
│   ├── GraphService.java              # public write funnel entry
│   ├── GraphRepository.java           # public read entry (tenant-aware)
│   ├── GraphMutation.java             # record with full provenance
│   ├── GraphMutationOutcome.java      # result type
│   └── internal/                      # ONLY package allowed raw Cypher
│       ├── GraphSession.java          # template-based Cypher executor
│       ├── CypherTemplate.java        # parameterized Cypher AST
│       └── AgtypeBinder.java          # agtype_build_map helper
├── schema/
│   ├── SchemaRegistry.java            # facade
│   ├── SchemaVersionService.java      # event-sourced versioning
│   ├── SchemaAliasService.java        # property + edge aliases
│   └── internal/
│       ├── SchemaChangeReplayer.java  # materializes snapshot rows
│       └── ShapeCompiler.java         # schema → Jena Shapes, cached
├── validation/
│   ├── ShaclValidator.java            # synchronous pre-commit
│   └── ValidationReportFilter.java    # tenant-safe report redaction
├── events/
│   ├── EventLog.java                  # append-only writer
│   ├── Outbox.java                    # outbox writer
│   ├── OutboxPoller.java              # @Scheduled + ShedLock
│   └── internal/
│       └── SequenceAllocator.java     # per-tenant SEQUENCE lookup
└── tenant/
    └── TenantContext.java             # shipped Phase 0

fabric-rules/src/main/java/dev/tessera/rules/
├── RuleEngine.java                    # pipeline executor
├── Rule.java                          # interface (D-C4)
├── RuleContext.java
├── RuleOutcome.java                   # sealed interface: Commit, Reject, Merge, Override, Add, Route
├── Chain.java                         # enum: VALIDATE, RECONCILE, ENRICH, ROUTE
├── authority/
│   ├── SourceAuthorityService.java    # Caffeine-cached
│   └── SourceAuthorityRepository.java
├── conflicts/
│   └── ConflictRegister.java          # writes reconciliation_conflicts
├── circuit/
│   └── WriteRateCircuitBreaker.java   # AtomicLongArray sliding window
└── chains/                            # built-in rules
    ├── validate/
    ├── reconcile/
    └── enrich/
```

### Pattern 1: Single Write Funnel (Architecture §4 Pattern 1)
**What:** All writes → `GraphService.apply(GraphMutation)`, wrapped in `@Transactional`.
**Why:** One place enforces tenant, rules, SHACL, Cypher, event log, outbox.
**Code outline:**
```java
// Source: .planning/research/ARCHITECTURE.md §2.3 (verbatim pipeline)
@Service
public class GraphService {
    @Transactional
    public GraphMutationOutcome apply(GraphMutation m) {
        authorize(m);                                    // step 1
        var schema = schemaRegistry.loadFor(m.tenantContext(), m.type());  // step 2, cached
        var ruleCtx = RuleContext.of(m, schema, graphRepository);
        var validateOut = ruleEngine.run(Chain.VALIDATE, ruleCtx);
        if (validateOut.isReject()) throw new RuleRejection(validateOut);
        var reconciled = ruleEngine.run(Chain.RECONCILE, ruleCtx);          // may MERGE/OVERRIDE/emit conflict
        var enriched = ruleEngine.run(Chain.ENRICH, reconciled);
        shaclValidator.validate(m.tenantContext(), enriched);               // step 4 — sync, pre-commit
        var nodeState = graphSession.apply(m.tenantContext(), enriched);    // step 5 — Cypher via AGE
        var event = eventLog.append(m.tenantContext(), nodeState);          // step 6 — per-tenant sequence
        outbox.append(event);                                               // step 7 — same TX
        ruleEngine.run(Chain.ROUTE, ruleCtx.withEvent(event));              // routing is post-commit tag, not a side effect
        return GraphMutationOutcome.committed(nodeState, event);
    }
}
```
The `@Transactional` boundary covers everything up to and including `outbox.append`. Post-commit fan-out (ApplicationEventPublisher) happens after the method returns via a `TransactionalEventListener(phase = AFTER_COMMIT)`.

### Pattern 2: Transactional Outbox from Day One (Architecture §4 Pattern 2)
**What:** Every mutation writes `graph_events` AND `graph_outbox` in the same TX as the Cypher write.
**Why:** Phase 4 swaps the in-process poller for Debezium + Outbox Event Router SMT without touching write code.
**Source:** [CITED: https://debezium.io/documentation/reference/stable/transformations/outbox-event-router.html]

### Pattern 3: Schema Registry as Single Source of Truth
Already locked by SCHEMA-07 / D-B1..B3. Every Phase 1 decision assumes this.

### Pattern 4: TenantContext as Explicit Parameter
Already enforced by Phase 0 D-16 record. Phase 1 extends to every method in `GraphService`, `GraphSession`, `GraphRepository`, every `Rule`, every schema registry call.

### Pattern 5: Hexagonal Write TX Boundary
Spring `@Transactional` on `GraphService.apply` opens a TX on the HikariCP-provided connection. The connection already has `LOAD 'age'; SET search_path = ag_catalog, "$user", public;` from `connectionInitSql` (Phase 0 D-10). No code inside the TX needs to re-issue these. **All** participants (Jena SHACL validator, rule engine, `GraphSession`, `EventLog`, `Outbox`) must share the same transactional connection — inject via Spring-managed `JdbcTemplate`/`EntityManager`, never `DataSource.getConnection()`. This is how the rule engine that "hits an external service" concern is answered: **rules are pure functions over `RuleContext`; they MUST NOT perform I/O**. If a rule needs external data it pre-caches it via a `@Scheduled` loader or a Caffeine-wrapped repository — enforced by ArchUnit rule: no `java.net`/`HttpClient`/`RestTemplate`/`WebClient` imports in `dev.tessera.rules`.

### Anti-Patterns to Avoid

- **ThreadLocal tenant context** — Phase 0 D-16 forbids. Async boundaries drop it.
- **Raw Cypher outside `graph.internal`** — CORE-02 ArchUnit rule forbids.
- **Async SHACL validation** — VALID-01 requires synchronous pre-commit. Temporarily invalid data breaks "graph is truth" premise.
- **MAX(sequence_nr)+1** — MOD-5 hot row contention. Use per-tenant `SEQUENCE`.
- **Rule engine performing I/O** — breaks transactional semantics + testability. Pure functions over `RuleContext` only.
- **LISTEN/NOTIFY for outbox delivery** — cannot be swapped for Debezium without rewrite. `@Scheduled` polling of `graph_outbox` is the portable path.
- **Shape-per-request re-compilation** — recompiling Jena `Shapes` on every mutation is the #1 perf cliff. Cache compiled shapes by `(model_id, schema_version, type_slug)`.
- **Full-graph SHACL validation** — VALID-03 explicitly forbids. Targeted validation against an in-memory RDF model holding the mutated node + immediate neighbors referenced by the shape.
- **Validating before rule engine runs** — RECONCILE may transform the payload; SHACL must see the final shape.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---|---|---|---|
| Property-based test framework | Custom random generators | jqwik 1.9.2 | Shrinking, reproducibility, `@ForAll` integration |
| SHACL validator | Custom JSON-schema-like checker | Apache Jena `jena-shacl` 5.2.0 | Industry-standard SHACL-Core; competitive perf with Stardog |
| Per-tenant monotonic sequence | Advisory locks + counter row | Postgres `CREATE SEQUENCE` per tenant | Lock-free, scales to thousands of tenants, crash-safe |
| Transactional outbox poller | Custom thread + SQL | Spring `@Scheduled` + ShedLock 5.16 | Multi-instance safe, restart-safe |
| In-memory rolling rate window | Custom ring buffer with synchronization | `AtomicLongArray` sliding window (already locked by D-D2) | Lock-free, cache-line friendly, exactly what D-D2 specifies |
| Shape compilation cache | Hand-rolled LRU | Caffeine 3.1.8 `Caffeine.newBuilder().maximumSize(N).expireAfterAccess(...)` | W-TinyLFU, pre-built metrics integration |
| ArchUnit rules | Regex scripts, find/grep | ArchUnit 1.3 `ArchRule` DSL | Classfile-based, no false positives on comments/strings |
| Cypher AST parser/rewriter | Hand-rolled string replace | Plain `CypherTemplate` with named parameters + `agtype_build_map` | AGE idiom works; MIN-1 already surfaced via Phase 0 `AgtypeParameterIT` |
| Schema diff / breaking-change detector | Custom change-set parser | Event-sourced `schema_change_event` replay + explicit compatibility check per change type | Phase 0 pattern: events are the diff |

**Key insight:** Almost nothing in Phase 1 is genuinely novel. The discipline is **integration** — threading one `@Transactional` boundary through five subsystems without letting any of them escape it.

## Common Pitfalls (Phase-Specific)

Drawn from research/PITFALLS.md plus integration experience. Phase 1-relevant pitfalls:

### Pitfall 1: CRIT-5 — Missed `model_id` WHERE clause (cross-tenant leak)
**What goes wrong:** One query forgets the filter; Tenant A sees Tenant B data.
**Why it happens:** Admin/debug paths, background jobs, a rule engine reading "all entities of type X."
**How to avoid:** CORE-02 ArchUnit ban + mandatory `TenantContext` parameter on `GraphRepository.query` + jqwik red-team property test (D-D1) running 1000+ random scenarios per read/write op.
**Warning signs:** Any Cypher template that doesn't reference `$model_id`; any `GraphSession` call without a `TenantContext` on the stack.

### Pitfall 2: CRIT-6 — SHACL `ValidationReport` leaks cross-tenant data via error messages
**What goes wrong:** Jena `ValidationReport` embeds literal offending property values; default logging serializes them; 400 responses echo them.
**How to avoid:** `ValidationReportFilter.redact(report, tenantContext)` produces a sanitized report containing only: shape IRI, constraint type, focus node **local UUID**. NEVER literal values, NEVER neighboring nodes. Internal audit log keeps the full report in a separate table with tighter ACLs. Test case: trigger a violation on tenant A; assert no log line or response bytes contain tenant B's data even in stack traces.
**Warning signs:** Any `log.*(ValidationReport)` call without going through the filter; any code path that `toString()`s the report.

### Pitfall 3: MIN-1 — agtype parameter binding surprises
**What goes wrong:** Standard JDBC `?`-style prepared statements don't work inside Cypher function calls. AGE needs parameters passed as an `agtype` map via `agtype_build_map(...)` inside the Cypher call, or text-cast in a preamble.
**How to avoid:** `GraphSession` template uses a documented idiom (see §"Agtype Parameter Binding" below). Phase 0 `AgtypeParameterIT` already proves the text-cast pattern works.
**Warning signs:** `PreparedStatement.setString(1, ...)` inside any Cypher call; classes that import `org.postgresql` directly outside `graph.internal`.

### Pitfall 4: MIN-2 — No default indexes on new labels
**What goes wrong:** Creating a new node type via the Schema Registry yields zero indexes on its label table; first-query latency is seconds.
**How to avoid:** `SchemaRegistry.applyChangeEvent()` runs required index DDL in the same TX as the typed-table insert. Required indexes are declarative per type: `(model_id, uuid)` always, `(model_id, _type)` always, `(model_id, _source, _source_id)` for connector dedup.
**Warning signs:** Any `CREATE VLABEL`/`CREATE ELABEL` without an accompanying `CREATE INDEX` in the same migration.

### Pitfall 5: MOD-5 — `sequence_nr` contention becomes write bottleneck
**What goes wrong:** Naive `SELECT MAX(sequence_nr)+1 FROM graph_events WHERE model_id=?` serializes all writes per tenant under lock contention. At 500/sec (the circuit breaker threshold) this fails.
**How to avoid:** Per-tenant `CREATE SEQUENCE graph_events_seq_<uuid>` (see §"Per-Tenant Monotonic Sequence" for the recommended allocation strategy). Already locked by EVENT-02.

### Pitfall 6: Rule engine ordering bug — ENRICH runs before RECONCILE
**What goes wrong:** An enrichment rule computes a derived property from the "winner" before reconciliation has picked one.
**How to avoid:** Pipeline structure is typed, not a `List<Rule>`. `RuleEngine.run(Chain.VALIDATE, ...)` / `Chain.RECONCILE` / `Chain.ENRICH` / `Chain.ROUTE` are four separate method calls at the `GraphService.apply` call site — compile-time ordering, not runtime.

### Pitfall 7: ArchUnit raw-Cypher detection false positives
**What goes wrong:** Rule forbids string literals containing `MATCH`/`CREATE`/`MERGE`/`RETURN`; trips on a Javadoc, a logger message, or a connector's JSON field named `"MATCH"`.
**How to avoid:** Use **type-based detection** (primary) + string pattern (secondary, scoped to main source set). See §"ArchUnit Raw-Cypher Ban" below.

## Code Examples

Verified patterns.

### Agtype Parameter Binding (MIN-1)

AGE does NOT accept JDBC `?` placeholders **inside** the Cypher string — the Cypher function takes a literal SQL-level string. Two working idioms:

**Idiom A — Explicit text-cast to agtype (Phase 0 `AgtypeParameterIT` pattern, already proven green):**

```java
// Source: fabric-core/src/test/java/.../AgtypeParameterIT (Phase 0) — verified green
// GraphSession internal use only
String cypher = """
    SELECT * FROM cypher('tessera_main', $$
        MATCH (n:Person)
        WHERE n.model_id = $model_id AND n.uuid = $uuid
        RETURN n
    $$, $1) AS (n agtype);
    """;
// $1 is a SQL-level parameter bound to a JSON string, cast to agtype server-side
String paramsJson = """
    {"model_id": "%s", "uuid": "%s"}
    """.formatted(ctx.modelId(), nodeUuid);
jdbc.query(cypher, ps -> ps.setObject(1, paramsJson, Types.OTHER), rowMapper);
```

The `agtype` type implicitly accepts a JSON text cast. This is the idiom Phase 0 already exercises.

**Idiom B — `agtype_build_map` inside Cypher (less common, more verbose):**
```sql
SELECT * FROM cypher('tessera_main', $$
  MATCH (n) WHERE n.model_id = $model_id RETURN n
$$, agtype_build_map('model_id', 'uuid-here'::agtype)) AS (n agtype);
```
Idiom A is preferred because all params live in one JSON blob and the Java side has one `setObject` call.

**Do NOT attempt:** `PreparedStatement.setString` with `?` placeholders inside the `$$ ... $$` block — AGE parses the Cypher at server side and does not substitute JDBC bind variables into the inner string. [CITED: https://age.apache.org/age-manual/master/intro/types.html, https://github.com/apache/age/issues/65]

`SET search_path = ag_catalog, "$user", public;` is already in HikariCP `connectionInitSql` (Phase 0 D-10), so every pooled connection has `cypher(...)` visible without schema-qualification.

**Spring `@Transactional` interaction:** the `@Transactional` advice obtains a connection from HikariCP lazily at first use. Because `connectionInitSql` runs when Hikari **creates** the physical connection (not every borrow), the `LOAD 'age'` / `search_path` preamble is stable for the lifetime of the pool. No per-TX re-priming is needed. The rule engine + SHACL validator + `EventLog` writes all share this single connection via Spring's TX synchronization.

### Per-Tenant Monotonic Sequence (EVENT-02)

**Recommended: one Postgres `SEQUENCE` per tenant, created at tenant provisioning.**

```sql
-- In SchemaRegistry.provisionTenant(modelId):
CREATE SEQUENCE graph_events_seq_<sanitized_model_id> AS BIGINT MINVALUE 1 CACHE 50;
```

| Strategy | Cost @ 500/sec/tenant | Contention | Crash safety | Notes |
|---|---|---|---|---|
| **Per-tenant `SEQUENCE` (recommended)** | Lock-free `nextval()` call; O(1) | None (sequences are MVCC-skipping) | Gaps on crash are acceptable — sequences aren't transactional; gaps in `sequence_nr` are benign because it's monotonic, not dense | [CITED: https://www.postgresql.org/docs/16/sql-createsequence.html]; `CACHE 50` amortizes WAL writes |
| Advisory lock + counter row | `pg_advisory_xact_lock(model_id_hash)` + `UPDATE counters SET n=n+1` | Advisory locks serialize per (db, hashed) key — tolerable at 500/sec | Transactional | Works but ~3× slower; unnecessary overhead |
| `SELECT FOR UPDATE` on counter row | `SELECT n FROM tenant_counters WHERE model_id=? FOR UPDATE` + UPDATE | Heavy — row lock held for full TX | Transactional | MOD-5 hot-row anti-pattern; rejected |
| Hi/Lo allocator (in-JVM) | Fetch block of 1000, allocate in-process | None after block fetch | Lost allocations on crash = gaps (same as SEQUENCE) | Spring Data JPA supports but requires JPA entity — overkill, use SEQUENCE directly |
| Single global SEQUENCE, sequence_nr scoped `(model_id, global_nr)` | `nextval('global_seq')` | None | Safe | Rejected by EVENT-02 wording ("per `model_id`") |

**Planner decision:** Per-tenant `SEQUENCE` with `CACHE 50`. Sequence created in the same migration/TX that registers the tenant in the schema registry. Naming: `graph_events_seq_<model_id_hex>` (UUID → hex, no dashes, prefix required for valid SQL identifier). `SequenceAllocator` service caches the sequence name per `model_id` (it's a function of the UUID so no DB lookup needed after construction).

**Deadlock / contention modes:** None — `nextval()` never takes row locks.

**Gap handling:** Gaps in `sequence_nr` are expected after crashes (cached block lost). Replay code must tolerate gaps. `graph_events` table's `(model_id, sequence_nr)` unique index catches any double-allocation.

### Transactional Outbox

**Write side (inside `@Transactional`):**
```sql
INSERT INTO graph_outbox (id, model_id, event_id, topic, payload, status, created_at)
VALUES (gen_random_uuid(), $1, $2, $3, $4, 'PENDING', now());
```

**Poll side (`@Scheduled`):**
```java
// Source: Spring reference docs + ShedLock 5.16 idiom [CITED: https://github.com/lukas-krecan/ShedLock#usage]
@Scheduled(fixedDelay = 500)
@SchedulerLock(name = "outbox-poller", lockAtMostFor = "30s", lockAtLeastFor = "100ms")
public void poll() {
    List<OutboxRow> pending = jdbc.query(
        "SELECT * FROM graph_outbox WHERE status='PENDING' ORDER BY created_at LIMIT 100 FOR UPDATE SKIP LOCKED",
        rowMapper
    );
    for (var row : pending) {
        publisher.publishEvent(new GraphEventPublished(row));
        jdbc.update("UPDATE graph_outbox SET status='DELIVERED', delivered_at=now() WHERE id=?", row.id());
    }
}
```

Key points:
- **`FOR UPDATE SKIP LOCKED`** is the Postgres idiom for safe multi-worker queue polling. Each worker grabs a distinct batch without blocking.
- **ShedLock** is defense-in-depth: even on a single instance, it prevents restart overlap.
- **Never block the write path** — `GraphService.apply()` returns as soon as the outbox row is INSERTed. Delivery happens on a separate thread pool via the `@Scheduled` method.
- **Debezium swap-in (Phase 4)** removes `OutboxPoller` entirely. `graph_outbox` row shape is chosen to match Debezium's Outbox Event Router SMT (columns: `id`, `aggregatetype`, `aggregateid`, `type`, `payload`). The write path does not change.

Do NOT use Postgres `LISTEN/NOTIFY`: (a) NOTIFY payloads are size-limited and lost if no listener is connected; (b) Debezium swap is a rewrite because Debezium doesn't replace NOTIFY semantics; (c) polling is boring, predictable, and testable.

### SHACL Shape Caching

```java
// Source: Apache Jena 5.2.0 API [CITED: https://jena.apache.org/documentation/shacl/]
private final Cache<ShapeKey, Shapes> shapeCache = Caffeine.newBuilder()
    .maximumSize(10_000)                         // 100 tenants × 50 types × 2 active versions ≈ 10k
    .expireAfterAccess(Duration.ofHours(1))
    .recordStats()
    .build();

record ShapeKey(UUID modelId, long schemaVersion, String typeSlug) {}

public Shapes shapesFor(TenantContext ctx, String typeSlug, long schemaVersion) {
    var key = new ShapeKey(ctx.modelId(), schemaVersion, typeSlug);
    return shapeCache.get(key, k -> compileFromRegistry(k));
}
```

**Cache key granularity: `(model_id, schema_version, type_slug)`** — NOT just `(model_id, type_slug)`. Rationale: schema_version is a monotonically-advancing bigint per tenant; on schema change the new version is published, new shapes compile on first use for that version, old-version entries naturally age out via `expireAfterAccess`. This lets concurrent mutations against version N continue using cached shapes while version N+1 rolls out. **Targeted eviction** happens implicitly — no "invalidate all" call needed. For explicit eviction (e.g. rule engine wants to force re-read), `shapeCache.invalidate(key)` is available but rarely needed.

**Memory envelope** (100 tenants × 50 types × 2 active versions = 10k entries):
- Compiled Jena `Shapes` for a typical 5-property type: ~8–20 KB (internal RDF model + constraint trees).
- 10k × 15 KB avg = ~150 MB. Acceptable on a 2 GB JVM heap.
- At 500 tenants × 100 types × 2 versions = 100k entries × 15 KB = 1.5 GB → tune `maximumSize` downward or by weight.

**Hit rate pattern:** After warmup (1 minute under typical connector load), expected >99% hit rate. Cold starts during a schema version rollout show a brief dip. `recordStats()` exposes hit/miss via Micrometer automatically.

### Jena SHACL Perf Envelope

Jena `ShaclValidator.get().validate(shapes, dataGraph)` operating on a targeted in-memory RDF graph (the mutated node + 1-hop neighbors referenced by the shapes):
- **Shape compilation (one-time per (model_id, schema_version, typeSlug)):** 5–50 ms for typical shapes with 10–30 constraints. [CITED: Jena SHACL documentation notes shape parsing is dominated by RDF load, not constraint analysis; one-time cost]
- **Per-mutation validation (cached shapes):** **sub-millisecond to ~2 ms p95** for single-node deltas against a pre-compiled shape, for shapes using only SHACL-Core constraints (sh:datatype, sh:minCount, sh:maxCount, sh:pattern, sh:in, sh:class). This is the Tessera configuration per D-C4.
- **SHACL-SPARQL constraints:** 10–100× slower (each SPARQL constraint is a query plan). **Explicitly rejected in Phase 1 per D-C4** — this is why business rules are Java, not SHACL-SPARQL.

[ASSUMED: specific p95 numbers — extrapolated from Jena benchmark discussions on the dev mailing list and Jena's own shape cache patterns. Planner should add a JMH bench `ShaclValidationBench` that measures real p95 for a representative Tessera shape during Phase 1, comparable to Phase 0's existing benches.]

**Shapes-as-Java-API vs Turtle-file loading:** Turtle loading is slower (string parse) but more testable and reviewable. Recommendation: **build shapes from Java API** (`Shapes.parse(graph)` where `graph` is programmatically constructed from the Schema Registry rows) for runtime; keep Turtle only for human-readable reference shapes in test resources.

### SHACL Report Filtering (VALID-04, CRIT-6)

```java
public ValidationReport redact(ValidationReport full, TenantContext ctx) {
    // Strip literal values; keep shape IRI + constraint type + focus node UUID only.
    // Focus node UUID is always the Tessera-owned UUID, not a literal from the payload.
    ValidationReport.Builder safe = ValidationReport.create();
    full.getEntries().forEach(e -> {
        safe.addReportEntry(
            /* severity */ e.severity(),
            /* focusNode */ extractTesseraUuid(e.focusNode()),
            /* resultPath */ e.resultPath(),        // property URI is schema metadata, not data — safe
            /* sourceShape */ e.sourceShape(),
            /* sourceConstraintComponent */ e.sourceConstraintComponent(),
            /* value */ null,                       // NEVER leak literal values
            /* message */ sanitizedMessage(e)       // template-based message, no literal interpolation
        );
    });
    return safe.build();
}
```

Internal audit log (separate table `shacl_audit_report`, tighter ACL) may store the full report; operator endpoints never return it directly.

### Jqwik Tenant-Bypass Fuzz (D-D1, CORE-03)

```java
// Source: jqwik 1.9 user guide [CITED: https://jqwik.net/docs/current/user-guide.html#property-based-testing]
@Testcontainers
class TenantBypassPropertyIT {

    @Container
    static final PostgreSQLContainer<?> PG = AgePostgresContainer.create();

    @Property(tries = 1000)
    @Report(Reporting.GENERATED)
    void queryCannotSeeOtherTenantNodes(
            @ForAll("tenantPair") Pair<UUID, UUID> tenants,
            @ForAll("mutations") List<GraphMutation> seedsA,
            @ForAll("mutations") List<GraphMutation> seedsB,
            @ForAll @IntRange(min = 1, max = 5) int hopDepth) {

        var ctxA = TenantContext.of(tenants.first());
        var ctxB = TenantContext.of(tenants.second());

        // Seed both tenants
        seedsA.forEach(m -> graphService.apply(m.withTenant(ctxA)));
        seedsB.forEach(m -> graphService.apply(m.withTenant(ctxB)));

        // Run every read op as tenant A; assert zero tenant-B uuids appear
        var aResults = graphRepository.queryAll(ctxA);
        var bUuids = seedsB.stream().map(GraphMutation::targetNodeUuid).collect(Collectors.toSet());
        assertThat(aResults).extracting(Node::uuid).doesNotContainAnyElementsOf(bUuids);
    }

    @Provide
    Arbitrary<Pair<UUID, UUID>> tenantPair() {
        return Arbitraries.create(UUID::randomUUID)
                .tuple2()
                .filter(p -> !p.get1().equals(p.get2()))
                .map(t -> Pair.of(t.get1(), t.get2()));
    }

    @Provide
    Arbitrary<List<GraphMutation>> mutations() {
        return Arbitraries.defaultFor(GraphMutation.class)
                .list().ofMinSize(1).ofMaxSize(20);
    }
}
```

**One `@Property` per read/write op:** `create`, `get`, `query`, `update`, `tombstone`, `traverse`, `find_path` → 7 property methods, each `tries = 1000` → 7000 scenarios. Wall-clock budget: with `@Container` reuse (`withReuse(true)` already on `AgePostgresContainer`), each scenario is ~5 ms of in-DB work → 35 s. Stays under the 60 s D-D1 budget.

**Shrinking strategy:** jqwik's default integrated shrinker minimizes to the smallest mutation list that still reproduces the bug. For composite types (records like `GraphMutation`), `@ForAll` uses reflection-based provider; override via `@Provide` methods where more targeted generation helps. When a property fails, jqwik prints the shrunk counter-example with the RNG seed — commit the seed into a regression `@Example` test so the exact case is permanent.

**Where property tests live:** `fabric-core/src/test/java/dev/tessera/core/graph/property/TenantBypassPropertyIT.java` (integration because it uses Testcontainers + real Cypher). Run via Failsafe in the standard `verify` lifecycle.

### ArchUnit Raw-Cypher Ban (CORE-02)

Phase 0 deferred this to Phase 1 per D-15. Two-layer detection — primary is type-based, secondary is string-pattern scoped to source only.

```java
// Source: ArchUnit 1.3 user guide [CITED: https://www.archunit.org/userguide/html/000_Index.html]
public class RawCypherBanTest {

    private static final JavaClasses ALL = new ClassFileImporter()
        .importPackages("dev.tessera");

    // Layer 1 (primary, type-based): pgJDBC + JdbcTemplate are only allowed in graph.internal
    @ArchTest
    static final ArchRule only_graph_internal_may_touch_pgjdbc =
        noClasses()
            .that().resideOutsideOfPackage("dev.tessera.core.graph.internal..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.postgresql..",
                "org.springframework.jdbc.core..")
            .because("CORE-02: only graph.internal may execute raw Cypher or touch pgJDBC directly");

    // Layer 2 (secondary, string-pattern): catch constant strings containing Cypher keywords
    // outside graph.internal. Use a custom ArchCondition over JavaField + instruction scan.
    @ArchTest
    static final ArchRule no_cypher_strings_outside_internal =
        noClasses()
            .that().resideOutsideOfPackage("dev.tessera.core.graph.internal..")
            .should(containCypherStringConstant())
            .because("CORE-02: Cypher string literals must live in graph.internal");

    private static ArchCondition<JavaClass> containCypherStringConstant() {
        return new ArchCondition<>("contain Cypher string literal") {
            @Override public void check(JavaClass cls, ConditionEvents events) {
                cls.getFields().stream()
                    .filter(f -> f.getRawType().getName().equals("java.lang.String"))
                    .forEach(f -> {
                        // Reflect the compile-time constant value if present
                        Object c = f.reflect().tryGetConstantValue().orElse(null);
                        if (c instanceof String s && looksLikeCypher(s)) {
                            events.add(SimpleConditionEvent.violated(f,
                                cls.getName() + "." + f.getName() + " looks like Cypher"));
                        }
                    });
            }
            private boolean looksLikeCypher(String s) {
                // Require co-occurrence of multiple keywords + the AGE cypher() call hint
                // to minimize false positives on doc strings / JSON field names.
                var upper = s.toUpperCase();
                int hits = 0;
                for (var kw : List.of(" MATCH ", " CREATE ", " MERGE ", " RETURN ", " SET ")) {
                    if (upper.contains(kw)) hits++;
                }
                return hits >= 2 || upper.contains("CYPHER('");
            }
        };
    }
}
```

**False positive rate:**
- **Layer 1 (type-based):** zero false positives on real code. Any violation is a genuine architectural leak. Catches connectors, rule engine, projection engine reaching into pgJDBC directly.
- **Layer 2 (string-pattern):** near-zero when keyed on "≥2 Cypher keywords with leading spaces" + the explicit `CYPHER('` AGE call hint. Documentation in Javadoc is `/** ... */` — ArchUnit's `tryGetConstantValue` only reads compile-time constants, NOT comments. A log message like `"Using CYPHER('tessera_main', ...)"` is a correctly-flagged violation (it shouldn't be outside `graph.internal`). Tests themselves live in `test/` source set and are not scanned.

**Extend, don't replace:** Phase 0 `ModuleDependencyTest` already exists. Add this as a sibling class `RawCypherBanTest` in `fabric-app/src/test/java/dev/tessera/arch/`. Both run in the standard `verify` lifecycle.

### Event Log Schema (EVENT-01..08)

```sql
-- V2__graph_events.sql
CREATE TABLE graph_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    model_id UUID NOT NULL,
    sequence_nr BIGINT NOT NULL,
    event_type TEXT NOT NULL,          -- CREATE_NODE | UPDATE_NODE | TOMBSTONE_NODE | CREATE_EDGE | TOMBSTONE_EDGE
    node_uuid UUID,                    -- nullable for edge events
    edge_uuid UUID,                    -- nullable for node events
    type_slug TEXT NOT NULL,
    payload JSONB NOT NULL,            -- full post-state (for EVENT-06 replay)
    delta JSONB,                       -- changed fields (EVENT-03)
    caused_by TEXT NOT NULL,
    source_type TEXT NOT NULL,         -- STRUCTURED | EXTRACTION | MANUAL | SYSTEM
    source_id TEXT NOT NULL,
    source_system TEXT NOT NULL,
    confidence NUMERIC(4,3) NOT NULL DEFAULT 1.0,
    extractor_version TEXT,            -- Phase 2.5 populates
    llm_model_id TEXT,                 -- Phase 2.5 populates
    origin_connector_id TEXT,          -- RULE-08: echo-loop prevention
    origin_change_id TEXT,             -- RULE-08
    event_time TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),  -- CORE-08
    CONSTRAINT graph_events_tenant_check CHECK (model_id IS NOT NULL)
) PARTITION BY RANGE (event_time);

CREATE UNIQUE INDEX idx_graph_events_model_seq ON graph_events (model_id, sequence_nr);
CREATE INDEX idx_graph_events_node_uuid ON graph_events (node_uuid) WHERE node_uuid IS NOT NULL;
CREATE INDEX idx_graph_events_model_type_time ON graph_events (model_id, type_slug, event_time DESC);

-- Monthly partition (MOD-6 partitioning from day 1)
CREATE TABLE graph_events_y2026m04 PARTITION OF graph_events
    FOR VALUES FROM ('2026-04-01') TO ('2026-05-01');
-- Partition creation automated via pg_partman or a @Scheduled job; pick one during plan phase.
```

`payload` column stores the full post-state so temporal replay can reconstruct `get_state_at(T)` without walking back to a snapshot (EVENT-06). `delta` stores only changed fields for cheap audit-diff display (EVENT-07).

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|---|---|---|---|
| `bootstrap.yml` for Vault | `spring.config.import=vault://` | Spring Cloud Vault 3.0 (2021) | Not Phase 1 (Vault is Phase 2), but reference for config patterns |
| Hand-rolled sequence counter | Per-tenant `CREATE SEQUENCE` with `CACHE N` | Standard since PG 9+ | Lock-free allocation |
| SHACL-SPARQL for business rules | Java Rule classes + SHACL-Core for shape only | Locked by D-C4 (this phase) | 10–100× faster business-rule eval |
| Thread-local tenant context | Explicit `TenantContext` parameter | Locked by D-16 (Phase 0) | Async-safe; compile-time enforcement |
| Single-column JSONB schema definitions | Typed tables + JSONB for flex fields | Locked by D-B1 | Indexable core attrs, extensible detail |
| MAX()+1 sequence allocation | Postgres `SEQUENCE` objects | ~PG 8 (ancient) | Still seen in bad code; MOD-5 |

**Deprecated / outdated:**
- **Spring Boot 3.4.x:** EOL, do not use.
- **SHACL-SPARQL inside Jena for hot paths:** rejected by D-C4.
- **Spring AI 2.0.0-Mx milestones:** not Phase 1 (MCP is Phase 3), but flagged for the record.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|---|---|---|
| A1 | Jena SHACL-Core per-mutation validation p95 < 2 ms for cached shapes at Tessera's shape | Jena SHACL Perf Envelope | Rule engine + SHACL combined budget could blow past Phase 0 baseline + 2× gate on write path. Mitigation: plan includes a `ShaclValidationBench` JMH class to measure real numbers in Phase 1 Wave 1. |
| A2 | jqwik 1.9.2 is the latest stable 1.9.x on Maven Central as of 2026-04 | Standard Stack | Minor — planner runs `mvn versions:display-dependency-updates` before pinning. |
| A3 | ArchUnit `tryGetConstantValue()` successfully extracts Cypher string constants from compiled classes without reading comments | ArchUnit Raw-Cypher Ban | Layer 2 might miss inlined string literals returned from methods. Mitigation: Layer 1 (type-based pgJDBC ban) is the primary defense; Layer 2 is defense-in-depth. |
| A4 | 100 tenants × 50 types × 2 active versions × 15 KB per compiled `Shapes` ≈ 150 MB heap is acceptable | SHACL Shape Caching | If real compiled-Shapes size is larger, cache hit rate will drop. Mitigation: `recordStats()` is enabled; plan includes a shape-size measurement task. |
| A5 | Full Phase 1 write pipeline (rules + SHACL + Cypher + event log + outbox) adds <10ms to Phase 0 point-lookup baseline of 1ms — i.e. total p95 < 11 ms, well inside the 2× budget | Performance Budget | If the budget is blown, planner must split rule engine evaluation out of the TX and accept eventual-consistency reconciliation. This would be a significant architectural deviation — plan must include a perf gate. |
| A6 | Per-tenant `SEQUENCE` objects scale to ~10k tenants without catalog bloat | Per-Tenant Monotonic Sequence | Postgres catalog can hold millions of sequences comfortably, but DDL on sequence creation is slow. Mitigation: sequence is created once per tenant at provisioning time, amortized. For >10k tenants, consider hi/lo with a counter table. |
| A7 | Jena `ValidationReport` can be reconstructed via a builder that omits literal values without breaking the API contract | SHACL Report Filtering | If Jena's builder doesn't support partial reconstruction, use a custom `TesseraValidationReport` DTO and map from Jena's report manually. |
| A8 | ShedLock 5.16 + `@Scheduled(fixedDelay = 500)` + `FOR UPDATE SKIP LOCKED` is sufficient for outbox delivery at 500 events/sec/tenant aggregate | Transactional Outbox | If aggregate outbox backlog grows, tune fixedDelay or increase batch size. Observable via `graph_outbox WHERE status='PENDING'` metric. |

**Risk consolidation:** A1 and A5 are the load-bearing assumptions. Plan MUST include a JMH regression that measures real write-pipeline p95 latency against the Phase 0 baseline and fails nightly if it exceeds `baseline × 2` for any of the four query shapes.

## Open Questions (RESOLVED)

1. **Real per-mutation SHACL p95 at Tessera shape**
   - What we know: Jena SHACL-Core is fast; cached shapes avoid the compile cost.
   - What's unclear: Actual number at 5–10 property shapes with 2–3 constraints each on typical Tessera payloads.
   - RESOLVED: Wave-3 `ShaclValidationBench` JMH class (plan 01-W3-01) measures real p95 on first nightly; tentative target < 2 ms p95 cached-shape per CONTEXT.md D-C1 perf budget. JMH skeleton exists from Wave 0 (01-W0-03) so Wave 3 only fills the body.

2. **pg_partman vs hand-rolled monthly partition creation**
   - What we know: EVENT-01 requires monthly partitioning; partition creation must happen.
   - What's unclear: Whether to pull `pg_partman` extension or write a small `@Scheduled` job that runs `CREATE TABLE ... PARTITION OF ...`.
   - RESOLVED: hand-rolled monthly partition creation via a Spring `@Scheduled` task in plan 01-W2-02; `pg_partman` deliberately avoided to minimize CRIT-1/2 extension-upgrade risk multiplication.

3. **Routing outcome semantics (D-C1 ROUTE chain)**
   - What we know: ROUTE chain exists; it tags outbox rows with downstream consumer hints.
   - What's unclear: What exactly the `route(target)` outcome encodes when there's no projection yet (Phase 1 ships no consumers).
   - RESOLVED: ROUTE outcomes write to a `graph_outbox.routing_hints` JSONB column (V3 migration in 01-W0-01); the ROUTE chain may be empty in Phase 1 but the plumbing is present so Phase 2+ projections consume without write-path changes.

4. **jqwik seed strategy for reproducible failures**
   - What we know: jqwik prints the seed on failure; re-running with `@Seed(...)` reproduces.
   - What's unclear: Should each property pin a stable seed for CI?
   - RESOLVED: unseeded in CI (jqwik's default random seed); failing seeds are promoted to `@Example` regression tests as permanent records. A comment in `TenantBypassPropertyIT` documents this policy.

5. **Circuit breaker cold-start grace window**
   - What we know: `AtomicLongArray` state is in-process only; restarts zero it.
   - What's unclear: Do we grant a grace period on startup before the breaker can trip?
   - RESOLVED: 60-second startup grace window configurable via `tessera.circuit.startup-grace` in `application.yml`, default `60000` ms. During the grace window the breaker accumulates rate data but does not trip. Enforced in plan 01-W3-03.

## Environment Availability

Phase 0 already verified the runtime. No new dependencies beyond Maven Central.

| Dependency | Required By | Available | Version | Fallback |
|---|---|---|---|---|
| Java 21 (Corretto) | Runtime | ✓ | OpenJDK 23 also installed | — |
| Maven 3.9 | Build | ✓ | 3.9.x | Maven Wrapper committed |
| Docker + Testcontainers | Integration tests | ✓ | 27.4 | — |
| apache/age image (digest-pinned) | AGE tests | ✓ | `sha256:16aa423d…feaed` | — |
| PostgreSQL 16 (in container) | Runtime | ✓ | via Docker image | — |
| jqwik 1.9.2 | Property-based tests | ✓ (Maven Central) | 1.9.2 | — |
| Jena jena-shacl 5.2.0 | SHACL | ✓ (Maven Central) | 5.2.0 | — |
| Caffeine 3.1.8 | Cache | ✓ (Maven Central) | 3.1.8 | — |
| ShedLock 5.16 | Outbox poller | ✓ (Maven Central) | 5.16.0 | — |

**Missing dependencies with no fallback:** None.
**Missing dependencies with fallback:** None.

## Validation Architecture

### Test Framework

| Property | Value |
|---|---|
| Framework | JUnit 5 (spring-boot-starter-test 3.5.13) + jqwik 1.9.2 (property-based) + ArchUnit 1.3 + Testcontainers 1.20.4 |
| Config file | `pom.xml` per module (surefire/failsafe); no central test config |
| Quick run command | `./mvnw -pl fabric-core,fabric-rules -am test` |
| Full suite command | `./mvnw -B verify` |
| Integration test suffix | `*IT.java` (Failsafe) |
| Unit test suffix | `*Test.java` (Surefire) |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|---|---|---|---|---|
| CORE-01 | Single TX: auth→rules→SHACL→Cypher→events→outbox atomic (rollback rolls all) | integration | `./mvnw -pl fabric-core -Dit.test=GraphServiceApplyIT verify` | ❌ Wave 0 |
| CORE-02 | ArchUnit ban on raw Cypher outside `graph.internal` | unit (ArchUnit) | `./mvnw -pl fabric-app -Dtest=RawCypherBanTest test` | ❌ Wave 0 |
| CORE-03 | Tenant bypass impossible (jqwik fuzz, 1000+ tries/op) | integration (jqwik + Testcontainers) | `./mvnw -pl fabric-core -Dit.test=TenantBypassPropertyIT verify` | ❌ Wave 0 |
| CORE-04 | Node CRUD through GraphService | integration | `./mvnw -pl fabric-core -Dit.test=NodeLifecycleIT verify` | ❌ Wave 0 |
| CORE-05 | Edge CRUD through GraphService | integration | `./mvnw -pl fabric-core -Dit.test=EdgeLifecycleIT verify` | ❌ Wave 0 |
| CORE-06 | System properties present on every node/edge | integration | `./mvnw -pl fabric-core -Dit.test=SystemPropertiesIT verify` | ❌ Wave 0 |
| CORE-07 | Tombstone-default delete; hard-delete explicit | integration | `./mvnw -pl fabric-core -Dit.test=TombstoneSemanticsIT verify` | ❌ Wave 0 |
| CORE-08 | Tessera-owned timestamps (never trusted from payload) | unit | `./mvnw -pl fabric-core -Dtest=TimestampOwnershipTest test` | ❌ Wave 0 |
| SCHEMA-01 | `schema_node_types` CRUD | integration | `./mvnw -pl fabric-core -Dit.test=SchemaNodeTypeCrudIT verify` | ❌ Wave 0 |
| SCHEMA-02 | `schema_properties` CRUD | integration | `./mvnw -pl fabric-core -Dit.test=SchemaPropertyCrudIT verify` | ❌ Wave 0 |
| SCHEMA-03 | `schema_edge_types` CRUD | integration | `./mvnw -pl fabric-core -Dit.test=SchemaEdgeTypeCrudIT verify` | ❌ Wave 0 |
| SCHEMA-04 | Versioned schema, old versions queryable | integration | `./mvnw -pl fabric-core -Dit.test=SchemaVersioningReplayIT verify` | ❌ Wave 0 |
| SCHEMA-05 | Property aliases preserve old-slug reads | integration | `./mvnw -pl fabric-core -Dit.test=SchemaAliasIT verify` | ❌ Wave 0 |
| SCHEMA-06 | Caffeine schema descriptor cache invalidates on change | unit | `./mvnw -pl fabric-core -Dtest=SchemaCacheInvalidationTest test` | ❌ Wave 0 |
| SCHEMA-07 | Schema registry is single source of truth — consumed by SHACL | integration | `./mvnw -pl fabric-core -Dit.test=SchemaToShaclIT verify` | ❌ Wave 0 |
| SCHEMA-08 | Breaking changes rejected unless forced | integration | `./mvnw -pl fabric-core -Dit.test=SchemaBreakingChangeIT verify` | ❌ Wave 0 |
| VALID-01 | SHACL runs synchronously in write TX, rejects invalid mutations | integration | `./mvnw -pl fabric-core -Dit.test=ShaclPreCommitIT verify` | ❌ Wave 0 |
| VALID-02 | Compiled shapes cached per `(model_id, schema_version, type_slug)` | unit | `./mvnw -pl fabric-core -Dtest=ShapeCacheTest test` | ❌ Wave 0 |
| VALID-03 | Targeted validation (single-node in-memory RDF, not full graph) | unit | `./mvnw -pl fabric-core -Dtest=TargetedValidationTest test` | ❌ Wave 0 |
| VALID-04 | `ValidationReport` tenant-filtered before consumer/logs | unit | `./mvnw -pl fabric-core -Dtest=ValidationReportFilterTest test` | ❌ Wave 0 |
| VALID-05 | Business-rule REJECT outcome from rule engine blocks commit | integration | `./mvnw -pl fabric-rules -Dit.test=BusinessRuleRejectIT verify` | ❌ Wave 0 |
| EVENT-01 | `graph_events` append-only, partitioned, indexed | integration | `./mvnw -pl fabric-core -Dit.test=EventLogSchemaIT verify` | ❌ Wave 0 |
| EVENT-02 | `sequence_nr` from per-tenant `SEQUENCE`, never MAX()+1 | integration (and unit on allocator) | `./mvnw -pl fabric-core -Dit.test=PerTenantSequenceIT verify` | ❌ Wave 0 |
| EVENT-03 | Every mutation emits full-provenance event | integration | `./mvnw -pl fabric-core -Dit.test=EventProvenanceIT verify` | ❌ Wave 0 |
| EVENT-04 | `graph_outbox` written in same TX as event+Cypher | integration (rollback injection test) | `./mvnw -pl fabric-core -Dit.test=OutboxTransactionalIT verify` | ❌ Wave 0 |
| EVENT-05 | In-process outbox poller publishes via ApplicationEventPublisher | integration | `./mvnw -pl fabric-core -Dit.test=OutboxPollerIT verify` | ❌ Wave 0 |
| EVENT-06 | Temporal replay: get_state_at(T) reconstructs from events | integration | `./mvnw -pl fabric-core -Dit.test=TemporalReplayIT verify` | ❌ Wave 0 |
| EVENT-07 | Audit query: full mutation history per node | integration | `./mvnw -pl fabric-core -Dit.test=AuditHistoryIT verify` | ❌ Wave 0 |
| RULE-01 | Chain-of-responsibility executor, priority-ordered, short-circuit | unit | `./mvnw -pl fabric-rules -Dtest=ChainExecutorTest test` | ❌ Wave 0 |
| RULE-02 | `Rule` interface semantics | unit | `./mvnw -pl fabric-rules -Dtest=RuleInterfaceTest test` | ❌ Wave 0 |
| RULE-03 | All 6 rule outcomes (COMMIT/REJECT/MERGE/OVERRIDE/ADD/ROUTE) | unit | `./mvnw -pl fabric-rules -Dtest=RuleOutcomeTest test` | ❌ Wave 0 |
| RULE-04 | Rules registered via Spring DI per chain; per-tenant config from DB | integration | `./mvnw -pl fabric-rules -Dit.test=RuleRegistrationIT verify` | ❌ Wave 0 |
| RULE-05 | Per-tenant per-property source authority matrix | integration | `./mvnw -pl fabric-rules -Dit.test=SourceAuthorityIT verify` | ❌ Wave 0 |
| RULE-06 | `reconciliation_conflicts` register writes on contested property | integration | `./mvnw -pl fabric-rules -Dit.test=ConflictRegisterIT verify` | ❌ Wave 0 |
| RULE-07 | Write-amplification circuit breaker trips at threshold; pause + DLQ + metric | unit + integration | `./mvnw -pl fabric-rules -Dtest=CircuitBreakerTest test` and `-Dit.test=CircuitBreakerIT verify` | ❌ Wave 0 |
| RULE-08 | Every event tracks origin_connector_id + origin_change_id; echo-loop test | integration | `./mvnw -pl fabric-rules -Dit.test=EchoLoopSuppressionIT verify` | ❌ Wave 0 |

### Sampling Rate

- **Per task commit:** `./mvnw -pl <touched-module>,<touched-module>/... test` (fast feedback, Surefire unit tests only)
- **Per wave merge:** `./mvnw -B verify` (full multi-module including Failsafe IT, ArchUnit, jqwik properties)
- **Phase gate (before `/gsd-verify-work`):** full suite green PLUS `./mvnw -pl fabric-core -Pjmh -Djmh.dataset=100000 verify` to confirm write-pipeline p95 stays within Phase 0 baseline × 2

### Wave 0 Gaps

All Phase 1 test files are new. Wave 0 tasks must seed:

- [ ] `fabric-core/pom.xml` — add jqwik 1.9.2 test dependency
- [ ] `fabric-rules/pom.xml` — add jqwik 1.9.2 test dependency
- [ ] `fabric-app/src/test/java/dev/tessera/arch/RawCypherBanTest.java` — sibling to `ModuleDependencyTest`, covers CORE-02
- [ ] `fabric-core/src/test/java/dev/tessera/core/graph/property/TenantBypassPropertyIT.java` — jqwik @Property harness for CORE-03, one method per op
- [ ] `fabric-core/src/test/java/dev/tessera/core/support/SchemaFixtures.java` — reusable builder for test schemas (node type + properties + edge types)
- [ ] `fabric-core/src/test/java/dev/tessera/core/support/MutationFixtures.java` — jqwik `Arbitrary<GraphMutation>` provider
- [ ] `fabric-core/src/jmh/java/dev/tessera/core/bench/ShaclValidationBench.java` — per-mutation SHACL validation p95 measurement (extends Phase 0 JMH harness)
- [ ] `fabric-core/src/jmh/java/dev/tessera/core/bench/WritePipelineBench.java` — full `GraphService.apply` write-pipeline p95 measurement against Phase 0 baseline
- [ ] All integration test files listed in the Requirements → Test Map above — each is a thin shell in Wave 0, filled in by its owning wave

**Framework install:** jqwik is a new dependency; everything else is already in the Phase 0 parent POM dependencyManagement.

## Sources

### Primary (HIGH confidence)
- `.planning/phases/01-graph-core-schema-registry-validation-rules/01-CONTEXT.md` — 15 locked decisions (A1..A3, B1..B3, C1..C4, D1..D3)
- `.planning/research/ARCHITECTURE.md` §2.3 write pipeline, §3.3 outbox, §3.4 tenancy, §3.5 SHACL, §4 patterns, §5 anti-patterns, §8 build order
- `.planning/research/STACK.md` — version locks (all pinned in Phase 0)
- `.planning/research/PITFALLS.md` — CRIT-3/5/6, MOD-1..8, MIN-1..3
- `.planning/phases/00-foundations-risk-burndown/00-VERIFICATION.md` — JMH baseline numbers (point 0.818ms, traversal 12.857ms, aggregate ~330ms, pagination 56.148ms), Phase 0 deviations still in scope for Phase 1
- `.planning/phases/00-foundations-risk-burndown/00-CONTEXT.md` — D-09/D-10/D-14/D-15/D-16
- `fabric-core/src/test/java/dev/tessera/core/support/AgePostgresContainer.java` — reusable digest-pinned container helper
- `fabric-core/src/main/java/dev/tessera/core/tenant/TenantContext.java` — Phase 0 record
- `.planning/REQUIREMENTS.md` §Graph Core / §Schema Registry / §Validation / §Event Log & Outbox / §Rule Engine

### Secondary (HIGH-MEDIUM)
- [Apache Jena SHACL documentation](https://jena.apache.org/documentation/shacl/) — shape compilation, validation API
- [Apache AGE types / parameter binding](https://age.apache.org/age-manual/master/intro/types.html) and [issue #65](https://github.com/apache/age/issues/65) — agtype param binding
- [Debezium Outbox Event Router](https://debezium.io/documentation/reference/stable/transformations/outbox-event-router.html) — target shape for `graph_outbox` columns
- [jqwik user guide](https://jqwik.net/docs/current/user-guide.html) — property-based test idioms, `@Provide`, shrinking, `@Seed`
- [PostgreSQL 16 CREATE SEQUENCE](https://www.postgresql.org/docs/16/sql-createsequence.html) — CACHE, crash gap semantics
- [ShedLock](https://github.com/lukas-krecan/ShedLock#usage) — `@SchedulerLock` for outbox poller
- [ArchUnit user guide](https://www.archunit.org/userguide/html/000_Index.html) — `noClasses().should()` patterns, custom `ArchCondition`
- [Spring `@TransactionalEventListener`](https://docs.spring.io/spring-framework/reference/data-access/transaction/event.html) — AFTER_COMMIT phase for post-commit fan-out

### Tertiary (LOW)
- Specific Jena SHACL p95 numbers at Tessera shape — extrapolated, not measured (see Assumption A1).
- 150 MB shape cache heap envelope — estimated, not measured (see Assumption A4).

## Metadata

**Confidence breakdown:**
- Standard Stack: HIGH — all versions locked from Phase 0 + STACK.md, verified Phase 0 build green.
- Architecture Patterns: HIGH — ARCHITECTURE.md is authoritative and CONTEXT.md locked 15 decisions.
- Pitfalls: HIGH for Phase 1-scoped items (CRIT-5/6, MOD-1..8, MIN-1..3) — sourced from upstream AGE issues and Phase 0 live-fire findings.
- Perf envelope (SHACL, write pipeline): MEDIUM — extrapolated from Jena benchmarks and Phase 0 baseline; nailed down by Wave 1 JMH bench tasks.
- ArchUnit raw-Cypher ban: HIGH — type-based detection is proven; string-pattern is defense-in-depth.
- Jqwik idioms: HIGH — direct mapping from jqwik user guide + Testcontainers reuse pattern.

**Research date:** 2026-04-13
**Valid until:** 2026-05-13 (30 days — stack is locked, no fast-moving dependencies)

## RESEARCH COMPLETE
