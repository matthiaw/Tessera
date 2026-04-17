---
phase: 04-sql-view-kafka-projections-hash-chained-audit
verified: 2026-04-17T10:15:00Z
status: human_needed
score: 8/8 must-haves verified
overrides_applied: 0
human_verification:
  - test: "Run `docker compose --profile kafka up -d` and confirm kafka, debezium-connect, and debezium-connector-init services all reach healthy status. Then publish an entity mutation via POST /api/v1/{model}/entities/{typeSlug} and confirm the event appears on topic tessera.{model_id}.{type_slug} via kafka-console-consumer."
    expected: "The Debezium Outbox Event Router reads graph_outbox CDC events from the WAL and delivers them to the correct per-tenant per-type Kafka topic without any write-path code change."
    why_human: "Cannot start Docker services or verify Kafka topic delivery programmatically in this environment. The configuration and code are wired correctly but end-to-end delivery requires a running Kafka + Debezium stack."
  - test: "Point a JDBC client (e.g. Metabase or psql) at one of the generated SQL views (v_{8-char-uuid}_{typeSlug}) and run a COUNT(*) or GROUP BY aggregate. Compare execution time vs an equivalent Cypher query."
    expected: "SQL aggregate runs measurably faster than Cypher equivalent because the view reads the AGE label table directly, bypassing the Cypher planner. View returns correct non-tombstoned rows scoped to the tenant."
    why_human: "Performance comparison requires a running database with seeded data. The view DDL and (properties::jsonb) cast are verified statically; actual BI-tool query execution needs a live environment."
---

# Phase 4: SQL View + Kafka Projections, Hash-Chained Audit Verification Report

**Phase Goal:** Add the two remaining projections — SQL views for BI tools and Kafka topics for downstream event fan-out via Debezium — plus optional hash-chained audit integrity for compliance-driven tenants. The write path must not change.
**Verified:** 2026-04-17T10:15:00Z
**Status:** human_needed
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|---------|
| 1 | Per-tenant per-type SQL views (`v_{model}_{typeSlug}`) are generated, queryable by JDBC clients, and regenerated on schema change and restart | VERIFIED | `SqlViewProjection` implements `ApplicationRunner`, calls `regenerateAll()` on startup; `regenerateForTenant()` queries `ag_catalog.ag_label` and executes `CREATE OR REPLACE VIEW` DDL; `SqlViewProjectionIT.viewCreatedForTenantNodeType()`, `viewIsReplacedAfterSchemaChange()`, `viewExcludesTombstonedEntities()` all enabled and substantive |
| 2 | Views bypass Cypher — they read AGE label tables directly using `(properties::jsonb)->>'key'` cast | VERIFIED | `SqlViewProjection.buildColumnExpression()` uses `(properties::jsonb)->>'key'` pattern throughout; `SqlViewProjectionIT.viewDdlUsesAgtypeToJsonbCast()` verifies the cast explicitly; no Cypher in view DDL path |
| 3 | Debezium 3.4 + Outbox Event Router SMT publishes one topic per `(model_id, typeSlug)` (tessera.{model_id}.{type_slug}) — write path unchanged | VERIFIED (code; Kafka delivery needs human) | `docker/debezium/connectors/tessera-outbox.json` configures `io.debezium.transforms.outbox.EventRouter` with `route.topic.replacement=tessera.${routedByValue}`; `GraphServiceImpl` passes `modelId + "." + typeSlug` as `aggregatetype` (line 194); `OutboxPoller` conditionalized via `@ConditionalOnProperty(tessera.kafka.enabled=false)`; Docker Compose adds kafka/debezium-connect/debezium-connector-init under `profiles: ["kafka"]` |
| 4 | Replication slot lag is monitored with configurable threshold; alerts fire before WAL bloat | VERIFIED | `DebeziumSlotHealthIndicator` extends `AbstractHealthIndicator`, queries `pg_wal_lsn_diff` for `tessera_outbox_slot`, fires DOWN when lag > `tessera.kafka.lag-threshold-bytes` (default 100MB); `DebeziumSlotHealthIndicatorTest` covers UP, DOWN, and slot-missing scenarios; `V26` sets `max_slot_wal_keep_size=2GB` |
| 5 | For hash-chain-enabled tenants, each event records SHA-256(prev_hash || payload) and the chain is tamper-evident | VERIFIED | `HashChain.java` implements `genesis()` and `compute(prevHash, payloadJson)` as pure static methods; `EventLog.appendWithHashChain()` queries `model_config.hash_chain_enabled`, uses per-tenant JVM `synchronized` lock + `FOR UPDATE` predecessor read, calls `HashChain.compute()`; `HashChainAppendIT` verifies enabled/disabled tenant behavior |
| 6 | Concurrent appends to a hash-chain-enabled tenant produce a valid chain | VERIFIED | Per-tenant JVM `synchronized` lock in `EventLog.appendWithHashChain()` serializes concurrent writers; `HashChainAppendIT.concurrentAppendsProduceNoNullPrevHash()` tests concurrency safety |
| 7 | On-demand verification detects any tampering and reports the first broken link | VERIFIED | `AuditVerificationService.verify()` streams `graph_events ORDER BY sequence_nr ASC` via `RowCallbackHandler`, recompacts JSONB via `recompactJson()`, compares stored vs recomputed hash; `HashChainVerifyIT` covers valid chain, tampered chain (direct DB update), and empty tenant |
| 8 | Verification is runnable in CI via REST endpoint | VERIFIED | `AuditVerificationController` exposes `POST /admin/audit/verify?model_id={uuid}` with JWT tenant check (403 on mismatch); documented CI usage: `curl -X POST /admin/audit/verify?model_id=<uuid>` against running instance |

**Score:** 8/8 truths verified (Kafka end-to-end delivery and SQL view query performance need human confirmation)

### Deferred Items

None identified. All Phase 4 items are addressed by Plans 01-03. Phase 5 addresses unrelated concerns (circlead integration, observability, DR drill).

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `fabric-app/src/main/resources/db/migration/V24__outbox_published_flag.sql` | `published BOOLEAN` on graph_outbox | VERIFIED | Contains `ALTER TABLE graph_outbox ADD COLUMN IF NOT EXISTS published BOOLEAN NOT NULL DEFAULT false` + partial index |
| `fabric-app/src/main/resources/db/migration/V25__hash_chain_audit.sql` | `prev_hash VARCHAR(64)` on graph_events | VERIFIED | Contains `ALTER TABLE graph_events ADD COLUMN IF NOT EXISTS prev_hash VARCHAR(64)` + DESC index |
| `fabric-app/src/main/resources/db/migration/V26__replication_slot_wal_limit.sql` | `max_slot_wal_keep_size` WAL cap | VERIFIED | `ALTER SYSTEM SET max_slot_wal_keep_size = '2GB'` |
| `fabric-app/src/main/resources/db/migration/V27__tenant_hash_chain_config.sql` | `hash_chain_enabled` per-tenant config | VERIFIED | Creates `model_config` table with `hash_chain_enabled BOOLEAN NOT NULL DEFAULT false` |
| `fabric-projections/src/main/java/dev/tessera/projections/sql/SqlViewProjection.java` | View generation + lifecycle management | VERIFIED | Implements `ApplicationRunner`, `regenerateAll()`, `regenerateForTenant()`, `listViews()`, staleness detection, 360 lines substantive |
| `fabric-projections/src/main/java/dev/tessera/projections/sql/SqlViewNameResolver.java` | 63-char Postgres identifier limit guard | VERIFIED | `resolve(UUID, String)` with truncation + 4-char hash suffix, null guards |
| `fabric-projections/src/main/java/dev/tessera/projections/sql/SqlViewAdminController.java` | `GET /admin/sql/views` endpoint | VERIFIED | JWT tenant isolation, delegates to `SqlViewProjection.listViews()` |
| `fabric-core/src/main/java/dev/tessera/core/events/HashChain.java` | Pure SHA-256 chain helper | VERIFIED | `genesis()`, `compute(prevHash, payloadJson)`, `GENESIS_INPUT = "TESSERA_GENESIS"`, no Spring annotations |
| `fabric-core/src/main/java/dev/tessera/core/audit/AuditVerificationService.java` | Sequential chain walk verification | VERIFIED | `RowCallbackHandler` streaming, `@Transactional(readOnly = true)`, `recompactJson()` JSONB normalization |
| `fabric-projections/src/main/java/dev/tessera/projections/audit/AuditVerificationController.java` | `POST /admin/audit/verify` endpoint | VERIFIED | JWT tenant check (403), returns `{valid, events_checked}` or broken-link details |
| `fabric-projections/src/main/java/dev/tessera/projections/kafka/DebeziumSlotHealthIndicator.java` | Actuator health for replication slot lag | VERIFIED | `@Component("debezium")`, `@ConditionalOnProperty(tessera.kafka.enabled=true)`, `pg_wal_lsn_diff` query |
| `docker-compose.yml` | Kafka (KRaft) + Debezium Connect + connector-init services | VERIFIED | Three services under `profiles: ["kafka"]`; `tessera-kafka-data` volume; healthchecks present |
| `docker/debezium/connectors/tessera-outbox.json` | Outbox Event Router SMT config | VERIFIED | `table.include.list=public.graph_outbox`, `transforms.outbox.type=io.debezium.transforms.outbox.EventRouter`, `route.topic.replacement=tessera.${routedByValue}` |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| `SqlViewProjection` | `SchemaRegistry.listNodeTypes()` | Spring DI | WIRED | Constructor-injected `SchemaRegistry`; `regenerateForTenant()` calls `schemaRegistry.listNodeTypes(ctx)` at line 124 |
| `SqlViewProjection` | `ag_catalog.ag_label` | JDBC query | WIRED | `resolveLabelTables()` queries `ag_catalog.ag_label JOIN ag_catalog.ag_graph` at line 306 |
| `SqlViewProjection` | `ApplicationRunner` | implements | WIRED | `SqlViewProjection implements ApplicationRunner`; `run()` calls `regenerateAll()` |
| `EventLog.append()` | `HashChain.compute()` | inline in `@Transactional` | WIRED | `appendWithHashChain()` calls `HashChain.compute(prevHash, payloadJson)` at line 353 |
| `EventLog.append()` | `model_config.hash_chain_enabled` | JDBC query + ConcurrentHashMap cache | WIRED | `hashChainEnabledCache.computeIfAbsent()` queries `SELECT hash_chain_enabled FROM model_config` at lines 92, 321 |
| `AuditVerificationService` | `graph_events ORDER BY sequence_nr` | RowCallbackHandler | WIRED | `EVENTS_SQL` contains `ORDER BY sequence_nr ASC`; verified at line 63 |
| `Debezium connector` | `graph_outbox table` | PostgreSQL WAL (pgoutput) | WIRED | `tessera-outbox.json` has `table.include.list=public.graph_outbox`, `plugin.name=pgoutput` |
| `DebeziumSlotHealthIndicator` | `pg_replication_slots` | JDBC | WIRED | `LAG_QUERY` uses `pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn) FROM pg_replication_slots WHERE slot_name = :slot` |
| `OutboxPoller` | `@ConditionalOnProperty` | Spring condition | WIRED | `@ConditionalOnProperty(name="tessera.kafka.enabled", havingValue="false", matchIfMissing=true)` at line 77-80 |
| `GraphServiceImpl.apply()` | Outbox aggregatetype | `modelId + "." + typeSlug` | WIRED | `GraphServiceImpl` line 194: `String aggregateType = effective.tenantContext().modelId() + "." + effective.type()` |

### Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | Status |
|----------|--------------|--------|--------------------|--------|
| `SqlViewProjection` | `labelMap` (AGE label tables) | `ag_catalog.ag_label` JDBC query | Yes — queries live Postgres catalog | FLOWING |
| `SqlViewProjection` | `types` (node types) | `SchemaRegistry.listNodeTypes(ctx)` | Yes — reads schema registry DB tables | FLOWING |
| `AuditVerificationService` | `storedPrevHash`, `pgPayload` | `graph_events` RowCallbackHandler stream | Yes — reads live event log rows | FLOWING |
| `DebeziumSlotHealthIndicator` | `lagBytes` | `pg_replication_slots` JDBC query | Yes — live Postgres system catalog | FLOWING |

### Behavioral Spot-Checks

Step 7b: SKIPPED for Kafka/Debezium stack (requires running Docker services). SQL view and audit code spot-checks are covered by the integration test suite (SqlViewProjectionIT, HashChainVerifyIT, AuditVerificationControllerIT).

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|------------|-------------|--------|---------|
| SQL-01 | 04-01-PLAN | Per-tenant per-type SQL views `v_{model}_{typeSlug}` readable by BI tools | SATISFIED | `SqlViewProjection.regenerateForTenant()` creates `CREATE OR REPLACE VIEW`; `SqlViewProjectionIT` covers view creation, column matching, tombstone exclusion |
| SQL-02 | 04-01-PLAN | Views bypass Cypher, read AGE label tables directly | SATISFIED | View DDL uses `FROM "{schema}"."{LabelTable}"` with `(properties::jsonb)` cast; `ag_catalog.ag_label` lookup in `resolveLabelTables()` |
| SQL-03 | 04-01-PLAN | View definitions regenerated on schema change and survive restart | SATISFIED | `ApplicationRunner.run()` calls `regenerateAll()` on startup; `SqlViewProjectionIT.viewIsReplacedAfterSchemaChange()` verifies schema-change replacement (supersedes disabled `SqlViewSchemaChangeIT` stub) |
| KAFKA-01 | 04-03-PLAN | Kafka topic per `(model_id, typeSlug)` — `tessera.{model_id}.{type_slug}` | SATISFIED | `tessera-outbox.json` routes via `aggregatetype` to `tessera.${routedByValue}`; `GraphServiceImpl` sets `aggregatetype = modelId + "." + typeSlug` |
| KAFKA-02 | 04-03-PLAN | Debezium Outbox Event Router SMT replaces in-process poller without write-path changes | SATISFIED | `OutboxPoller` `@ConditionalOnProperty` verified; `docker-compose.yml` Kafka profile confirmed; write path (`GraphService.apply` → `Outbox.append`) unchanged; `OutboxPollerConditionalIT` 3 tests pass |
| KAFKA-03 | 04-03-PLAN | Replication slot lifecycle monitored with `max_slot_wal_keep_size` and alerts on lag | SATISFIED | `V26` sets WAL cap; `DebeziumSlotHealthIndicator` monitors `tessera_outbox_slot` lag vs threshold |
| AUDIT-01 | 04-02-PLAN | Hash-chained audit log with SHA-256(prev_hash \|\| payload) per event | SATISFIED | `HashChain.compute()` + `EventLog.appendWithHashChain()` + `V25` `prev_hash` column + `V27` `model_config.hash_chain_enabled`; `HashChainAppendIT` verifies enabled/disabled/concurrent behavior |
| AUDIT-02 | 04-02-PLAN | Audit integrity verification job detectable tampering, runnable in CI | SATISFIED | `AuditVerificationService.verify()` + `AuditVerificationController POST /admin/audit/verify`; `HashChainVerifyIT` proves intact-chain valid=true + tampered-chain broken detection |

All 8 Phase 4 requirements are SATISFIED.

### Anti-Patterns Found

No blockers or stubs found in production code. One intentional `@Disabled` stub remains:

| File | Pattern | Severity | Impact |
|------|---------|----------|--------|
| `fabric-projections/src/test/.../sql/SqlViewSchemaChangeIT.java` | `@Disabled` Wave 0 stub | Info | Intentional — plan 04-01 superseded this stub via `SqlViewProjectionIT.viewIsReplacedAfterSchemaChange()`. The stub is a dead test class, not a production stub. Not a blocker. |

### Human Verification Required

**1. Kafka End-to-End Topic Delivery**

**Test:** Start the kafka-profile stack with `docker compose --profile kafka up -d`. Wait for all services to report healthy. Submit an entity mutation via the REST API. Use `kafka-console-consumer --topic tessera.{model_id}.{type_slug}` to confirm the event arrives on the correct topic.
**Expected:** The Debezium Outbox Event Router reads `graph_outbox` CDC events from the Postgres WAL and routes them to `tessera.{model_id}.{type_slug}`. The event payload is the `graph_outbox.payload` field expanded as JSON. The `OutboxPoller` bean must be absent from the application context when `tessera.kafka.enabled=true`.
**Why human:** Cannot start Docker services or verify Kafka topic delivery programmatically. The connector config and topic routing are correctly wired but the full end-to-end path requires a running Kafka + Debezium stack.

**2. SQL View BI-Tool Query Performance**

**Test:** With a seeded AGE database (at least 10k nodes of one type), point a JDBC client at `v_{model_id_prefix}_{typeSlug}` and run `SELECT COUNT(*), some_property FROM v_... GROUP BY some_property`. Compare the query plan and execution time against the equivalent Cypher aggregate via the REST/MCP API.
**Expected:** The SQL view delivers measurably faster aggregate query execution because it reads the AGE label table's Postgres heap directly, bypassing the Cypher planner and the ~15x aggregation cliff (CRIT-3). The view returns only the tenant's non-tombstoned rows.
**Why human:** Performance comparison requires a running database with representative data volume. Static code analysis confirms the AGE label table bypass but cannot measure query execution time.

### Gaps Summary

No gaps. All 8 requirements are implemented with substantive, wired code. The two human verification items are confirmation tests for behaviors that cannot be validated statically, not indicators of missing implementation.

The only remaining `@Disabled` file (`SqlViewSchemaChangeIT.java`) is an intentional wave-0 stub that was explicitly superseded by a passing test in `SqlViewProjectionIT`. It poses no risk to phase goal achievement.

---

_Verified: 2026-04-17T10:15:00Z_
_Verifier: Claude (gsd-verifier)_
