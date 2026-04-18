---
gsd_artifact: context
phase: "04"
phase_name: "SQL View + Kafka Projections, Hash-Chained Audit"
created: 2026-04-17
requirements: [SQL-01, SQL-02, SQL-03, KAFKA-01, KAFKA-02, KAFKA-03, AUDIT-01, AUDIT-02]
---

# Phase 4 — Context

**Goal (from ROADMAP.md):** Add the two remaining projections required for real-world consumption — SQL views for BI tools (bypassing the AGE aggregation cliff) and Kafka topics for downstream event fan-out via Debezium — plus optional hash-chained audit integrity for compliance-driven tenants. The write path must not change.

**Depends on:** Phase 3 (MCP projection — complete).

**Boundary:** 8 requirements (SQL-01..03, KAFKA-01..03, AUDIT-01..02). No GraphQL (Phase 5+), no UI dashboard, no circlead integration.

---

<domain>
## Phase Boundary

Deliver Tessera's remaining two projections — SQL views for BI/analytics tools and Kafka topics for event fan-out — plus an optional hash-chained audit trail for compliance-sensitive tenants. All three features are additive: no changes to the existing write path (`GraphService.apply` → `EventLog` → `graph_outbox`). SQL views read AGE label tables directly. Kafka replaces the in-process `OutboxPoller` with Debezium CDC. Hash chains are opt-in per tenant and append-only.

</domain>

<decisions>
## Implementation Decisions

### A. SQL View Generation

- **D-A1:** SQL views are **regular (non-materialized) views** reading AGE label tables directly. Materialized views add refresh complexity without meaningful benefit — AGE label tables are already indexed and BI tools run aggregate queries on them. View DDL is `CREATE OR REPLACE VIEW v_{model}_{typeSlug} AS SELECT ...` with columns derived from the Schema Registry's `NodeTypeDescriptor.properties()`.

- **D-A2:** Views are **regenerated on schema change** via a `@TransactionalEventListener` on `SchemaChangeEvent` (or equivalent ApplicationEvent published by `SchemaRegistry`). The `SqlViewProjection` service reads the current schema, drops stale views, and creates/replaces views for all exposed types in the changed tenant. Views also regenerate on application startup to survive restarts.

- **D-A3:** View code lives in **`fabric-projections`** under `dev.tessera.projections.sql`. Shares `SchemaRegistry` access with the REST and MCP projections. No new Maven module.

- **D-A4:** View naming: `v_{model_id}_{type_slug}` with underscores replacing hyphens. Each view is tenant-scoped — a view for model "acme" type "person" becomes `v_acme_person`. An admin endpoint `GET /admin/sql/views` lists all active views per tenant.

### B. Debezium/Kafka Integration

- **D-B1:** Use **Debezium 3.4 Outbox Event Router SMT** (`io.debezium.transforms.outbox.EventRouter`). The existing `graph_outbox` table already has the shape Debezium expects (id, aggregate_type, aggregate_id, payload, timestamp). The SMT routes events to topics named `tessera.{model_id}.{type_slug}`.

- **D-B2:** Debezium runs as an **external Docker container** in the Compose stack (`debezium/connect:3.4`), not embedded in the Tessera JVM. This matches the IONOS VPS deployment model and avoids classpath conflicts with Spring Boot.

- **D-B3:** The existing `OutboxPoller` (in-process, `@Scheduled`) is **retained as a fallback** for development without Kafka/Debezium. A `tessera.kafka.enabled=true` config flag switches between in-process polling and CDC. When Kafka is enabled, `OutboxPoller` is disabled via `@ConditionalOnProperty`.

- **D-B4:** Flyway migration adds a `graph_outbox.published` boolean column (default false). Debezium reads unpublished rows; after routing, marks them published. The `OutboxPoller` fallback also uses this column. This is a minor write-path change to `Outbox.append()` — the only acceptable write-path modification.

- **D-B5:** Docker Compose adds `kafka`, `zookeeper` (or KRaft single-node), and `debezium-connect` services. The Tessera service depends on `kafka` only when `tessera.kafka.enabled=true`.

### C. Hash-Chained Audit

- **D-C1:** Hash chains are **opt-in per tenant** via a `hash_chain_enabled` boolean column on the tenant/model configuration (Flyway migration). When enabled, each `graph_events` row includes a `prev_hash` column containing `SHA-256(prev_hash || event_payload_json)`.

- **D-C2:** The hash is computed **inline during EventLog.append()** within the same transaction. For the first event in a chain, `prev_hash` is a well-known genesis value (`SHA-256("TESSERA_GENESIS")`). The `EventLog` reads the previous event's hash in the same transaction using `SELECT prev_hash FROM graph_events WHERE model_id = ? ORDER BY seq DESC LIMIT 1 FOR UPDATE`.

- **D-C3:** An **on-demand verification endpoint** `POST /admin/audit/verify?model_id={id}` walks the chain and returns `{valid: true, events_checked: N}` or `{valid: false, broken_at_seq: N, expected_hash: ..., actual_hash: ...}`. This is also runnable as a CLI command / CI job via `mvn exec:java -Dexec.mainClass=... -Dmodel_id=...`.

- **D-C4:** Flyway migration adds `prev_hash VARCHAR(64)` to `graph_events` (nullable — only populated for hash-chain-enabled tenants).

### D. Monitoring & Observability

- **D-D1:** Replication slot lag exposed as a **Spring Boot Actuator health indicator** (`/actuator/health/debezium`). Queries `pg_stat_replication` for the Debezium slot's `confirmed_flush_lsn` lag. Health degrades to `DOWN` when lag exceeds a configurable threshold (default: 100MB WAL).

- **D-D2:** A **Flyway migration sets `max_slot_wal_keep_size`** (e.g., 2GB) to prevent unbounded WAL growth if Debezium falls behind. The Actuator health indicator includes WAL usage percentage.

- **D-D3:** SQL view staleness detected by comparing the view's implicit schema version (from a comment in the view DDL) with the current `SchemaVersionService` version. Stale views are regenerated automatically; staleness is logged as a warning.

### Claude's Discretion
- Kafka topic partitioning strategy (single partition per type is fine for MVP)
- Debezium connector configuration details (slot name, publication name, heartbeat interval)
- Whether to use KRaft or ZooKeeper for the Kafka broker in Docker Compose (KRaft preferred for simplicity)
- Hash chain batch verification parallelism (sequential is fine for MVP)
- SQL view column ordering and type mapping details

</decisions>

<code_context>
## Existing Code Insights

### Reusable Assets
- `Outbox` / `OutboxPoller` — existing outbox infrastructure in `fabric-core/src/main/java/dev/tessera/core/events/`
- `EventLog` — event append with transactional guarantees
- `SchemaRegistry` / `SchemaVersionService` — type discovery and version tracking
- `SchemaChangeReplayer` / `SchemaDescriptorCache` — schema change event infrastructure
- `GraphService.apply()` — the write path that must not change
- `AgePostgresContainer` — Testcontainers AGE base for ITs

### Established Patterns
- Flyway plain SQL migrations (V24+ for this phase)
- `@ConditionalOnProperty` for feature flags (see `OutboxPoller` scheduling)
- Spring `ApplicationEventPublisher` for internal events
- `NamedParameterJdbcTemplate` for all JDBC access
- Admin endpoints under `/admin/*` with JWT tenant scoping
- ArchUnit tests enforcing module boundaries

### Integration Points
- `graph_outbox` table — Debezium reads from here
- `graph_events` table — hash chain appended here
- `SchemaRegistry` — SQL view generation reads types from here
- `SecurityConfig` — admin endpoints share existing JWT auth
- `docker-compose.yml` — Kafka + Debezium containers added here

</code_context>

<specifics>
## Specific Ideas

- CLAUDE.md specifies Debezium 3.4 and Kafka 3.9.x (matching local install at `~/Programmming/Services/Kafka 3.9`)
- ADR-4: "Postgres event log is authoritative; Kafka is a downstream projection, not the source of truth"
- The `graph_outbox` table already exists from Phase 1 (V3 migration) — Debezium reads from it
- BI tools (Metabase, Looker, PowerBI) connect via standard PostgreSQL JDBC — views appear as regular tables

</specifics>

<deferred>
## Deferred Ideas

- Kafka Schema Registry (Avro/Protobuf serialization) — JSON payloads sufficient for MVP
- Dead letter topic for failed Debezium transformations
- GraphQL projection (Phase 5+)
- Real-time SQL view refresh notifications to BI tools
- Hash chain verification across multiple tenants in batch

</deferred>

---

*Phase: 04-sql-view-kafka-projections-hash-chained-audit*
*Context gathered: 2026-04-17 via Smart Discuss (autonomous mode)*
