---
gsd_artifact: context
phase: "05"
phase_name: "Circlead Integration & Production Hardening"
created: 2026-04-17
requirements: [CIRC-01, CIRC-02, CIRC-03, OPS-01, OPS-02, OPS-03, OPS-04, OPS-05]
---

# Phase 5 — Context

**Goal (from ROADMAP.md):** Prove the whole stack against the first real consumer (circlead) without a big-bang migration, and harden operations with observability, snapshots, retention, and a rehearsed DR drill so Tessera is safe to run on IONOS VPS.

**Depends on:** Phase 4 (SQL views, Kafka, hash-chained audit — complete).

**Boundary:** 8 requirements (CIRC-01..03, OPS-01..05). This is the final milestone phase — after this, the v1.0 milestone is complete.

---

<domain>
## Phase Boundary

Deliver the circlead integration proving Tessera's full stack against a real consumer, plus production hardening: observability (Prometheus/OTel metrics, Actuator health), event-log lifecycle management (retention, snapshots), and a rehearsed DR drill. The circlead integration runs in read-only parallel mode alongside circlead's own JPA model — no big-bang migration. Operator dashboards are backend metrics endpoints, not custom UI.

</domain>

<decisions>
## Implementation Decisions

### A. Circlead Integration

- **D-A1:** Circlead integration is **read-only parallel mode** — circlead reads Role, Circle, and Activity data from Tessera via REST and MCP projections while retaining its own JPA model. No writes from circlead to Tessera in v1.0. A Tessera REST connector polls circlead's existing API to ingest Role/Circle/Activity data into Tessera's graph.

- **D-A2:** A **documented mapping** (markdown) maps circlead's JPA entities (Role, Circle, Activity, Rolegroup) to Tessera node types and edge types. The mapping includes field-level correspondence, cardinality, and any transformation rules.

- **D-A3:** Circlead must **gracefully degrade** when Tessera is unavailable — a circuit breaker (Resilience4j or Spring Retry) wraps Tessera REST/MCP calls. When the circuit is open, circlead falls back to its local JPA data. The circuit breaker configuration is documented. **Scope note:** The circuit breaker is circlead-side code, not Tessera-side. Tessera delivers: (1) documented circuit breaker pattern in `circlead-mapping.md`, (2) a connector-disconnect test in `CircleadConnectorIT` proving Tessera's connector handles circlead unavailability gracefully. Circlead-side implementation is tracked separately.

- **D-A4:** Integration testing uses a **circlead-stub** module or WireMock that simulates circlead's API responses for Tessera's REST connector, rather than requiring a live circlead instance.

### B. Observability

- **D-B1:** Metrics are **Micrometer-based** (Spring Boot Actuator + `micrometer-registry-prometheus`). Exposed at `/actuator/prometheus`. Key metrics: `tessera.ingest.rate`, `tessera.rules.evaluations`, `tessera.conflicts.count`, `tessera.outbox.lag`, `tessera.replication.slot.lag`, `tessera.shacl.validation.time`.

- **D-B2:** **Custom health indicators** for: PostgreSQL (existing), AGE graph (new — query `ag_catalog.ag_graph`), Vault connectivity (Spring Cloud Vault existing), and each registered connector (new — `ConnectorHealthIndicator`). All report to `/actuator/health`.

- **D-B3:** **OpenTelemetry** traces added via `spring-boot-starter-actuator` + `micrometer-tracing-bridge-otel` for distributed tracing context propagation to Debezium and downstream consumers. No custom Jaeger/Zipkin setup in v1.0 — just trace headers.

### C. Event-Log Lifecycle

- **D-C1:** Per-tenant **event-log retention** is configurable via `model_config.retention_days` (nullable, default: no retention = keep forever). A scheduled job (`EventRetentionJob`) runs daily and deletes events older than the retention threshold for tenants with it configured.

- **D-C2:** Per-tenant **snapshot** compaction: `POST /admin/events/snapshot?model_id={id}` reads the current state of all entities in the tenant, writes a single "snapshot" event per entity to the event log, then deletes pre-snapshot events. Temporal queries above the snapshot boundary still work; queries below return "snapshot boundary exceeded."

- **D-C3:** Snapshot and retention operations are **idempotent** and **non-blocking** — they run in a separate transaction and do not lock the write path. The snapshot boundary timestamp is recorded in `model_config.snapshot_boundary`.

### D. DR Drill & CI

- **D-D1:** A **DR drill script** (`scripts/dr_drill.sh`) performs: pg_dump → pg_restore to a fresh container → Flyway verify → DB-layer data integrity queries. The CI job validates the DB layer only (no Spring Boot startup). The full API smoke test (GET /actuator/health + GET /api/v1/{model}/entities/role) runs in the IONOS VPS manual drill documented in `docs/DR-DRILL.md`.

- **D-D2:** **CI pipeline** (`ci.yml`) runs all milestone-1 phases' test suites (unit + integration tests) on every push. The Testcontainers AGE image is used for integration tests. The pipeline must remain green after Phase 5 is complete.

- **D-D3:** DR drill documentation lives in `docs/DR-DRILL.md` with step-by-step instructions, expected outputs, and troubleshooting for IONOS VPS deployment.

### Claude's Discretion
- Circlead REST connector polling interval and error handling details
- Specific Micrometer metric tag dimensions (tenant, type, connector)
- EventRetentionJob scheduling expression (daily at 2am is fine)
- Snapshot compaction batch size and progress reporting
- DR drill script exact pg_dump flags and restore verification queries
- Whether to use Resilience4j or Spring Retry for circlead circuit breaker

</decisions>

<code_context>
## Existing Code Insights

### Reusable Assets
- `GenericRestPollerConnector` — existing REST polling connector pattern for circlead data ingestion
- `ConnectorRegistry` / `ConnectorRunner` — connector lifecycle management
- `WriteRateCircuitBreaker` — existing circuit breaker pattern in fabric-rules
- `DebeziumSlotHealthIndicator` — Phase 4 health indicator pattern for new custom indicators
- `EventLog` — event append/replay infrastructure for snapshot operations
- `McpAuditController` — admin endpoint pattern with JWT tenant scoping

### Established Patterns
- Spring Boot Actuator for health and metrics endpoints
- `@Scheduled` + `@SchedulerLock` for background jobs (OutboxPoller, PartitionMaintenance)
- Docker Compose for local dev + IONOS deploy
- `NamedParameterJdbcTemplate` for all JDBC operations
- Flyway plain SQL migrations (V28+ for this phase)
- WireMock for external API mocking in tests
- ArchUnit for module boundary enforcement

### Integration Points
- circlead REST API — source for Role/Circle/Activity data
- `SchemaRegistry` — register circlead entity types
- `GraphService.apply()` — write circlead data to graph
- `EventLog` — snapshot and retention operations
- `model_config` — retention_days and snapshot_boundary columns
- Docker Compose — no new services needed (Prometheus optional)

</code_context>

<specifics>
## Specific Ideas

- Circlead is a Java/Maven project in the same GitHub organization — the mapping should reference circlead's actual entity class names
- IONOS VPS is the target deployment — DR drill must work with Docker Compose, not cloud-managed services
- The CI pipeline already exists at `.github/workflows/ci.yml` — Phase 5 extends it, doesn't replace it
- Prometheus scraping is external to Tessera — Tessera only exposes the metrics endpoint

</specifics>

<deferred>
## Deferred Ideas

- Circlead write-back (bidirectional sync) — v2.0 scope
- Grafana dashboard provisioning — operator imports existing community dashboards
- Automated snapshot scheduling (vs on-demand) — future enhancement
- Multi-region DR replication — beyond single VPS scope
- Custom OpenTelemetry exporters (Jaeger, Zipkin) — v2.0

</deferred>

---

*Phase: 05-circlead-integration-production-hardening*
*Context gathered: 2026-04-17 via Smart Discuss (autonomous mode)*
