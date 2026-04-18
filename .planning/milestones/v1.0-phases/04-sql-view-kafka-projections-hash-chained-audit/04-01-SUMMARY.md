---
phase: 04-sql-view-kafka-projections-hash-chained-audit
plan: "01"
subsystem: sql-view-projections
tags: [sql-views, flyway, age, postgresql, bi-tools, schema-change, agtype]

requires:
  - phase: 04-sql-view-kafka-projections-hash-chained-audit
    plan: "00"
    provides: SqlViewProjectionIT Wave 0 stubs, compile-clean test targets

provides:
  - Flyway migrations V24-V27 (Phase 4 schema foundation)
  - SqlViewProjection service with ApplicationRunner startup regeneration and schema-change-aware regeneration
  - SqlViewNameResolver with 63-char Postgres identifier limit guard
  - SqlViewAdminController GET /admin/sql/views with JWT tenant isolation
  - SqlViewNameResolverTest (9 unit tests) + SqlViewProjectionIT (6 IT tests replacing Wave 0 stubs)

affects: [04-02-PLAN, 04-03-PLAN]

tech-stack:
  added: []
  patterns:
    - "(properties::jsonb)->>'key' cast for AGE agtype → SQL type bridge (Pitfall 1 mitigation)"
    - "/* schema_version:N model_id:X type:Y */ comment in view DDL for D-D3 staleness detection"
    - "CREATE OR REPLACE VIEW over AGE label tables for schema-change-safe view regeneration"
    - "ApplicationRunner for startup view regeneration (D-A2)"
    - "ConcurrentHashMap<String, ViewMetadata> as in-memory view registry for admin endpoint"

key-files:
  created:
    - fabric-app/src/main/resources/db/migration/V24__outbox_published_flag.sql
    - fabric-app/src/main/resources/db/migration/V25__hash_chain_audit.sql
    - fabric-app/src/main/resources/db/migration/V26__replication_slot_wal_limit.sql
    - fabric-app/src/main/resources/db/migration/V27__tenant_hash_chain_config.sql
    - fabric-projections/src/main/java/dev/tessera/projections/sql/SqlViewNameResolver.java
    - fabric-projections/src/main/java/dev/tessera/projections/sql/SqlViewProjection.java
    - fabric-projections/src/main/java/dev/tessera/projections/sql/SqlViewAdminController.java
    - fabric-projections/src/test/java/dev/tessera/projections/sql/SqlViewNameResolverTest.java
    - fabric-projections/src/test/resources/db/migration/V24__outbox_published_flag.sql
    - fabric-projections/src/test/resources/db/migration/V25__hash_chain_audit.sql
    - fabric-projections/src/test/resources/db/migration/V26__replication_slot_wal_limit.sql
    - fabric-projections/src/test/resources/db/migration/V27__tenant_hash_chain_config.sql
  modified:
    - fabric-projections/src/test/java/dev/tessera/projections/sql/SqlViewProjectionIT.java

key-decisions:
  - "AddPropertySpec(slug, name, dataType, required) is the correct 4-arg constructor — not the 9-arg form used in the plan spec; CreateNodeTypeSpec is (slug, name, label, description)"
  - "SqlViewProjection.regenerateAll() enumerates tenants via schemaRegistry.listDistinctExposedModels() — reuses existing fabric-core API rather than scanning a separate tenant table"
  - "V26 test resource uses SELECT 1 AS wal_limit_skipped_in_test instead of ALTER SYSTEM — ALTER SYSTEM persists across sessions in Testcontainers and may interfere with other tests; production fabric-app V26 remains authoritative"
  - "SqlViewSchemaChangeIT stub left @Disabled — SqlViewProjectionIT.viewIsReplacedAfterSchemaChange covers the SQL-02 schema-change scenario; the stub is superseded"

requirements-completed: [SQL-01, SQL-02, SQL-03]

duration: 6min
completed: 2026-04-17
---

# Phase 04 Plan 01: SQL View Projection + Flyway Migrations V24-V27 Summary

**4 Flyway migrations (V24-V27) as Phase 4 schema foundation + SqlViewProjection generating per-tenant per-type PostgreSQL views over AGE label tables via (properties::jsonb) cast, bypassing Cypher for BI-tool aggregate queries**

## Performance

- **Duration:** 6 min
- **Started:** 2026-04-17T08:53:07Z
- **Completed:** 2026-04-17T08:58:35Z
- **Tasks:** 2
- **Files modified:** 13 (12 created + 1 modified)

## Accomplishments

- Created 4 Flyway migrations (V24-V27) that form the shared schema foundation for all Phase 4 plans
- Implemented `SqlViewNameResolver` — pure utility class resolving `v_{8-char-UUID-prefix}_{slug}` view names with 63-char Postgres identifier truncation + 4-char SHA-256 hash suffix for uniqueness
- Implemented `SqlViewProjection` service with:
  - `regenerateForTenant(ctx)` — queries AGE ag_catalog to resolve label tables, generates CREATE OR REPLACE VIEW DDL with embedded schema_version comment
  - `regenerateAll()` — enumerates tenants via `schemaRegistry.listDistinctExposedModels()` and regenerates all views
  - `ApplicationRunner.run()` — triggers `regenerateAll()` on startup (D-A2)
  - Staleness detection via `pg_get_viewdef` + regex parse of `schema_version:N` comment (D-D3)
  - `ConcurrentHashMap<String, ViewMetadata>` for in-memory view registry
- Implemented `SqlViewAdminController` — `GET /admin/sql/views?model_id={uuid}` with JWT tenant claim check (T-04-S1)
- 9 unit tests in `SqlViewNameResolverTest` covering naming convention, truncation at 63 chars, determinism, and error handling
- 6 IT tests in `SqlViewProjectionIT` replacing Wave 0 stubs: view creation, agtype cast, schema_version comment, schema-change replacement, tombstone exclusion, column matching
- Copied V24-V27 migrations to fabric-projections test resources (V26 replaced with safe SELECT no-op for Testcontainers compatibility)

## Task Commits

1. **Task 1: Flyway migrations V24-V27** — `6641cee`
2. **Task 2: SqlViewProjection + SqlViewNameResolver + SqlViewAdminController + tests** — `3711ad2`

## Files Created/Modified

- `V24__outbox_published_flag.sql` — published BOOLEAN on graph_outbox + partial index (D-B4)
- `V25__hash_chain_audit.sql` — prev_hash VARCHAR(64) on graph_events + DESC index for FOR UPDATE tail (D-C1/C4)
- `V26__replication_slot_wal_limit.sql` — ALTER SYSTEM max_slot_wal_keep_size=2GB (D-D2)
- `V27__tenant_hash_chain_config.sql` — model_config table with hash_chain_enabled per-tenant flag (D-C1)
- `SqlViewNameResolver.java` — pure utility: `resolve(UUID, String) → String`, max 63 chars, hyphen normalization
- `SqlViewProjection.java` — @Component implementing ApplicationRunner, agtype bridge, staleness detection
- `SqlViewAdminController.java` — GET /admin/sql/views with JWT tenant isolation (T-04-S1)
- `SqlViewNameResolverTest.java` — 9 unit tests
- `SqlViewProjectionIT.java` — 6 IT tests (Wave 0 stubs replaced)
- Test resource migrations V24-V27

## Decisions Made

- `AddPropertySpec` and `CreateNodeTypeSpec` constructors confirmed from actual source: 4-arg forms only (plan spec listed extra args that don't exist)
- `SqlViewProjection.regenerateAll()` uses `schemaRegistry.listDistinctExposedModels()` to enumerate tenants — avoids introducing a separate tenant registry table
- V26 test resource uses `SELECT 1` no-op instead of `ALTER SYSTEM` to avoid persisting system parameter changes across Testcontainers container reuse
- `SqlViewSchemaChangeIT` left `@Disabled` — the schema-change scenario is fully covered by `SqlViewProjectionIT.viewIsReplacedAfterSchemaChange`

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Corrected CreateNodeTypeSpec and AddPropertySpec constructor signatures**
- **Found during:** Task 2 (reading actual source files before writing IT)
- **Issue:** Plan spec referenced `new CreateNodeTypeSpec("slug", "name", "label", "desc", true, false)` (6-arg) and `new AddPropertySpec(slug, name, type, required, null, null, null, null)` (8-arg) but actual records have 4-arg constructors only
- **Fix:** Used the correct 4-arg constructors in `SqlViewProjectionIT`
- **Files modified:** `SqlViewProjectionIT.java`
- **Commit:** `3711ad2`

**2. [Rule 3 - Blocking] V26 test resource uses SELECT no-op for Testcontainers compatibility**
- **Found during:** Task 2 (adding test resource migrations)
- **Issue:** `ALTER SYSTEM SET max_slot_wal_keep_size = '2GB'` persists server-level configuration in the Docker container; in Testcontainers with `withReuse(true)` this could affect subsequent test runs and requires superuser in all configurations
- **Fix:** Test resource V26 is a safe `SELECT 1 AS wal_limit_skipped_in_test`; production V26 in `fabric-app` remains the authoritative migration
- **Files modified:** `fabric-projections/src/test/resources/db/migration/V26__replication_slot_wal_limit.sql`
- **Commit:** `3711ad2`

---

**Total deviations:** 2 auto-fixed (1 Rule 1 — constructor signature correction, 1 Rule 3 — test resource compatibility)

## Known Stubs

None — `SqlViewProjectionIT` Wave 0 stubs replaced with real tests. `SqlViewSchemaChangeIT` remains `@Disabled` as its behavioral contract is subsumed by `SqlViewProjectionIT.viewIsReplacedAfterSchemaChange`.

## Threat Flags

No new security surface beyond what is documented in the plan's threat model. All T-04-* mitigations applied:
- T-04-S1: JWT tenant claim check in `SqlViewAdminController.isTenantMatch()`
- T-04-I1: All views include `WHERE model_id = 'tenantId'::uuid` tenant scope clause
- T-04-T1: Property slug column aliases are double-quoted (`"slug"`) and slugs come from SchemaRegistry (admin-validated on creation)
- T-04-E1: V26 ALTER SYSTEM risk accepted for Docker Compose dev environment

## Next Phase Readiness

- Plan 04-02 (Hash-chain audit) can proceed: V25 migration (prev_hash) and V27 (model_config.hash_chain_enabled) are in place
- Plan 04-03 (Outbox poller conditionalization) can proceed: V24 migration (published flag) is in place
- `SqlViewProjection.regenerateForTenant(ctx)` is the schema-change notification hook — Plan 04-02 or application event wiring can call it when schema changes occur

---
*Phase: 04-sql-view-kafka-projections-hash-chained-audit*
*Completed: 2026-04-17*
