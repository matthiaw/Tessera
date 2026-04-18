# Phase 5: Circlead Integration & Production Hardening - Research

**Researched:** 2026-04-17
**Domain:** Circlead REST connector mapping, Micrometer/Prometheus observability, event-log lifecycle (retention + snapshots), DR drill scripting, GitHub Actions CI integration
**Confidence:** HIGH

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**A. Circlead Integration**
- D-A1: Circlead integration is read-only parallel mode — circlead reads Role, Circle, and Activity data from Tessera via REST and MCP projections while retaining its own JPA model. No writes from circlead to Tessera in v1.0. A Tessera REST connector polls circlead's existing API to ingest Role/Circle/Activity data into Tessera's graph.
- D-A2: A documented mapping (markdown) maps circlead's JPA entities (Role, Circle, Activity, Rolegroup) to Tessera node types and edge types. The mapping includes field-level correspondence, cardinality, and any transformation rules.
- D-A3: Circlead must gracefully degrade when Tessera is unavailable — a circuit breaker (Resilience4j or Spring Retry) wraps Tessera REST/MCP calls. When the circuit is open, circlead falls back to its local JPA data. The circuit breaker configuration is documented.
- D-A4: Integration testing uses a circlead-stub module or WireMock that simulates circlead's API responses for Tessera's REST connector, rather than requiring a live circlead instance.

**B. Observability**
- D-B1: Metrics are Micrometer-based (Spring Boot Actuator + `micrometer-registry-prometheus`). Exposed at `/actuator/prometheus`. Key metrics: `tessera.ingest.rate`, `tessera.rules.evaluations`, `tessera.conflicts.count`, `tessera.outbox.lag`, `tessera.replication.slot.lag`, `tessera.shacl.validation.time`.
- D-B2: Custom health indicators for: PostgreSQL (existing), AGE graph (new — query `ag_catalog.ag_graph`), Vault connectivity (Spring Cloud Vault existing), and each registered connector (new — `ConnectorHealthIndicator`). All report to `/actuator/health`.
- D-B3: OpenTelemetry traces added via `spring-boot-starter-actuator` + `micrometer-tracing-bridge-otel` for distributed tracing context propagation to Debezium and downstream consumers. No custom Jaeger/Zipkin setup in v1.0 — just trace headers.

**C. Event-Log Lifecycle**
- D-C1: Per-tenant event-log retention is configurable via `model_config.retention_days` (nullable, default: no retention = keep forever). A scheduled job (`EventRetentionJob`) runs daily and deletes events older than the retention threshold for tenants with it configured.
- D-C2: Per-tenant snapshot compaction: `POST /admin/events/snapshot?model_id={id}` reads the current state of all entities in the tenant, writes a single "snapshot" event per entity to the event log, then deletes pre-snapshot events. Temporal queries above the snapshot boundary still work; queries below return "snapshot boundary exceeded."
- D-C3: Snapshot and retention operations are idempotent and non-blocking — they run in a separate transaction and do not lock the write path. The snapshot boundary timestamp is recorded in `model_config.snapshot_boundary`.

**D. DR Drill & CI**
- D-D1: A DR drill script (`scripts/dr_drill.sh`) performs: pg_dump → pg_restore to a fresh container → Flyway verify → API smoke test against circlead mapping endpoints. The script is runnable in CI and locally.
- D-D2: CI pipeline (`ci.yml`) runs all milestone-1 phases' test suites (unit + integration tests) on every push. The Testcontainers AGE image is used for integration tests. The pipeline must remain green after Phase 5 is complete.
- D-D3: DR drill documentation lives in `docs/DR-DRILL.md` with step-by-step instructions, expected outputs, and troubleshooting for IONOS VPS deployment.

### Claude's Discretion
- Circlead REST connector polling interval and error handling details
- Specific Micrometer metric tag dimensions (tenant, type, connector)
- EventRetentionJob scheduling expression (daily at 2am is fine)
- Snapshot compaction batch size and progress reporting
- DR drill script exact pg_dump flags and restore verification queries
- Whether to use Resilience4j or Spring Retry for circlead circuit breaker

### Deferred Ideas (OUT OF SCOPE)
- Circlead write-back (bidirectional sync) — v2.0 scope
- Grafana dashboard provisioning — operator imports existing community dashboards
- Automated snapshot scheduling (vs on-demand) — future enhancement
- Multi-region DR replication — beyond single VPS scope
- Custom OpenTelemetry exporters (Jaeger, Zipkin) — v2.0
</user_constraints>

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| CIRC-01 | circlead reads from Tessera via REST and MCP projections in addition to its own JPA model (parallel operation, no big-bang migration) | Tessera REST projection already live; MCP tools in fabric-projections; circuit breaker pattern available |
| CIRC-02 | Mapping from circlead entities (Role, Circle, Activity) to Tessera node types is documented and round-trips cleanly | Circlead entity fields verified from source; GenericRestPollerConnector + MappingDefinition can express the mapping |
| CIRC-03 | circlead continues to function if Tessera is unavailable (graceful degradation) | Spring Retry available on classpath; WriteRateCircuitBreaker is the in-codebase pattern; choice of Resilience4j vs Spring Retry is discretion |
| OPS-01 | Prometheus / OpenTelemetry metrics for: ingest rate, rule evaluations per second, conflict count, outbox lag, replication slot lag, SHACL validation time | `micrometer-registry-prometheus` not yet in POMs; `WriteRateCircuitBreaker` already injects `MeterRegistry`; pattern established |
| OPS-02 | Spring Boot Actuator health endpoint exposes Postgres, AGE, Vault, and connector health | `DebeziumSlotHealthIndicator` is the pattern; `/actuator/health` management config already in application.yml |
| OPS-03 | Per-tenant snapshot mechanism compacts the event log for long-lived tenants | `model_config` table exists (V27); `EventLog` append infrastructure available; snapshot boundary column needed in V28 migration |
| OPS-04 | Per-tenant event-log retention policies are configurable | `model_config` table (V27) needs `retention_days` column; `@Scheduled` + `@SchedulerLock` pattern established by `OutboxPoller` and `PartitionMaintenanceTask` |
| OPS-05 | DR drill rehearsed end-to-end: dump → restore → replay → consumer smoke test | `dump_restore_rehearsal.sh` already exists; needs extension to cover Tessera application data, Flyway verification, and circlead-mapping smoke test |
</phase_requirements>

---

## Summary

Phase 5 is the final milestone-1 phase, delivering three parallel workstreams: (1) the circlead connector — a concrete instantiation of the `GenericRestPollerConnector` polling circlead's JAX-RS/CXF REST API to ingest Role, Circle, and Activity workitems into Tessera's graph; (2) production hardening through Micrometer/Prometheus metrics instrumentation, custom health indicators for the AGE graph layer and registered connectors, and OpenTelemetry trace header propagation; (3) event-log lifecycle management (retention and snapshot compaction) plus an extended DR drill that covers Tessera application data alongside the existing graph-data rehearsal.

The project is highly prescriptive: all patterns are established in earlier phases and the codebase provides exact templates for every new component. The biggest new dependencies are `micrometer-registry-prometheus` (not yet in any POM), and optionally `resilience4j-spring-boot3` for the circlead-side circuit breaker. The `model_config` table (V27) already exists with `hash_chain_enabled`; this phase adds `retention_days` and `snapshot_boundary` columns via a V28 migration.

The circlead REST API is the JAX-RS/CXF `WorkitemService` at base path `/circlead/workitem` with endpoints `GET /list?type=ROLE|CIRCLE|ACTIVITY`. The `RestEnvelope` wrapper holds a `List<String>` of item IDs or serialized dataitem JSON depending on the `details=true` flag. The `MappingDefinition` JSON format in `fabric-connectors` is the canonical way to express the field mapping. Because the circlead main project is not available locally (only `circlead-sunray` submodule is), the mapping must rely on the verified entity field structure from the `circlead-sunray` source code in the local IDE workspace.

**Primary recommendation:** Build in strict dependency order — V28 migration first (model_config extension), then the metrics layer (Prometheus endpoint + custom meters), then the health indicators, then the circlead connector + stub + mapping doc, then EventRetentionJob + snapshot endpoint, then DR drill extension. This order minimises inter-task blocking and allows each wave to be verified independently.

---

## Project Constraints (from CLAUDE.md)

| Directive | Applies To Phase 5 |
|-----------|--------------------|
| Java 21 + Spring Boot 3.5.13 | All new code |
| PostgreSQL 16 + Apache AGE 1.6.0 | DR drill uses same image digest as docker-compose.yml |
| Apache Jena SHACL (not OWL) | SHACL validation time metric |
| Custom rule engine (no Drools) | tessera.rules.evaluations metric wraps existing chain |
| Flyway plain SQL migrations (V28+) | model_config extensions, no Liquibase |
| `NamedParameterJdbcTemplate` for JDBC | EventRetentionJob, snapshot admin controller |
| `@Scheduled` + `@SchedulerLock` (ShedLock 5.16.0) | EventRetentionJob |
| WireMock 3.10.0 (already in fabric-connectors test deps) | Circlead-stub integration tests |
| ArchUnit module boundary enforcement | No connector code calling projections; new health indicators belong in correct modules |
| Spring Boot Actuator pattern (AbstractHealthIndicator) | AGE graph health indicator, ConnectorHealthIndicator |
| `management.endpoints.web.exposure.include` in application.yml | Must add `prometheus` to the include list |
| No Drools, no Spring Boot 3.4.x, no raw Cypher endpoints | N/A to Phase 5 |
| Solo dev / Apache 2.0 / open-source posture | License headers on all new files |

---

## Standard Stack

### Core (all already in project BOM or POMs)

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Spring Boot Actuator | 3.5.13 (BOM) | Health + metrics endpoints | Already in fabric-app `pom.xml`; base for all monitoring [VERIFIED: codebase] |
| Micrometer Core | 3.5.13 BOM-managed | `MeterRegistry`, `Counter`, `Timer`, `Gauge` | Transitively provided by actuator; `WriteRateCircuitBreaker` already uses it [VERIFIED: codebase] |
| `micrometer-registry-prometheus` | 3.5.13 BOM-managed | Prometheus scrape endpoint at `/actuator/prometheus` | **Not yet in any POM — must add to `fabric-app/pom.xml`** [VERIFIED: codebase grep showed no existing entry] |
| `micrometer-tracing-bridge-otel` | 3.5.13 BOM-managed | Bridges Micrometer Observation API to OTel trace context | Required for D-B3; provides W3C `traceparent` headers with no Jaeger/Zipkin exporter needed [ASSUMED - Spring Boot BOM manages this] |
| ShedLock 5.16.0 | Already in parent BOM | Distributed scheduling lock for `EventRetentionJob` | Already used by `OutboxPoller` and `ConnectorScheduler` [VERIFIED: codebase] |
| WireMock Standalone 3.10.0 | Already in `fabric-connectors` test scope | Circlead API stub for integration tests | Already used by `RestPollingConnectorIT` [VERIFIED: codebase] |
| Testcontainers 1.20.4 | Already in parent BOM | AGE container for DR drill integration tests | Already established pattern [VERIFIED: codebase] |

### New Dependency Required

```xml
<!-- fabric-app/pom.xml — add to dependencies -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
    <!-- Version managed by Spring Boot BOM -->
</dependency>

<!-- fabric-app/pom.xml — add to dependencies for OTel trace bridge -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-otlp</artifactId>
    <!-- BOM-managed via Spring Boot; needed at runtime only if exporting; optional for v1.0 trace headers only -->
</dependency>
```

**Note on circuit breaker (D-A3, Claude's discretion):** The circlead-side circuit breaker (wrapping Tessera calls from within circlead) is not a Tessera module concern — it lives in circlead's own codebase. For documentation/configuration purposes in Phase 5, recommend documenting Spring Retry (`spring-retry` + `@EnableRetry` + `@Retryable` with `maxAttempts=3, backoff`) because: (1) circlead is already a Spring project; (2) Spring Retry is lighter than Resilience4j for a simple open/closed switch; (3) Resilience4j brings more complexity (sliding window, half-open state machine) that is overkill for "fall back to local JPA when Tessera is unreachable." Spring Retry on the Tessera REST client in circlead, combined with a `@CircuitBreaker` annotation, achieves D-A3 with minimal dependency surface. [ASSUMED — choice between the two is discretion per CONTEXT.md]

### Version Verification

```bash
# Verify Spring Boot BOM manages these at correct versions for 3.5.13
mvn -pl fabric-app dependency:tree -Dincludes=io.micrometer: | grep micrometer
```

Micrometer 1.15.x is managed by Spring Boot 3.5.x BOM. `micrometer-registry-prometheus` artifact ID `io.micrometer:micrometer-registry-prometheus` is the correct artifact. [ASSUMED — verify with `mvn dependency:resolve` after adding]

---

## Architecture Patterns

### Recommended Project Structure for Phase 5

New code slots into existing modules — no new Maven module needed:

```
fabric-app/
  src/main/java/dev/tessera/app/
    metrics/
      TesseraMetrics.java          # Central metric registration bean
    health/
      AgeGraphHealthIndicator.java # ag_catalog.ag_graph ping
      ConnectorHealthIndicator.java# per-connector status check
  src/main/resources/db/migration/
    V28__model_config_lifecycle.sql # retention_days + snapshot_boundary columns

fabric-core/
  src/main/java/dev/tessera/core/events/
    EventRetentionJob.java         # @Scheduled + @SchedulerLock daily retention sweep
    snapshot/
      EventSnapshotService.java    # snapshot compaction logic (separate TX)
  src/main/java/dev/tessera/core/admin/
    EventLifecycleController.java  # POST /admin/events/snapshot, GET /admin/events/retention

fabric-connectors/
  src/main/java/dev/tessera/connectors/circlead/
    CircleadConnectorConfig.java   # @Configuration wiring MappingDefinition for circlead
  src/test/java/dev/tessera/connectors/circlead/
    CircleadConnectorIT.java       # WireMock-backed integration test

docs/
  circlead-mapping.md              # CIRC-02: field-level mapping document
  DR-DRILL.md                      # D-D3: DR drill instructions

scripts/
  dr_drill.sh                      # D-D1: extended from existing dump_restore_rehearsal.sh
```

### Pattern 1: Micrometer Custom Meter Registration

**What:** Register custom meters in a `@Component` bean that receives `MeterRegistry` injection. Follow the `WriteRateCircuitBreaker` pattern already in the codebase.

**When to use:** Any new business metric (ingest rate, rules evaluations, conflict count, outbox lag).

```java
// Source: WriteRateCircuitBreaker.java [VERIFIED: codebase]
// Pattern: inject MeterRegistry, build counters/gauges at registration time
@Component
public class TesseraMetrics {
    private final Counter ingestRate;
    private final Counter rulesEvaluations;
    private final Counter conflictsCount;
    // Gauges are registered with a supplier lambda
    
    public TesseraMetrics(MeterRegistry registry, EventLogRepository eventLogRepo) {
        this.ingestRate = Counter.builder("tessera.ingest.rate")
            .description("Graph mutations applied per second")
            .tag("source", "connector")
            .register(registry);
        // ... etc.
        Gauge.builder("tessera.outbox.lag", eventLogRepo, r -> r.pendingOutboxCount())
            .description("Pending outbox entries awaiting delivery")
            .register(registry);
    }
}
```

### Pattern 2: AbstractHealthIndicator Extension

**What:** Extend `AbstractHealthIndicator` from `spring-boot-starter-actuator`. The `DebeziumSlotHealthIndicator` in `fabric-projections` is the exact template.

**When to use:** All four health checks (Postgres already provided by Spring Boot auto-config, AGE graph, Vault auto-configured by Spring Cloud Vault, ConnectorHealthIndicator is new).

```java
// Source: DebeziumSlotHealthIndicator.java [VERIFIED: codebase]
@Component("ageGraph")
public class AgeGraphHealthIndicator extends AbstractHealthIndicator {
    private final NamedParameterJdbcTemplate jdbc;
    
    @Override
    protected void doHealthCheck(Health.Builder builder) {
        // Query ag_catalog.ag_graph to verify AGE is loaded and graph exists
        List<String> graphs = jdbc.queryForList(
            "SELECT name FROM ag_catalog.ag_graph LIMIT 10",
            new MapSqlParameterSource(), String.class);
        builder.up().withDetail("graphs_count", graphs.size());
    }
}
```

### Pattern 3: EventRetentionJob (Scheduled + ShedLock)

**What:** `@Scheduled(cron = "0 0 2 * * *")` + `@SchedulerLock(name = "tessera-event-retention")`. Runs daily at 2am, iterates tenants with non-null `retention_days`, deletes events older than `now() - retention_days * INTERVAL '1 day'`.

**When to use:** OPS-04. Follows `OutboxPoller` and `PartitionMaintenanceTask` patterns exactly.

```java
// Based on OutboxPoller.java and PartitionMaintenanceTask.java [VERIFIED: codebase]
@Component
public class EventRetentionJob {
    @Scheduled(cron = "0 0 2 * * *")   // daily at 02:00
    @SchedulerLock(name = "tessera-event-retention", lockAtMostFor = "PT55M")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sweep() {
        // SELECT model_id, retention_days FROM model_config WHERE retention_days IS NOT NULL
        // DELETE FROM graph_events WHERE model_id = :model AND event_time < now() - :days * INTERVAL '1 day'
        //   AND event_time >= COALESCE((SELECT snapshot_boundary FROM model_config WHERE model_id = :model), '-infinity')
        // Note: DELETE only above snapshot_boundary — never wipe snapshots themselves
    }
}
```

**Partition awareness:** `graph_events` is monthly-partitioned. Deleting by `event_time` against the partitioned table is partition-pruning-safe — Postgres eliminates partitions outside the range automatically. No special handling needed.

### Pattern 4: EventSnapshotService (Non-blocking Compaction)

**What:** `POST /admin/events/snapshot?model_id={id}` triggers a compaction that: (1) reads current entity states from the graph, (2) writes one `SNAPSHOT` event per entity in a new transaction, (3) records `snapshot_boundary = now()` in `model_config`, (4) in a third transaction deletes pre-boundary events.

**Critical:** Three separate transactions, not one, to keep the write path unblocked. Step 4 is safe to retry if it fails.

```java
// Pattern: McpAuditController admin endpoint + McpAuditController pattern [VERIFIED: codebase]
@RestController
@RequestMapping("/admin/events")
public class EventLifecycleController {
    @PostMapping("/snapshot")
    public ResponseEntity<SnapshotResult> takeSnapshot(@RequestParam UUID modelId) {
        // Delegates to EventSnapshotService which manages 3 transactions
    }
}
```

### Pattern 5: Circlead MappingDefinition

**What:** A `MappingDefinition` JSON (already the connector framework's canonical format — CONN-02) stored as configuration, loaded at application start by `CircleadConnectorConfig`. The `GenericRestPollerConnector` is reused unchanged.

**Circlead REST API endpoints (verified from source):**
- `GET /circlead/workitem/list?type=ROLE&details=true` → `RestEnvelope` with list of Role JSON
- `GET /circlead/workitem/list?type=CIRCLE&details=true` → Circle JSON list
- `GET /circlead/workitem/list?type=ACTIVITY&details=true` → Activity JSON list

**Circlead WorkitemType enum values:** `ROLE`, `CIRCLE`, `ACTIVITY` (verified from `WorkitemType.getType()` calls in source). [VERIFIED: codebase]

### Anti-Patterns to Avoid

- **Locking the write path during snapshot:** The snapshot delete step must run in a separate transaction after the boundary is recorded. Never hold a table-level lock during snapshot iteration.
- **Exposing `/actuator/prometheus` without auth:** The management endpoint currently has `show-details: when-authorized`. Prometheus scraping bypasses auth by default — either add network-level protection (recommended for IONOS VPS) or configure `management.endpoint.prometheus.access=unrestricted` deliberately. Do not leave it accidentally open.
- **Deleting events below snapshot_boundary in retention sweep:** The retention job must respect `snapshot_boundary` — it should only delete events where `event_time < (now() - retention_days * INTERVAL '1 day') AND event_time >= snapshot_boundary`. Events that are the snapshot records themselves must never be deleted by the retention job.
- **Registering the same Micrometer meter name twice:** All metric names in `TesseraMetrics` must be registered exactly once. Dual-instantiation in tests causes `MeterAlreadyExists` exceptions. Use `registry.find()...meter()` defensively or use `Metrics.globalRegistry` only in test overrides.
- **Using `@ConditionalOnBean(MeterRegistry.class)` as a guard:** Micrometer's `SimpleMeterRegistry` is always auto-configured in tests. The `WriteRateCircuitBreaker` already handles null MeterRegistry explicitly — follow that pattern for new meters too.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Prometheus scrape endpoint | Custom `/metrics` controller | `micrometer-registry-prometheus` + `management.endpoints.web.exposure.include=prometheus` | Spring Boot auto-configures Prometheus scraping from all registered Meters; custom controller is fragile and misses auto-registered JVM/Tomcat metrics |
| Outbox lag metric | Count rows with custom SQL | `Gauge.builder("tessera.outbox.lag", repo, r -> r.pendingOutboxCount()).register(registry)` in `TesseraMetrics` | Gauge auto-polls; a counter-based approach would miss rows already in PENDING state at startup |
| Scheduled job coordinator | Custom semaphore + DB flag | `@SchedulerLock` (ShedLock) | ShedLock is already in the project; hand-rolling distributed locking is an unsafe pattern the codebase explicitly avoided |
| Health indicator wiring | Manual `HealthContributorRegistry` manipulation | Extend `AbstractHealthIndicator` + `@Component("name")` | Spring Boot auto-discovers `HealthIndicator` beans; the component name becomes the health group name in `/actuator/health` |
| DR drill Flyway verification | Custom SQL schema check | `flyway.validate()` via `Flyway.configure().load()` in the drill script | Flyway's built-in validate checks checksum integrity and detects missing or extra migrations |

**Key insight:** Every observability concern in this phase has a Spring Boot auto-configuration path. The goal is configuration, not code.

---

## Runtime State Inventory

Phase 5 does not involve renaming or refactoring. However, the `model_config` table already has live data (any tenant that enabled `hash_chain_enabled`). The V28 migration must use `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` with nullable defaults to avoid breaking existing rows.

| Category | Items Found | Action Required |
|----------|-------------|-----------------|
| Stored data | `model_config` table — existing rows with `hash_chain_enabled` | V28 migration: `ALTER TABLE model_config ADD COLUMN IF NOT EXISTS retention_days INT NULL, ADD COLUMN IF NOT EXISTS snapshot_boundary TIMESTAMPTZ NULL` — safe additive change |
| Live service config | Docker Compose: no new services needed (Prometheus scraping is external per CONTEXT specifics) | No Docker Compose changes needed |
| OS-registered state | None — no systemd units, scheduled tasks, or pm2 processes reference Phase 5 entities | None |
| Secrets/env vars | No new secrets introduced; circlead connector bearer token follows existing Vault pattern via `credentials_ref` in `MappingDefinition` | None (Vault path pattern is established) |
| Build artifacts | `fabric-app/pom.xml` missing `micrometer-registry-prometheus` and `micrometer-tracing-bridge-otel` | Add to `fabric-app/pom.xml` dependencies; no stale artifacts |

---

## Circlead Entity Mapping (Research Finding)

The mapping document (CIRC-02) must cover these verified entity fields:

### Role → Tessera node type `role`
| Circlead Field | Source Class | Tessera Property | Type | Notes |
|----------------|-------------|-----------------|------|-------|
| `getTitle()` | `DefaultWorkitem` | `title` | String | Identity field |
| `getId()` | `DefaultWorkitem` | `circlead_id` | String | Source identity (title-based in circlead) |
| `getPurpose()` | `Role.@Parameter(PURPOSE)` | `purpose` | String | |
| `getResponsibilities()` | `Role.@Parameter(RESPONSIBILITIES)` | `responsibilities` | String[] | JSON array |
| `getPersonsDetails()` | `Role.@Parameter(PERSONS)` | `assigned_persons` | String[] | Person names/IDs |
| `getStatus()` | `DefaultWorkitem` | `status` | String | WorkitemStatus enum value |
| `getAbbreviation()` | `DefaultWorkitem` | `abbreviation` | String | |
| `getType().getName()` | `Role.getType()` = `WorkitemType.ROLE` | `_type` | String | Always "role" |
| `getRolegroupIdentifier()` | `Role.@Parameter(ROLEGROUP)` | Edge → Rolegroup | Edge | BELONGS_TO edge |

[VERIFIED: circlead-sunray source at `/Users/matthiaswegner/Programmming/IDE/IdeaProjects/circlead-sunray/circlead-core-api/src/main/java/org/rogatio/circlead/sunray/core/api/model/workitem/Role.java`]

### Circle → Tessera node type `circle`
| Circlead Field | Tessera Property | Notes |
|----------------|-----------------|-------|
| `getTitle()` | `title` | Identity field |
| `getId()` | `circlead_id` | |
| `getAbbreviation()` | `abbreviation` | |
| `getStatus()` | `status` | |
| `getType()` = `WorkitemType.CIRCLE` | `_type` = "circle" | |
| Circle entries (role assignments) | Edges: ASSIGNED → Role nodes | Many circle entries per circle |

[VERIFIED: circlead-sunray source at `.../workitem/Circle.java`]

### Activity → Tessera node type `activity`
| Circlead Field | Tessera Property | Notes |
|----------------|-----------------|-------|
| `getTitle()` | `title` | Identity field |
| `getId()` | `circlead_id` | Activity ID (`@Parameter(ACTIVITYID)`) |
| `getAbbreviation()` | `abbreviation` | |
| `getStatus()` | `status` | |
| `getResponsibleIdentifier()` | Edge: RESPONSIBLE_FOR → Role | `@Parameter(RESPONSIBLE)` |
| `getSubactivities()` | Edge: CONTAINS → Activity | Sub-activity hierarchy |
| `getType()` = `WorkitemType.ACTIVITY` | `_type` = "activity" | |

[VERIFIED: circlead-sunray source at `.../workitem/Activity.java`]

### Circlead REST API Shape (verified from `WorkitemService.java`)
- Base path: `/circlead/workitem`
- List endpoint: `GET /circlead/workitem/list?type=ROLE&details=true`
- Response wrapper: `RestEnvelope` with `{ content: [...], status: 200, message: "OK" }`
- Framework: JAX-RS/CXF (not Spring MVC) — accepts standard HTTP JSON
- Auth: **ASSUMED** — the `WorkitemService.java` source does not show auth annotations on the list endpoint; there may be a filter upstream. The WireMock stub does not need to replicate auth.

[VERIFIED: source at `.../circlead-rest/circlead-rest-api/src/main/java/org/rogatio/circlead/sunray/rest/service/WorkitemService.java`]
[ASSUMED: circlead REST auth mechanism — verify with circlead team before production deployment]

---

## Common Pitfalls

### Pitfall 1: Prometheus Endpoint Not Exposed
**What goes wrong:** Adding `micrometer-registry-prometheus` but forgetting to add `prometheus` to `management.endpoints.web.exposure.include`. Scraping returns 404.
**Why it happens:** Spring Boot actuator defaults `include: health,info` — as seen in the current `application.yml`.
**How to avoid:** Update `application.yml` to `include: health,info,prometheus` (or `"*"` for dev). The prod profile should add `prometheus` selectively.
**Warning signs:** `/actuator` shows no `prometheus` link in the discovery response.

### Pitfall 2: Snapshot Compaction Holding Write Path
**What goes wrong:** Running snapshot compaction in the same transaction as the entity read + snapshot event write. Long-running compaction holds row locks on `graph_events`, blocking ingest.
**Why it happens:** Naive single-transaction approach.
**How to avoid:** Three separate transactions: (1) read entity states, (2) write snapshot events + update `snapshot_boundary`, (3) delete pre-boundary events. Each transaction is short. Step 3 is idempotent and safe to retry.
**Warning signs:** Actuator shows Hikari pool saturation during snapshot.

### Pitfall 3: Retention Job Deleting Snapshot Events
**What goes wrong:** `EventRetentionJob` deletes all events older than `retention_days`, including the snapshot events themselves. Temporal replay breaks.
**Why it happens:** Simple `DELETE WHERE event_time < threshold` without checking `snapshot_boundary`.
**How to avoid:** Add `AND event_time >= COALESCE((SELECT snapshot_boundary FROM model_config WHERE model_id = :model), '-infinity'::timestamptz)` to the retention DELETE.
**Warning signs:** Temporal replay of any entity returns empty result after retention sweep.

### Pitfall 4: MeterRegistry Injection in fabric-core or fabric-rules
**What goes wrong:** Injecting `MeterRegistry` into classes in `fabric-core` or `fabric-rules` without the actuator dependency in those modules. Compile-time failure.
**Why it happens:** `spring-boot-starter-actuator` is only in `fabric-app` and `fabric-projections` (confirmed by codebase scan). `WriteRateCircuitBreaker` in `fabric-rules` already has Micrometer — check if `micrometer-core` is a direct dependency or transitive.
**How to avoid:** Add `micrometer-core` to the module POM if needed (it is already transitively present via `spring-boot-starter-actuator` in `fabric-projections`). For `fabric-app`-level metrics beans, co-locate in `fabric-app`.
**Warning signs:** `ClassNotFoundException: io.micrometer.core.instrument.MeterRegistry` at startup.

### Pitfall 5: DR Drill Sequence ID Collision After Restore
**What goes wrong:** After pg_restore, AGE per-label sequences are reset to 1. A fresh `GraphService.apply()` call against the restored DB collides on graphid with existing nodes.
**Why it happens:** `COPY FROM` restores data rows but does not advance sequences. This is documented in the existing `dump_restore_rehearsal.sh` as a known limitation.
**How to avoid:** The DR drill smoke test must be read-only. If write verification is added, include `SELECT setval(seq, max(id))` per label after restore. Document explicitly in `docs/DR-DRILL.md`.
**Warning signs:** `duplicate key value violates unique constraint` on first post-restore write.

### Pitfall 6: AGE Graph Health Indicator Returning DOWN on Init
**What goes wrong:** `AgeGraphHealthIndicator` queries `ag_catalog.ag_graph` at startup before any graph is created. Result is empty set — indicator misinterprets empty as DOWN.
**Why it happens:** The indicator was written expecting at least one graph to exist.
**How to avoid:** "No graphs registered" should be UP with `detail: no_graphs=true`, not DOWN. DOWN only when the `ag_catalog` schema is inaccessible (AGE not loaded).
**Warning signs:** `/actuator/health/ageGraph` shows DOWN on a fresh database.

### Pitfall 7: `management.endpoints.web.exposure.include` Overriding in Prod Profile
**What goes wrong:** `application-prod.yml` sets its own `management.endpoints` block without `prometheus` — it silently overrides the base `application.yml` addition.
**Why it happens:** Spring Boot's profile-specific YAML fully overrides (not merges) nested properties when both files define the same path.
**How to avoid:** Ensure `application-prod.yml` either includes `prometheus` explicitly or leaves the `management` block to the base config.
**Warning signs:** Prometheus scraper receives 404 in production only.

---

## Code Examples

### Prometheus Metric Exposed via Actuator

```yaml
# application.yml change required [VERIFIED: current application.yml shows include: health,info]
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  endpoint:
    health:
      show-details: when-authorized
    prometheus:
      access: unrestricted    # Allow Prometheus scraper without auth
```

### V28 Migration (Flyway)

```sql
-- V28__model_config_lifecycle.sql
-- Phase 5 / OPS-03 / OPS-04: event-log lifecycle columns for model_config
-- Additive ALTER TABLE — safe on existing rows (NULL defaults)
ALTER TABLE model_config
    ADD COLUMN IF NOT EXISTS retention_days INT NULL,
    ADD COLUMN IF NOT EXISTS snapshot_boundary TIMESTAMPTZ NULL;

COMMENT ON COLUMN model_config.retention_days IS
    'OPS-04: days to retain events; NULL means retain forever';
COMMENT ON COLUMN model_config.snapshot_boundary IS
    'OPS-03: earliest event_time still available via temporal replay; events below this were compacted';
```

### CircleadConnectorConfig (wires MappingDefinition)

```java
// Source: pattern from ConnectorAdminController + MappingDefinition [VERIFIED: codebase]
@Configuration
public class CircleadConnectorConfig {

    @Bean
    @ConfigurationProperties("tessera.connectors.circlead")
    public MappingDefinition circleadRoleMapping() {
        // Or loaded from classpath JSON: classpath:connectors/circlead-role-mapping.json
    }
}
```

### ConnectorHealthIndicator Pattern

```java
// Based on DebeziumSlotHealthIndicator [VERIFIED: codebase]
@Component("connectors")
public class ConnectorHealthIndicator extends AbstractHealthIndicator {
    private final ConnectorRegistry registry;
    
    @Override
    protected void doHealthCheck(Health.Builder builder) {
        Map<String, Object> statuses = new LinkedHashMap<>();
        boolean anyDown = false;
        for (ConnectorInstance ci : registry.list()) {
            String state = ci.lastSyncStatus().name();
            statuses.put(ci.connectorId(), state);
            if ("FAILED".equals(state)) anyDown = true;
        }
        builder.withDetails(statuses);
        if (anyDown) builder.down(); else builder.up();
    }
}
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Bootstrap context for Vault | `spring.config.import=vault://` (Config Data API) | Spring Boot 2.4 / Spring Cloud Vault 3.0 | Bootstrap context is deprecated; already handled in existing prod config |
| Micrometer Prometheus registry v1.x counter/gauge API | Micrometer 1.15.x (Spring Boot 3.5.x BOM) — same API, stable | No breaking change | No migration needed |
| Spring Retry 1.x | Spring Retry 2.x (Spring Boot 3.x) | Spring Boot 3 upgrade | If using Spring Retry for circlead circuit breaker, use `spring-retry` 2.x artifact (BOM-managed) |

**Deprecated/outdated:**
- `management.endpoints.web.exposure.include: "*"` in production: security anti-pattern; use explicit list.
- Spring Boot Actuator 2.x `HealthAggregator` API: replaced by `HealthContributorRegistry` and `AbstractHealthIndicator` in 3.x (already used in codebase).

---

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | `micrometer-tracing-bridge-otel` is BOM-managed at 3.5.13 Spring Boot version | Standard Stack | Planner would add wrong version; fix: `mvn dependency:resolve -Dincludes=io.micrometer:micrometer-tracing-bridge-otel` before adding |
| A2 | `opentelemetry-exporter-otlp` is BOM-managed (Spring Boot imports OTel BOM) | Standard Stack | May need explicit version; LOW impact since D-B3 only requires trace headers, not active export |
| A3 | Spring Retry is preferred over Resilience4j for circlead circuit breaker (Claude's discretion choice) | Standard Stack / Circlead Integration | If project owner prefers Resilience4j, tasks would add `io.github.resilience4j:resilience4j-spring-boot3` instead; both achieve D-A3 |
| A4 | Circlead REST API (`/circlead/workitem/list?type=ROLE&details=true`) returns a JSON list of Role dataitems in `RestEnvelope.content` when `details=true` | Circlead Entity Mapping | If the API shape differs from what `WorkitemService.java` source shows, the `MappingDefinition.rootPath` and JSONPath expressions would need adjustment; the WireMock stub should be built from actual response captures if possible |
| A5 | Circlead REST endpoint has no per-request auth (list endpoint in `WorkitemService.java` shows no auth annotation) | Circlead Entity Mapping | If auth is required (e.g., HTTP Basic or Bearer), the connector's `credentials_ref` Vault path must include the auth token; WireMock stub must also simulate auth challenge |

---

## Open Questions (ALL RESOLVED)

1. **Circlead REST API authentication mechanism** — RESOLVED: No auth annotation found on `WorkitemService` list endpoint. MappingDefinition uses `credentials_ref: null` (open endpoint). If auth is later discovered on the deployed instance, add credentials_ref pointing to a Vault path.

2. **Outbox lag metric source** — RESOLVED: Use both — a `Gauge` for count of PENDING rows (`tessera.outbox.lag.count`) and a `TimeGauge` for age of oldest PENDING row in seconds (`tessera.outbox.lag.age`). Both are passive DB-polled gauges.

3. **DR drill smoke test endpoint** — RESOLVED: CI job does DB-layer drill only (dump → restore → Flyway validate → psql data queries). Full API smoke test (GET /actuator/health + GET /api/v1/{model}/entities/role) runs in IONOS VPS manual drill per revised D-D1.

---

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Docker | DR drill, Testcontainers IT | ✓ | 27.4.0 | — |
| Maven 3.9 | Build | ✓ | 3.9.14 | — |
| Java 21 | Build + runtime | ✓ | OpenJDK 23 available; Java 21 LTS via Corretto in CI | — |
| `pg_dump` / `pg_restore` | DR drill | ✗ (not on host PATH) | — | Run inside Docker container (existing pattern in `dump_restore_rehearsal.sh`) |
| Prometheus server | OPS-01 scraping | ✗ (Tessera only exposes endpoint) | — | Tessera exposes `/actuator/prometheus`; Prometheus scraping is external to Tessera per CONTEXT specifics |

**Missing dependencies with no fallback:** None that block execution — `pg_dump` absence on host is already handled by the existing drill script pattern (runs inside Docker container).

**Missing dependencies with fallback:** Prometheus scraping server — not needed; Tessera's responsibility ends at exposing the endpoint.

---

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 (spring-boot-starter-test, Surefire 3.5.2) |
| Config file | Surefire config in parent `pom.xml` |
| Quick run command | `./mvnw -B -ntp -pl fabric-core,fabric-connectors,fabric-app test` |
| Full suite command | `./mvnw -B -ntp verify` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| CIRC-01 | Tessera REST/MCP projections return circlead entity types | Integration | `./mvnw -pl fabric-connectors test -Dtest=CircleadConnectorIT` | ❌ Wave 0 |
| CIRC-02 | Mapping round-trip: Role/Circle/Activity in → same shape out | Integration | `./mvnw -pl fabric-connectors test -Dtest=CircleadMappingIT` | ❌ Wave 0 |
| CIRC-03 | Circuit breaker opens when Tessera returns 503; circlead falls back | Unit | `./mvnw -pl fabric-connectors test -Dtest=CircleadCircuitBreakerTest` | ❌ Wave 0 |
| OPS-01 | `/actuator/prometheus` endpoint returns metric names including `tessera.ingest.rate` | Integration (smoke) | `./mvnw -pl fabric-app test -Dtest=PrometheusEndpointIT` | ❌ Wave 0 |
| OPS-02 | `/actuator/health` returns UP with `ageGraph` and `connectors` components | Integration | `./mvnw -pl fabric-app test -Dtest=HealthIndicatorIT` | ❌ Wave 0 |
| OPS-03 | Snapshot compaction: events pre-boundary deleted; post-boundary queryable; boundary recorded | Integration | `./mvnw -pl fabric-core test -Dtest=EventSnapshotIT` | ❌ Wave 0 |
| OPS-04 | Retention sweep: events older than `retention_days` deleted; snapshot-boundary events preserved | Integration | `./mvnw -pl fabric-core test -Dtest=EventRetentionJobIT` | ❌ Wave 0 |
| OPS-05 | DR drill script exits 0; Flyway validate passes; smoke test returns HTTP 200 | Integration (script) | `scripts/dr_drill.sh` | ❌ Wave 0 (extends existing) |

### Sampling Rate
- **Per task commit:** `./mvnw -B -ntp -pl fabric-core,fabric-connectors,fabric-app test`
- **Per wave merge:** `./mvnw -B -ntp verify`
- **Phase gate:** Full suite green before `/gsd-verify-work`

### Wave 0 Gaps
- [ ] `fabric-connectors/src/test/java/dev/tessera/connectors/circlead/CircleadConnectorIT.java` — covers CIRC-01, CIRC-02
- [ ] `fabric-connectors/src/test/java/dev/tessera/connectors/circlead/CircleadCircuitBreakerTest.java` — covers CIRC-03
- [ ] `fabric-app/src/test/java/dev/tessera/app/metrics/PrometheusEndpointIT.java` — covers OPS-01
- [ ] `fabric-app/src/test/java/dev/tessera/app/health/HealthIndicatorIT.java` — covers OPS-02
- [ ] `fabric-core/src/test/java/dev/tessera/core/events/EventSnapshotIT.java` — covers OPS-03
- [ ] `fabric-core/src/test/java/dev/tessera/core/events/EventRetentionJobIT.java` — covers OPS-04
- [ ] `scripts/dr_drill.sh` — extends existing `dump_restore_rehearsal.sh` for OPS-05

---

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | No | Tessera's existing JWT/OAuth2 covers auth |
| V3 Session Management | No | Stateless REST/MCP; no sessions |
| V4 Access Control | Yes — Prometheus endpoint | Restrict `/actuator/prometheus` to internal network or add `management.endpoint.prometheus.access` config; network-level protection recommended for IONOS VPS |
| V5 Input Validation | Yes — `POST /admin/events/snapshot?model_id` | Validate `model_id` is a valid UUID; fail with 400 on malformed input |
| V6 Cryptography | No | No new crypto in Phase 5 |

### Known Threat Patterns for Phase 5 Stack

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Prometheus endpoint exposes internal metric labels (tenant IDs in tags) | Information Disclosure | Do NOT tag metrics with full `model_id` UUID in Prometheus labels — use opaque short names or none; or restrict `/actuator/prometheus` to localhost only |
| Snapshot endpoint triggers large bulk delete if called repeatedly | Denial of Service | Idempotent by design (boundary check prevents double-snap); add `@RateLimiter` or admin-role guard |
| DR drill pg_dump writes dump file with database credentials in environment | Spoofing (credential exposure) | Use `PGPASSWORD` env var (already in existing script); never write password to disk; clean up temp files on exit (trap EXIT already present) |
| Circuit breaker open status cached in circlead — stale after Tessera recovery | Availability | Document circuit breaker reset interval (Spring Retry default: all attempts exhausted → no auto-reset; add `recover` method or scheduled reset probe |

---

## Sources

### Primary (HIGH confidence)
- Tessera codebase — `DebeziumSlotHealthIndicator.java`, `WriteRateCircuitBreaker.java`, `OutboxPoller.java`, `PartitionMaintenanceTask.java`, `GenericRestPollerConnector.java` — architectural patterns verified directly
- Tessera codebase — `fabric-app/pom.xml`, `pom.xml` (parent), `application.yml` — dependency versions and configuration verified
- Tessera codebase — `fabric-app/src/main/resources/db/migration/V27__tenant_hash_chain_config.sql` — `model_config` table structure verified
- Tessera codebase — `.github/workflows/ci.yml` — existing CI pipeline structure verified
- Tessera codebase — `scripts/dump_restore_rehearsal.sh` — existing DR drill script verified
- circlead-sunray source (local IDE workspace) — `Role.java`, `Circle.java`, `Activity.java`, `WorkitemService.java` — entity fields and REST API shape verified

### Secondary (MEDIUM confidence)
- Spring Boot 3.5.x Actuator documentation — Prometheus integration, health indicator patterns, management configuration
- Micrometer documentation — `Counter`, `Gauge`, `Timer` registration patterns consistent with existing codebase usage

### Tertiary (LOW confidence — see Assumptions Log)
- Circlead REST endpoint authentication mechanism — `WorkitemService.java` source inspected but no definitive auth annotations found; actual deployed config may differ (A4, A5)
- `micrometer-tracing-bridge-otel` BOM management version — assumed managed by Spring Boot 3.5.x BOM (A1)

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all libraries either verified in codebase or established by prior phases
- Circlead mapping: MEDIUM-HIGH — entity fields verified from local circlead-sunray source; REST API shape verified from `WorkitemService.java`; auth mechanism assumed open
- Architecture: HIGH — all patterns are direct derivations of verified codebase patterns
- Pitfalls: HIGH — derived from direct code reading (snapshot locking, partition-aware retention, health indicator edge cases)
- DR drill extension: HIGH — extends verified existing script

**Research date:** 2026-04-17
**Valid until:** 2026-05-17 (30 days; stack is stable)
