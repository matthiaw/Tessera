# Phase 4: SQL View + Kafka Projections, Hash-Chained Audit — Research

**Researched:** 2026-04-17
**Domain:** SQL view generation from AGE label tables, Debezium CDC / Kafka outbox fan-out, hash-chained tamper-evident audit log, Spring Boot Actuator replication-slot monitoring
**Confidence:** HIGH (codebase verified + stack decisions pre-locked in CLAUDE.md/CONTEXT.md)

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**A. SQL View Generation**
- D-A1: Regular (non-materialized) views reading AGE label tables directly. DDL: `CREATE OR REPLACE VIEW v_{model}_{typeSlug} AS SELECT ...` with columns from `NodeTypeDescriptor.properties()`.
- D-A2: Views regenerated on `SchemaChangeEvent` via `@TransactionalEventListener` + on startup. Lives in `SqlViewProjection` service.
- D-A3: View code lives in `fabric-projections` under `dev.tessera.projections.sql`. No new Maven module.
- D-A4: Naming: `v_{model_id}_{type_slug}` (hyphens → underscores). Admin endpoint `GET /admin/sql/views` lists active views per tenant.

**B. Debezium/Kafka Integration**
- D-B1: Debezium 3.4 Outbox Event Router SMT (`io.debezium.transforms.outbox.EventRouter`). Existing `graph_outbox` table. Topics: `tessera.{model_id}.{type_slug}`.
- D-B2: Debezium as external Docker container (`debezium/connect:3.4`), not embedded in Tessera JVM.
- D-B3: Existing `OutboxPoller` retained as fallback. `tessera.kafka.enabled=true` flag; `@ConditionalOnProperty` disables `OutboxPoller` when Kafka enabled.
- D-B4: Flyway migration adds `graph_outbox.published` boolean (default false). Debezium marks published. Only acceptable write-path modification.
- D-B5: Docker Compose adds `kafka`, KRaft single-node or ZooKeeper, `debezium-connect`. Tessera depends on `kafka` only when enabled.

**C. Hash-Chained Audit**
- D-C1: Opt-in per tenant via `hash_chain_enabled` boolean on model configuration (Flyway migration).
- D-C2: Hash computed inline in `EventLog.append()`, same transaction. Formula: `SHA-256(prev_hash || event_payload_json)`. Genesis value: `SHA-256("TESSERA_GENESIS")`. Reads previous hash with `SELECT prev_hash ... FOR UPDATE`.
- D-C3: Verification endpoint `POST /admin/audit/verify?model_id={id}` returns `{valid, events_checked}` or `{valid: false, broken_at_seq, expected_hash, actual_hash}`. Also runnable as CLI/CI.
- D-C4: Flyway migration adds `prev_hash VARCHAR(64)` to `graph_events` (nullable for non-chained tenants).

**D. Monitoring & Observability**
- D-D1: Replication slot lag as Spring Boot Actuator health indicator (`/actuator/health/debezium`). Queries `pg_stat_replication`. DOWN when lag exceeds configurable threshold (default 100MB WAL).
- D-D2: Flyway migration sets `max_slot_wal_keep_size` (e.g., 2GB) via `ALTER SYSTEM`. WAL usage percentage in health indicator.
- D-D3: SQL view staleness detected via schema version comment in DDL vs `SchemaVersionService`. Stale views auto-regenerated; logged as warning.

### Claude's Discretion
- Kafka topic partitioning strategy (single partition per type fine for MVP)
- Debezium connector configuration details (slot name, publication name, heartbeat interval)
- KRaft vs ZooKeeper for Kafka in Docker Compose (KRaft preferred for simplicity)
- Hash chain batch verification parallelism (sequential fine for MVP)
- SQL view column ordering and type mapping details

### Deferred Ideas (OUT OF SCOPE)
- Kafka Schema Registry (Avro/Protobuf) — JSON payloads sufficient
- Dead letter topic for failed Debezium transformations
- GraphQL projection (Phase 5+)
- Real-time SQL view refresh notifications to BI tools
- Hash chain verification across multiple tenants in batch
</user_constraints>

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| SQL-01 | Per-tenant per-type SQL views `v_{model}_{typeSlug}` readable by Metabase/Looker/PowerBI | AGE label table structure verified: `properties` column is agtype/JSONB; `->>` operator extracts text. `CREATE OR REPLACE VIEW` DDL pattern confirmed. |
| SQL-02 | Views bypass Cypher for aggregate queries, reading AGE label tables directly — mitigates ~15× aggregation cliff (CRIT-3) | AGE label tables are regular PG tables in the graph's namespace. SQL views over them are pure PostgreSQL; no Cypher involved. |
| SQL-03 | View definitions regenerated on schema change and survive restart | `SchemaChangeReplayer`/`SchemaDescriptorCache` event infrastructure exists. `@TransactionalEventListener` + startup `ApplicationRunner` covers both triggers. |
| KAFKA-01 | Kafka topic per `(model_id, typeSlug)` — `fabric.{model}.{typeSlug}.events` | Decision D-B1 uses `tessera.{model_id}.{type_slug}` (CONTEXT.md). Confirmed Debezium SMT `route.topic.replacement` config supports arbitrary topic naming. |
| KAFKA-02 | Debezium 3.4 + Outbox Event Router SMT replaces in-process outbox poller without changing write path | `graph_outbox` table already has Debezium-compatible shape (aggregatetype, aggregateid, type, payload — verified in V3 migration + Outbox.java). Only addition: `published` boolean column. |
| KAFKA-03 | Replication slot lifecycle monitored with `max_slot_wal_keep_size` and alerts on lag | `pg_stat_replication` view and `pg_replication_slots` available. `AbstractHealthIndicator` pattern confirmed for Spring Boot Actuator. |
| AUDIT-01 | Hash-chained audit log — SHA-256(prev_hash \|\| payload); opt-in per tenant; GxP/SOX/BSI C5 compliance | `EventLog.append()` already has clean extension point. `MessageDigest.getInstance("SHA-256")` is JDK stdlib (no extra dependency). `graph_events` is append-only partitioned table. |
| AUDIT-02 | Audit integrity verification job runnable on demand and in CI | Sequential walk of `graph_events ORDER BY sequence_nr` per `model_id`. O(n) SHA-256 ops. Endpoint + Maven exec plugin runner pattern. |
</phase_requirements>

---

## Summary

Phase 4 adds three independent, additive features to Tessera — SQL views, Kafka/Debezium CDC fan-out, and hash-chained audit — none of which touch the existing write path beyond a narrow `published` column addition to `graph_outbox`.

The SQL view projection is the simplest of the three. Apache AGE stores each vertex label in its own PostgreSQL table (`{graph_namespace}."{LabelName}"`) with exactly two columns: `id` (graphid) and `properties` (agtype, which is a JSONB superset). A view DDL of `SELECT properties->>'uuid' AS uuid, properties->>'model_id' AS model_id, ... FROM {graph}."{TypeLabel}" WHERE (properties->>'model_id')::uuid = '{tenant_uuid}'` creates a fully relational projection that BI tools see as a normal table. The `SqlViewProjection` service generates and replaces these views whenever the schema changes via the existing `SchemaDescriptorCache` invalidation event path.

The Kafka integration replaces the in-process `OutboxPoller` with Debezium 3.4 CDC. The `graph_outbox` table was deliberately designed (V3 migration) with the Debezium Outbox Event Router SMT column shape already in place: `aggregatetype`, `aggregateid`, `type`, `payload`. The only required schema addition is a `published` boolean column. Debezium runs as an external container (not embedded), connected to a KRaft-mode Kafka broker; the Spring app retains `OutboxPoller` as a `@ConditionalOnProperty` fallback for development without Docker Compose.

The hash-chained audit is the most security-critical piece. SHA-256 is the correct choice (MD5/SHA-1 are disqualified for compliance use cases). The chain must be computed synchronously within the same transaction as the event write, reading the previous hash with `FOR UPDATE` to prevent races in concurrent appends. Because `graph_events` is partitioned, the `FOR UPDATE SKIP LOCKED` pattern used by `OutboxPoller` must NOT be reused here — the hash chain requires strict ordering and must not be skipped.

**Primary recommendation:** Implement in this wave order — Wave 0 (Flyway migrations), Wave 1 (SQL view generation), Wave 2 (hash chain in EventLog), Wave 3 (Debezium + Kafka Docker Compose + OutboxPoller conditionalization), Wave 4 (monitoring + verification endpoint + integration tests). Kafka last because it requires external Docker services; all other features are self-contained.

---

## Standard Stack

### Core (all pre-locked in CLAUDE.md and parent POM)

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Spring Boot | 3.5.13 | App framework | Locked decision; parent POM already at this version [VERIFIED: pom.xml] |
| PostgreSQL JDBC | 42.7.5 | DB driver for SQL views, hash chain queries | Already in parent POM dependencyManagement [VERIFIED: pom.xml] |
| Spring Boot Actuator | 3.5.13 | Health indicators for replication slot lag | Part of Spring Boot BOM; already a starter dependency pattern used in project [VERIFIED: CLAUDE.md] |
| JDK `MessageDigest` | JDK 21 (stdlib) | SHA-256 hash chain computation | Zero-dependency; `MessageDigest.getInstance("SHA-256")` is standard JDK [ASSUMED] |
| Flyway 10.x | via Spring Boot BOM | Migrations V24–V27 | Already in use (V1–V23 present) [VERIFIED: fabric-app/src/main/resources/db/migration/] |

### New Dependencies Needed

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `org.testcontainers:kafka` | 1.20.4 (matches BOM) | KafkaContainer for Kafka ITs | Integration tests for Debezium/Kafka projection only [VERIFIED: testcontainers-bom in pom.xml; module existence CITED: java.testcontainers.org/modules/kafka/] |
| `debezium/connect` Docker image | `3.4` (quay.io/debezium/connect:3.4) | Kafka Connect + Debezium PostgreSQL connector in Docker Compose | External container only — not a Maven dep [CITED: hub.docker.com/r/debezium/connect] |
| Kafka (KRaft) Docker image | `bitnami/kafka:3.9` or `apache/kafka:3.9.0` | Kafka broker in Docker Compose | External container only [VERIFIED: kafka-3.9.0 present at ~/Programmming/Services/kafka-3.9.0] |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Regular views | Materialized views | Refresh complexity, stale reads — deferred per D-A1 |
| External Debezium container | Embedded Debezium (debezium-embedded) | Classpath conflicts with Spring Boot, harder lifecycle management — ruled out in D-B2 |
| JDK `MessageDigest` SHA-256 | Bouncy Castle | Zero extra dependency wins; Bouncy Castle only needed for FIPS-validated libs, which is out of scope here |
| KRaft single-node Kafka | ZooKeeper + Kafka | KRaft is the current standard (ZK removed in Kafka 4.0); simpler compose file [CITED: medium.com/@kinneko-de/kafka-4-kraft-docker-compose] |

**No new Maven module.** All Phase 4 code lives in existing modules: SQL view service in `fabric-projections`, hash chain modifications in `fabric-core`, Flyway migrations in `fabric-app`.

---

## Architecture Patterns

### Recommended Project Structure (additions only)

```
fabric-projections/src/main/java/dev/tessera/projections/
├── sql/
│   ├── SqlViewProjection.java          # generates + replaces views per tenant
│   ├── SqlViewNameResolver.java        # v_{model}_{typeSlug} naming util
│   └── SqlViewAdminController.java     # GET /admin/sql/views
fabric-core/src/main/java/dev/tessera/core/
├── events/
│   ├── HashChain.java                  # SHA-256 chain computation helper
│   └── EventLog.java                   # modified: prev_hash computation in append()
├── audit/
│   └── AuditVerificationService.java   # chain walk + verification logic
fabric-projections/src/main/java/dev/tessera/projections/
└── audit/
    └── AuditVerificationController.java # POST /admin/audit/verify

fabric-app/src/main/resources/db/migration/
├── V24__outbox_published_flag.sql       # adds published col to graph_outbox
├── V25__hash_chain_audit.sql            # adds prev_hash + hash_chain_enabled
├── V26__replication_slot_wal_limit.sql  # ALTER SYSTEM max_slot_wal_keep_size
└── V27__tenant_hash_chain_config.sql    # model config table or column

docker-compose.yml  (modified)           # adds kafka + debezium-connect services
docker/debezium/
└── connectors/tessera-outbox.json       # Debezium connector registration payload
```

### Pattern 1: SQL View Generation from AGE Label Tables

**What:** `SqlViewProjection` queries `SchemaRegistry.listNodeTypes(ctx)` and for each type generates DDL using the AGE internal table path.

**When to use:** On `SchemaChangeEvent` (via `@TransactionalEventListener`) and on application startup.

**AGE internal table path:**
```sql
-- AGE stores each vertex label as a table in the graph's namespace.
-- Graph namespace is the graph name (Tessera uses model_id as graph name).
-- Table name is the label (= type slug with capital first letter, or exact slug).
-- The properties column is agtype (JSONB superset); use ->> to extract as text.
-- Source: [VERIFIED: matheusfarias03.github.io/AGE-quick-guide + AGE GitHub discussions/652]

SELECT properties->>'uuid' AS uuid,
       properties->>'model_id' AS model_id,
       properties->>'name' AS name,
       ...
FROM   {graph_schema}."{TypeLabel}"
WHERE  (properties->>'model_id')::uuid = '{tenant_uuid}'::uuid
```

**DDL generation pattern:**
```java
// Source: [ASSUMED — established Java string formatting pattern]
// In SqlViewProjection.java
String viewName = "v_" + modelSlug + "_" + typeDescriptor.slug().replace("-", "_");
String columnList = typeDescriptor.properties().stream()
    .filter(p -> p.deprecatedAt() == null)
    .map(p -> "properties->>'%s' AS %s".formatted(p.slug(), safeSqlIdentifier(p.slug())))
    .collect(Collectors.joining(",\n    "));

String ddl = """
    CREATE OR REPLACE VIEW %s AS
    /* schema_version:%d model_id:%s type:%s */
    SELECT
        (properties->>'uuid')::uuid AS uuid,
        (properties->>'model_id')::uuid AS model_id,
        %s
    FROM %s."%s"
    WHERE (properties->>'model_id')::uuid = '%s'::uuid
        AND COALESCE(properties->>'_tombstoned', 'false')::boolean = false
    """.formatted(viewName, descriptor.schemaVersion(), modelId, type, columnList, graphSchema, labelName, tenantId);
```

**Critical detail — AGE graph namespace:** The AGE graph namespace (schema) for a given graph is created by `ag_catalog.create_graph(graph_name)`. Tessera uses a single fixed graph named `tessera_main` (defined in `GraphSession.GRAPH_NAME` and created by `V1__enable_age.sql`). It is NOT per-tenant — all tenants share this graph, with `model_id` filtering at the Cypher/SQL level. The SQL view must reference the `tessera_main` schema: `FROM tessera_main."LabelName"`. RESOLVED: verified via `GraphSession.java` line 58: `public static final String GRAPH_NAME = "tessera_main";`

**Staleness detection:**
```sql
-- Compare comment-embedded schema version in the view DDL vs current version
SELECT pg_get_viewdef('v_acme_person'::regclass, true);
-- Parse '/* schema_version:7 ...' from output
```

### Pattern 2: Debezium Outbox Event Router SMT

**What:** Debezium reads INSERT events from `graph_outbox`, transforms them via the Outbox Event Router SMT, and routes to Kafka topics. Spring app's `OutboxPoller` is disabled when `tessera.kafka.enabled=true`.

**`OutboxPoller` conditionalization (no change to class, just bean condition):**
```java
// Source: [ASSUMED — existing @ConditionalOnProperty pattern in codebase]
// On the OutboxPoller @Component or in a @Configuration class:
@ConditionalOnProperty(name = "tessera.kafka.enabled", havingValue = "false", matchIfMissing = true)
```

**Debezium connector JSON registration (POST to Kafka Connect REST API):**
```json
// Source: [CITED: debezium.io/documentation/reference/stable/connectors/postgresql.html]
// [CITED: debezium.io/documentation/reference/stable/transformations/outbox-event-router.html]
{
  "name": "tessera-outbox-connector",
  "config": {
    "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
    "database.hostname": "postgres-age",
    "database.port": "5432",
    "database.user": "tessera",
    "database.password": "tessera",
    "database.dbname": "tessera",
    "database.server.name": "tessera",
    "topic.prefix": "tessera",
    "plugin.name": "pgoutput",
    "slot.name": "tessera_outbox_slot",
    "publication.name": "tessera_outbox_pub",
    "publication.autocreate.mode": "filtered",
    "table.include.list": "public.graph_outbox",
    "transforms": "outbox",
    "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
    "transforms.outbox.table.field.event.id": "id",
    "transforms.outbox.table.field.event.key": "aggregateid",
    "transforms.outbox.table.field.event.payload": "payload",
    "transforms.outbox.table.field.event.payload.id": "id",
    "transforms.outbox.table.field.event.timestamp": "created_at",
    "transforms.outbox.route.by.field": "aggregatetype",
    "transforms.outbox.route.topic.replacement": "tessera.${routedByValue}.${routedByValue}",
    "transforms.outbox.table.fields.additional.placement": "type:header:eventType,model_id:header:modelId",
    "transforms.outbox.table.expand.json.payload": true
  }
}
```

**Note on topic naming:** Decision D-B1 uses `tessera.{model_id}.{type_slug}`. The `aggregatetype` field in `graph_outbox` currently carries the entity type slug (see `Outbox.java` — `aggregatetype` parameter). To route by `model_id + type_slug`, either: (a) populate `aggregatetype` as `{model_id}.{type_slug}` at write time (write path change, undesirable), or (b) use `routing_hints` JSONB column with a custom SMT predicate. The cleanest approach is (a) with `aggregatetype` = `{model_id}-{type_slug}` and `route.topic.replacement` = `tessera.${routedByValue}` which becomes `tessera.acme-person`. The planner must decide.

### Pattern 3: Hash Chain in EventLog

**What:** `EventLog.append()` reads the previous event's `prev_hash` with `FOR UPDATE`, computes new hash, stores it. Gated by per-tenant `hash_chain_enabled`.

**Implementation sketch:**
```java
// Source: [ASSUMED — JDK SHA-256 + existing EventLog.append() pattern]
// In EventLog.append(), after tenancy check but before INSERT:
if (isHashChainEnabled(ctx)) {
    String prevHash = jdbc.queryForObject(
        """
        SELECT prev_hash FROM graph_events
        WHERE model_id = :mid::uuid
        ORDER BY sequence_nr DESC LIMIT 1
        FOR UPDATE
        """,
        Map.of("mid", ctx.modelId().toString()),
        String.class);
    String genesis = sha256("TESSERA_GENESIS");
    String prev = prevHash != null ? prevHash : genesis;
    String newHash = sha256(prev + payloadJson);
    // include newHash in the INSERT statement
}

private static String sha256(String input) {
    try {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);  // JDK 17+ HexFormat
    } catch (NoSuchAlgorithmException e) {
        throw new IllegalStateException("SHA-256 not available", e);
    }
}
```

**Partitioned table `FOR UPDATE` caveat:** `graph_events` is a partitioned table. `FOR UPDATE` on partitioned tables works in PostgreSQL 14+ but requires specifying the partition or using `FOR UPDATE OF table`. The query must include `event_time` in the WHERE clause to target the correct partition, OR use a non-partitioned index scan. The simplest safe approach: include `event_time > now() - interval '90 days'` to limit partition scan, with a fallback to all partitions. [ASSUMED — verify against PG16 partitioned table FOR UPDATE behavior]

### Pattern 4: Actuator Health Indicator for Debezium Lag

**What:** `DebeziumSlotHealthIndicator extends AbstractHealthIndicator` queries `pg_stat_replication` / `pg_replication_slots` for the `tessera_outbox_slot` slot lag.

```java
// Source: [CITED: baeldung.com/spring-boot-health-indicators]
// [CITED: postgresql.org/docs/current/runtime-config-replication.html]
@Component
@ConditionalOnProperty(name = "tessera.kafka.enabled", havingValue = "true")
public class DebeziumSlotHealthIndicator extends AbstractHealthIndicator {

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        Long lagBytes = jdbc.queryForObject("""
            SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn)
            FROM pg_replication_slots
            WHERE slot_name = 'tessera_outbox_slot'
            """, Long.class);
        if (lagBytes == null) {
            builder.down().withDetail("reason", "slot not found").build();
            return;
        }
        long thresholdBytes = 100L * 1024 * 1024; // 100MB default
        if (lagBytes > thresholdBytes) {
            builder.down().withDetail("lag_bytes", lagBytes).build();
        } else {
            builder.up().withDetail("lag_bytes", lagBytes).build();
        }
    }
}
```

**`max_slot_wal_keep_size` Flyway migration:**
```sql
-- V26__replication_slot_wal_limit.sql
-- Sets an upper bound on WAL kept for replication slots.
-- Prevents unbounded disk growth if Debezium falls behind.
-- Source: [CITED: postgresql.org/docs/current/runtime-config-replication.html]
ALTER SYSTEM SET max_slot_wal_keep_size = '2GB';
SELECT pg_reload_conf();
```

**Important:** `ALTER SYSTEM` requires PostgreSQL SUPERUSER or `pg_ctl` access. The Tessera DB user in Docker Compose must be granted appropriate privilege, or this must be done via `postgresql.conf` in the Docker image. The planner must add a note to the Docker Compose setup task. [ASSUMED — verify tessera user privileges in Docker Compose]

### Anti-Patterns to Avoid

- **Using `OutboxPoller`'s `FOR UPDATE SKIP LOCKED` pattern for hash chain reads:** The hash chain MUST read the immediately preceding row and CANNOT skip rows. `SKIP LOCKED` would break chain integrity.
- **Generating views referencing `ag_catalog.cypher()` function:** Views must reference the AGE label tables directly (plain SQL), not via the `cypher()` function, which requires `ag_catalog` in `search_path` and returns `SETOF record` — unusable by BI tools.
- **Committing AGE label table schema as a string literal:** The actual table names depend on the AGE graph schema created per-model. The view generator must query `ag_catalog.ag_label` or `ag_catalog.ag_graph` to get the correct namespace at DDL generation time, not hardcode it.
- **`CREATE VIEW` instead of `CREATE OR REPLACE VIEW`:** On schema change the view may already exist; always use `CREATE OR REPLACE`.
- **Embedding model_id in topic name as UUID with dashes:** Kafka topic names allow alphanumeric, `.`, `-`, `_`. UUIDs (with dashes) are valid Kafka topic names. Using underscore replacements is optional but cleaner.
- **Running hash verification in the write transaction:** The verification endpoint (`POST /admin/audit/verify`) must run outside any write transaction — it's a long sequential read-only operation.
- **Publishing Debezium connector config in application.properties:** The connector JSON must be POSTed to Kafka Connect's REST API (port 8083) after the service starts. Use a Spring `ApplicationRunner` or Docker Compose `healthcheck + depends_on` sequencing.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| SHA-256 hex encoding | Custom byte-to-hex loop | `HexFormat.of().formatHex(digest)` (JDK 17+) | Standard, no import |
| Kafka Connect connector registration | Custom HTTP client in Spring app | `docker/debezium/connectors/tessera-outbox.json` + Compose healthcheck + `curl` in `command` | Kafka Connect REST API already handles idempotent PUT |
| Replication lag in bytes | Parse WAL file names | `pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn)` | Built-in PG function, returns bytes directly [CITED: postgresql.org/docs/current/functions-admin.html] |
| AGE label table name lookup | Parse `information_schema.tables` | `SELECT * FROM ag_catalog.ag_label WHERE graph = (SELECT oid FROM ag_catalog.ag_graph WHERE name = $1)` | AGE's own catalog for authoritative label-to-table mapping |

**Key insight:** The Debezium Outbox Event Router SMT handles topic routing, event unwrapping, and JSON payload expansion — building a custom Kafka producer in the Spring app would duplicate all of that and lose transactionality guarantees.

---

## Common Pitfalls

### Pitfall 1: AGE `properties` Column is `agtype`, Not `jsonb`

**What goes wrong:** `properties->>'key'` works on `jsonb` via the `->>` operator. AGE's `agtype` type does NOT natively support the standard PostgreSQL JSONB operators. Queries like `SELECT properties->>'uuid'` will fail with `operator does not exist: agtype ->> unknown`.

**Why it happens:** AGE uses a custom `agtype` data type (a superset of JSONB) stored as `agtype`, not `jsonb`. Standard PostgreSQL JSONB operators are not defined for `agtype`.

**How to avoid:** Use AGE's accessor function `agtype_access_operator` or cast first: `(properties::jsonb)->>'uuid'`. Alternatively, use `ag_catalog.agtype_to_text(properties->'uuid'::agtype)`. The cast approach `(properties::jsonb)` is simpler. [CITED: age.apache.org/age-manual/master/intro/types.html]

**Warning signs:** SQL errors mentioning `operator does not exist: agtype` during view DDL testing.

**Correct view column pattern:**
```sql
-- Cast agtype to jsonb first, then use standard ->> operator
(properties::jsonb)->>'uuid' AS uuid,
((properties::jsonb)->>'model_id')::uuid AS model_id
```

### Pitfall 2: `graph_events` Partitioned Table and `FOR UPDATE` Scope

**What goes wrong:** `SELECT ... FOR UPDATE` on a partitioned parent table in PostgreSQL requires that the query hits specific partitions. If the ORDER BY clause doesn't leverage a partition-aligned index, the query may scan all partitions and take a full-table lock scope.

**Why it happens:** `graph_events` is partitioned by `event_time` (range). Queries without an `event_time` predicate scan all partitions. The `FOR UPDATE` for hash chain read needs only the last row per `model_id` — this is safe with a proper index but slow without one.

**How to avoid:** Add a separate index `CREATE INDEX idx_graph_events_model_seq_desc ON graph_events (model_id, sequence_nr DESC)` in the Flyway migration that adds `prev_hash`. The query `SELECT prev_hash FROM graph_events WHERE model_id = :mid ORDER BY sequence_nr DESC LIMIT 1 FOR UPDATE` will hit this index efficiently. Test explicitly in `HashChainConcurrencyIT`.

**Warning signs:** Hash chain append takes >10ms on a database with more than a few thousand events per tenant.

### Pitfall 3: Debezium Connector Fails If `published` Column Doesn't Exist Before CDC Starts

**What goes wrong:** Debezium starts capturing the `graph_outbox` table via logical replication. If the Flyway migration adding `published` column hasn't run yet (ordering issue), Debezium will see rows without the column and the SMT may fail.

**Why it happens:** Debezium reads the table's schema at connector registration time. Schema changes after slot creation require a connector restart or schema evolution configuration.

**How to avoid:** Flyway migration V24 (adding `published` column) must execute before any Debezium connector is registered. Since Flyway runs at Spring Boot startup and Debezium is an external container, the Docker Compose ordering (tessera healthcheck → debezium connector registration) handles this naturally. The connector registration script must wait for the Tessera service to report healthy.

### Pitfall 4: View Names Collide on Multi-Tenant Deployments with UUID `model_id`

**What goes wrong:** `v_{model_id}_{type_slug}` where model_id is a UUID like `550e8400-e29b-41d4-a716-446655440000` produces a view name with hyphens. Postgres identifiers with special chars need quoting — BI tools may not quote them.

**Why it happens:** D-A4 says hyphens replaced with underscores. But UUID `model_id` contains hyphens, producing names like `v_550e8400_e29b_41d4_a716_446655440000_person` which are valid unquoted identifiers but very long (>63 chars — Postgres identifier limit).

**How to avoid:** Truncate UUID to 8-char prefix or use model slug (not UUID) in view names. The view name must fit within Postgres's 63-character identifier limit. `v_{8-char-uuid-prefix}_{type_slug}` keeps names short and unambiguous. Alternatively, require a tenant `slug` field on the model config table and use that. The `SqlViewNameResolver` helper must validate the generated name length.

**Warning signs:** `WARNING: identifier "v_550e8400_e29b_41d4..." will be truncated to 63 characters` in Postgres logs.

### Pitfall 5: Hash Chain Breaks on Concurrent Appends to Same Tenant

**What goes wrong:** Two concurrent write transactions both read the same `prev_hash` and both compute their new hash based on it. The second commit inserts a row with the wrong `prev_hash` (based on a pre-first-commit snapshot), breaking the chain.

**Why it happens:** Without row-level locking, both transactions see the same MAX(sequence_nr) and compute chains independently.

**How to avoid:** The `SELECT prev_hash ... FOR UPDATE` pattern (D-C2) is correct — it locks the row that will be the predecessor, preventing concurrent appends until the first transaction commits. Verify this with a `HashChainConcurrencyIT` that runs 10 parallel threads each appending 100 events to the same tenant and verifies the chain remains valid afterwards. This is load-bearing for compliance use cases.

### Pitfall 6: Outbox `published` Column Addition Breaks Existing OutboxPoller

**What goes wrong:** Adding `published BOOLEAN DEFAULT false` to `graph_outbox` (D-B4) must not break the existing `OutboxPoller` which only reads `status='PENDING'` and updates to `'DELIVERED'`. The `published` column is only written by Debezium or the poller.

**Why it happens:** The `OutboxPoller` SQL is a string literal that doesn't SELECT `published`. Adding the column is safe as long as the INSERT in `Outbox.java` doesn't try to set it (it shouldn't — default false is correct for new rows).

**How to avoid:** Verify `Outbox.append()` INSERT statement does not need updating — the `published` column defaults to false, which is correct. The Debezium connector will UPDATE published=true via a Debezium signal or by marking rows via a post-processor. Actually: Debezium reads via CDC (WAL), not by updating rows — the `published` column is only for the `OutboxPoller` fallback path to know what's already been sent. The column should be set to `true` by the poller after delivery (not by Debezium which just reads WAL). This design needs clarification in the plan.

---

## Code Examples

Verified patterns from official sources and codebase inspection:

### AGE Label Table SQL View DDL

```sql
-- Source: [VERIFIED: AGE quick guide + AGE GitHub] + [CITED: age.apache.org/age-manual/master/intro/types.html]
-- AGE stores vertices in {graph_schema}."{LabelName}" with id + properties(agtype) columns.
-- Cast agtype to jsonb for standard JSONB operators.
CREATE OR REPLACE VIEW v_acme_person AS
/* schema_version:5 model_id:acme */
SELECT
    ((properties::jsonb)->>'uuid')::uuid            AS uuid,
    ((properties::jsonb)->>'model_id')::uuid         AS model_id,
    (properties::jsonb)->>'name'                     AS name,
    (properties::jsonb)->>'email'                    AS email,
    ((properties::jsonb)->>'_created_at')::timestamptz AS created_at,
    ((properties::jsonb)->>'_updated_at')::timestamptz AS updated_at
FROM  "acme"."Person"
WHERE ((properties::jsonb)->>'model_id')::uuid = 'model-uuid-here'::uuid
  AND COALESCE((properties::jsonb)->>'_tombstoned', 'false')::boolean = false;
```

### Querying AGE Label Catalog for View Generation

```sql
-- Source: [CITED: age.apache.org/age-manual/master/intro/graphs.html]
-- Get all label tables for a graph (graph name = model_id string in Tessera)
SELECT l.name AS label_name, l.relation::regclass AS table_oid
FROM   ag_catalog.ag_label l
JOIN   ag_catalog.ag_graph g ON l.graph = g.graphid
WHERE  g.name = :graph_name     -- Tessera model_id as string
  AND  l.kind = 'v';            -- 'v' = vertex labels, 'e' = edge labels
```

### Testcontainers Kafka + Debezium Integration Test

```java
// Source: [CITED: java.testcontainers.org/modules/kafka/]
// Use org.testcontainers.kafka.KafkaContainer (new API, not deprecated containers.KafkaContainer)
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

static KafkaContainer kafka = new KafkaContainer(
    DockerImageName.parse("apache/kafka-native:3.8.0")  // 3.9 has known startup issues
).withReuse(true);
```

### `@ConditionalOnProperty` for OutboxPoller disable

```java
// Source: [ASSUMED — existing Spring pattern, consistent with CLAUDE.md]
@ConditionalOnProperty(
    name = "tessera.kafka.enabled",
    havingValue = "false",
    matchIfMissing = true   // enabled by default (fallback behavior preserved)
)
@Component
public class OutboxPoller { ... }
```

### Spring AbstractHealthIndicator for Debezium Slot

```java
// Source: [CITED: baeldung.com/spring-boot-health-indicators]
// [CITED: postgresql.org/docs/current/functions-admin.html]
@Component
@ConditionalOnProperty(name = "tessera.kafka.enabled", havingValue = "true")
public class DebeziumSlotHealthIndicator extends AbstractHealthIndicator {

    private final NamedParameterJdbcTemplate jdbc;
    @Value("${tessera.kafka.lag-threshold-bytes:104857600}") // 100MB default
    private long lagThresholdBytes;

    @Override
    protected void doHealthCheck(Health.Builder builder) throws Exception {
        var params = Map.of("slot", "tessera_outbox_slot");
        Long lagBytes = jdbc.queryForObject(
            "SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn) " +
            "FROM pg_replication_slots WHERE slot_name = :slot",
            params, Long.class);
        if (lagBytes == null) {
            builder.down().withDetail("error", "replication slot not found");
        } else if (lagBytes > lagThresholdBytes) {
            builder.down().withDetail("lag_bytes", lagBytes).withDetail("threshold_bytes", lagThresholdBytes);
        } else {
            builder.up().withDetail("lag_bytes", lagBytes);
        }
    }
}
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| ZooKeeper-based Kafka | KRaft mode (no ZooKeeper) | Kafka 3.3+ (stable), mandatory in Kafka 4.0 | Docker Compose simpler: 1 less service |
| `org.testcontainers.containers.KafkaContainer` | `org.testcontainers.kafka.KafkaContainer` | Testcontainers 1.20 | Old class deprecated; new class supports apache/kafka images |
| Debezium images on Docker Hub | Images on `quay.io/debezium/` | 2023–2024 | Image pulls must use `quay.io/debezium/connect:3.4` not `debezium/connect:3.4` [CITED: hub.docker.com/r/debezium/connect] |
| MD5 / SHA-1 for audit hashes | SHA-256 minimum | Ongoing (NIST deprecations) | GxP/SOX/BSI C5 compliance requires SHA-256+ |
| `bootstrap.yml` for Spring Cloud Vault | `spring.config.import=vault://` (Config Data API) | Spring Cloud Vault 3.0+ | Already correct in this project per CLAUDE.md |

**Deprecated/outdated:**
- `debezium/connect` Docker Hub image: use `quay.io/debezium/connect` instead
- Kafka with ZooKeeper: use KRaft for new setups
- `org.testcontainers.containers.KafkaContainer`: use `org.testcontainers.kafka.KafkaContainer` or `ConfluentKafkaContainer`
- apache/kafka-native:3.9.0 Docker image: has known startup failure with Testcontainers 1.20 (GitHub issue #9506); use `apache/kafka-native:3.8.0` for tests [CITED: github.com/testcontainers/testcontainers-java/issues/9506]

---

## Runtime State Inventory

> Phase 4 is greenfield additions only (no rename/refactor). Included as a quick safety check.

| Category | Items Found | Action Required |
|----------|-------------|------------------|
| Stored data | `graph_outbox` table (existing rows, status=PENDING/DELIVERED) | Flyway V24 adds `published` column with DEFAULT false — safe for existing rows |
| Live service config | No running Debezium connector yet | None at migration time; connector registers after deployment |
| OS-registered state | None | — |
| Secrets/env vars | Debezium needs DB password | Add `DEBEZIUM_DB_PASSWORD` to Docker Compose env (sourced from `.env` file matching existing pattern) |
| Build artifacts | No stale artifacts from Phase 4 changes | — |

---

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Docker | Debezium/Kafka containers, ITs | Yes | 27.4.0 [VERIFIED] | — |
| Kafka 3.9 (local) | Docker Compose local dev | Yes | 3.9.0 [VERIFIED: ~/Programmming/Services/kafka-3.9.0] | Kafka container in Compose |
| `org.testcontainers:kafka` | Kafka integration tests | Available via BOM (1.20.4) | 1.20.4 (needs adding to fabric-projections pom) [VERIFIED: BOM in parent pom] | — |
| `quay.io/debezium/connect:3.4` | Docker Compose Debezium service | Pullable (standard image) | 3.4 [CITED: quay.io/debezium/connect] | — |
| `bitnami/kafka:3.9` | Docker Compose Kafka service | Pullable | 3.9 [CITED: bitnami docs] | `apache/kafka:3.9.0` |
| `apache/kafka-native:3.8.0` | Testcontainers ITs (NOT 3.9) | Pullable | 3.8.0 [CITED: TC docs] | `confluentinc/cp-kafka:7.4.0` |
| PostgreSQL WAL logical replication | Debezium CDC | Requires `wal_level=logical` in postgres config | — | Must set in Docker image or compose env |

**Missing/action required:**

- `wal_level=logical` must be set in the postgres-age Docker service. Currently the `docker-compose.yml` does not set `POSTGRES_INITDB_ARGS` or a custom `postgresql.conf`. This is required for Debezium logical replication and must be added to Docker Compose as `command: ["postgres", "-c", "wal_level=logical"]`.
- `org.testcontainers:kafka` is not yet in `fabric-projections/pom.xml` — must be added (no version needed, inherited from BOM).

---

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Spring Boot Test + Testcontainers 1.20.4 |
| Config file | None (Spring Boot Test auto-config; `ProjectionItApplication` in test/java) |
| Quick run command | `./mvnw test -pl fabric-projections,fabric-core -Dtest="SqlView*,HashChain*,DebeziumSlot*"` |
| Full suite command | `./mvnw verify -pl fabric-projections,fabric-core` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| SQL-01 | View `v_{model}_{type}` exists and returns rows queryable by BI tool (JDBC) | Integration | `./mvnw test -pl fabric-projections -Dtest="SqlViewGenerationIT"` | ❌ Wave 0 |
| SQL-02 | Aggregate query on view runs <500ms (vs Cypher baseline) | Integration | `./mvnw test -pl fabric-projections -Dtest="SqlViewAggregateIT"` | ❌ Wave 0 |
| SQL-03 | View regenerates after schema change; persists after restart | Integration | `./mvnw test -pl fabric-projections -Dtest="SqlViewSchemaChangeIT"` | ❌ Wave 0 |
| KAFKA-01 | Events land in correct topic `tessera.{model}.{type}` | Integration (Kafka TC) | `./mvnw test -pl fabric-projections -Dtest="KafkaTopicRoutingIT"` | ❌ Wave 0 |
| KAFKA-02 | OutboxPoller disabled when `tessera.kafka.enabled=true` | Unit | `./mvnw test -pl fabric-core -Dtest="OutboxPollerConditionalTest"` | ❌ Wave 0 |
| KAFKA-03 | Health indicator reports DOWN when lag > threshold | Unit (mock JDBC) | `./mvnw test -pl fabric-projections -Dtest="DebeziumSlotHealthIndicatorTest"` | ❌ Wave 0 |
| AUDIT-01 | Hash chain appended for enabled tenants; NULL for disabled | Integration | `./mvnw test -pl fabric-core -Dtest="HashChainIT"` | ❌ Wave 0 |
| AUDIT-01 | Concurrent appends produce valid chain (no gaps/duplication) | Integration | `./mvnw test -pl fabric-core -Dtest="HashChainConcurrencyIT"` | ❌ Wave 0 |
| AUDIT-02 | Verify endpoint returns `valid:true` on untampered chain | Integration | `./mvnw test -pl fabric-projections -Dtest="AuditVerifyIT"` | ❌ Wave 0 |
| AUDIT-02 | Verify detects tampered row (broken_at_seq) | Unit | `./mvnw test -pl fabric-projections -Dtest="AuditVerifyTamperTest"` | ❌ Wave 0 |

### Sampling Rate
- **Per task commit:** `./mvnw test -pl fabric-core,fabric-projections -Dtest="*SqlView*,*HashChain*,*Debezium*,*Audit*"`
- **Per wave merge:** `./mvnw verify -pl fabric-core,fabric-projections`
- **Phase gate:** Full suite green before `/gsd-verify-work`

### Wave 0 Gaps
- [ ] `fabric-projections/src/test/java/dev/tessera/projections/sql/SqlViewGenerationIT.java`
- [ ] `fabric-projections/src/test/java/dev/tessera/projections/sql/SqlViewAggregateIT.java`
- [ ] `fabric-projections/src/test/java/dev/tessera/projections/sql/SqlViewSchemaChangeIT.java`
- [ ] `fabric-projections/src/test/java/dev/tessera/projections/kafka/KafkaTopicRoutingIT.java`
- [ ] `fabric-core/src/test/java/dev/tessera/core/events/HashChainIT.java`
- [ ] `fabric-core/src/test/java/dev/tessera/core/events/HashChainConcurrencyIT.java`
- [ ] `fabric-projections/src/test/java/dev/tessera/projections/audit/AuditVerifyIT.java`
- [ ] Add `org.testcontainers:kafka` dependency to `fabric-projections/pom.xml`
- [ ] Extend `AgePostgresContainer` or create `AgePostgresKafkaContainer` composite for Kafka ITs that need both AGE + Kafka

---

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | No (admin endpoints inherit existing JWT SecurityConfig) | Existing `SecurityConfig` JWT filter |
| V3 Session Management | No | — |
| V4 Access Control | Yes — admin endpoints must be tenant-scoped | Existing `@PreAuthorize` / JWT model_id claim pattern |
| V5 Input Validation | Yes — SQL view DDL uses property slugs as identifiers | `safeSqlIdentifier()` helper must reject non-`[a-z0-9_]` slugs to prevent SQL injection in DDL |
| V6 Cryptography | Yes — SHA-256 hash chain | JDK `MessageDigest.getInstance("SHA-256")` is FIPS-approved; never MD5/SHA-1 |

### Known Threat Patterns for This Stack

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| SQL injection via property slug in view DDL | Tampering | `safeSqlIdentifier()` — allowlist `[a-zA-Z0-9_]` only; reject or sanitize anything else before embedding in DDL string |
| Cross-tenant view access (view `v_acme_person` readable by `model_id=b`) | Information disclosure | View DDL includes `WHERE model_id = '{tenant_uuid}'::uuid` predicate; ArchUnit test verifies `SqlViewProjection` adds the WHERE clause |
| Hash chain bypass (hash_chain_enabled=false for a tenant that claims compliance) | Repudiation | Feature is opt-in — if a tenant claims GxP compliance they must enable the flag; admin endpoint to toggle creates audit trail of the toggle |
| Replication slot accumulates WAL indefinitely | Denial of service | `max_slot_wal_keep_size` + health indicator DOWN alert is the mitigation (D-D2) |
| Debezium reads `graph_outbox.payload` containing sensitive data | Information disclosure | Kafka topics should use the same access control as the REST projection; document that Kafka ACLs are needed before production use (deferred per scope) |

---

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | `(properties::jsonb)->>'key'` works in AGE 1.6.0 label tables via `agtype::jsonb` cast | Code Examples | View DDL fails; must use `agtype_access_operator` instead |
| A2 | Tessera uses `model_id` UUID string as the AGE graph name (passed to `ag_catalog.create_graph()`) | Architecture Patterns — Pattern 1 | View DDL references wrong schema; must inspect `GraphSession` code to confirm |
| A3 | `tessera.kafka.enabled` is the correct property name for the OutboxPoller condition | Code Examples | Property mismatch means OutboxPoller never disables; pick name from D-B3 spec |
| A4 | `FOR UPDATE` on partitioned `graph_events` works without specifying `event_time` partition key in WHERE | Common Pitfalls — Pitfall 2 | Query falls back to seq-scan of all partitions; performance regression for large tenants |
| A5 | The Debezium `tessera` DB user has `REPLICATION` privilege in the Docker Compose setup | Environment Availability | Debezium connector registration fails; must add `POSTGRES_USER` REPLICATION grant to V1 migration or Docker init |
| A6 | `ALTER SYSTEM SET max_slot_wal_keep_size` is executable by the `tessera` DB user | Code Examples — Pattern 4 | Migration fails; must use `SUPERUSER` role or set via `postgresql.conf` in Docker image |

---

## Open Questions (ALL RESOLVED)

1. **What is the actual AGE graph name used per tenant?**
   - **RESOLVED:** Tessera uses a single fixed graph named `tessera_main`, NOT per-tenant graphs. Verified via `GraphSession.java` line 58: `public static final String GRAPH_NAME = "tessera_main";` and `V1__enable_age.sql`: `SELECT create_graph('tessera_main');`. All tenants share this graph; tenant isolation is via `model_id` filtering in Cypher WHERE clauses and SQL view WHERE clauses. The `SqlViewProjection` must use `tessera_main` as the graph schema name: `FROM tessera_main."LabelName"`.

2. **`published` column purpose vs Debezium CDC model**
   - **RESOLVED:** The `published` column is for the `OutboxPoller` fallback path, NOT for Debezium. Debezium reads via WAL (CDC) using the replication slot `confirmed_flush_lsn` — it does NOT query or update rows. The `OutboxPoller` must set `published = true` after successful dispatch to prevent re-processing rows on restart. Current `OutboxPoller` uses `status = 'DELIVERED'` (via `MARK_DELIVERED_SQL`); the `published` column adds a secondary coordination flag. The `OutboxPoller.poll()` method must be updated to also set `published = true` in its `MARK_DELIVERED_SQL` update.

3. **Debezium connector registration timing in Docker Compose**
   - **RESOLVED:** Use a one-shot `debezium-connector-init` service (Docker Compose `restart: "no"`) following the existing `ollama-init` pattern in docker-compose.yml. The init container uses `curlimages/curl` and `depends_on: debezium-connect: condition: service_healthy`. It does NOT need to wait for Tessera/Flyway because Debezium reads from the WAL (not the table directly) and the replication slot is created by the connector, not by Flyway. The connector registration fires after Debezium Connect is healthy.

---

## Sources

### Primary (HIGH confidence)
- Codebase: `fabric-core/src/main/java/dev/tessera/core/events/Outbox.java`, `EventLog.java`, `OutboxPoller.java` — verified graph_outbox shape and event log schema
- Codebase: `fabric-app/src/main/resources/db/migration/V2__graph_events.sql`, `V3__graph_outbox.sql` — verified column shapes
- Codebase: `pom.xml` — verified versions (Spring Boot 3.5.13, testcontainers 1.20.4, kafka absent from parent BOM)
- Codebase: `docker/age-pgvector/Dockerfile` — verified AGE image digest and PG16 baseline
- [AGE quick guide (matheusfarias03.github.io/AGE-quick-guide/)](https://matheusfarias03.github.io/AGE-quick-guide/) — label table structure (id + properties columns)
- [Apache AGE types documentation](https://age.apache.org/age-manual/master/intro/types.html) — agtype column type

### Secondary (MEDIUM confidence)
- [Debezium 3.4.0.Final release blog](https://debezium.io/blog/2025/12/16/debezium-3-4-final-released/) — confirmed version and PG16 support
- [Testcontainers Kafka module docs](https://java.testcontainers.org/modules/kafka/) — `org.testcontainers.kafka.KafkaContainer` new API
- [Testcontainers issue #9506](https://github.com/testcontainers/testcontainers-java/issues/9506) — apache/kafka-native:3.9.0 startup bug
- [PostgreSQL replication config docs](https://www.postgresql.org/docs/current/runtime-config-replication.html) — `max_slot_wal_keep_size`, `pg_stat_replication`
- [Baeldung Spring Boot Health Indicators](https://www.baeldung.com/spring-boot-health-indicators) — `AbstractHealthIndicator` pattern
- [DEV Community hash chain audit log](https://dev.to/veritaschain/building-a-tamper-evident-audit-log-with-sha-256-hash-chains-zero-dependencies-h0b) — SHA-256 hash chain pattern

### Tertiary (LOW confidence — flag for validation)
- Kafka KRaft Docker Compose configuration — [Medium article](https://medium.com/@kinneko-de/kafka-4-kraft-docker-compose-874d8f1ffd9b) — needs verification with actual Bitnami Kafka 3.9 env vars
- Debezium connector JSON format for `route.topic.replacement` — topic naming pattern needs end-to-end test to verify

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — all core dependencies already in project; only `testcontainers:kafka` addition is new
- Architecture: HIGH for SQL views (AGE internals verified) and hash chain (EventLog code read); MEDIUM for Debezium connector config (doc-cited, not end-to-end tested locally)
- Pitfalls: HIGH for AGE agtype operator issue (common real-world mistake); MEDIUM for partitioned table FOR UPDATE behavior

**Research date:** 2026-04-17
**Valid until:** 2026-07-17 (90 days; Debezium and Testcontainers sections are 30-day if 3.4.x patch releases add breaking changes)
