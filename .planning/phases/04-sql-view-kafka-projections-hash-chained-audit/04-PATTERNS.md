---
gsd_artifact: patterns
phase: "04"
phase_name: "SQL View + Kafka Projections, Hash-Chained Audit"
created: 2026-04-17
---

# Phase 4 — Pattern Mapping

## Files to Create / Modify

### New Files

| File | Module | Role | Data Flow |
|------|--------|------|-----------|
| `fabric-projections/src/main/java/dev/tessera/projections/sql/SqlViewProjection.java` | fabric-projections | Service: generate + replace views per tenant | Reads SchemaRegistry → executes DDL via NamedParameterJdbcTemplate |
| `fabric-projections/src/main/java/dev/tessera/projections/sql/SqlViewNameResolver.java` | fabric-projections | Helper: `v_{model}_{typeSlug}` naming + 63-char guard | Pure string → identifier |
| `fabric-projections/src/main/java/dev/tessera/projections/sql/SqlViewAdminController.java` | fabric-projections | REST: `GET /admin/sql/views` | Reads SqlViewProjection state → JSON response |
| `fabric-projections/src/main/java/dev/tessera/projections/audit/AuditVerificationController.java` | fabric-projections | REST: `POST /admin/audit/verify?model_id=` | Delegates to AuditVerificationService → JSON response |
| `fabric-core/src/main/java/dev/tessera/core/events/HashChain.java` | fabric-core | Helper: SHA-256(prev_hash ∥ payload), genesis constant | Pure function; no Spring, no JDBC |
| `fabric-core/src/main/java/dev/tessera/core/audit/AuditVerificationService.java` | fabric-core | Service: sequential chain walk over graph_events | NamedParameterJdbcTemplate → AuditVerificationResult |
| `fabric-app/src/main/resources/db/migration/V24__outbox_published_flag.sql` | fabric-app | Flyway: adds `graph_outbox.published BOOLEAN DEFAULT false` | DDL only |
| `fabric-app/src/main/resources/db/migration/V25__hash_chain_audit.sql` | fabric-app | Flyway: adds `graph_events.prev_hash VARCHAR(64)` + index | DDL only |
| `fabric-app/src/main/resources/db/migration/V26__replication_slot_wal_limit.sql` | fabric-app | Flyway: `ALTER SYSTEM SET max_slot_wal_keep_size = '2GB'` | DDL / ALTER SYSTEM |
| `fabric-app/src/main/resources/db/migration/V27__tenant_hash_chain_config.sql` | fabric-app | Flyway: adds `hash_chain_enabled BOOLEAN DEFAULT false` to model config | DDL only |
| `docker/debezium/connectors/tessera-outbox.json` | (top-level docker/) | Debezium connector registration payload | Registered via `curl` POST to Kafka Connect REST API |
| `fabric-projections/src/main/java/dev/tessera/projections/kafka/DebeziumSlotHealthIndicator.java` | fabric-projections | Actuator: replication slot lag health indicator | Queries `pg_replication_slots` → AbstractHealthIndicator |

### Modified Files

| File | Module | Change | Reason |
|------|--------|--------|--------|
| `fabric-core/src/main/java/dev/tessera/core/events/EventLog.java` | fabric-core | Add hash chain computation in `append()` — reads prev_hash FOR UPDATE, computes SHA-256, includes in INSERT | D-C2: inline hash computation within the write transaction |
| `fabric-core/src/main/java/dev/tessera/core/events/Outbox.java` | fabric-core | Add `published` column to INSERT; default false | D-B4: published flag for Debezium/OutboxPoller coordination |
| `fabric-core/src/main/java/dev/tessera/core/events/OutboxPoller.java` | fabric-core | Add `@ConditionalOnProperty(name="tessera.kafka.enabled", havingValue="false", matchIfMissing=true)` | D-B3: disable OutboxPoller when Debezium/Kafka is enabled |
| `docker-compose.yml` | (top-level) | Add `kafka` (KRaft), `debezium-connect` services | D-B2/D-B5: external Debezium container in Compose stack |

---

## Pattern Excerpts from Analog Files

### 1. JDBC INSERT with NamedParameterJdbcTemplate and MapSqlParameterSource

**Analog:** `EventLog.java` (lines 61–76, 248–274) and `McpAuditLog.java` (lines 41–48)

The project uses a static `String INSERT` constant with text blocks, `MapSqlParameterSource`, and `jdbc.update()` / `jdbc.queryForObject()`. The INSERT for EventLog returns `RETURNING id` via `queryForObject`.

```java
// From EventLog.java — canonical INSERT + RETURNING pattern
private static final String INSERT =
        """
        INSERT INTO graph_events (
            model_id, sequence_nr, event_type, ...
        ) VALUES (
            :model_id::uuid, :sequence_nr, :event_type, ...
        )
        RETURNING id
        """;

MapSqlParameterSource p = new MapSqlParameterSource();
p.addValue("model_id", ctx.modelId().toString());
// ...
UUID id = jdbc.queryForObject(INSERT, p, UUID.class);
```

**Apply to:** `EventLog.java` modification — extend the existing `INSERT` constant to include `prev_hash` as a named parameter. The param is `null` for non-chained tenants (no cast needed; NULL is safe for `VARCHAR(64)`).

---

### 2. `@ConditionalOnProperty` for Feature Flag Gating

**Analog:** `OutboxPoller.java` class-level annotation usage (lines 70–71 declare it as `@Component`; the pattern is established in the codebase's existing connector scheduler)

```java
// Established pattern — add to OutboxPoller:
@ConditionalOnProperty(
    name = "tessera.kafka.enabled",
    havingValue = "false",
    matchIfMissing = true   // OutboxPoller is the default; Kafka must be explicitly enabled
)
@Component
public class OutboxPoller { ... }
```

Note: currently `OutboxPoller` is annotated with `@Component` and `@Scheduled`. The `@ConditionalOnProperty` goes at class level alongside `@Component`. The `@Scheduled` on the `poll()` method is unchanged.

**Apply to:** `OutboxPoller.java` — add `@ConditionalOnProperty` at class level.

---

### 3. Admin Controller under `/admin/**` with JWT Tenant Check

**Analog:** `McpAuditController.java` (full file) and `ConnectorAdminController.java` (lines 51–60)

Both admin controllers follow the same structure:
- `@RestController` + `@RequestMapping("/admin/{feature}")`
- `@AuthenticationPrincipal Jwt jwt` on each handler
- Tenant isolation check: `jwt.getClaimAsString("tenant").equals(modelId.toString())`
- Return `Map<String, Object>` via `ResponseEntity`
- `SecurityConfig` already permits all `/admin/**` for `ROLE_ADMIN` — no changes needed

```java
// From McpAuditController.java — tenant isolation check pattern
private static boolean isTenantMatch(Jwt jwt, UUID modelId) {
    if (jwt == null) return false;
    String tenant = jwt.getClaimAsString("tenant");
    return modelId.toString().equals(tenant);
}
```

**Apply to:**
- `SqlViewAdminController.java` — `GET /admin/sql/views?model_id=`
- `AuditVerificationController.java` — `POST /admin/audit/verify?model_id=`

Both follow this exact pattern. Return 403 on tenant mismatch (consistent with `McpAuditController`).

---

### 4. Transactional JDBC Query with FOR UPDATE (row locking)

**Analog:** `OutboxPoller.java` lines 80–84 — `FOR UPDATE SKIP LOCKED` in a `@Transactional(propagation = REQUIRES_NEW)` method

```java
// OutboxPoller.java — the existing locking pattern (SKIP LOCKED is for polling)
private static final String SELECT_SQL = "SELECT id, ... FROM graph_outbox WHERE status = 'PENDING' "
    + "ORDER BY created_at LIMIT " + BATCH_SIZE + " "
    + "FOR UPDATE SKIP LOCKED";
```

**For the hash chain**, the query must NOT use `SKIP LOCKED` — it needs strict row locking on the predecessor:

```java
// HashChain predecessor query — strict FOR UPDATE (no SKIP LOCKED)
// Runs inside EventLog.append()'s existing @Transactional boundary
String prevHash = jdbc.queryForObject(
    """
    SELECT prev_hash FROM graph_events
    WHERE model_id = :mid::uuid
    ORDER BY sequence_nr DESC LIMIT 1
    FOR UPDATE
    """,
    Map.of("mid", ctx.modelId().toString()),
    String.class);
```

The `EventLog.append()` method already runs within `GraphServiceImpl.apply()`'s `@Transactional` boundary — no new transaction scope is required.

---

### 5. Flyway Plain-SQL Migration Style

**Analog:** `V3__graph_outbox.sql`, `V22__mcp_audit_log.sql`, `V21__connectors_auth_type_widen.sql`

```sql
-- V3__graph_outbox.sql — canonical migration style
-- Phase 1 / Wave 0 / graph_outbox
-- [comment explaining purpose and design decision]
CREATE TABLE graph_outbox (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    model_id UUID NOT NULL,
    ...
);
CREATE INDEX idx_graph_outbox_status_created ON graph_outbox (status, created_at);
```

Rules extracted from existing migrations:
- Header comment: `-- Phase N / feature / purpose`
- Column name in `snake_case`
- Every table has `model_id UUID NOT NULL` for tenant scoping
- Indexes named `idx_{table}_{columns}` in abbreviated form
- `ALTER TABLE` migrations like V21 use separate statements, one per change

**Apply to V24–V27:**

```sql
-- V24__outbox_published_flag.sql
-- Phase 4 / Debezium integration / D-B4
-- Adds published flag so OutboxPoller fallback and Debezium can coordinate.
-- Default false: all existing + new rows start unpublished.
ALTER TABLE graph_outbox ADD COLUMN IF NOT EXISTS published BOOLEAN NOT NULL DEFAULT false;
CREATE INDEX IF NOT EXISTS idx_graph_outbox_unpublished
    ON graph_outbox (model_id, created_at) WHERE published = false;
```

```sql
-- V25__hash_chain_audit.sql
-- Phase 4 / Hash-chained audit / D-C1/C4
-- prev_hash nullable: only populated for tenants with hash_chain_enabled=true.
ALTER TABLE graph_events ADD COLUMN IF NOT EXISTS prev_hash VARCHAR(64);
-- Index for efficient FOR UPDATE prev_hash lookup (Pitfall 2 mitigation).
CREATE INDEX IF NOT EXISTS idx_graph_events_model_seq_desc
    ON graph_events (model_id, sequence_nr DESC);
```

---

### 6. SchemaRegistry.listNodeTypes() for Type Discovery

**Analog:** `SchemaRegistry.java` lines 77–79

```java
// SchemaRegistry.java — type discovery for all types in a tenant
public List<NodeTypeDescriptor> listNodeTypes(TenantContext ctx) {
    return repo.listNodeTypes(ctx, versions.currentVersion(ctx));
}
```

**Apply to `SqlViewProjection`:** Call `schemaRegistry.listNodeTypes(ctx)` to get all `NodeTypeDescriptor` instances, then iterate to generate one view DDL per type.

---

### 7. SchemaDescriptorCache.invalidateAll() on Schema Change

**Analog:** `SchemaDescriptorCache.java` lines 48–51 and `SchemaRegistry.createNodeType()` (line 72) which calls `cache.invalidateAll(ctx.modelId())`

```java
// SchemaDescriptorCache.java — invalidation on schema change
public void invalidateAll(UUID modelId) {
    cache.asMap().keySet().removeIf(k -> k.modelId().equals(modelId));
}
```

**Apply to `SqlViewProjection`:** Subscribe to the same schema-change event path used by `SchemaRegistry`. When `SchemaDescriptorCache.invalidateAll()` is called (by `SchemaRegistry` on any mutation), `SqlViewProjection` must react to regenerate views. The cleanest hook is a Spring `@EventListener` or `@TransactionalEventListener` on the `ApplicationEvent` published by `SchemaRegistry` (the existing `SchemaChangeEvent` / `ConnectorMutatedEvent` pattern).

---

### 8. `@Scheduled` + `@SchedulerLock` for Periodic Tasks

**Analog:** `OutboxPoller.java` lines 98–101

```java
// OutboxPoller.java — scheduling pattern with ShedLock
@Scheduled(fixedDelay = 500)
@SchedulerLock(name = "tessera-outbox-poller", lockAtMostFor = "PT1M", lockAtLeastFor = "PT0.1S")
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void poll() { ... }
```

**Not directly applied** to Phase 4 — but if a startup `ApplicationRunner` for view generation needs to be periodic, use this same three-annotation stack.

For `SqlViewProjection`'s startup-regeneration path, use an `ApplicationRunner` (once-on-start, not scheduled):

```java
@Component
public class SqlViewProjectionStartup implements ApplicationRunner {
    private final SqlViewProjection projection;
    @Override
    public void run(ApplicationArguments args) {
        projection.regenerateAll();
    }
}
```

---

### 9. Testcontainers AGE Base Container

**Analog:** `AgePostgresContainer.java` (both copies — fabric-core and fabric-rules)

```java
// AgePostgresContainer.java — reuse for all IT tests requiring AGE + Flyway
public static PostgreSQLContainer<?> create() {
    DockerImageName image = DockerImageName.parse(AGE_IMAGE_DIGEST).asCompatibleSubstituteFor("postgres");
    return new PostgreSQLContainer<>(image)
            .withDatabaseName("tessera")
            .withUsername("tessera")
            .withPassword("tessera")
            .withReuse(true);
}
```

**Apply to:** All Phase 4 integration tests. For Kafka integration tests (`DebeziumOutboxIT`), add a `KafkaContainer` alongside the AGE container using the same pattern:

```java
// New for Phase 4 Kafka ITs — append alongside AgePostgresContainer.create()
import org.testcontainers.kafka.KafkaContainer;  // NOT deprecated containers.KafkaContainer
static KafkaContainer kafka = new KafkaContainer(
    DockerImageName.parse("apache/kafka-native:3.8.0")
).withReuse(true);
```

Use `apache/kafka-native:3.8.0` (not 3.9 — known startup issues in Testcontainers per RESEARCH.md).

---

### 10. Docker Compose Service Definition Style

**Analog:** `docker-compose.yml` (full file — `postgres-age` and `ollama-init` services)

Current compose uses:
- `build: context:` for custom images, `image:` for off-the-shelf
- `healthcheck` with `test: ["CMD-SHELL", ...]`
- `depends_on: {service: {condition: service_healthy}}`
- `restart: unless-stopped` for persistent services, `restart: "no"` for one-shot
- Named volumes under top-level `volumes:`

```yaml
# From docker-compose.yml — one-shot init container pattern (ollama-init)
# Apply this pattern to debezium connector registration:
debezium-connector-init:
  image: curlimages/curl:latest
  container_name: tessera-debezium-connector-init
  depends_on:
    debezium-connect:
      condition: service_healthy
  entrypoint:
    - sh
    - -c
    - |
      curl -s -X PUT http://debezium-connect:8083/connectors/tessera-outbox-connector/config \
        -H 'Content-Type: application/json' \
        -d @/connectors/tessera-outbox.json
  volumes:
    - ./docker/debezium/connectors:/connectors:ro
  restart: "no"
```

---

### 11. `Outbox.append()` INSERT Shape

**Analog:** `Outbox.java` lines 38–75 (full file)

Current INSERT:
```java
// Outbox.java — existing INSERT column list (V24 adds 'published')
INSERT INTO graph_outbox (
    model_id, event_id, aggregatetype, aggregateid, type, payload, routing_hints, status
) VALUES (
    :model_id::uuid, :event_id::uuid, :aggregatetype, :aggregateid, :type,
    :payload::jsonb, :routing_hints::jsonb, 'PENDING'
)
```

After V24 migration adds `published BOOLEAN DEFAULT false`, the INSERT does NOT need to include the `published` column — the column default handles it. This means `Outbox.java` requires no SQL change; only the Flyway migration is needed.

The `OutboxPoller.java` `SELECT_SQL` (line 80–84) also does not SELECT `published` — it filters by `status = 'PENDING'`. This is safe: the poller is the fallback path, and it marks rows `DELIVERED` after polling regardless of the `published` column.

---

## Integration Points

### A. SqlViewProjection — How It Integrates

```
SchemaRegistry.listNodeTypes(TenantContext)
    └─> [List<NodeTypeDescriptor>]
        └─> SqlViewProjection.generateDdl(descriptor)
            └─> ag_catalog.ag_label + ag_catalog.ag_graph  (AGE label namespace lookup)
            └─> "CREATE OR REPLACE VIEW v_{model}_{typeSlug} AS ..."
                └─> jdbc.execute(ddl)
                    └─> PostgreSQL view in public schema
                        └─> BI tool sees it as a normal table
```

**Trigger points:**
1. `ApplicationRunner.run()` → `SqlViewProjection.regenerateAll()` (startup)
2. `@TransactionalEventListener` on whatever `ApplicationEvent` `SchemaRegistry` publishes after a schema mutation (same event that triggers `cache.invalidateAll()`)

**Analog for trigger 2:** There is no existing `@TransactionalEventListener` in the current codebase — the closest is the `ConnectorMutatedEvent` pattern in `ConnectorAdminController` using `ApplicationEventPublisher.publishEvent()` and `ConnectorRegistry` consuming it via `@EventListener`. The `SqlViewProjection` would consume a new `SchemaChangedEvent` (or reuse an existing `ApplicationEvent` published by `SchemaRegistry`).

Check whether `SchemaRegistry` already publishes an `ApplicationEvent` after `createNodeType` / `applyChangeOrReject`. If not, add one. The planner must verify this before task decomposition.

---

### B. EventLog.append() Hash Chain Extension

```
GraphServiceImpl.apply()   [@Transactional boundary owner]
    └─> EventLog.append()
        ├─> SequenceAllocator.nextSequenceNr()   [unchanged]
        ├─> [NEW] isHashChainEnabled(ctx)        [reads model config table V27]
        │   └─> if true:
        │       ├─> SELECT prev_hash ... ORDER BY sequence_nr DESC LIMIT 1 FOR UPDATE
        │       └─> HashChain.compute(prev, payloadJson)  → newHash
        └─> INSERT INTO graph_events (..., prev_hash)  [prev_hash = newHash or null]
```

**Critical:** `isHashChainEnabled(ctx)` must be a lightweight JDBC query against the model config table added in V27. It should cache the result per `model_id` in a `@Cacheable` method or a simple `ConcurrentHashMap` in `EventLog` (not Caffeine — that's `SchemaDescriptorCache`'s concern). Avoid a per-event DB round-trip for a config flag.

---

### C. OutboxPoller Conditionalization

```
Application startup:
    tessera.kafka.enabled = false (default)
        └─> OutboxPoller bean IS created (@ConditionalOnProperty matchIfMissing=true)
        └─> DebeziumSlotHealthIndicator bean IS NOT created
    tessera.kafka.enabled = true
        └─> OutboxPoller bean IS NOT created
        └─> DebeziumSlotHealthIndicator bean IS created
```

No change to `GraphServiceImpl.apply()` or `Outbox.append()` — the routing is purely at the Spring bean lifecycle level.

---

### D. Debezium → Kafka Topic Routing

```
graph_outbox row inserted (aggregatetype = "{model_id}-{type_slug}" or similar)
    └─> PostgreSQL WAL (logical replication, plugin=pgoutput)
        └─> Debezium connector (tessera_outbox_slot)
            └─> Outbox Event Router SMT
                ├─> routes.by.field = aggregatetype
                ├─> route.topic.replacement = "tessera.${routedByValue}"
                └─> Kafka topic: tessera.{model_id}-{type_slug}
                    └─> downstream consumers
```

**Open routing decision (from RESEARCH.md Pattern 2 note):** The current `Outbox.append()` takes `aggregateType` as a parameter. Today this carries the entity type slug. To route by `model_id + type_slug`, either:
- Option A: Caller passes `aggregateType = ctx.modelId() + "-" + typeSlug` — minimal change, SMT routes to `tessera.{model_id}-{type_slug}`
- Option B: Use `routing_hints` JSONB column with a custom predicate

The planner must pick one. Option A is simpler and matches the SMT's design intent.

---

### E. Admin Endpoint Security (No New Config Required)

`SecurityConfig.java` already covers all `/admin/**` routes:

```java
// SecurityConfig.java lines 67–71 — admin routes already secured
.requestMatchers("/admin/**")
.hasAnyRole("ADMIN", "TOKEN_ISSUER")
```

New admin endpoints `/admin/sql/views` and `/admin/audit/verify` are automatically covered. No `SecurityConfig` changes needed.

Tenant isolation for new controllers: use the `isTenantMatch(jwt, modelId)` pattern from `McpAuditController` (return 403 on mismatch).

---

### F. Flyway Migration Sequencing

Current highest migration: `V23__mcp_agent_quotas.sql`

Phase 4 migrations must start at V24:

| Version | File | Must precede |
|---------|------|-------------|
| V24 | `outbox_published_flag.sql` | Debezium connector registration (Pitfall 3) |
| V25 | `hash_chain_audit.sql` | Any hash chain append |
| V26 | `replication_slot_wal_limit.sql` | Debezium connector start (WAL cap protection) |
| V27 | `tenant_hash_chain_config.sql` | `isHashChainEnabled()` reads this table |

V26 uses `ALTER SYSTEM` which requires PostgreSQL superuser. In Docker Compose, the `tessera` user is created via `POSTGRES_USER=tessera` — this is the superuser in the default Postgres Docker image setup. Verify before coding.

---

## Key Structural Observations

1. **No new Maven module.** All new Java code fits in `fabric-projections` (controllers, SqlViewProjection, DebeziumSlotHealthIndicator) and `fabric-core` (HashChain, AuditVerificationService, EventLog modification). The ArchUnit module dependency tests must remain green: `fabric-projections` may depend on `fabric-core`; `fabric-core` must NOT depend on `fabric-projections`.

2. **`agtype` cast is load-bearing.** Every view DDL column must use `(properties::jsonb)->>'key'` not `properties->>'key'`. The SQL in `SqlViewProjection.generateDdl()` must apply this cast unconditionally. Missing the cast produces a runtime error invisible at compilation time (Pitfall 1 in RESEARCH.md).

3. **`FOR UPDATE` must not use `SKIP LOCKED` for hash chain.** The two uses of `FOR UPDATE` in the codebase serve opposite purposes: `OutboxPoller` uses `SKIP LOCKED` to skip locked rows (parallel poll semantics), while the hash chain predecessor query must BLOCK until the predecessor commits. These are structurally different and must not be conflated.

4. **View names are bounded by Postgres's 63-character identifier limit.** `SqlViewNameResolver` must validate the generated name and truncate or substitute before executing DDL. If `model_id` is a UUID string, use the first 8 hex chars (e.g., `550e8400`) as the model segment, not the full UUID, to stay well under the limit.

5. **`docker-compose.yml` image pinning policy (D-09).** When adding `bitnami/kafka:3.9` or `debezium/connect:3.4`, pin to a specific digest. The `ImagePinningTest` in `fabric-app` enforces that all images in compose are digest-pinned. Check `ImagePinningTest.java` before writing the compose additions to understand the enforcement mechanism.
