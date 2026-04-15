---
phase: 1
plan: 01-W2
wave: W2
subsystem: schema-registry + event-log + outbox
tags: [schema-registry, event-log, outbox, shedlock, flyway, shacl-feeder]
status: complete
completed: 2026-04-15
requires:
  - 01-W0 (plan contracts, Flyway V2..V9, test shells)
  - 01-W1 (GraphServiceImpl single-TX funnel, EventLog.append, Outbox.append, SequenceAllocator)
provides:
  - SchemaRegistry CRUD + event-sourced versioning + alias translation + Caffeine cache (SCHEMA-01..06, SCHEMA-08)
  - Event log partitioning, temporal replay, audit history, per-tenant sequences (EVENT-01, EVENT-02, EVENT-03, EVENT-06, EVENT-07)
  - Transactional outbox poller with ShedLock + FOR UPDATE SKIP LOCKED publishing GraphEventPublished (EVENT-04, EVENT-05)
affects:
  - fabric-core (schema, events, events.internal)
  - fabric-app (V4..V10 Flyway migrations, scanBasePackages widened)
tech-stack:
  added:
    - ShedLock 5.16 (shedlock-spring + shedlock-provider-jdbc-template) — wired via LockProviderConfig
    - Spring ApplicationEventPublisher for in-process GraphEventPublished fan-out
  patterns:
    - Event-sourced schema versioning (schema_change_event + schema_version snapshots)
    - Transactional outbox with polling delivery (Debezium-swap-ready)
    - JdbcTemplateLockProvider with DB time (usingDbTime)
key-files:
  created:
    - fabric-core/src/main/java/dev/tessera/core/events/GraphEventPublished.java
    - fabric-core/src/main/java/dev/tessera/core/events/OutboxPoller.java
    - fabric-core/src/main/java/dev/tessera/core/events/internal/LockProviderConfig.java
    - fabric-core/src/main/java/dev/tessera/core/events/internal/PartitionMaintenanceTask.java
    - fabric-core/src/main/java/dev/tessera/core/schema/* (SchemaRegistry + descriptors + cache)
    - fabric-app/src/main/resources/db/migration/V4__schema_registry.sql
    - fabric-app/src/main/resources/db/migration/V5__schema_versioning_and_aliases.sql
    - fabric-app/src/main/resources/db/migration/V6__source_authority.sql
    - fabric-app/src/main/resources/db/migration/V7__reconciliation_conflicts.sql
    - fabric-app/src/main/resources/db/migration/V8__connector_limits_and_dlq.sql
    - fabric-app/src/main/resources/db/migration/V9__reconciliation_rules.sql
    - fabric-app/src/main/resources/db/migration/V10__shedlock.sql
    - fabric-core/src/test/resources/db/migration/V10__shedlock.sql
  modified:
    - fabric-core/src/main/java/dev/tessera/core/events/EventLog.java (replayToState, history, EventRow)
    - fabric-core/src/main/java/dev/tessera/core/graph/internal/GraphServiceImpl.java (SchemaRegistry load on hot path)
    - fabric-app/src/main/java/dev/tessera/app/TesseraApplication.java (scanBasePackages=dev.tessera)
    - fabric-core/src/test/java/dev/tessera/core/support/FlywayItApplication.java (@EnableScheduling)
decisions:
  - Poll via @Scheduled(fixedDelay=500) with ShedLock + FOR UPDATE SKIP LOCKED; @TransactionalEventListener rejected to keep Phase-4 Debezium swap portable
  - LockProvider bean lives in dev.tessera.core.events.internal so both TesseraApplication and FlywayItApplication scans pick it up with no per-harness duplication
  - OutboxPoller uses its own @Transactional(REQUIRES_NEW) so poll-batch failures never leak into the main write TX; listener failures roll back the batch so rows are re-polled (at-least-once)
  - ShedLock uses usingDbTime() so multiple JVMs without synchronized clocks still lock correctly
metrics:
  tasks: 3
  duration_hours: null
  files_created: 12+
  files_modified: 4
---

# Phase 1 Plan 01-W2: Schema Registry + Event Log Hardening + Outbox Poller — Summary

Wave 2 lands the three load-bearing spines that Phase 2+ consumers will read
from: a queryable **Schema Registry** with event-sourced versioning, a
**hardened Event Log** with temporal replay and full provenance history, and a
**Transactional Outbox Poller** that publishes `GraphEventPublished` via Spring's
in-process event bus under ShedLock. All 15 Wave-2 acceptance criteria land
green and the full `./mvnw -B verify` completes in ~7 min with every Wave 0
and Wave 1 test still passing.

## One-liner

Wave 2 delivers the Schema Registry (SCHEMA-01..08), Event Log hardening with
temporal replay + audit history + partition maintenance (EVENT-01, EVENT-02,
EVENT-03, EVENT-06, EVENT-07), and the Transactional Outbox poller with
ShedLock + `FOR UPDATE SKIP LOCKED` publishing to `ApplicationEventPublisher`
(EVENT-04, EVENT-05).

## Task Breakdown

### Task 01-W2-01: Schema Registry CRUD + Versioning + Aliases + Cache

- Typed Postgres tables (`schema_node_types`, `schema_properties`,
  `schema_edge_types`) with JSONB flex columns per D-B1.
- Event-sourced schema versioning via `schema_change_event` +
  `schema_version` snapshots + `is_current` flag (D-B2).
- Property + edge alias translation tables (D-B3).
- Caffeine cache for `NodeTypeDescriptor` keyed by
  `(modelId, typeSlug, schemaVersion)` with rejection of cross-tenant
  descriptors (T-01-15 mitigation).
- Breaking-change detection with explicit `force=true` opt-in (SCHEMA-08).
- `GraphServiceImpl.apply` now calls `SchemaRegistry.loadFor` on the hot path
  (permissive — unregistered types are allowed; Wave 3 SHACL promotes to reject).
- Commit: `ee340ea`.
- Covers: **SCHEMA-01, SCHEMA-02, SCHEMA-03, SCHEMA-04, SCHEMA-05, SCHEMA-06, SCHEMA-08**.

### Task 01-W2-02: Event Log Hardening — Replay, Audit, Partition Maintenance

- `EventLog.replayToState(ctx, nodeUuid, Instant at)` folds events in
  `sequence_nr` order up to the given time (EVENT-06). Tombstones surface via
  `_tombstoned` sentinel in the returned map.
- `EventLog.history(ctx, nodeUuid)` returns the full `EventRow` list with
  provenance + payload + delta parsed back into maps (EVENT-07).
- `PartitionMaintenanceTask` (weekly cron, Sunday 02:00) idempotently creates
  monthly `graph_events_yYYYYmMM` partitions via plain SQL — no `pg_partman`
  dependency per RESEARCH Q2.
- `@EnableScheduling` added to `TesseraApplication` (main) and
  `FlywayItApplication` (test harness).
- ITs: `TemporalReplayIT`, `AuditHistoryIT`, `EventProvenanceIT`,
  `PerTenantSequenceIT`, `PartitionMaintenanceIT`.
- Commit: `0b6e0a6`.
- Covers: **EVENT-01, EVENT-02, EVENT-03, EVENT-06, EVENT-07**.

### Task 01-W2-03: Outbox Poller — @Scheduled + ShedLock + SKIP LOCKED + ApplicationEventPublisher

- `GraphEventPublished` record (Spring event payload): `modelId`, `eventId`,
  `aggregateType`, `aggregateId`, `type`, `payload`, `routingHints`,
  `deliveredAt`. Null-safe constructor normalizes missing maps to `Map.of()`.
- `OutboxPoller` — `@Scheduled(fixedDelay = 500)`,
  `@SchedulerLock(name="tessera-outbox-poller", lockAtMostFor="PT1M", lockAtLeastFor="PT0.1S")`,
  `@Transactional(REQUIRES_NEW)`. Polls up to 100 `PENDING` rows with
  `FOR UPDATE SKIP LOCKED`, publishes each, then marks delivered. Row mapper
  explicitly reads the `routing_hints` JSONB column and parses via Jackson
  (`Map<String,Object>`, null → empty). An internal `PolledRow(outboxId, event)`
  record keeps the public `GraphEventPublished` free of persistence-layer
  concerns while still allowing the poller to issue UPDATEs keyed by the
  outbox PK.
- `LockProviderConfig` in `dev.tessera.core.events.internal` carries
  `@EnableSchedulerLock(defaultLockAtMostFor="PT1M")` and a
  `JdbcTemplateLockProvider` using `usingDbTime()`. Lives in the main-classpath
  so both `TesseraApplication` (scanBasePackages widened to `dev.tessera`) and
  `FlywayItApplication` (already rooted at `dev.tessera.core`) pick it up
  without per-harness duplication.
- Flyway `V10__shedlock.sql` added in both `fabric-app/src/main/resources/db/migration`
  and `fabric-core/src/test/resources/db/migration` (duplicated per project
  convention from earlier waves).
- `OutboxTransactionalIT` — success path asserts 1 event + 1 outbox row on
  commit (same TX); failure path drives a TOMBSTONE against a nonexistent
  node, catches the `IllegalStateException`, asserts **zero rows** in both
  `graph_events` and `graph_outbox` (EVENT-04 atomicity).
- `OutboxPollerIT` — two tests via a test `@EventListener` → `Received`
  buffer bean:
  1. Apply 5 mutations, assert within 5 s the listener received 5 events,
     the outbox shows 5 `DELIVERED` rows and 0 `PENDING`, and the aggregate
     ids match the created nodes.
  2. Directly `INSERT` an outbox row with non-null `routing_hints`
     (`{"projection":"rest","topic":"people"}`), assert the listener's
     `GraphEventPublished.routingHints()` contains both entries — proves the
     JSONB column plumbs end-to-end through the row mapper to the published
     record (W8 acceptance criterion).
- Commit: `<filled by commit step>`.
- Covers: **EVENT-04, EVENT-05**.

## Requirement Coverage

| Requirement | Status | Evidence |
|-------------|--------|----------|
| SCHEMA-01..06, 08 | Green | `SchemaNodeTypeCrudIT`, `SchemaPropertyCrudIT`, `SchemaEdgeTypeCrudIT`, `SchemaVersioningReplayIT`, `SchemaAliasIT`, `SchemaCacheInvalidationTest`, `SchemaBreakingChangeIT` |
| SCHEMA-07 | **Wave 3** | `SchemaToShaclIT` remains `@Disabled` — feeding SHACL is a W3 responsibility |
| EVENT-01 | Green | `EventLogSchemaIT` (3 named indexes, partitioning) |
| EVENT-02 | Green | `PerTenantSequenceIT` (per-tenant Postgres SEQUENCE, CACHE 50) |
| EVENT-03 | Green | `EventProvenanceIT` (+ Wave-1 `EventProvenanceSmokeIT`) |
| EVENT-04 | **Green (this task)** | `OutboxTransactionalIT` — rollback atomicity asserted |
| EVENT-05 | **Green (this task)** | `OutboxPollerIT` — polling + publish + routing_hints round-trip |
| EVENT-06 | Green | `TemporalReplayIT` |
| EVENT-07 | Green | `AuditHistoryIT` |

Rule engine, SHACL, circuit breaker, and echo-loop suppression remain
Wave 3.

## Deviations from Plan

### [Rule 3 - Blocking] OutboxPoller cannot be `final`

- **Found during:** Task 3 first IT run
- **Issue:** Spring's `@Transactional` + `@SchedulerLock` AOP requires a
  CGLIB subclass; `public final class OutboxPoller` failed bean creation
  with `IllegalArgumentException: Cannot subclass final class`.
- **Fix:** Dropped `final` from the class declaration. Behavior unchanged.
- **Files modified:** `fabric-core/src/main/java/dev/tessera/core/events/OutboxPoller.java`

### [Rule 3 - Blocking] ShedLock `lockAtLeastFor` duration format

- **Found during:** Task 3 second IT run
- **Issue:** Plan spec used `lockAtLeastFor = "100ms"` / `"PT100MS"`;
  ShedLock 5.16 rejects both with
  `Invalid lockAtLeastForString value "PT100MS" - cannot parse into long nor duration`.
- **Fix:** Changed to canonical ISO-8601 `"PT0.1S"` (100 ms). Semantically
  identical.
- **Files modified:** `fabric-core/src/main/java/dev/tessera/core/events/OutboxPoller.java`

### [Rule 3 - Blocking] V10 Flyway migration in two locations

- **Found during:** Task 3 third IT run
- **Issue:** `fabric-core` ITs ship their own `src/test/resources/db/migration/`
  copy of each `Vn.sql` (established in Wave 0). A single V10 in
  `fabric-app/src/main/resources/db/migration/` was invisible to
  `FlywayItApplication`.
- **Fix:** Added identical `V10__shedlock.sql` to both locations. Matches the
  Wave-0 convention for every prior V2..V9 migration.
- **Files created:** `fabric-core/src/test/resources/db/migration/V10__shedlock.sql`

### [Rule 2 - Missing Critical Functionality] TesseraApplication scan root

- **Found during:** Task 3 wiring review
- **Issue:** `TesseraApplication` lives in `dev.tessera.app` and
  `@SpringBootApplication` defaults scan-base to its own package. The
  `LockProviderConfig` in `dev.tessera.core.events.internal` would not be
  picked up by the main app at runtime (only the test harness, which
  scans `dev.tessera.core`).
- **Fix:** Widened to `@SpringBootApplication(scanBasePackages = "dev.tessera")`
  so every fabric-module bean, including `LockProviderConfig`, is discovered.
- **Files modified:** `fabric-app/src/main/java/dev/tessera/app/TesseraApplication.java`

No other deviations. Rule engine, SHACL, circuit breaker all remain Wave 3
scope exactly as planned.

## Design Notes Worth Keeping

- **Poll vs @TransactionalEventListener.** `@TransactionalEventListener(phase=AFTER_COMMIT)`
  would be cheaper but couples delivery to the JVM that wrote the row and
  cannot survive a crash between commit and listener invocation. Polling is
  at-least-once and swap-compatible with Phase 4 Debezium — preferred.
- **At-least-once semantics.** The poll batch is a single TX. If any listener
  throws, the whole batch rolls back and every row is re-delivered next tick.
  `GraphEventPublished` Javadoc documents this; consumers must be idempotent.
- **FOR UPDATE SKIP LOCKED + ShedLock is belt-and-braces.** ShedLock prevents
  two JVMs from even attempting the poll simultaneously; `SKIP LOCKED` guards
  the single-JVM edge case where a slow tick overlaps the next schedule fire.
- **Internal `PolledRow` record.** Keeps `GraphEventPublished` free of the
  outbox PK (consumers should not know how the truth is stored) while still
  letting the poller issue targeted `UPDATE ... WHERE id = ?` statements after
  publish.

## Verification

```
./mvnw -B verify
# BUILD SUCCESS — 6 modules, ~7 min wall clock
```

Targeted Task 3 verification:

```
./mvnw -B -pl fabric-core -Dit.test='OutboxTransactionalIT,OutboxPollerIT' verify
# Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
```

Acceptance-criteria greps (all satisfied):

```
grep -q '@Scheduled(fixedDelay = 500)' fabric-core/src/main/java/dev/tessera/core/events/OutboxPoller.java
grep -q 'FOR UPDATE SKIP LOCKED'        fabric-core/src/main/java/dev/tessera/core/events/OutboxPoller.java
grep -q '@SchedulerLock'                fabric-core/src/main/java/dev/tessera/core/events/OutboxPoller.java
grep -q 'publishEvent'                  fabric-core/src/main/java/dev/tessera/core/events/OutboxPoller.java
grep -q 'routing_hints'                 fabric-core/src/main/java/dev/tessera/core/events/OutboxPoller.java
```

## Self-Check: PASSED

- Files present: `OutboxPoller.java`, `GraphEventPublished.java`,
  `LockProviderConfig.java`, both `V10__shedlock.sql` migrations, filled
  `OutboxTransactionalIT.java` + `OutboxPollerIT.java`.
- Acceptance greps: 5/5 green (verified above).
- `./mvnw -B verify`: BUILD SUCCESS across all six modules.
- No Wave 0 / Wave 1 / Wave 2 Task 1-2 regressions: all prior ITs still green
  (schema, event, tenant-bypass, raw-Cypher ban, partition, provenance).
- SCHEMA-07, VALID-*, RULE-*, Perf gates remain Wave 3 scope.
