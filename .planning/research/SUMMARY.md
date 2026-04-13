# Project Research Summary

**Project:** Tessera
**Domain:** Graph-based, protocol-agnostic data fabric / meta-model-driven integration layer
**Researched:** 2026-04-13
**Confidence:** MEDIUM-HIGH

## Executive Summary

Tessera is a graph-first, LLM-native integration layer where REST, GraphQL, MCP, SQL views, and Kafka topics are projections of a central meta-model over a Postgres + Apache AGE knowledge graph. Research across stack, features, architecture, and pitfalls converges on a clear picture: the locked decisions (Java 21 / Spring Boot 3.5 / PG16 + AGE 1.6 / Jena SHACL / Spring AI MCP / custom rule engine / Maven multi-module) are sound and need no re-litigation, but the way they are assembled in Phase 1 will determine whether Tessera becomes a robust fabric or a fragile prototype. Two spines must land first and land well: the **Schema Registry** (which feeds validation, projections, MCP, lineage, ontology evolution) and the **Event Log + transactional outbox** (which feeds temporal queries, replay, audit, and the future Debezium/Kafka swap). Everything else hangs off these two.

The recommended approach is strict bottom-up construction inside a single ACID transaction per write: `GraphService.apply()` is the only write funnel, runs auth → rules → SHACL → Cypher → event log → outbox in one Postgres transaction, with a mandatory `TenantContext` parameter that no code path can bypass. Projections are runtime-routed (one generic handler per protocol, schema-driven dispatch) — never code-generated, never reflection over JPA entities. The MCP projection is the flagship differentiator and must arrive in Phase 2 (v1.x), not slip further. A handful of cheap-but-high-leverage choices (per-property source authority matrix, transactional outbox from day one, snapshot/partition design for the event log, fail-closed endpoint defaults) cost little in MVP and prevent multi-week rewrites later.

The dominant risk is **Apache AGE itself**: aggregation queries are documented at ~15x slower than equivalent SQL, `pg_upgrade` is blocked entirely (every Postgres major upgrade requires dump/restore), AGE releases lag Postgres minors and have already had near-miss ABI breaks, and all releases ship tagged `-rc0`. Mitigations are non-optional Phase 1 work: pin exact image digests, build a benchmark harness on day 1 (not "later"), keep the SQL projection architecturally independent of AGE so it can read underlying label tables directly, partition `graph_events` from day 1, and rehearse dump/restore on a realistic dataset. The second-largest risk is **multi-tenant `model_id` leakage**, which must be engineered against (central GraphSession, ArchUnit ban on raw Cypher, property-based fuzz tests, scoped SHACL validation) rather than hoped against. Field-level encryption is all-or-nothing — either ship it fully (per-tenant blind index keys, multi-version DEKs, KMS chaos test, fail-closed writes) or defer the entire feature; the half-built version is security theater.

## Key Findings

### Recommended Stack

The locked stack is confirmed end-to-end. Pin exact patch versions and image digests because both AGE and Spring AI are moving fast.

**Core technologies:**
- **Java 21 (Corretto) + Spring Boot 3.5.13** — LTS, virtual threads remove the WebFlux temptation; 3.4.x is EOL, 4.0 still stabilizing
- **PostgreSQL 16.6+ + Apache AGE 1.6.0 (PG16 branch, `PG16/v1.6.0-rc0`)** — only AGE line for PG16; pin to digest; do not drift to PG17 without simultaneously moving to AGE 1.7
- **Apache Jena 5.2.0 (jena-shacl)** — Java 21 compatible, sweet spot for SHACL Core + SPARQL constraints
- **Spring AI MCP Server 1.0.5 (`spring-ai-starter-mcp-server-webmvc`)** — pin strictly, isolate behind interface, keep Anthropic raw `io.modelcontextprotocol:sdk-java` as fallback
- **SpringDoc OpenAPI 2.8.x** — dynamic OpenAPI via `OpenApiCustomizer`; mind lifecycle ordering with dynamic routes
- **Spring Cloud Vault 4.2.x** — Config Data API (`spring.config.import=vault://`), never deprecated bootstrap
- **Flyway 10.x** — plain SQL fits AGE's `LOAD 'age'` setup; Liquibase fights you
- **Testcontainers 1.20.4 with `apache/age:PG16_latest`** — embedded Postgres cannot load AGE
- **Custom chain-of-responsibility rule engine** — Drools deferred (ADR-3); ~100 lines vs 20+ MB of KIE
- **HikariCP `connectionInitSql: "LOAD 'age'; SET search_path = ag_catalog, '$user', public;"`** — session-local AGE init
- **ShedLock 5.16+** — cheap insurance for `@Scheduled` connector polling
- **ArchUnit 1.3 + Maven enforcer (banned cycles)** — module dependency direction enforced

Full version lock file in `STACK.md`.

### Expected Features

Tessera inherits user expectations from data fabrics (Atlan, Foundry, Dataverse, Stardog) but its identity is graph-first + LLM-native — projections generated from a meta-model. Triaged into table stakes (T1-T24), differentiators (D1-D15), anti-features (A1-A17).

**Must have (table stakes — all P1 in v1):**
- Typed Schema Registry with versioning, cardinalities, evolution
- SHACL validation on write + DQ rules with visible violations
- Multi-tenant `model_id` isolation on every node, edge, event
- Conflict register + reconciliation rule chain + per-property source authority matrix
- Connector framework (pluggable, declarative mappings, delta detection)
- KMS/Vault for connector credentials
- Sync dashboard, audit log, health, metrics
- Generated REST projection + OpenAPI
- Event log (Postgres-authoritative) + basic point-in-time query
- Row + field-level access control; field-level encryption (only if shipped fully)
- First connector: generic REST polling

**Should have (differentiators — P2, must arrive in v1.x):**
- **MCP projection** with `query_entities`, `traverse`, `get_state_at`, `find_path`, `describe_schema` — flagship, do not slip
- Graph as durable typed multi-agent shared memory (vs vector store)
- Per-property source authority matrix (already in P1)
- Hash-chained audit log (opt-in, GxP/SOX/BSI C5)
- Kafka projection with topic-per-entity-type
- SQL view projection — bypasses Cypher to avoid CRIT-3 aggregation cliff
- Rule + event lineage surfaced in API
- Self-hosted-first

**Defer (v2+):**
GraphQL projection (Phase 4), LLM-assisted ontology evolution, visual schema designer, OWL reasoning, Drools migration, write-back connectors, Neo4j read-replica.

**Anti-features:** warehouse-scale OLAP, BI dashboards, ETL orchestration, vector/RAG, big-bang migration, "Palantir alternative" positioning, auto-applied LLM mappings, column-level SQL lineage, plug-in marketplace, in-process LLM inference.

### Architecture Approach

Six logical layers (Consumers → Protocol Adapters → Projection Engine → Rule Engine → Knowledge Graph Core → Connector Layer) implemented as five Maven modules (`fabric-core`, `fabric-rules`, `fabric-projections`, `fabric-connectors`, `fabric-app`). Module dependencies strictly upward from `fabric-core`, enforced by Maven enforcer + ArchUnit.

**Major components:**
1. **GraphService (fabric-core)** — single write funnel; auth → schema lookup → reconciliation → SHACL → Cypher → `graph_events` → `graph_outbox` in one Postgres transaction
2. **Schema Registry (fabric-core)** — type/property/edge tables; descriptor cache invalidated on change; source of truth for SHACL shapes, REST/MCP routes, OpenAPI, SQL views
3. **Event Log + Transactional Outbox (fabric-core)** — `graph_events` is semantic history (replayable, queryable); `graph_outbox` is delivery state in same TX; Phase 1 poller → Phase 4 Debezium swap with no write-path changes
4. **SHACL Validator (fabric-core)** — synchronous, pre-commit, in-TX; per-`(model_id, typeSlug)` Caffeine cache; targeted validation against minimal in-memory RDF model
5. **Rule Engine (fabric-rules)** — chain-of-responsibility executor; per-tenant authority matrix; produces conflict register; runs before SHACL inside `GraphService.apply`
6. **Projection Engine (fabric-projections)** — runtime routing (NOT code-gen, NOT reflection over JPA); one `GenericEntityController` resolves `/api/v1/{model}/entities/{typeSlug}` per request; same pattern for MCP
7. **Connector Framework (fabric-connectors)** — unified pull-shaped `Connector.sync(SyncContext)`; webhook adapters reuse same `Sink`; bounded queues; Spring Retry; `connector_dlq` table; sync status per `(connector_id, model_id)`

**Critical pattern:** `TenantContext` is an explicit method parameter — never `ThreadLocal`. Compile-time enforcement, biggest defense against cross-tenant leakage.

### Critical Pitfalls

1. **AGE Cypher aggregation cliff (~15x slower than SQL, upstream #2194)** — Build benchmark harness day 1. SQL projection bypasses Cypher entirely. Allow native-SQL overrides in projection engine. Document required indexes per label (AGE auto-indexes nothing).
2. **AGE blocks `pg_upgrade` + lags Postgres releases (CRIT-1, CRIT-2)** — Pin Postgres minor + AGE digest. Document: PG major upgrades require dump/restore. CI job that exercises `pg_dump`/`pg_restore` on seeded AGE DB. Add ADR-7 candidate.
3. **Multi-tenant `model_id` filter bypass (CRIT-5, CRIT-6)** — Central `GraphSession` is only Cypher surface; ArchUnit forbids raw Cypher outside `graph.internal`; `TenantContext` is required parameter; SHACL `ValidationReport` filtered before consumer/log surfaces; property-based fuzz tests; no admin raw-Cypher endpoint ever.
4. **Reconciliation conflict storm / write amplification (CRIT-4)** — Every event carries `origin_connector_id` + `origin_change_id`; authority matrix is total (deterministic tiebreak); per-entity write-rate circuit breaker; first connector stays read-only.
5. **Field-level encryption is all-or-nothing (CRIT-7, CRIT-8)** — If shipped: per-tenant blind index keys, equality only, multi-version DEKs walking versions on decrypt, fail-closed writes on KMS outage, KMS chaos test in CI. Otherwise defer the entire feature.

Plus moderate pitfalls that must be in Phase 1 design (retrofitting is painful): event log monthly partitioning, Postgres `SEQUENCE` not `MAX()+1`, tombstone-default deletes, Tessera-owned timestamps (never source clocks), schema versioning + property aliases day 1, fail-closed defaults on dynamic endpoints.

## Implications for Roadmap

Research strongly supports PROJECT.md's Phase 1/2 framing but tightens what must land *inside* Phase 1. Phase 1 is broader than "graph + REST + 1 connector" — it also includes transactional outbox, event log partitioning, tenant choke points, benchmark harness, dump/restore rehearsal, and (if encryption ships) full KMS/blind-index machinery.

### Phase 0: Foundations & Risk-Burndown
**Rationale:** Everything depends on knowing AGE behaves and tenant isolation is mechanically enforced. Calling these out as Phase 0 forces them to be done first.
**Delivers:** Maven multi-module scaffold; Maven enforcer + ArchUnit banned-cycle rules; Postgres 16 + AGE 1.6 Docker Compose (image pinned to digest); Flyway baseline with `CREATE EXTENSION age`; `TenantContext` class; **benchmark harness** (100k / 1M nodes; point-lookup, 2-hop, aggregate, ordered pagination); **`pg_dump`/`pg_restore` rehearsal CI job**.
**Avoids:** CRIT-1, CRIT-2, CRIT-3, MIN-1, MIN-2, SOLO-1.

### Phase 1: Graph Core, Schema Registry, Validation, Rules
**Rationale:** Two spines (Schema Registry + Event Log) plus rule engine and SHACL must exist before any projection or connector. All four research streams agree on strict bottom-up ordering.
**Delivers:** `GraphService.apply()` single write funnel; `GraphRepository`/`GraphSession` as only Cypher execution surface (mandatory `TenantContext`); `graph_events` (monthly-partitioned, `model_id NOT NULL`, indexed `(model_id, ...)`, Postgres `SEQUENCE`); `graph_outbox` written same TX; Schema Registry CRUD with cache + versioning + property aliases; Jena SHACL synchronous pre-commit interceptor with shape cache + tenant-scoped reports; custom rule engine; `reconciliation_rules` table; per-tenant authority matrix; conflict register; per-entity write-rate circuit breaker; tombstone-default deletes.
**Addresses:** T1, T2, T3, T4, T5, T6, T7, T13, T14, T15, T16, T17, T21, D5, D9, D14.
**Avoids:** CRIT-3 (benchmark in place), CRIT-4 (origin tracking + circuit breaker), CRIT-5 (central GraphSession + ArchUnit), CRIT-6 (scoped SHACL reports), MOD-1/2/3 (versioning + aliases), MOD-5 (SEQUENCE), MOD-6 (partitioning), MOD-7 (tombstone), MOD-8 (Tessera-owned timestamps).

### Phase 2: REST Projection, Connector Framework, First Connector, Encryption Decision
**Rationale:** Projections need the registry; connectors need the full write pipeline; both can develop in parallel after Phase 1 but must merge before any external consumer. Encryption must be decided now: ship fully or defer behind a flag.
**Delivers:** Runtime-routed REST projection (`GenericEntityController` resolves against Projection Engine); SpringDoc dynamic OpenAPI customizer; **fail-closed endpoint defaults** (newly generated routes are deny-all until schema declares `exposure` policy); `Connector` SPI + `MappingDefinition` + `SyncContext`; `ConnectorRegistry` + scheduler with ShedLock; bounded queues; Spring Retry; `connector_dlq`; sync status table; **first concrete connector** (generic REST poller with ETag/last_modified delta); outbox poller wired to `ApplicationEventPublisher`; KMS/Vault via Spring Cloud Vault Config Data API. **Encryption (gated):** if shipped, full per-tenant blind index + multi-version DEKs + fail-closed writes + KMS chaos test; otherwise feature-flagged off.
**Addresses:** T8, T9, T10, T11, T12, T18 (gated), T19, T20, T22 (basic), T23, T24, D4, D8 (gated), D13.
**Avoids:** MOD-4 (fail-closed), MIN-3 (explicit `agtype`→DTO), MIN-4 (async executor), CRIT-7/8 (only if encryption ships).

### Phase 3: MCP Projection (Flagship Differentiator)
**Rationale:** Single feature that makes Tessera novel in 2026. Everything in 0-2 enables it. P2 only because it depends on schema registry + event log + access control being solid; with those in place must arrive in v1.x.
**Delivers:** Spring AI MCP Server module isolated behind interface (swap to raw Anthropic SDK feasible); MCP tools `query_entities`, `traverse`, `get_state_at`, `find_path`, `describe_schema`; dynamic tool registration driven by Schema Registry; **per-agent write quota** at MCP tool layer; **untrusted-content wrappers** (`<data>...</data>`) on tool responses; agents read-only by default; **no schema mutation tools exposed to agents**; MCP audit log; entity + event + rule lineage via `describe_schema` and traversal results.
**Addresses:** D1, D2, D6, D12, D15.
**Avoids:** MCP-1 (prompt injection), MCP-2 (runaway mutations), MCP-3 (LLM schema drift).

### Phase 4: SQL View + Kafka Projections, Hash-Chained Audit
**Rationale:** SQL projection bypasses Cypher entirely for aggregates (documented escape from CRIT-3). Kafka swap is write-path no-op because outbox already exists — Debezium with Outbox Event Router SMT replaces in-process poller.
**Delivers:** SQL view projection reading underlying AGE label tables directly; Debezium 3.4 + Kafka 3.9 connecting to `graph_outbox` via Outbox Event Router SMT; tenant-aware partitioning; outbox poller retired; hash-chained audit table (opt-in); replication slot lifecycle monitoring with `max_slot_wal_keep_size`.
**Addresses:** D7, D10, D11.
**Avoids:** CRIT-3 (SQL bypass for aggregates), replication slot WAL bloat.

### Phase 5: Circlead Integration & Production Hardening
**Rationale:** First real consumer validates the stack against live data. Production hardening lands alongside.
**Delivers:** circlead consumes Tessera REST + MCP projections; per-tenant snapshot mechanism for event log; retention policies per tenant; full DR drill rehearsed; observability (Prometheus/OpenTelemetry for ingest rate, rule eval count, conflict count, outbox lag, replication slot lag).
**Avoids:** SOLO-5 (circlead stays standalone), MOD-6 (snapshots), CRIT-1 (DR drill).

### Phase 6+: Deferred (v2+)
GraphQL projection; LLM-assisted ontology evolution; visual schema designer / graph explorer UI; OWL reasoning; Drools migration; write-back connectors (per-connector, opt-in only, after conflict register battle-tested); Neo4j read-replica.

### Phase Ordering Rationale

- **Bottom-up is non-negotiable.** Schema Registry before Projection Engine. Rule Engine before SHACL (rules may transform shape before validation). Outbox table before in-process events (otherwise Phase 4 swap is a rewrite).
- **Two parallel tracks after Phase 1:** REST projection and connector framework can develop in parallel inside Phase 2.
- **MCP cannot move earlier.** Depends on schema registry + event log + access control + stable read path.
- **SQL projection deliberately bypasses Cypher.** Research is unambiguous on the 15x aggregation cliff.
- **Encryption is binary.** Half-built is worse than absent.
- **Bidirectional connectors stay deferred** until conflict register battle-tested.

### Research Flags

**Phases needing deeper research during planning:**
- **Phase 1** — SHACL validation perf envelope on real schemas; feasibility of Postgres RLS on AGE label tables as belt-and-braces tenancy mitigation
- **Phase 1** — Event log partitioning + sequence design at 10x expected load
- **Phase 2** — Connector SPI shape (framework-owned vs connector-owned reconciliation); SpringDoc dynamic OpenAPI lifecycle ordering with runtime-registered routes
- **Phase 2** — Encryption (only if in scope): blind-index threat model, DEK rotation runbook, KMS chaos test scope
- **Phase 3** — Spring AI MCP Server dynamic tool registration semantics; MCP tool surface (sync vs paginated vs streaming); Spring AI version stability **(high-priority research flag)**
- **Phase 4** — Debezium Outbox Event Router with multi-tenant partitioning; replication slot lifecycle on PG16

**Phases with standard patterns (skip research-phase):**
- **Phase 0** — Maven scaffolding, Flyway baseline, Docker Compose
- **Phase 5** — Production hardening (Prometheus/OpenTelemetry, Actuator, DR drills)

## Confidence Assessment

| Area | Confidence | Notes |
|------|------------|-------|
| Stack | MEDIUM-HIGH | HIGH for Spring Boot/Jena/Debezium/Testcontainers/Vault. MEDIUM for AGE (`-rc0` tagging, small-team cadence). MEDIUM for Spring AI MCP (young, churning across 1.0.x → 1.1.x → 2.0.0-Mx). |
| Features | MEDIUM-HIGH | HIGH on architectural feature set (PROJECT.md + concept doc). MEDIUM on competitive framing. HIGH that MCP is novel in 2026; MEDIUM on exact tool surface until prototype. |
| Architecture | MEDIUM-HIGH | HIGH on transactional outbox, runtime routing, single write funnel. MEDIUM on multi-tenant choke point details (vendor blogs MEDIUM; pattern itself HIGH). MEDIUM on SHACL synchronous validation perf envelope at scale. |
| Pitfalls | HIGH | HIGH on AGE-specific (upstream issues, release notes, Crunchy Data). HIGH on multi-tenancy and event log standards. MEDIUM on reconciliation patterns. MEDIUM-HIGH on encryption. HIGH on solo-dev scope-creep. |

**Overall confidence:** MEDIUM-HIGH. Locked decisions are sound; dominant uncertainty is operational risk around Apache AGE, not architectural risk.

### Gaps to Address

- **AGE behavior under sustained write load** — must be characterized by Phase 0 benchmark harness, not assumed
- **Postgres RLS on AGE label tables** — promising belt-and-braces tenancy mitigation; spike in Phase 1
- **Spring AI MCP Server dynamic tool registration semantics** — verify before Phase 3 starts; fallback is restart-on-schema-change
- **MCP tool surface (sync vs paginated vs streaming)** — prototype before committing API
- **Tenant model granularity** — one `model_id` per customer, or per "domain within a customer"? Decide before Phase 1 schema freezes
- **DQ rule authoring format** — SHACL only, or higher-level DSL on top?
- **Conflict register UX** — API-only in MVP, or minimal operator UI from day one?
- **KMS chaos test scope** (if encryption ships) — explicit failure modes and acceptance criteria
- **Event log volume forecast per tenant** — drives partitioning interval, snapshot cadence, retention

## Sources

### Primary (HIGH confidence)
- Apache AGE GitHub releases, FAQ, manual, issues #2111 / #2194 / #2229
- Crunchy Data: Postgres 17.1 ABI change near-miss
- Spring Boot 3.5.12 / 3.5.13 release blogs; endoflife.date
- Spring AI 1.0 GA blog and 2026-03-17 release notes
- Spring AI MCP Server Boot Starter docs
- Apache Jena download + SHACL documentation
- Debezium 3.4.0.Final release blog and Outbox Event Router docs
- Testcontainers Postgres module docs
- Spring Cloud Vault project page and reference docs
- Apache AGE JDBC driver source

### Secondary (MEDIUM confidence)
- SeatGeek ChairNerd: Transactional Outbox Pattern at scale
- Decodable: Revisiting the Outbox Pattern
- event-driven.io: Push-based Outbox with Postgres Logical Replication
- Memgraph multi-tenancy blog
- AWS Database Blog: Multi-tenant on Neptune
- FalkorDB multi-tenant cloud security
- Microsoft Learn: AGE performance best practices
- Restzilla / Spring dynamic controller POC (anti-pattern reference)
- 2026 vendor/analyst pieces from Domo, Atlan, Alation, Promethium, AnalyticsCreator, Palantir Foundry docs, Stardog docs

### Tertiary (LOW confidence)
- SHACL benchmark mail archive entry on Jena perf characteristics

### Internal cross-references
- /Users/matthiaswegner/Programmming/GitHub/Tessera/.planning/PROJECT.md
- /Users/matthiaswegner/Programmming/GitHub/Tessera/.planning/research/STACK.md
- /Users/matthiaswegner/Programmming/GitHub/Tessera/.planning/research/FEATURES.md
- /Users/matthiaswegner/Programmming/GitHub/Tessera/.planning/research/ARCHITECTURE.md
- /Users/matthiaswegner/Programmming/GitHub/Tessera/.planning/research/PITFALLS.md
- /Users/matthiaswegner/Documents/Leo's Gedankenspeicher/CONCEPT-Meta-Model-Driven-Data-Fabric.md

---
*Research completed: 2026-04-13*
*Ready for roadmap: yes*
