# Architecture Patterns

**Domain:** Graph-based, protocol-agnostic data fabric / meta-model-driven integration layer
**Project:** Tessera
**Researched:** 2026-04-13
**Overall confidence:** MEDIUM-HIGH

---

## 1. Executive Summary

Tessera's proposed 6-layer architecture (Consumers -> Protocol Adapters -> Projection Engine -> Rule Engine -> Knowledge Graph -> Connector Layer) aligns cleanly with modern data fabric patterns: a graph/event core wrapped by a rule/reconciliation layer, fed from connectors on the south side and exposed through generated projections on the north side. The proposed Maven module split (`fabric-core`, `fabric-rules`, `fabric-connectors`, `fabric-projections`, `fabric-app`) mirrors responsibility boundaries correctly and keeps the graph core free of projection/protocol concerns.

The most consequential refinements this research recommends are: (1) treat the Projection Engine as a **runtime routing layer backed by a schema-driven metadata registry**, not as a code-generation step, (2) adopt a **transactional outbox** inside `fabric-core` from day one so the in-process Spring event bus can later be replaced by Debezium -> Kafka without refactoring the write path, (3) make SHACL validation a **synchronous pre-commit interceptor** inside the graph write pipeline (not a post-commit async check), and (4) keep the **single-graph + `model_id` filter** multi-tenancy model but enforce it via a mandatory repository-layer guard rather than application discipline.

The architecture is buildable by a solo developer in Phase 1 scope if built in a strict bottom-up order: graph core + event log before rules, rules before projections, projections before connectors. Skipping this order — especially starting with the REST projection before the schema registry is stable — is the most common failure mode in comparable projects.

---

## 2. Recommended Architecture

### 2.1 Layer Diagram (validated / refined)

```
+-------------------------------------------------------------+
|                    Consumers (North)                        |
|   circlead | BI | LLM agents | dashboards | webhooks        |
+------------------------------+------------------------------+
                               |
+------------------------------v------------------------------+
|               Protocol Adapters  (fabric-projections)       |
|   REST (MVP) | MCP | SQL views | Kafka | GraphQL | WS/SSE   |
|   -- thin, stateless, delegate to Projection Engine --      |
+------------------------------+------------------------------+
                               |
+------------------------------v------------------------------+
|           Projection Engine  (fabric-projections/core)      |
|   Schema -> route table | query builder | response shaper   |
|   Caching | pagination | filtering | subscription mgmt     |
+------------------------------+------------------------------+
                               |
+------------------------------v------------------------------+
|                Rule Engine  (fabric-rules)                  |
|   Reconciliation | Validation (business) | Routing          |
|   Chain-of-Responsibility, priority DESC, DB-backed rules   |
+------------------------------+------------------------------+
                               |
+------------------------------v------------------------------+
|           Knowledge Graph Core  (fabric-core)               |
|   GraphService facade                                       |
|     +-- Write pipeline: Authorize -> Map -> SHACL ->        |
|         Cypher (AGE) -> Event append -> Outbox append       |
|     +-- Read pipeline: Cypher -> agtype -> DTO              |
|   Schema Registry (same DB) | Event log | Outbox table      |
|   PostgreSQL 16 + Apache AGE                                |
+------------------------------+------------------------------+
                               |
+------------------------------v------------------------------+
|            Connector Layer (South)  (fabric-connectors)     |
|   Polling (MVP: generic REST + ETag)                        |
|   Webhook | CDC/Debezium | JDBC | File                      |
|   -- emits GraphMutation records, never writes AGE direct - |
+-------------------------------------------------------------+
```

Arrows are **data/control flow**, not dependency direction. Module dependency direction is strictly upward from `fabric-core`:

```
fabric-app  ->  fabric-projections, fabric-connectors, fabric-rules, fabric-core
fabric-projections  ->  fabric-rules (only for validation rules), fabric-core
fabric-connectors   ->  fabric-rules, fabric-core
fabric-rules        ->  fabric-core
fabric-core         ->  (no internal deps)
```

`fabric-core` must not know that projections or connectors exist.

### 2.2 Component Boundaries

| Component | Module | Responsibility | Does NOT |
|---|---|---|---|
| GraphService | fabric-core | Single write entrypoint; runs SHACL, Cypher, event append, outbox append in one TX | Know about HTTP, Kafka, or connectors |
| Schema Registry | fabric-core | CRUD on `schema_node_types`, `schema_properties`, `schema_edge_types`; emits schema-change events | Generate code; host runtime routing |
| Event Log | fabric-core | Append-only `graph_events` table; sequence per `model_id` | Fan out to external consumers directly |
| Outbox | fabric-core | `graph_outbox` table written in same TX as event log; polled or CDC'd later | Guarantee ordering across tenants |
| SHACL Validator | fabric-core | Pre-commit Shape validation of pending mutations | Run reconciliation; decide authority |
| Rule Engine | fabric-rules | Chain-of-Responsibility executor; rule repo; authority matrix | Write to graph directly; execute Cypher |
| Projection Engine | fabric-projections/core | Read schema registry, build request-routing tables, translate external query -> Cypher | Cache auth decisions; bypass GraphService for writes |
| REST Adapter | fabric-projections/rest | One generic `RequestMappingHandlerMapping` that resolves `/api/v1/{model}/entities/{typeSlug}` against Projection Engine | Contain per-type controllers |
| Connector Framework | fabric-connectors/api | `Connector` SPI; `MappingDefinition` model; scheduler; retry / DLQ | Own the graph write path |
| Connector Runtime | fabric-connectors/runtime | Scheduler, backpressure, retry/backoff, DLQ table, sync-status | Decide reconciliation outcome |

### 2.3 Write Pipeline (authoritative flow)

All writes — whether from a connector, a REST PUT, or an MCP tool — funnel through `GraphService.apply(GraphMutation)`:

```
1. Authorize(model_id, caller, operation)       -- tenant guard
2. Load schema(type) from registry              -- cached
3. RuleEngine.reconcile(mutation, currentNode)  -- may transform, reject, flag
4. SHACL.validate(proposedGraphDelta)           -- synchronous, tx-scoped
5. Cypher write via AGE                         -- same TX
6. Append to graph_events                       -- same TX, monotonic seq per model_id
7. Append to graph_outbox                       -- same TX
8. TX commit
9. Post-commit: ApplicationEventPublisher fires GraphEventPublished (in-process, MVP)
10. Post-commit: outbox poller / Debezium picks up row (Phase 2)
```

Everything through step 8 is in **one Postgres transaction**. This is only possible because AGE lives inside Postgres (the entire ADR-1 payoff).

---

## 3. Answers to Architecture Questions

### 3.1 Dynamic schema -> endpoint generation: runtime routing (NOT code-gen, NOT reflection of JPA entities)

**Recommendation:** Runtime routing with a single generic handler, backed by the Schema Registry. Confidence: HIGH.

Three approaches exist:

| Approach | Mechanism | Verdict |
|---|---|---|
| **Code-generation** | Generate Java source files per type on schema change, recompile, hot-reload | REJECT — adds build-time complexity, breaks IDE, slow feedback, painful in a live multi-tenant system |
| **Reflection of pre-existing entity classes** | Scan @Entity classes, build controllers via Class.forName (e.g. Restzilla pattern) | REJECT — requires Java classes per type, defeats "add a type via API" |
| **Runtime routing via generic handler** | One `RequestMappingHandlerMapping` (or one `@RequestMapping("/api/v1/{model}/entities/{typeSlug}/**")` controller) that resolves typeSlug against the Schema Registry at request time | **ADOPT** |

**Concrete pattern for `fabric-projections/rest`:**

```java
@RestController
@RequestMapping("/api/v1/{modelSlug}/entities/{typeSlug}")
public class GenericEntityController {

    private final ProjectionEngine projectionEngine;
    private final GraphService graphService;

    @GetMapping
    public PageResponse list(@PathVariable String modelSlug,
                             @PathVariable String typeSlug,
                             @RequestParam Map<String, String> filters,
                             Pageable pageable) {
        var ctx = projectionEngine.resolve(modelSlug, typeSlug); // validates type exists
        var cypher = projectionEngine.buildListQuery(ctx, filters, pageable);
        return projectionEngine.execute(ctx, cypher);
    }

    @PostMapping
    public EntityResponse create(...) {
        var ctx = projectionEngine.resolve(...);
        var mutation = projectionEngine.parseCreate(ctx, body);
        return graphService.apply(mutation).toResponse(ctx);
    }
    // PUT, PATCH, DELETE, GET /{id}, GET /{id}/relations/{rel}
}
```

The Projection Engine is a **schema-aware query builder and response shaper**, not a code generator. On schema change, the engine invalidates its cached type descriptor for that `(model_id, typeSlug)` pair — there is nothing to recompile.

**Request validation** uses the same schema registry entries that SHACL shapes are derived from, ensuring REST layer and graph core agree on what "a valid Risk" is.

**OpenAPI generation** is done at runtime too: a `/v3/api-docs` handler walks the schema registry and emits a synthetic OpenAPI document per model. SpringDoc's dynamic `OpenApiCustomizer` hook is the integration point.

**MCP projection (Phase 2)** follows the same pattern — one `tool_call(typeSlug, operation, payload)` dispatcher instead of pre-registered tools per type. Spring AI MCP Server supports dynamic tool registration.

### 3.2 Connector framework: push vs pull, backpressure, retry, DLQ

**Recommendation:** Unified pull-shaped `sync(SyncContext)` SPI with webhook adapters that reuse the same downstream pipeline. Confidence: HIGH.

**SPI shape** (already close in concept doc section 6.1):

```java
public interface Connector {
    String getSystemId();
    ConnectorCapabilities getCapabilities();
    Stream<GraphMutation> sync(SyncContext ctx);   // pull
    void onWebhook(WebhookPayload p, Sink sink);   // push -> same Sink
    MappingDefinition getMapping();
}
```

Both push and pull converge on a single `Sink` that ultimately calls `GraphService.apply`. This keeps the write path unified.

**Backpressure:** use bounded work queues per connector (Spring's `ThreadPoolTaskExecutor` with a `LinkedBlockingQueue` of bounded size). When the queue is full, the connector's sync loop blocks — never drops. For webhook push, 429 Too Many Requests is returned upstream when the sink is saturated.

**Retry:** Spring Retry with exponential backoff for transient errors (network, 5xx, Postgres serialization failures). Classify errors up front — validation / SHACL / rule-reject errors are **non-retriable** and go straight to DLQ.

**DLQ:** a `connector_dlq` table in the same Postgres, not a Kafka DLQ topic (Kafka is not present in Phase 1). Columns: `id, connector_id, payload JSONB, error JSONB, attempt_count, first_seen, last_seen, status`. Re-drive is a manual admin endpoint in MVP, automated in Phase 3.

**Idempotency:** `_source` + `_source_id` + content hash is the dedup key. `sync` results are deduped in-memory per run; cross-run idempotency comes from the upsert semantics of `GraphService.apply` (it compares `_source_hash`).

**Sync status table:** per-(connector, model_id) last-success timestamp, last-cursor (ETag / last_modified / LSN), error counters. Exposed via a read-only projection.

### 3.3 Event log + outbox vs pure CDC: which fits Postgres+AGE best?

**Recommendation:** **Event log + transactional outbox** in Phase 1; Debezium reads outbox in Phase 2. Confidence: HIGH (validated against current industry practice).

Three patterns exist; the decision turns on (a) what is "source of truth" and (b) what external systems consume events:

| Pattern | Source of truth | External delivery | Fit for Tessera |
|---|---|---|---|
| Pure CDC (Debezium on base tables) | The graph / AGE tables | Debezium reads WAL | **REJECT for MVP** — AGE stores nodes/edges in opaque agtype columns inside internal tables; CDC on these is unreadable. Also couples consumer schema to AGE internals. |
| Pure event sourcing (Kafka is truth) | Kafka topic | Native | REJECT — already excluded by ADR-4. |
| **Event log + transactional outbox** | `graph_events` table | Outbox row written in same TX; delivered by either a poller (MVP) or Debezium (Phase 2) | **ADOPT** |

**Why event log AND outbox (not just one):**

- `graph_events` is **semantic history** (rich, queryable, supports temporal queries, stores delta + payload, is the replay source for "state at T"). It is the thing humans and agents query.
- `graph_outbox` is **delivery state** (small, append-only, consumed and ACK'd, can be truncated after delivery). It exists to decouple delivery semantics from history semantics.

Two tables, both written in the same TX as the Cypher mutation. The outbox row references the event by `(model_id, sequence_nr)`.

**Phase 1 delivery:** a Spring `@Scheduled` outbox poller reads unprocessed outbox rows, publishes via `ApplicationEventPublisher`, marks row as delivered. Single JVM, no infra dependencies.

**Phase 2 delivery:** Debezium (postgres connector) tails WAL for the `graph_outbox` table and produces to Kafka topics using the Outbox Event Router SMT. The poller is removed. **This is why the outbox exists from day one** — it lets you swap transport without touching the write pipeline.

Sources (HIGH confidence, cross-verified):
- Debezium Outbox Event Router (official): current stable pattern for Postgres -> Kafka outbox
- SeatGeek ChairNerd engineering post on transactional outbox at scale
- Decodable "Revisiting the Outbox Pattern" (2024/2025)

### 3.4 Multi-tenancy at the graph level: single graph + `model_id` filter

**Recommendation:** Single AGE graph + mandatory `model_id` predicate, enforced by a repository-layer guard. Confidence: MEDIUM-HIGH.

| Option | Verdict |
|---|---|
| **Separate AGE graph per tenant** (e.g. one `ag_graph` per model_id) | REJECT — AGE graphs are heavyweight, creating/dropping them is DDL, schema migrations multiply, operational nightmare at >10 tenants |
| **Separate Postgres schema per tenant, one AGE graph each** | REJECT — same problems, plus every schema migration runs N times |
| **Separate Postgres database per tenant** | REJECT — defeats the "one ops system" rationale of ADR-1; BI views can't span tenants (not a goal, but rules out future federated reads) |
| **Single AGE graph, `model_id` property on every node/edge** (concept doc) | **ADOPT** |

**Tradeoff being accepted:** tenant data is physically commingled. A missing `model_id` filter is a cross-tenant leak. This is the known failure mode of shared-tenancy graphs and must be engineered against, not hoped against.

**Mitigation — a single choke point for the filter:**

1. No module outside `fabric-core` constructs Cypher directly. All reads go through `GraphRepository.query(TenantContext ctx, CypherTemplate t, Map params)`. The repository injects `WHERE n.model_id = $modelId` on every `MATCH` via a Cypher AST rewrite (or, for MVP, a parameter binding that every template must accept and a lint test that fails if a template omits it).
2. `TenantContext` is a required method parameter, not a `ThreadLocal`. Missing it is a compile error.
3. Writes go through `GraphService.apply`, which sets `model_id` on every created node/edge from `TenantContext`. Mutations are rejected if their payload disagrees with the context.
4. Integration test suite: a "red team" test per read path that executes as tenant A and asserts zero rows from tenant B data.
5. `graph_events` and `graph_outbox` tables have `model_id NOT NULL` plus a check constraint; every index is `(model_id, ...)`.

**Future upgrade path:** if a large tenant needs physical isolation (compliance, perf), the single-graph model can graduate that tenant to its own schema without touching the SPIs — `TenantContext` becomes the routing key to a per-tenant `DataSource`. This is why `TenantContext` is a parameter, not a global.

Sources: Memgraph multi-tenancy blog, AWS Neptune multi-tenant architectures blog, FalkorDB multi-tenant cloud security post. Confidence MEDIUM because these are vendor posts; the pattern itself is well-established in RDBMS multi-tenancy (HIGH confidence there).

### 3.5 SHACL validator: synchronous write-path interceptor

**Recommendation:** Synchronous, pre-commit, inside the write transaction. Confidence: MEDIUM-HIGH.

**Why synchronous:**

- The graph is the source of truth. Async validation means invalid data is temporarily authoritative, which undermines the entire "graph is truth" premise.
- Reconciliation downstream (rules, projections, consumers) assumes graph state is consistent. Async SHACL means rules run against unvalidated state.
- Apache Jena's `ShaclValidator` is fast enough at Tessera's scale: validating a single-node delta against a compiled shape is sub-millisecond. The benchmark reference shows Jena compiles shapes to an execution tree and is competitive with Stardog. Batch validation of a full graph is slow, but per-mutation validation is not.

**Where in the pipeline** (pre-commit, post-rule-engine):

```
RuleEngine.reconcile(mutation)  -- may transform
  -> SHACL.validate(proposedDelta)  -- uses Jena Shacl, targets only the node(s) touched
    -> Cypher write
      -> TX commit
```

**Optimization:** shapes are compiled once per `(model_id, node_type_slug)` and cached. Cache invalidated on schema-registry change for that type. Jena's `Shapes.parse` is the one-time cost; `ShaclValidator.get().validate(shapes, graph)` is the per-write cost.

**Targeted validation:** do NOT validate the whole graph. Use a `targetNode`-scoped validation against a minimal in-memory RDF model representing only the mutated node(s) and their immediate relations that the relevant shapes reference. This keeps validation O(1) in graph size.

**When async might make sense (NOT now):** very large bulk imports from a connector, where validation happens in a second pass and invalid rows are sent to DLQ. This is a Phase 2+ concern and should be an explicit `BulkImport` SPI, not the default write path.

### 3.6 Testing strategy for a dynamically-generated API surface

**Recommendation:** Test the generator, not the generated. Confidence: HIGH.

Because there are no per-type controllers, classical per-endpoint tests are the wrong shape. Instead:

**Layer 1 — Contract tests on the generic machinery (unit):**

- Given a synthetic schema (`Risk` with 3 properties, 1 enum, 1 reference), assert the Projection Engine produces the correct Cypher for list/get/create/update/delete.
- Given the same synthetic schema, assert SHACL shape compilation produces the expected constraints.
- Given the schema, assert the OpenAPI document contains the expected paths and schemas.

**Layer 2 — Parameterized schema-driven integration tests:**

- Define a test fixture schema in YAML. Run the full HTTP test suite (spring-boot-test + MockMvc or RestAssured) against it using parameterized tests. Each test is "for every node type in the fixture, POST valid data returns 201, POST invalid data returns 400, GET by id returns 200, ..." — the test enumerates the fixture, not hard-coded types.

**Layer 3 — Testcontainers-backed end-to-end:**

- One Postgres+AGE container per test class (or per module). Load a real fixture schema. Drive a canned REST-polling connector against a WireMock source system. Assert events in `graph_events`, outbox rows, and that the REST projection returns reconciled state.

**Layer 4 — Property-based / fuzz tests for the query builder:**

- Use jqwik to generate random filter combinations and assert the Cypher builder never produces a query that omits `model_id` or that injects untrusted strings. This is the tenant-leak red team test automated.

**Layer 5 — Schema evolution tests:**

- A test that adds a property, reloads the Projection Engine, and asserts old data is still readable and new data is validated against the new shape.

**What NOT to test:** a hand-written controller per type. It doesn't exist.

---

## 4. Patterns to Follow

### Pattern 1: Single Write Funnel
**What:** All writes pass through `GraphService.apply(GraphMutation)`.
**Why:** One place to enforce auth, rules, SHACL, event log, outbox, tenant scoping. No alternate write paths.
**Anti-case:** A connector that writes Cypher directly to skip the rule engine "for performance." Forbidden.

### Pattern 2: Transactional Outbox from Day One
**What:** Every mutation writes to `graph_events` AND `graph_outbox` in the same TX.
**Why:** Decouples delivery from history; swap in-process publisher -> Debezium without touching write code.

### Pattern 3: Schema Registry as Single Source of Truth for Shape
**What:** REST validation, SHACL shapes, Cypher builders, OpenAPI docs, MCP tool descriptors, SQL views — all derive from one registry.
**Why:** Drift between these is the #1 bug class in schema-driven systems.

### Pattern 4: Runtime Routing, Not Code Generation
**What:** One generic request handler per protocol, schema-driven dispatch.
**Why:** Code-gen systems become build-system problems. Runtime routing is invalidate-and-rebuild-cache.

### Pattern 5: TenantContext as Explicit Parameter
**What:** `TenantContext` is passed as a method argument down the read/write stacks, never read from a ThreadLocal.
**Why:** Compile-time enforcement that every query knows its tenant. Removes an entire class of leak bugs.

### Pattern 6: Connector-Agnostic Mutation Protocol
**What:** Connectors emit `GraphMutation` records; the runtime applies them. Connectors do not touch AGE.
**Why:** Swap/mock connectors freely; a connector bug cannot corrupt the graph.

---

## 5. Anti-Patterns to Avoid

### Anti-Pattern 1: Reflection over JPA entities to build endpoints
**Why bad:** Requires compile-time entity classes, defeats runtime schema evolution.
**Instead:** Runtime routing backed by Schema Registry.

### Anti-Pattern 2: Per-type generated controller classes
**Why bad:** Build complexity, class explosion, hot-reload pain, breaks IDE.
**Instead:** One generic controller + Projection Engine.

### Anti-Pattern 3: Async SHACL validation on the default write path
**Why bad:** Temporarily invalid data becomes authoritative; downstream rules see inconsistent state.
**Instead:** Synchronous pre-commit validation. Use explicit `BulkImport` SPI for async cases.

### Anti-Pattern 4: Debezium on AGE internal tables
**Why bad:** agtype is opaque, couples consumers to AGE internals, unreadable events.
**Instead:** Debezium on `graph_outbox` with the Outbox Event Router SMT.

### Anti-Pattern 5: ThreadLocal tenant context
**Why bad:** Async boundaries (Spring @Async, reactive, CompletableFuture) silently drop it. Leaks ensue.
**Instead:** Explicit parameter.

### Anti-Pattern 6: Hand-rolled Cypher string concatenation outside fabric-core
**Why bad:** Tenant filter enforcement and injection safety are unenforceable.
**Instead:** `CypherTemplate` + parameter binding through `GraphRepository`.

### Anti-Pattern 7: Kafka as source of truth "because we'll add it later"
**Why bad:** Contradicts ADR-4, doubles infra, doubles write paths.
**Instead:** Postgres is truth; Kafka is an outbox consumer.

### Anti-Pattern 8: One big `fabric-core` module
**Why bad:** Rule engine ends up importing projection code "just this once," cycle follows.
**Instead:** Hard dependency direction enforced by Maven (`enforcer-plugin` with dependency convergence + banned cycles).

---

## 6. Scalability Considerations

| Concern | At 100 nodes, 1 tenant | At 100K nodes, 10 tenants | At 10M nodes, 100 tenants |
|---|---|---|---|
| Write path | Single JVM, in-process events | Single JVM, outbox poller | Debezium + Kafka; outbox retention policy |
| SHACL validation | Sub-ms, shapes cached in-memory | Same; per-mutation targeted validation | Same; consider shape-per-type cache eviction |
| Cypher reads | Trivial | Composite indexes on `(model_id, _type, uuid)` | Partial indexes per hot tenant; materialized views for heavy projections |
| Event log | Single table | Partition `graph_events` by `model_id` (LIST) or by `event_time` (RANGE) | Partitioned + cold-storage archive of partitions older than N months |
| Outbox | Polled every 1s | Polled every 100ms or switch to Debezium | Debezium mandatory |
| Connector concurrency | One thread per connector | Per-connector bounded pool | Horizontal: shard connectors across nodes by `(connector_id % N)` |
| Tenant isolation | WHERE filter | WHERE filter + partition pruning | Graduate hottest tenants to dedicated schema/DataSource via `TenantContext` routing |
| Read replicas | None | Postgres streaming replica for BI | Neo4j read-replica for graph-heavy traversals (ADR-1 upgrade path) |

---

## 7. Data Flow Direction

**Write flow (south -> core -> outbox):**
```
Source system -> Connector.sync() -> GraphMutation stream -> Sink
  -> GraphService.apply()
    -> [TX] Auth -> RuleEngine -> SHACL -> Cypher(AGE) -> graph_events -> graph_outbox
  -> post-commit ApplicationEvent (MVP) | Debezium->Kafka (Phase 2)
```

**Read flow (consumer -> projection -> core):**
```
Consumer HTTP GET -> GenericEntityController -> ProjectionEngine.resolve(type)
  -> ProjectionEngine.buildListQuery() -> GraphRepository.query(TenantContext, template)
    -> Cypher(AGE) -> agtype rows -> DTO -> JSON
```

**Schema-change flow (admin -> registry -> invalidation):**
```
Admin POST /schema/types -> SchemaRegistryService (in fabric-core)
  -> [TX] insert schema_node_types/schema_properties -> compile SHACL shape -> cache -> commit
  -> publish SchemaChanged ApplicationEvent
    -> ProjectionEngine invalidates type descriptor cache
    -> OpenAPI doc invalidates
    -> (Phase 2) MCP tool list refreshed, Kafka topic ensured, SQL view recreated
```

---

## 8. Build Order for Phase 1 (with dependency rationale)

Strict bottom-up. Each step depends on prior step being **testable in isolation**, not "mostly working."

### Step 0 — Project scaffolding (day 0)
- Parent POM, 5 modules, Java 21, Spring Boot 3.x, Testcontainers, Maven enforcer with banned cycles
- Postgres+AGE Docker Compose for dev
- `TenantContext` class in `fabric-core` (tiny, but everything downstream takes it)

### Step 1 — Graph core read/write (week 1)
**Dependencies:** none
- Postgres + AGE setup, migration tooling (Flyway)
- `Node`, `Edge`, `GraphMutation`, `GraphEvent` domain records
- `GraphRepository` with Cypher template + mandatory `TenantContext` parameter
- `GraphService.apply()` stub: auth -> cypher -> graph_events -> outbox (no rules, no SHACL yet)
- `graph_events` and `graph_outbox` tables, `model_id NOT NULL`, indexes
- Integration test: create/read/update/delete a node via GraphService, assert event and outbox rows
**Must exist before:** anything else, because everything writes through GraphService

### Step 2 — Schema Registry (week 1-2)
**Dependencies:** Step 1 (the registry tables live in the same DB and the `schemachanged` event goes on the same event bus)
- `schema_node_types`, `schema_properties`, `schema_edge_types` tables
- `SchemaRegistryService` CRUD
- Type descriptor cache with invalidation on change
- Admin REST endpoints for schema CRUD (hand-written, not generated — this is the bootstrap)
**Must exist before:** Rule Engine (rules reference type slugs), Projection Engine (endpoints resolve types), SHACL (shapes derive from properties)

### Step 3 — SHACL validator (week 2)
**Dependencies:** Step 2 (shapes are compiled from schema registry)
- Apache Jena dependency
- `ShapeCompiler`: schema row -> `Shapes` object, cached per `(model_id, typeSlug)`
- `ShaclValidationInterceptor` inserted into `GraphService.apply` before Cypher write
- Cache invalidation hook on schema change
- Tests: valid/invalid payloads for each data type, cardinality, enum, range constraints
**Must exist before:** Rule Engine (rules assume validated shape), any connector (connectors push unvalidated data otherwise)

### Step 4 — Rule Engine (week 2-3)
**Dependencies:** Step 1 (operates on mutations), Step 2 (rules target types), Step 3 (validation runs after rules)
- `ReconciliationRule` SPI, `RuleChainExecutor`
- `reconciliation_rules` table, JSON-based condition/action
- Authority matrix loader per tenant
- Integrated into `GraphService.apply` before SHACL
- Conflict register (`reconciliation_conflicts` table)
- Tests: chain evaluation order, fallback, flag-for-review path
**Must exist before:** first real connector (otherwise connectors have no reconciliation policy)

### Step 5 — Projection Engine + REST adapter (week 3-4)
**Dependencies:** Step 2 (needs schema registry), Step 1 (calls GraphService for writes and GraphRepository for reads)
- `ProjectionEngine.resolve(modelSlug, typeSlug)` -> `TypeDescriptor`
- Cypher query builder: list (with filter/paging/sort), get, create, update, delete
- `GenericEntityController` with `/api/v1/{model}/entities/{typeSlug}` routes
- Filter parsing (simple MVP: `?name=foo&probability__gt=0.5`)
- SpringDoc dynamic OpenAPI customizer
- Tests: schema-driven parameterized test suite (Layer 2 from section 3.6)
**Must exist before:** end-to-end demo; first connector can be developed in parallel but not demo'd until this is up

### Step 6 — Connector framework + first connector (week 4-5)
**Dependencies:** Steps 1-5 (connectors need the full write pipeline to exist)
- `Connector` SPI, `MappingDefinition` JSON schema, `SyncContext`
- `ConnectorRegistry` + scheduler (Spring `@Scheduled` with per-connector cron)
- Bounded work queue, Spring Retry, `connector_dlq` table
- **First concrete connector:** generic REST poller with ETag / last_modified delta
  - Input: WireMock-backed test source system
  - Mapping: source JSON field paths -> target schema properties
- Sync status table + admin endpoint
- Tests: Testcontainers end-to-end — WireMock source, connector sync, assert graph state, assert events, assert outbox
**Must exist before:** Phase 1 demo

### Step 7 — In-process event transport + outbox poller (week 5)
**Dependencies:** Steps 1, 6
- `@Scheduled` outbox poller -> `ApplicationEventPublisher`
- Event listener examples (logging, simple metrics)
- Mark outbox row delivered in same TX as listener ack (or at-least-once with dedup in listener)
**Must exist before:** Phase 1 demo (but is mostly done once Step 1's outbox table exists)

### Step 8 — Phase 1 hardening (week 5-6)
- Red-team tenant-leak tests (Layer 4)
- Maven enforcer rules for module cycles
- Actuator health checks for Postgres, connector scheduler, outbox backlog
- Basic docs (ARCHITECTURE.md in repo, CONTRIBUTING.md)

### Dependency graph (must-before)
```
Scaffolding
    |
Graph core + GraphService (writes go through ONE place)
    |
    +-- Schema Registry
    |       |
    |       +-- SHACL Validator (plugged into GraphService)
    |       |       |
    |       |       +-- Rule Engine (plugged into GraphService, runs before SHACL)
    |       |       |       |
    |       |       |       +-- Projection Engine + REST
    |       |       |       |       |
    |       |       |       |       +-- Connector framework + first connector
    |       |       |       |       |       |
    |       |       |       |       |       +-- Outbox poller + in-process transport
    |       |       |       |       |       |       |
    |       |       |       |       |       |       +-- Phase 1 hardening / demo
```

**Things that CAN run in parallel once their prerequisite is done:**
- Projection Engine REST work (Step 5) and Connector framework scaffolding (Step 6) can be developed in parallel after Step 4, as long as they merge before demo.
- Hardening work (Step 8) begins incrementally from Step 1.

**Things that absolutely CANNOT be reordered:**
- Projection Engine before Schema Registry — nothing to project.
- Rule Engine before SHACL — rules may transform shape before validation.
- Connectors before GraphService.apply is stable — otherwise connector bugs corrupt graph.
- Outbox table before in-process events — otherwise transport swap in Phase 2 is a rewrite.

---

## 9. Open Architecture Risks

| Risk | Phase | Mitigation |
|---|---|---|
| Cypher-level tenant filter enforcement has gaps | 1 | Lint test + property-based fuzz test + code review checklist |
| AGE Cypher feature coverage is narrower than Neo4j (some traversal syntax differs) | 1 | Pin AGE version; maintain a compatibility matrix; prefer SQL fallbacks for complex traversals in MVP |
| Outbox poller lag under load | 1-2 | Monitor backlog; move to Debezium in Phase 2 |
| SHACL shape compilation cost on large schemas | 2 | Shape cache, invalidation per type, not per change |
| OpenAPI doc regeneration thrash on rapid schema changes | 2 | Debounce invalidation; serve cached doc with `ETag` |
| Dynamic MCP tool registration in Spring AI MCP Server may not support full runtime add/remove yet | 2 | Verify with Context7 before Phase 2 starts; fallback is restart-on-schema-change |
| Neo4j read-replica upgrade path requires a dual-write or CDC-replay mechanism not yet designed | 4 | Defer; revisit when traversal perf becomes measurable bottleneck |

---

## 10. Sources

HIGH confidence (authoritative):
- Debezium Outbox Event Router documentation (https://debezium.io/documentation/reference/stable/transformations/outbox-event-router.html)
- Apache Jena SHACL documentation (https://jena.apache.org/documentation/shacl/)
- Apache AGE documentation (https://age.apache.org/)

MEDIUM confidence (engineering blogs, vendor docs):
- SeatGeek ChairNerd: Transactional Outbox Pattern at scale (https://chairnerd.seatgeek.com/transactional-outbox-pattern/)
- Decodable: Revisiting the Outbox Pattern (https://www.decodable.co/blog/revisiting-the-outbox-pattern)
- event-driven.io: Push-based Outbox with Postgres Logical Replication (https://event-driven.io/en/push_based_outbox_pattern_with_postgres_logical_replication/)
- Memgraph: Multi-Tenancy in Graph Databases (https://memgraph.com/blog/why-multi-tenancy-matters-in-graph-databases)
- AWS: Multi-tenant architectures on Amazon Neptune (https://aws.amazon.com/blogs/database/build-multi-tenant-architectures-on-amazon-neptune/)
- FalkorDB: Graph Database Multi-Tenant Cloud Security (https://www.falkordb.com/blog/graph-database-multi-tenant-cloud-security/)
- Restzilla dynamic REST endpoints (https://github.com/42BV/restzilla) — referenced as an anti-pattern comparison, not as the recommendation
- Spring dynamic controller POC (https://github.com/tsarenkotxt/poc-spring-boot-dynamic-controller)

LOW confidence (single-source, treat as directional):
- SHACL benchmark mail archive entry on Jena perf characteristics (https://www.mail-archive.com/dev@jena.apache.org/msg28251.html)

Cross-references to Tessera concept doc:
- `/Users/matthiaswegner/Programmming/GitHub/Tessera/.planning/PROJECT.md` (constraints, ADRs, Phase 1 scope)
- `/Users/matthiaswegner/Documents/Leo's Gedankenspeicher/CONCEPT-Meta-Model-Driven-Data-Fabric.md` (sections 2-13: layering, schema registry, rule engine, connectors, projection engine, MCP, phase plan)
