# Tessera

## What This Is

Tessera is a graph-based, protocol-agnostic integration layer built on PostgreSQL + Apache AGE that serves as the single source of truth across heterogeneous systems. Four projection engines (REST, MCP, SQL views, Kafka) are dynamically generated from a central Schema Registry. Connectors ingest data from structured APIs and unstructured text (via LLM extraction) through a single-transaction write funnel with SHACL validation, a 4-chain rule engine, and priority-based reconciliation. circlead is the first real consumer, reading Role/Circle/Activity data via REST and MCP projections.

## Core Value

**The graph is the truth; everything else is a projection.** If Tessera cannot reliably serve a consistent, reconciled, schema-validated view of entities across source systems via dynamically-generated endpoints, nothing else matters.

## Requirements

### Validated

- ✓ Knowledge Graph core on PostgreSQL + Apache AGE (nodes, edges, properties, Cypher access) — v1.0
- ✓ Event log (every mutation persisted as an event with source attribution and sequence) — v1.0
- ✓ Schema Registry / Meta-Model layer (node types, properties, edge types, per-tenant / `model_id` scoping) — v1.0
- ✓ SHACL-based schema validation via Apache Jena — v1.0
- ✓ Custom Rule Engine (chain-of-responsibility, priority-based) for reconciliation, validation, routing — v1.0
- ✓ Source Authority Matrix configurable per tenant; reconciliation conflict register — v1.0
- ✓ Dynamic REST projection generated from schema (`/api/v1/{model}/entities/{typeSlug}`) — v1.0
- ✓ Connector framework (polling / CDC / webhook / JDBC / file) with mapping definitions — v1.0
- ✓ First concrete connector: generic REST polling with delta detection (ETag / last_modified) — v1.0
- ✓ In-process event bus (Spring `ApplicationEventPublisher`) as MVP transport — v1.0
- ✓ Multi-tenant isolation via `model_id` on every node, edge, and event — v1.0
- ✓ MCP projection — agents query/mutate graph via MCP tools — v1.0
- ✓ SQL view projection for BI tools (Metabase/Looker/PowerBI) — v1.0
- ✓ Kafka projection — topics per entity type for event fan-out — v1.0
- ✓ Circlead integration as first real consumer — v1.0
- ✓ Encryption: TLS 1.3 in transit, Postgres TDE runbook at rest, field-level ACL for sensitive properties — v1.0
- ✓ KMS integration (HashiCorp Vault for self-hosted) via Spring Cloud Vault Config Data API — v1.0
- ✓ Audit-log integrity via hash chaining (optional / compliance-driven) — v1.0
- ✓ LLM-based entity extraction from unstructured text with pgvector entity resolution — v1.0
- ✓ SchemaChangeEvent infrastructure for live projection refresh — v1.0
- ✓ Production observability (Prometheus/Micrometer metrics, health indicators) — v1.0
- ✓ DR drill rehearsed end-to-end (dump/restore/replay/smoke test) — v1.0

### Active

(Fresh for next milestone — define via `/gsd-new-milestone`)

### Out of Scope

- **Neo4j as primary store** — PostgreSQL + AGE chosen for ACID over graph+relational in one system; Neo4j may come later as read-replica for graph-heavy traversals (ADR-1)
- **Full OWL reasoning in MVP** — SHACL covers 90% of practical validation needs; OWL as optional opt-in module later (ADR-2)
- **Drools in MVP** — custom rule engine for MVP; Drools as later migration when CEP/decision tables are needed (ADR-3)
- **Kafka-native event sourcing** — Postgres is primary event store; Kafka is a projection/consumer (ADR-4)
- **Big-bang circlead migration** — circlead keeps its JPA model and consumes Tessera in parallel during transition (ADR-6)
- **Cloud-specific lock-in** — deployment targets self-hosted (IONOS VPS) first; managed cloud remains possible but not required
- **GraphQL projection in MVP** — deferred to Phase 4 after REST, MCP, SQL, Kafka projections
- **Positioning as Palantir Foundry / Dataverse alternative** — focus is graph-first + LLM-native angle on its own terms, not comparison marketing
- **Read-write connectors to source systems in MVP** — first connector is read-only polling; bidirectional propagation deferred

## Context

- **Ecosystem:** Tessera lives next to `circlead` (Java 21, Spring Boot, Maven, IONOS VPS hosting). Same conventions, same ops model. circlead is the first planned real consumer but is not the driver — Tessera is its own product.
- **Prior concept work:** Full architecture blueprint exists at `~/Documents/Leo's Gedankenspeicher/CONCEPT-Meta-Model-Driven-Data-Fabric.md` (DRAFT 2026-04-13) covering graph model, rule engine, connectors, projection engine, security architecture (sections 10.4–10.10 on encryption, KMS, field-level crypto), MCP integration, and ADRs.
- **Motivation:** Point-to-point integrations scale as N² with the number of systems. LLM agents currently have to call N different APIs to form a complete picture. No system knows who holds the "right" version of a given datum. Tessera collapses that into one graph + dynamically-generated projections.
- **LLM angle:** The graph becomes agent-shared memory — one Cypher traversal replaces multiple API calls across CRM/ERP/Jira/etc. MCP tools (`query_entities`, `traverse`, `get_state_at`, etc.) make this native.
- **Temporal queries:** Every mutation is an event. "What did the org look like on March 1?" is a replay, not a backup restore.
- **Security posture:** Conservative defaults. Per-tenant encryption keys, envelope encryption, secrets never in the fabric DB, TLS everywhere, optional hash-chained audit log for GxP/SOX/BSI C5 compliance.

## Constraints

- **Tech stack:** Java 21 + Spring Boot 3.x, PostgreSQL 16 + Apache AGE, Apache Jena (SHACL), Maven multi-module, Spring AI MCP Server, SpringDoc OpenAPI. Chosen for consistency with circlead ecosystem and enterprise readiness.
- **Primary store:** PostgreSQL + Apache AGE (not Neo4j). Rationale: ACID across graph + relational in one transaction, native SQL views, managed cloud availability, Apache 2.0 licensing. (ADR-1)
- **Schema validation:** SHACL, not OWL. (ADR-2)
- **Rule engine:** Custom chain-of-responsibility for MVP, Drools only when complexity demands it. (ADR-3)
- **Event transport:** Postgres event log is authoritative; Kafka is a downstream projection, not the source of truth. (ADR-4)
- **Schema registry location:** Same database as the graph — transactional consistency for schema change + data validation. (ADR-5)
- **Hosting:** Self-hosted first (IONOS VPS, Docker Compose). Must remain portable — no cloud-specific lock-in.
- **License:** Apache 2.0, matching circlead ecosystem.
- **Tenant isolation:** Every node, edge, and event is scoped by `model_id`. Queries are always filtered.
- **Secrets:** Connector credentials and encryption keys live in a KMS (Vault self-hosted, cloud KMS otherwise), never in Postgres or the graph.
- **Team:** Solo development; repo open to contributors from the start (CONTRIBUTING.md, issue templates, public OSS posture).

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| PostgreSQL + Apache AGE as primary store (ADR-1) | ACID over graph+relational in one transaction; native SQL views; single ops system; Apache 2.0 | ✓ Good — AGE 1.6/PG16 works; SQL views bypass 15× aggregation cliff; agtype→jsonb cast required |
| SHACL for schema validation; OWL opt-in only (ADR-2) | Covers 90% of validation needs at a fraction of OWL's complexity; Apache Jena has mature Java tooling | ✓ Good — Jena 5.x SHACL covers all validation needs; cached shapes < 2ms |
| Custom rule engine for MVP; Drools deferred (ADR-3) | Chain-of-responsibility is sufficient for initial reconciliation logic; Drools adds weight without MVP payoff | ✓ Good — 4-chain rule engine (VALIDATE→RECONCILE→ENRICH→ROUTE) handles all MVP cases |
| Event log in PostgreSQL; Kafka as projection (ADR-4) | Postgres is already authoritative and transactional; Kafka handles fan-out to external consumers | ✓ Good — Debezium Outbox Event Router swapped in without write-path changes |
| Schema registry co-located with graph DB (ADR-5) | Schema + data validation in one transaction; avoids distributed schema management | ✓ Good — SchemaChangeEvent enables live projection refresh |
| Circlead stays standalone, consumes via REST/MCP (ADR-6) | No big-bang migration; circlead keeps running independently during transition | ✓ Good — 3 mapping definitions (Role, Circle, Activity) wired with placeholder resolution |
| Apache 2.0 license | Matches circlead ecosystem; permissive, contributor-friendly | ✓ Good |
| First connector: generic REST polling | Lowest risk, no dependency on a specific live system, exercises full ingest/reconcile loop | ✓ Good — ETag/Last-Modified delta detection, DLQ, ShedLock scheduling all proven |
| Self-hosted on IONOS VPS | Consistent with circlead operational model; stays portable | ✓ Good — Docker Compose deployment, DR drill rehearsed |
| Open to contributors from day one | Public OSS posture with CONTRIBUTING.md and issue templates from the start | ✓ Good |
| Own positioning (not "Palantir alternative") | Graph-first + LLM-native angle is the story; avoid comparison marketing | ✓ Good |
| MVP scope expanded beyond concept Phase 1 | Added unstructured ingestion (Phase 2.5), gap-closure phases 6-10 | ✓ Good — broader MVP proves more of the architecture |
| agtype requires explicit ::jsonb cast in SQL views | AGE agtype is NOT jsonb — SQL views must cast explicitly | ✓ Good — discovered in Phase 4, applied consistently |
| MetricsPort SPI pattern for cross-module metrics | fabric-core defines port interface; fabric-app provides Micrometer adapter | ✓ Good — clean module boundary, no upward dependency |
| Field-level ACL via PropertyDescriptor annotations | Per-property read/write role lists, cached in AclPropertyCache | ✓ Good — enforced in both REST and MCP projections |

## Context

Shipped v1.0 with ~140k LOC Java across 5 Maven modules (`fabric-core`, `fabric-rules`, `fabric-projections`, `fabric-connectors`, `fabric-app`).
Tech stack: Java 21 + Spring Boot 3.5, PostgreSQL 16 + Apache AGE 1.6, Apache Jena 5.x SHACL, Spring AI MCP Server 1.0.x, Debezium 3.4, SpringDoc OpenAPI 2.8.x.
29 Flyway migrations (V1-V29). Testcontainers-based integration tests with custom AGE+pgvector Docker image.
circlead wired as first consumer with 3 mapping definitions (Role, Circle, Activity).
DR drill rehearsed. Prometheus metrics wired into production code paths. Field-level ACL enforced in REST and MCP projections.

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition** (via `/gsd-transition`):
1. Requirements invalidated? → Move to Out of Scope with reason
2. Requirements validated? → Move to Validated with phase reference
3. New requirements emerged? → Add to Active
4. Decisions to log? → Add to Key Decisions
5. "What This Is" still accurate? → Update if drifted

**After each milestone** (via `/gsd-complete-milestone`):
1. Full review of all sections
2. Core Value check — still the right priority?
3. Audit Out of Scope — reasons still valid?
4. Update Context with current state

---
*Last updated: 2026-04-18 after v1.0 milestone completion*
