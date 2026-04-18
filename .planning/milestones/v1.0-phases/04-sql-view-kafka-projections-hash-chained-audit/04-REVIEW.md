---
phase: 04-sql-view-kafka-projections-hash-chained-audit
reviewed: 2026-04-17T00:00:00Z
depth: standard
files_reviewed: 14
files_reviewed_list:
  - fabric-app/src/main/resources/db/migration/V24__outbox_published_flag.sql
  - fabric-app/src/main/resources/db/migration/V25__hash_chain_audit.sql
  - fabric-app/src/main/resources/db/migration/V26__replication_slot_wal_limit.sql
  - fabric-app/src/main/resources/db/migration/V27__tenant_hash_chain_config.sql
  - fabric-core/src/main/java/dev/tessera/core/events/HashChain.java
  - fabric-core/src/main/java/dev/tessera/core/events/EventLog.java
  - fabric-core/src/main/java/dev/tessera/core/audit/AuditVerificationService.java
  - fabric-projections/src/main/java/dev/tessera/projections/sql/SqlViewProjection.java
  - fabric-projections/src/main/java/dev/tessera/projections/sql/SqlViewNameResolver.java
  - fabric-projections/src/main/java/dev/tessera/projections/sql/SqlViewAdminController.java
  - fabric-projections/src/main/java/dev/tessera/projections/audit/AuditVerificationController.java
  - fabric-projections/src/main/java/dev/tessera/projections/kafka/DebeziumSlotHealthIndicator.java
  - fabric-core/src/main/java/dev/tessera/core/events/OutboxPoller.java
  - docker-compose.yml
  - docker/debezium/connectors/tessera-outbox.json
findings:
  critical: 2
  warning: 4
  info: 4
  total: 10
status: issues_found
---

# Phase 04: Code Review Report

**Reviewed:** 2026-04-17
**Depth:** standard
**Files Reviewed:** 14
**Status:** issues_found

## Summary

Phase 04 delivers SQL view projections, hash-chained audit logging, Debezium integration (with the OutboxPoller as a fallback), and supporting migrations. The overall architecture is solid and the code is well-commented. Two critical issues require attention before this phase ships: a SQL injection vector in the generated view DDL and a hash-chain correctness bug in `AuditVerificationService`. Four warnings address logic gaps that could surface as silent failures or broken invariants in production.

---

## Critical Issues

### CR-01: SQL Injection via Unvalidated Property Slug in `buildColumnExpression`

**File:** `fabric-projections/src/main/java/dev/tessera/projections/sql/SqlViewProjection.java:258`

**Issue:** `buildColumnExpression` interpolates `prop.slug()` directly into the DDL string without any sanitisation beyond what the schema registry may or may not enforce. The Javadoc on `buildViewDdl` (line 206) claims "column aliases are validated by the schema registry on creation" and "we additionally quote all column aliases with double-quotes", but **only the alias** (line 233) receives the double-quote treatment. The expression itself on line 258:

```java
String base = "(properties::jsonb)->>'" + slug + "'";
```

...embeds `slug` inside a single-quoted JSON key within the DDL. A slug containing `'` (single quote) or `)` characters would break out of the JSON operator expression and allow arbitrary SQL to be appended to the DDL before `jdbc.getJdbcTemplate().execute(ddl)` runs it on line 195.

The schema registry validation is an upstream guardrail, but `SqlViewProjection` should not rely exclusively on callers to prevent injection — defence in depth is required at the DDL boundary.

**Fix:**
```java
private static String buildColumnExpression(PropertyDescriptor prop) {
    // Escape single quotes to prevent DDL injection via crafted slugs.
    String slug = prop.slug().replace("'", "''");
    String base = "(properties::jsonb)->>'" + slug + "'";
    return switch (prop.dataType()) {
        case "INTEGER"   -> "(" + base + ")::integer";
        case "BOOLEAN"   -> "(" + base + ")::boolean";
        case "TIMESTAMP" -> "(" + base + ")::timestamptz";
        case "REFERENCE" -> "(" + base + ")::uuid";
        default          -> base;
    };
}
```

Additionally, enforce a slug format check (e.g., `^[a-z][a-z0-9_]{0,62}$`) in `SqlViewProjection.regenerateView` as a belt-and-braces guard, consistent with the alias-quoting rationale already documented on line 206.

---

### CR-02: Hash-Chain Verification Logic Error — Compares Wrong Link

**File:** `fabric-core/src/main/java/dev/tessera/core/audit/AuditVerificationService.java:105-113`

**Issue:** The verification loop recomputes the expected `prev_hash` for event `i` as `SHA-256(prevHashHolder[i-1] || payload[i])`, then compares it against `storedPrevHash[i]` (the value actually stored in the row). However, **at write time** the invariant stored is:

```
event[i].prev_hash = SHA-256(event[i-1].prev_hash || event[i].payload)
```

That is, `event[i].prev_hash` is the hash computed _for_ event `i`, not the hash of the predecessor. The verifier currently checks whether `expected == storedPrevHash[i]`, which is correct on the first event but advances `prevHashHolder` to `storedPrevHash[i]` (line 113), not to the _computed new hash_. This means after any event, the "expected predecessor" for the next event is the stored `prev_hash` rather than the hash the verifier just computed — so a tampered row where `prev_hash` was replaced wholesale (both entry and its successor's pointer updated) would pass verification.

The verifier should advance `prevHashHolder` to `expectedHash` (the value it computed), not `storedPrevHash`. A tamper that patches two consecutive rows consistently would currently go undetected.

**Fix:** Change lines 112-113:
```java
// WRONG — advances to the stored value, allowing consistent double-row tampering
prevHashHolder[0] = storedPrevHash;

// CORRECT — advance to the value the verifier computed; any divergence breaks next link
prevHashHolder[0] = expectedHash;
```

The comparison on line 107 stays as-is:
```java
if (!expectedHash.equals(storedPrevHash)) { ... }
```

---

## Warnings

### WR-01: `SELECT ... FOR UPDATE` on `graph_events` Requires Active Transaction; Missing `@Transactional` on `append`

**File:** `fabric-core/src/main/java/dev/tessera/core/events/EventLog.java:96`

**Issue:** `PREDECESSOR_HASH_SQL` contains `FOR UPDATE`, which requires an active transaction. The class Javadoc states `EventLog` "runs inside the caller's `@Transactional` boundary", but `append` has no `@Transactional` annotation itself, and `appendWithHashChain` will silently downgrade to an auto-committed single-statement execution if a caller forgets to open a transaction. Under auto-commit, `FOR UPDATE` on a single-row `SELECT` is a no-op with respect to locking, defeating the mutex against concurrent hash-chain appenders at the DB level.

The JVM `synchronized` block (line 339) provides single-instance safety, but the `FOR UPDATE` is meant as the multi-instance guard (per the comment on line 96–97). If that guard is silently ineffective when called outside a transaction, the multi-instance path is broken without any visible failure.

**Fix:** Add `@Transactional(propagation = Propagation.MANDATORY)` to `append` so that a missing outer transaction fails fast rather than silently degrading:
```java
@Transactional(propagation = Propagation.MANDATORY)
public Appended append(TenantContext ctx, GraphMutation mutation, ...) {
```

---

### WR-02: `replayToState` Loads All Events into Memory for Large Node Histories

**File:** `fabric-core/src/main/java/dev/tessera/core/events/EventLog.java:132-154`

**Issue:** `replayToState` calls `jdbc.query(...)` with a `RowMapper` that accumulates all matching events into a `List<Map<String,Object>>` (`folded`), then returns only the last element. For a node with thousands of mutation events this materialises the full history into heap before discarding all but the last row. While a `LIMIT 1` with `ORDER BY sequence_nr DESC` would return only the final state, the current query fetches every event up to `at` in ascending order.

This is particularly concerning for audit-log-heavy nodes because `graph_events` is expected to grow large (compliance tenants store every mutation). The result is potential OOM for nodes with high mutation rates queried over a long time window.

**Fix:** Rewrite the query to use `ORDER BY sequence_nr DESC LIMIT 1` and return the single row directly:
```java
return jdbc.query(
    """
    SELECT event_type, payload
      FROM graph_events
     WHERE model_id = :model_id::uuid
       AND node_uuid = :node_uuid::uuid
       AND event_time <= :at
     ORDER BY sequence_nr DESC
     LIMIT 1
    """,
    p,
    (rs, rowNum) -> {
        String type = rs.getString("event_type");
        Map<String, Object> result = new LinkedHashMap<>(parseJson(rs.getString("payload")));
        if ("TOMBSTONE_NODE".equals(type)) result.put("_tombstoned", Boolean.TRUE);
        return result;
    }
).stream().findFirst();
```

---

### WR-03: `AuditVerificationService.verify` Silently Passes Hash-Chain-Disabled Tenants as `valid`

**File:** `fabric-core/src/main/java/dev/tessera/core/audit/AuditVerificationService.java:79-121`

**Issue:** `verify` does not check whether the requested tenant has `hash_chain_enabled = true` in `model_config`. For a tenant whose `graph_events.prev_hash` column is `NULL` for all rows (hash chain disabled), `HashChain.compute(genesis, payload)` will produce a non-null hash that never matches `storedPrevHash = NULL`, causing `broken[0]` to fire on sequence 1. Alternatively, if the query returns zero rows, `valid(0)` is returned — indistinguishable from an empty-but-valid chain.

Callers (and the REST endpoint) currently receive either a false positive (broken) or an uninformative valid(0) when accidentally verifying a non-hash-chain tenant.

**Fix:** Add a pre-check that queries `model_config.hash_chain_enabled` and returns a dedicated result or throws an `IllegalArgumentException` when the tenant has not opted in:
```java
boolean enabled = isHashChainEnabled(ctx.modelId());
if (!enabled) {
    throw new IllegalArgumentException(
        "Hash-chain audit is not enabled for model_id=" + ctx.modelId());
}
```

Or add a new `AuditVerificationResult.notApplicable()` variant and return it when the tenant has `hash_chain_enabled = false`.

---

### WR-04: Debezium Connector Config Uses Hardcoded Credentials in a Committed File

**File:** `docker/debezium/connectors/tessera-outbox.json:6-7`

**Issue:** The connector configuration hardcodes `"database.password": "tessera"`. While the values match the Docker Compose defaults and are intended for local dev, this file is committed to the repository. Any deployment that applies this connector JSON against a production Debezium Connect instance (e.g., via the `debezium-connector-init` service or a CI script) will send the literal password to the Connect REST API.

The `docker-compose.yml` itself already uses `${POSTGRES_PASSWORD:-tessera}` substitution (line 26), but the connector JSON has no equivalent mechanism.

**Fix:** Either:
1. Template the file with a placeholder and have the `debezium-connector-init` entrypoint substitute it at runtime using `envsubst`, or
2. Replace the static JSON with a startup script that reads `$POSTGRES_PASSWORD` from the environment and emits the connector config inline via `curl -d "{...\"database.password\":\"$POSTGRES_PASSWORD\"...}"`.

At minimum, add a comment in the file and a note in the README warning that this file must not be used unmodified against any non-dev environment.

---

## Info

### IN-01: `model_config.updated_at` Has No Trigger to Keep It Current

**File:** `fabric-app/src/main/resources/db/migration/V27__tenant_hash_chain_config.sql:8`

**Issue:** `model_config.updated_at` defaults to `now()` at INSERT time but is not automatically updated on `UPDATE`. Any application code that modifies `hash_chain_enabled` without explicitly setting `updated_at` will leave the column stale.

**Fix:** Add an `updated_at` trigger on `model_config`, consistent with the pattern likely already used on other tables:
```sql
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN NEW.updated_at = now(); RETURN NEW; END;
$$;

CREATE TRIGGER trg_model_config_updated_at
BEFORE UPDATE ON model_config
FOR EACH ROW EXECUTE FUNCTION set_updated_at();
```

---

### IN-02: `V26__replication_slot_wal_limit.sql` Uses `ALTER SYSTEM` in a Migration — Will Fail Against Managed Databases

**File:** `fabric-app/src/main/resources/db/migration/V26__replication_slot_wal_limit.sql:10`

**Issue:** `ALTER SYSTEM SET max_slot_wal_keep_size = '2GB'` writes to `postgresql.auto.conf` — a server-side file that requires the Postgres instance to be running as a modifiable server process. This will fail against:
- Read-replica targets (Flyway may run against a replica)
- Managed cloud databases (Amazon RDS, Google Cloud SQL, Azure Database for PostgreSQL) where `ALTER SYSTEM` is blocked
- CI Testcontainers environments where the postgres user may not be superuser

The comment acknowledges this risk (line 8) but the current default behavior is to include this in the normal migration chain where any deviation from the expected environment causes a hard `Flyway.migrate()` failure.

**Fix:** Wrap the `ALTER SYSTEM` in an exception handler, or move it out of a Flyway migration into a separate operational runbook/Docker entrypoint script. A safer Flyway migration would target `postgresql.conf` via a `CREATE EXTENSION` parameter change or simply document the required `postgresql.conf` setting. If keeping it in Flyway, mark the migration `repeatable = false` and add a `DO $$ BEGIN ... EXCEPTION WHEN ... END $$;` PL/pgSQL block to fail gracefully:
```sql
DO $$
BEGIN
    ALTER SYSTEM SET max_slot_wal_keep_size = '2GB';
    PERFORM pg_reload_conf();
EXCEPTION WHEN insufficient_privilege THEN
    RAISE WARNING 'max_slot_wal_keep_size not set — insufficient privilege. Set manually in postgresql.conf.';
END;
$$;
```

---

### IN-03: `SqlViewProjection` Generates View Name Prefix from First 8 Hex Chars of UUID, Then Uses Same 8 Chars for `listViews` Filter — Low Collision Risk but Documented

**File:** `fabric-projections/src/main/java/dev/tessera/projections/sql/SqlViewProjection.java:153`

**Issue:** `listViews` filters the `activeViews` map by the 8-character hex prefix `v_{first8}_{...}`. Because only 32 bits of the 128-bit UUID are used for the prefix, two tenants whose UUIDs share the same first 8 hex characters will have their views cross-listed. This is a low-probability event but is not zero (birthday paradox: with ~65,536 tenants there is a ~50% chance of at least one collision).

**Fix:** Pass the full model UUID into `listViews` and compare against `v.modelId()` directly rather than via the string prefix:
```java
public List<ViewMetadata> listViews(UUID modelId) {
    return activeViews.values().stream()
            .filter(v -> modelId.equals(v.modelId()))
            .toList();
}
```

---

### IN-04: `OutboxPoller` `SELECT_SQL` Does Not Filter by `published = false` Partial Index

**File:** `fabric-core/src/main/java/dev/tessera/core/events/OutboxPoller.java:91-95`

**Issue:** The `SELECT_SQL` queries `WHERE status = 'PENDING'` without the `published = false` predicate. Migration V24 creates a partial index `idx_graph_outbox_unpublished` that covers `WHERE published = false`. The poller's query will use the `status` index (if one exists) rather than the partial index on `published`. This is not a correctness issue — `PENDING` rows also have `published = false` — but the stated intent of V24 is that the partial index keeps polling cheap (V24 comment, lines 8-12), which is only realised if the query predicate matches the index condition.

**Fix:** Add `AND published = false` to `SELECT_SQL`:
```java
private static final String SELECT_SQL = "SELECT id, model_id, event_id, aggregatetype, aggregateid, type, "
        + "payload, routing_hints, created_at "
        + "FROM graph_outbox WHERE status = 'PENDING' AND published = false "
        + "ORDER BY created_at LIMIT " + BATCH_SIZE + " "
        + "FOR UPDATE SKIP LOCKED";
```

---

_Reviewed: 2026-04-17_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
