# Tessera Glossary & Project Scope

A single reference for every term, abbreviation, and concept used across Tessera — including features that are planned but not yet implemented. Entries are marked with their **status** (shipped / planned / deferred) so readers can tell what's real today versus what's on the roadmap.

> **Core value:** The graph is the truth; everything else is a projection.

---

## 1. Project Overview

**Tessera** is a graph-based, protocol-agnostic integration layer that sits between heterogeneous systems (ERP, CRM, legacy DBs, SaaS, LLM agents) as a single source of truth. The graph holds the truth; REST, GraphQL, MCP, SQL views, and Kafka topics are all **projections** generated from a central meta-model. Inbound data from source systems is reconciled through a priority-based rule engine before landing in the graph.

- **License:** Apache 2.0
- **Primary stack:** Java 21, Spring Boot 3.5.x, PostgreSQL 16 + Apache AGE, Apache Jena (SHACL), Maven multi-module
- **Hosting target:** Self-hosted first (IONOS VPS, Docker Compose); portable, no cloud lock-in
- **Development model:** Solo, open to contributors from day one

---

## 2. Core Concepts

### Graph (shipped, Phase 1)
The authoritative data store. A labeled property graph held inside PostgreSQL via the Apache AGE extension. Nodes represent entities; edges represent relationships. Every node, edge, and event is scoped by `model_id` (tenant).

### Projection (partially shipped)
A read-oriented view of the graph in some protocol — REST, MCP, SQL view, Kafka topic, GraphQL. Projections are generated from the Schema Registry; they never own truth. If a projection disagrees with the graph, the graph wins.

### Meta-Model / Schema Registry (shipped, Phase 1)
The typed description of what node types, edge types, and properties exist in a given tenant. Stored in the same database as the graph for transactional consistency. Versioned, alias-aware, and event-sourced — renaming a property doesn't break historical reads.

### Write Funnel (shipped, Phase 1)
The single entry point `GraphService.apply()` through which every mutation flows. One Postgres transaction covers auth → rules → SHACL → Cypher → event log → outbox. An ArchUnit test fails the build if any caller bypasses it or executes raw Cypher outside `graph.internal`.

### Event Log (shipped, Phase 1)
Append-only table in Postgres recording every mutation with full provenance: `origin_connector_id`, `origin_change_id`, sequence per tenant, timestamps. Authoritative. All other event transports (Kafka) are projections of this log.

### Outbox (shipped, Phase 1)
A same-transaction "to publish" table. Events are written to the outbox inside the same DB transaction as the graph mutation, then drained asynchronously by a poller (MVP) or Debezium (Phase 4). Guarantees at-least-once delivery without two-phase commit.

### Rule Engine (shipped, Phase 1)
A custom, priority-based chain-of-responsibility engine with four chains: **VALIDATE → RECONCILE → ENRICH → ROUTE**. Rules can be registered in code, enabled/disabled per tenant, and re-prioritized via DB. Not Drools — deliberately minimal for MVP.

### Connector (partially planned, Phase 2 / 2.5)
A component that ingests data from an external system (REST poll, JDBC, CDC, webhook, file, or LLM-extracted text) and hands `CandidateMutation` objects to the write funnel. First concrete connector is a generic REST poller with ETag / Last-Modified delta detection.

### Tenant / `model_id` (shipped, Phase 1)
The isolation boundary. Every node, edge, and event carries `model_id`. Every query is filtered by it. Property-based fuzz tests (`TenantBypassPropertyIT`) prove no code path can read or write across tenants.

### Source Authority Matrix (shipped, Phase 1)
Per-tenant table declaring priority of source systems for a given property. When two connectors claim different values, the matrix deterministically resolves the winner. The loser lands in `reconciliation_conflicts`.

### Reconciliation Conflict Register (shipped, Phase 1)
A table (`reconciliation_conflicts`) where losing mutations are recorded with full context (winner, loser, mutation payload, tenant). Queryable; exposed to operators.

### Circuit Breaker (shipped, Phase 1)
A write-rate limiter that halts a runaway connector before a conflict storm. Per-connector counters, Micrometer metrics, DLQ, admin reset endpoint.

### Dead Letter Queue / DLQ (partially shipped)
Rows/messages that could not be processed. Currently used by the circuit breaker; extends to connector-level errors in Phase 2.

### Echo Loop Suppression (shipped, Phase 1, RULE-08)
Guards against a write propagating back to itself through a second connector. A replay of the same `(origin_connector_id, origin_change_id)` is rejected before reaching AGE.

### Temporal Replay (shipped, Phase 1)
Reconstructing the state of a node, edge, or entire tenant at a past timestamp by replaying event log entries. Powers "what did the org look like on March 1?" queries.

### Candidate Mutation (shipped, Phase 1; generalized in Phase 2.5)
The neutral input shape for the rule engine. Carries property values plus provenance metadata (`source_type`, `source_id`, `confidence`, `extractor_version`). Deliberately designed in Phase 1 to accommodate both structured connectors and LLM-extracted mutations without changing the rule engine contract.

### Entity Resolution (planned, Phase 2.5)
Deterministic matching of a newly-extracted entity against existing graph state based on `(name, type, optional embedding)`. Matches above threshold merge; matches below threshold land in a review queue.

### Field-Level Encryption (planned, Phase 2 — ship or feature-flag)
Per-tenant envelope encryption of sensitive properties using AES-GCM with blind indexes for equality search. Decision recorded in Phase 2: either fully shipped (with KMS chaos test in CI) or feature-flagged off (rejecting writes to encrypted-marked properties at startup).

### Hash-Chained Audit Log (planned, Phase 4)
Optional, compliance-driven integrity mechanism. Each event row records the hash of the previous event plus its own payload. A verification job detects tampering and reports the first broken link. For tenants subject to GxP / SOX / BSI C5.

---

## 3. Abbreviations & Acronyms

### Domain / architecture

| Abbreviation | Expands to | Meaning in Tessera |
|---|---|---|
| **AGE** | Apache AGE | PostgreSQL extension adding Cypher + graph storage. Tessera's graph engine. |
| **ADR** | Architecture Decision Record | Short document capturing a technical decision with rationale. Stored in `.planning/adr/`. |
| **CDC** | Change Data Capture | Streaming row-level changes out of a source DB. Planned as a connector mode. |
| **DAG** | Directed Acyclic Graph | The shape of most Tessera traversals; enforced by validation rules where needed. |
| **DEK** | Data Encryption Key | The symmetric key that actually encrypts a property value. Envelope-encrypted by a KEK held in the KMS. |
| **DLQ** | Dead Letter Queue | Holding pen for unprocessable messages. |
| **DR** | Disaster Recovery | Full rehearsal: dump → restore → replay → consumer smoke test. Phase 5. |
| **ERP / CRM** | Enterprise Resource Planning / Customer Relationship Management | Representative source systems Tessera integrates with. |
| **KEK** | Key Encryption Key | The long-lived key in the KMS that wraps DEKs. |
| **KMS** | Key Management Service | HashiCorp Vault (self-hosted) or cloud KMS. Holds connector secrets and encryption keys. Never in Postgres. |
| **LPG** | Labeled Property Graph | The graph model used by AGE (and Neo4j). Nodes and edges carry labels and arbitrary properties. |
| **MCP** | Model Context Protocol | Anthropic's tool protocol for LLM agents. Tessera exposes graph queries as MCP tools (Phase 3). |
| **MVP** | Minimum Viable Product | Milestone 1: graph core + REST + 1 connector + custom rule engine + event log. |
| **OWL** | Web Ontology Language | W3C standard for ontology modeling. Deliberately deferred; SHACL covers 90% of needs (ADR-2). |
| **RBAC** | Role-Based Access Control | Row- and field-level access filtering based on caller role. Phase 2. |
| **RDF** | Resource Description Framework | W3C triple model. Tessera uses RDF indirectly via Jena's SHACL engine; the graph itself is LPG, not RDF. |
| **SHACL** | Shapes Constraint Language | W3C standard for validating RDF/graph data against shape definitions. Tessera's schema validator via Apache Jena (ADR-2). |
| **SLA / SLO** | Service Level Agreement / Objective | Operational commitments. Phase 5 concern. |
| **SMT** | Single Message Transform (Kafka Connect) | Debezium's Outbox Event Router SMT transforms outbox rows into per-topic events. Phase 4. |
| **SPI** | Service Provider Interface | Plug-in contract. Connector SPI shape is a Phase 2 research flag. |
| **TDE** | Transparent Data Encryption | Postgres-level at-rest encryption. Part of the security baseline. |
| **TLS** | Transport Layer Security | TLS 1.3 with HSTS for all consumer-facing HTTP. Phase 2. |
| **TX** | Transaction | Database transaction. The write funnel is strictly single-TX. |
| **UAT** | User Acceptance Testing | Phase verification step in the GSD workflow. |
| **VPS** | Virtual Private Server | IONOS VPS is Tessera's primary deployment target. |

### Build / tooling / runtime

| Abbreviation | Expands to | Meaning in Tessera |
|---|---|---|
| **ArchUnit** | — (library name) | Java library that enforces architectural rules as JUnit tests. Used to ban raw Cypher outside `graph.internal` and enforce module direction. |
| **BOM** | Bill of Materials | Maven dependency version aggregator (Spring Boot BOM, Spring Cloud BOM). |
| **CI** | Continuous Integration | GitHub Actions runs build + tests + benchmark regression on every push. |
| **DDL** | Data Definition Language | SQL `CREATE TABLE` etc. Tessera uses Flyway plain-SQL migrations because AGE's catalog setup fights abstraction layers. |
| **JDBC** | Java Database Connectivity | Plain pgJDBC is used to talk to AGE; no AGE-specific driver needed. |
| **JMH** | Java Microbenchmark Harness | Benchmark framework. `ShaclValidationBench` (<2ms p95) and `WritePipelineBench` (<11ms p95) gate Phase 1 perf. |
| **JPA** | Jakarta Persistence API | Used for relational metadata (schema registry, event log read side). Not used for graph access. |
| **MCP Server** | Model Context Protocol Server | Spring AI MCP Server starter. Exposes tools to agents. Phase 3. |
| **OGM** | Object Graph Mapper | Deliberately **not used**. Tessera talks to AGE via raw JDBC + Cypher templates. |
| **pgvector** | PostgreSQL extension | Adds vector columns + ANN search. Planned for Phase 2.5 for embedding-based entity resolution. |
| **pgoutput** | Postgres logical replication plugin | Built-in since PG10; what Debezium uses. No separate decoder plugin needed. |
| **RLS** | Row-Level Security | Postgres feature. Research flag for Phase 1 — whether to enforce tenant isolation via RLS on AGE label tables. |
| **SBOM** | Software Bill of Materials | Dependency inventory. Planned for OSS hygiene. |
| **WAL** | Write-Ahead Log | Postgres replication log. Phase 4 monitors WAL bloat via `max_slot_wal_keep_size`. |

### Compliance / security

| Abbreviation | Expands to | Meaning in Tessera |
|---|---|---|
| **BSI C5** | Bundesamt für Sicherheit in der Informationstechnik – Cloud Computing Compliance Criteria Catalogue | German cloud security framework. Hash-chained audit log is aimed at this class of compliance. |
| **GxP** | "Good x Practice" (GMP, GLP, GCP, …) | Life-sciences compliance framework. Drives the hash-chained audit log requirement. |
| **HSTS** | HTTP Strict Transport Security | Forces browsers/clients to use HTTPS. Enforced in Phase 2. |
| **SOX** | Sarbanes-Oxley Act | US financial compliance. Same audit-log rationale. |

### Workflow / GSD

| Abbreviation | Expands to | Meaning in Tessera |
|---|---|---|
| **GSD** | "Get Shit Done" | The opinionated planning/execution workflow used in this repo (`.planning/`, `/gsd-*` commands). |
| **ITs** | Integration Tests | Files suffixed `*IT.java`, run by Maven Failsafe. |
| **PLAN / SUMMARY / VERIFICATION** | — | GSD artifacts per phase wave. Plans are executed, summaries are written on completion, verifications gate phase closure. |

---

## 4. Module Layout (Maven Multi-Module)

| Module | Purpose | Status |
|---|---|---|
| `fabric-core` | Graph write funnel, schema registry, event log, outbox, SHACL validation | Shipped (Phase 1) |
| `fabric-rules` | Rule engine, source authority matrix, conflict register, circuit breaker | Shipped (Phase 1) |
| `fabric-projections` | REST / MCP / SQL view / Kafka projections | Planned (Phase 2+) |
| `fabric-connectors` | Connector framework + concrete connectors | Planned (Phase 2, 2.5) |
| `fabric-app` | Spring Boot application, Flyway migrations, entry point | Shipped (Phase 0/1) |

Module naming: the product is **Tessera**, the internal namespace / module prefix is **`fabric-`** (historical — Tessera was formerly called "Meta-Model Driven Data Fabric"). This naming may converge in a future pass.

Package roots follow `dev.tessera.core.*`, `dev.tessera.rules.*`, etc.

---

## 5. Requirement ID Prefixes

Every requirement in `.planning/REQUIREMENTS.md` carries a prefix that identifies its domain. Phase assignments follow `ROADMAP.md`.

| Prefix | Domain | Phase | Examples |
|---|---|---|---|
| **FOUND** | Foundations — scaffold, Docker image pinning, benchmarks, DR rehearsal | 0 | FOUND-01 .. FOUND-06 |
| **CORE** | Graph core — write funnel, tenant isolation, system properties, tombstones | 1 | CORE-01 .. CORE-08 |
| **SCHEMA** | Schema Registry — node/edge/property types, versioning, aliases, cache | 1 | SCHEMA-01 .. SCHEMA-08 |
| **VALID** | SHACL validation — shape compilation, cache, tenant-filtered reports | 1 | VALID-01 .. VALID-05 |
| **EVENT** | Event log + outbox — provenance, partitioning, replay, audit history | 1 | EVENT-01 .. EVENT-07 |
| **RULE** | Rule engine — chains, priorities, authority, conflicts, circuit breaker, echo-loop | 1 | RULE-01 .. RULE-08 |
| **REST** | REST projection — dynamic endpoints, deny-all default, OpenAPI | 2 | REST-01 .. REST-07 |
| **CONN** | Connector framework — mapping definitions, polling, DLQ, sync status | 2 | CONN-01 .. CONN-08 |
| **SEC** | Security baseline — TLS, Vault, RBAC, row/field access, (optional) field-level crypto | 2–3 | SEC-01 .. SEC-08 |
| **EXTR** | Unstructured extraction — LLM chunking, entity resolution, pgvector | 2.5 | EXTR-01 .. EXTR-08 |
| **MCP** | MCP projection — tool registration, temporal tools, prompt-injection defense, audit | 3 | MCP-01 .. MCP-09 |
| **SQL** | SQL view projection — per-tenant per-type views for BI | 4 | SQL-01 .. SQL-03 |
| **KAFKA** | Kafka projection — Debezium outbox router, per-type topics | 4 | KAFKA-01 .. KAFKA-03 |
| **AUDIT** | Hash-chained audit integrity — compliance-driven | 4 | AUDIT-01 .. AUDIT-02 |
| **CIRC** | Circlead integration — REST + MCP parallel read, graceful degradation | 5 | CIRC-01 .. CIRC-03 |
| **OPS** | Operations — metrics, health, retention, snapshots, DR drill | 5 | OPS-01 .. OPS-05 |

Total v1 requirements: **98**, all mapped to exactly one phase.

---

## 6. Roadmap Scope (Milestone 1)

All phases below are part of the MVP milestone. Status reflects the state of `.planning/ROADMAP.md` at the time of writing — see that file for the live view.

### Phase 0 — Foundations & Risk Burndown
Pin `apache/age` Docker image by sha256 digest, stand up Maven multi-module scaffold, Flyway baseline, HikariCP + AGE session init, JMH benchmark harness against 100k/1M node datasets, and rehearse `pg_dump` + `pg_restore` on a seeded AGE database. **Why first:** Apache AGE is the single highest-risk dependency; the major-upgrade path is unproven and must be rehearsed before any feature work.

### Phase 1 — Graph Core, Schema Registry, Validation, Rules ✅ complete
The two spines — Schema Registry and Event Log + Outbox — plus the single transactional write funnel, SHACL validation, and the full rule engine (VALIDATE → RECONCILE → ENRICH → ROUTE). Includes tenant-isolation fuzz tests, JMH perf budgets (SHACL <2ms p95, write pipeline <11ms p95), source authority matrix, conflict register, write-rate circuit breaker with DLQ, and echo-loop suppression.

### Phase 2 — REST Projection, Connector Framework, First Connector, Security Baseline
Dynamically-generated REST endpoints (`/api/v1/{model}/entities/{typeSlug}`) routed through a single `GenericEntityController`. Deny-all default until an explicit `exposure` policy is declared. Generic REST polling connector with ETag / Last-Modified delta detection. TLS 1.3 + HSTS, Vault-managed connector credentials via Spring Cloud Vault Config Data API, RBAC on rows and fields. Explicit decision on field-level encryption: ship fully or feature-flag off.

### Phase 2.5 — Unstructured Ingestion & Entity Extraction
Second connector mode: LLM-based extraction of typed entities and relationships from free text (wikis, notes, chat logs, emails). Chunking, schema-driven extraction, provenance recording, entity resolution against existing graph state (deterministic, reproducible), pgvector-backed embedding search. First concrete unstructured connector: Markdown folder / Obsidian-vault shape. Extracted candidates flow through the same rule engine, SHACL, and source authority matrix as structured connectors.

### Phase 3 — MCP Projection (Flagship Differentiator)
Spring AI MCP Server with tools dynamically registered from the Schema Registry: `list_entity_types`, `describe_type`, `query_entities`, `get_entity`, `traverse`, `find_path`, `get_state_at`. Read-only by default; writes require explicit per-agent quota. Prompt-injection defense via `<data>...</data>` wrapping. Every tool invocation audited per tenant.

### Phase 4 — SQL View + Kafka Projections, Hash-Chained Audit
Per-tenant per-type SQL views (`v_{model}_{typeSlug}`) for Metabase / Looker / PowerBI, reading AGE label tables directly to bypass the AGE aggregation cliff. Debezium 3.4 with Outbox Event Router SMT replaces the in-process poller — the write path is unchanged. WAL / replication slot monitoring. Optional per-tenant hash-chained audit integrity.

### Phase 5 — Circlead Integration & Production Hardening
First real consumer: **circlead** reads Role, Circle, and Activity data via REST + MCP in parallel with its own JPA model. Documented round-trip mapping. Graceful degradation when Tessera is unavailable. Prometheus / OpenTelemetry metrics, Actuator health, per-tenant retention + snapshots, full DR drill rehearsed end-to-end.

---

## 7. Deliberately Out of Scope

These are named because they're the obvious things a reader would assume are planned. They aren't — and each has a decision record behind it.

| Item | Why not | Reference |
|---|---|---|
| **Neo4j as primary store** | Loses ACID across graph + relational; second ops system. May appear later as a read-replica for deep traversals. | ADR-1 |
| **Full OWL reasoning** | SHACL covers 90% of practical validation needs at a fraction of OWL's complexity. OWL as optional opt-in module later. | ADR-2 |
| **Drools in MVP** | Brings KIE / Workbench / ~20 MB of dependencies for priority-based chains that fit in ~100 lines of Java. Revisit when CEP or decision tables are genuinely needed. | ADR-3 |
| **Kafka as source of truth** | Postgres event log is authoritative. Kafka is a downstream projection via Debezium. | ADR-4 |
| **Distributed schema registry** | Schema lives in the same DB as the graph for transactional consistency. | ADR-5 |
| **Big-bang circlead migration** | Circlead keeps its JPA model and consumes Tessera in parallel during transition. | ADR-6 |
| **GraphQL projection in MVP** | REST + MCP + SQL + Kafka ship first. GraphQL revisited after Phase 4. | PROJECT.md |
| **Bidirectional read-write connectors in MVP** | First connector is read-only polling; write-back is deferred. | PROJECT.md |
| **Cloud-specific lock-in** | Self-hosted on IONOS VPS is the primary target. Managed cloud must remain possible but never required. | PROJECT.md |
| **Positioning as "Palantir Foundry alternative"** | Tessera stands on graph-first + LLM-native on its own terms, not comparison marketing. | PROJECT.md |
| **Neo4j Java driver / Spring Data Neo4j** | Actively harmful — confuses contributors about the architecture. Plain pgJDBC + Cypher templates only. | STACK.md |
| **Liquibase** | Loses to Flyway because AGE catalog setup + `LOAD 'age'` fits plain SQL, fights XML/YAML abstractions. | STACK.md |
| **Embedded Postgres for tests** | Can't load AGE extension. Testcontainers against real `apache/age` image is the only viable path. | STACK.md |

---

## 8. ADR Index

Short form; full records in `.planning/adr/`.

| ADR | Decision |
|---|---|
| **ADR-1** | PostgreSQL 16 + Apache AGE as primary store (not Neo4j). ACID over graph + relational in one transaction. |
| **ADR-2** | SHACL via Apache Jena for schema validation. OWL deferred. |
| **ADR-3** | Custom chain-of-responsibility rule engine for MVP. Drools deferred. |
| **ADR-4** | Postgres event log is authoritative. Kafka is a downstream projection via Debezium. |
| **ADR-5** | Schema registry co-located with the graph DB. Transactional schema-change + data-validation consistency. |
| **ADR-6** | Circlead stays standalone and consumes Tessera via REST / MCP. No big-bang migration. |
| **ADR-7** | Rule engine contract — four chains (VALIDATE → RECONCILE → ENRICH → ROUTE), priority-based, tenant-scoped activation. |

---

## 9. Pointers

- `.planning/PROJECT.md` — core value, constraints, key decisions
- `.planning/REQUIREMENTS.md` — 98 v1 requirements with phase traceability
- `.planning/ROADMAP.md` — live phase breakdown with success criteria
- `.planning/adr/` — architecture decision records
- `.planning/research/` — stack, architecture, pitfalls, features research synthesis
- `.planning/phases/01-.../01-VERIFICATION.md` — Phase 1 verification report (latest)
- `CLAUDE.md` — tech stack, constraints, conventions (for AI assistants and humans alike)

---

*Last updated: 2026-04-15. This document is a living glossary — update it when a new term, abbreviation, or scope item enters the project vocabulary.*
