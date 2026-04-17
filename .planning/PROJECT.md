# Tessera

## What This Is

Tessera is a graph-based, protocol-agnostic integration layer that sits between heterogeneous systems (ERP, CRM, legacy DBs, SaaS, LLM agents) as the single source of truth. The graph holds the truth — REST, GraphQL, MCP, SQL views, and Kafka topics are all projections generated from a central meta-model. Consumers (circlead, BI tools, LLM agents, dashboards) read projections; connectors reconcile inbound data from source systems through a priority-based rule engine.

## Core Value

**The graph is the truth; everything else is a projection.** If Tessera cannot reliably serve a consistent, reconciled, schema-validated view of entities across source systems via dynamically-generated endpoints, nothing else matters.

## Requirements

### Validated

(None yet — ship to validate)

### Active

- [ ] Knowledge Graph core on PostgreSQL + Apache AGE (nodes, edges, properties, Cypher access)
- [ ] Event log (every mutation persisted as an event with source attribution and sequence)
- [ ] Schema Registry / Meta-Model layer (node types, properties, edge types, per-tenant / `model_id` scoping)
- [ ] SHACL-based schema validation via Apache Jena
- [ ] Custom Rule Engine (chain-of-responsibility, priority-based) for reconciliation, validation, routing
- [ ] Source Authority Matrix configurable per tenant; reconciliation conflict register
- [ ] Dynamic REST projection generated from schema (`/api/v1/{model}/entities/{typeSlug}`)
- [ ] Connector framework (polling / CDC / webhook / JDBC / file) with mapping definitions
- [ ] First concrete connector: generic REST polling with delta detection (ETag / last_modified)
- [ ] In-process event bus (Spring `ApplicationEventPublisher`) as MVP transport
- [ ] Multi-tenant isolation via `model_id` on every node, edge, and event
- [ ] MCP projection — agents query/mutate graph via MCP tools (Phase 2)
- [ ] SQL view projection for BI tools (Metabase/Looker/PowerBI) (Phase 2)
- [ ] Kafka projection — topics per entity type for event fan-out (Phase 2)
- [ ] Circlead integration as first real consumer (Phase 3)
- [ ] Encryption: TLS 1.3 in transit, Postgres TDE at rest, field-level AES-GCM for sensitive properties
- [ ] KMS integration (HashiCorp Vault for self-hosted) with envelope encryption and key rotation
- [ ] Audit-log integrity via hash chaining (optional / compliance-driven)

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
| PostgreSQL + Apache AGE as primary store (ADR-1) | ACID over graph+relational in one transaction; native SQL views; single ops system; Apache 2.0 | — Pending |
| SHACL for schema validation; OWL opt-in only (ADR-2) | Covers 90% of validation needs at a fraction of OWL's complexity; Apache Jena has mature Java tooling | — Pending |
| Custom rule engine for MVP; Drools deferred (ADR-3) | Chain-of-responsibility is sufficient for initial reconciliation logic; Drools adds weight without MVP payoff | — Pending |
| Event log in PostgreSQL; Kafka as projection (ADR-4) | Postgres is already authoritative and transactional; Kafka handles fan-out to external consumers | — Pending |
| Schema registry co-located with graph DB (ADR-5) | Schema + data validation in one transaction; avoids distributed schema management | — Pending |
| Circlead stays standalone, consumes via REST/MCP (ADR-6) | No big-bang migration; circlead keeps running independently during transition | — Pending |
| Apache 2.0 license | Matches circlead ecosystem; permissive, contributor-friendly | — Pending |
| First connector: generic REST polling | Lowest risk, no dependency on a specific live system, exercises full ingest/reconcile loop | — Pending |
| Self-hosted on IONOS VPS | Consistent with circlead operational model; stays portable | — Pending |
| Open to contributors from day one | Public OSS posture with CONTRIBUTING.md and issue templates from the start | — Pending |
| Own positioning (not "Palantir alternative") | Graph-first + LLM-native angle is the story; avoid comparison marketing | — Pending |
| MVP scope follows concept's Phase 1 as-is | Graph core + REST projection + 1 connector + custom rule engine + event log | — Pending |

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
*Last updated: 2026-04-17 after Phase 8 (circlead-production-wiring-dr-drill-fix) completion — CircleadConnectorConfig resolves placeholders + registers 3 connectors in DB; DR drill script fixed with correct column names, replay verification, and smoke test*
