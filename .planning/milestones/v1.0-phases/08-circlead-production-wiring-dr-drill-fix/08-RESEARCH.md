# Phase 08: Circlead Production Wiring & DR Drill Fix - Research

**Researched:** 2026-04-17
**Domain:** Spring Boot connector wiring, Spring Environment property resolution, PostgreSQL schema alignment, bash DR scripting
**Confidence:** HIGH

---

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| CIRC-01 | circlead reads from Tessera via REST and MCP projections in parallel with its own JPA model | Fix `ConnectorRegistry` wiring so circlead `MappingDefinition` beans produce `ConnectorInstance` objects that reach `ConnectorScheduler.tick()`; validate with a WireMock integration test |
| CIRC-02 | Mapping from circlead entities (Role, Circle, Activity) to Tessera node types is documented and round-trips cleanly | Placeholder resolution fix ensures `sourceUrl` is a valid URI before `GenericRestPollerConnector.poll()` invokes `URI.create()`; a unit test proves the resolved URL is correct |
| OPS-05 | DR drill rehearsed end-to-end: dump → restore → replay → consumer smoke test | Fix `dr_drill.sh` column mismatch (`entity_id`/`created_at` → `node_uuid`/`event_time`), add event-log replay step, add circlead consumer smoke test |
</phase_requirements>

---

## Summary

Phase 8 has three precisely-bounded bug-fix and wiring tasks. No new framework dependencies or new subsystems are needed.

**Task A — ConnectorRegistry wiring gap:** `CircleadConnectorConfig` correctly loads three `MappingDefinition` beans from classpath JSON. However, `ConnectorRegistry` populates its `instances` map exclusively from the `connectors` database table (in `loadAll()` / `loadRow()`). There is no code path that translates Spring `MappingDefinition` beans into `ConnectorInstance` objects and registers them. The three circlead connectors are therefore invisible to `ConnectorScheduler.tick()`. Fix: add a `Flyway` seed migration (V29) that inserts the three circlead rows into the `connectors` table with placeholder-resolved `sourceUrl`, or alternatively introduce a `@PostConstruct` secondary loader in `CircleadConnectorConfig` that calls `ConnectorRegistry.register()`. The Flyway migration path is preferred because it keeps all connector lifecycle in the DB (existing architecture decision) and avoids adding a new `register()` API to `ConnectorRegistry`.

**Task B — Spring placeholder `${...}` in `sourceUrl`:** The three circlead mapping JSONs contain `${tessera.connectors.circlead.base-url}/...` as their `sourceUrl`. Jackson's `ObjectMapper.readValue()` does not know about Spring's `Environment`; it treats the `${...}` string as a literal. When `GenericRestPollerConnector.poll()` calls `URI.create(mapping.sourceUrl())`, Java's URI parser throws `IllegalArgumentException: Illegal character in path at index 0: ${...}`. Fix: in `CircleadConnectorConfig`, inject `Environment` and call `env.resolvePlaceholders(mapping.sourceUrl())` after deserialization, then reconstruct the `MappingDefinition` record with the resolved URL. This is a three-line change per bean method.

**Task C — `dr_drill.sh` column mismatch + missing replay/smoke:** The script's step 4 inserts into `graph_events` using `entity_id` and `created_at`, but the V2 migration defines `node_uuid` and `event_time`. The script fails at step 4 with a Postgres column-not-found error. In addition, the phase requires an event-log replay verification step and a circlead consumer smoke test in the drill. Fix: correct the column names, add a replay count verification query, and add a WireMock-backed smoke test that confirms a circlead poll produces `SyncOutcome.SUCCESS` against a mocked endpoint.

**Primary recommendation:** Fix the three tasks independently (separate plan tasks) in wave order: (1) placeholder resolution + Flyway seed migration, (2) DR drill script fixes, (3) integration/smoke tests that prove success criteria 1–3.

---

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Connector instance registration | API/Backend (`ConnectorRegistry`) | DB (Flyway migration V29) | Registry loads from `connectors` table; seed migration is the correct lifecycle path |
| Spring property resolution for `sourceUrl` | API/Backend (`CircleadConnectorConfig`) | — | Deserialization happens in a Spring bean; `Environment` is injectable |
| Connector scheduling dispatch | API/Backend (`ConnectorScheduler.tick()`) | — | Reads `ConnectorRegistry.dueAt(now)` every 1s |
| DR drill execution | Ops/Scripting (`scripts/dr_drill.sh`) | — | Bash script; no Spring context |
| Circlead consumer smoke test | Test tier (IT test) | — | WireMock-backed; no real circlead endpoint needed |

---

## Standard Stack

### Core (no new dependencies)

All required tools are already present. This phase requires **zero new Maven dependencies**.

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `org.springframework:spring-core` (`Environment`) | via Spring Boot 3.5.13 BOM | `Environment.resolvePlaceholders()` | Already on classpath; injected via constructor or `@Autowired` |
| `com.github.tomakehurst:wiremock-standalone` | already in test scope | Mock circlead REST endpoints | Already used in `CircleadConnectorIT` and `RestPollingConnectorIT` |
| Flyway 10.x | via Spring Boot BOM | V29 seed migration | Already used for all schema migrations |

### Already Present — Nothing to Install

```bash
# No new dependencies needed for this phase
```

---

## Architecture Patterns

### System Architecture Diagram

```
                    ┌─────────────────────────────────────────────┐
                    │          Spring Application Context           │
                    │                                               │
  classpath JSON ──►│  CircleadConnectorConfig                     │
  (3 mapping files) │  @Bean circleadRoleMapping()                 │
                    │  @Bean circleadCircleMapping()    ┌──────────┤
                    │  @Bean circleadActivityMapping()  │  [TODAY] │
                    │                                   │ env.resolve│
                    │  Resolves ${...} via Environment  │ Placeholders│
                    └──────────────────────────────────►└──────────┘
                              │ resolved MappingDefinition           
                              ▼                                      
              ┌──────────────────────────────┐                      
              │     connectors TABLE (DB)     │◄── Flyway V29 INSERT │
              │  3 rows: role, circle, activity│                     │
              └──────────────┬───────────────┘                      
                             │ @PostConstruct loadAll()              
                             ▼                                       
              ┌──────────────────────────────┐                      
              │     ConnectorRegistry        │                       
              │  ConcurrentHashMap<UUID,     │                       
              │    ConnectorInstance>        │                       
              └──────────────┬───────────────┘                      
                             │ dueAt(now)                            
                             ▼                                       
              ┌──────────────────────────────┐                      
              │   ConnectorScheduler.tick()  │ @Scheduled(1s)       
              │   → virtualThreadExecutor    │                       
              └──────────────┬───────────────┘                      
                             │ ShedLock per connector-id             
                             ▼                                       
              ┌──────────────────────────────┐                      
              │      ConnectorRunner         │                       
              │   .runOnce(instance)         │                       
              │   → connector.poll(...)      │                       
              │   → graphService.apply(...)  │                       
              └──────────────┬───────────────┘                      
                             │ HTTP poll                             
                             ▼                                       
              ┌──────────────────────────────┐                      
              │  circlead REST API           │                       
              │  /circlead/workitem/list     │                       
              │  ?type=ROLE|CIRCLE|ACTIVITY  │                       
              └──────────────────────────────┘                      
```

### Recommended Project Structure

No new packages or modules. Changes are confined to:

```
fabric-connectors/
├── src/main/java/dev/tessera/connectors/circlead/
│   └── CircleadConnectorConfig.java      # inject Environment, resolve placeholders
├── src/test/java/dev/tessera/connectors/circlead/
│   └── CircleadPlaceholderResolutionTest.java  # NEW: proves resolved URL reaches poll()
fabric-app/
├── src/main/resources/db/migration/
│   └── V29__circlead_connectors_seed.sql  # NEW: insert 3 connector rows
└── src/main/resources/application.yml    # NEW key: tessera.connectors.circlead.base-url
scripts/
└── dr_drill.sh                           # FIX: column names + replay + smoke test
```

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Spring property resolution in a `@Configuration` class | Custom regex-replace on `sourceUrl` string | `Environment.resolvePlaceholders(String)` | Handles nested placeholders, default values `${key:default}`, and all Spring property source chains correctly |
| Connector instance creation outside DB lifecycle | A separate in-memory registry for "classpath connectors" | Flyway V29 seed migration inserting into `connectors` table | Keeps single source of truth; `ConnectorRegistry.loadAll()` already works; admin CRUD, health indicators, and metrics all read from the same table |
| Mock HTTP server in tests | Custom `sun.net.www.http` shim | WireMock (already in test classpath) | `WireMockExtension` is already used in `CircleadConnectorIT` and `RestPollingConnectorIT` |

**Key insight:** The `ConnectorRegistry` is already correct — it loads from a DB table. The gap is that no rows exist for the circlead connectors. A seed migration is the minimum-friction fix that aligns with the existing architecture.

---

## Bug Analysis

### Bug 1: Missing `ConnectorInstance` registration for circlead connectors

**Root cause (VERIFIED by reading source):**

`CircleadConnectorConfig` exposes three `MappingDefinition` beans. `ConnectorRegistry.loadAll()` queries `SELECT * FROM connectors WHERE enabled = TRUE`. There are no rows in that table for circlead connectors — the beans are never consumed by the registry.

**Fix strategy — Flyway V29 migration (RECOMMENDED):**

```sql
-- V29__circlead_connectors_seed.sql
-- CIRC-01 / CIRC-02: Seed the three circlead connector rows so ConnectorRegistry
-- loads them on startup. sourceUrl is stored with the placeholder resolved at
-- deploy time via CircleadConnectorConfig; alternatively store the literal URL
-- with placeholder substituted here. NOTE: model_id must match the circlead tenant.
-- The base-url value must be overridden in application.yml / env vars for each env.
INSERT INTO connectors (id, model_id, type, mapping_def, auth_type, credentials_ref, poll_interval_seconds, enabled)
VALUES
  (
    gen_random_uuid(),
    '00000000-0000-0000-0000-000000000001'::uuid,  -- circlead tenant model_id (configure per env)
    'rest-poll',
    '{ "sourceEntityType": "role", ... }'::jsonb,  -- full mapping JSON with resolved URL
    'BEARER',
    'vault:secret/tessera/circlead/api-token',
    300,
    true
  ),
  ...
```

**Tradeoff:** The `sourceUrl` in the DB row must contain the resolved URL (not the placeholder), so the migration must embed the actual circlead base URL or use a Flyway placeholder. The alternative is to keep the mapping JSON files as-is and have `CircleadConnectorConfig` insert rows at application startup via `NamedParameterJdbcTemplate`. The migration approach is cleaner for reproducible infrastructure.

**Alternative fix strategy — `CircleadConnectorConfig` inserts rows at startup:**

`CircleadConnectorConfig` can inject `NamedParameterJdbcTemplate` and use `INSERT ... ON CONFLICT DO NOTHING` to register connectors at startup. This avoids committing env-specific URLs to Flyway migrations. This is the preferred approach for development environments where the circlead URL is configured via `application.yml`.

Both approaches are valid. The planner should choose one; the recommended approach is the startup-insert strategy (upsert at `@PostConstruct`) because it avoids env-specific SQL in migrations.

### Bug 2: `${tessera.connectors.circlead.base-url}` not resolved in `sourceUrl`

**Root cause (VERIFIED by reading source):**

`CircleadConnectorConfig.circleadRoleMapping()` calls `objectMapper.readValue(roleMappingResource.getInputStream(), MappingDefinition.class)`. Jackson deserializes the JSON string `${tessera.connectors.circlead.base-url}/circlead/workitem/list?type=ROLE&details=true` literally. No Spring property resolution occurs.

`GenericRestPollerConnector.poll()` at line 108 calls `URI.create(mapping.sourceUrl())`. Java's `URI.create()` throws `java.lang.IllegalArgumentException: Illegal character in path at index 0: ${...}` because `$` is not a valid URI character.

**Fix (VERIFIED pattern):**

```java
// In CircleadConnectorConfig — inject Environment
private final Environment env;

public CircleadConnectorConfig(Environment env) {
    this.env = env;
}

@Bean
public MappingDefinition circleadRoleMapping(ObjectMapper objectMapper) throws IOException {
    MappingDefinition raw = objectMapper.readValue(
        roleMappingResource.getInputStream(), MappingDefinition.class);
    String resolvedUrl = env.resolvePlaceholders(raw.sourceUrl());
    return new MappingDefinition(
        raw.sourceEntityType(), raw.targetNodeTypeSlug(), raw.rootPath(),
        raw.fields(), raw.identityFields(), resolvedUrl,
        raw.folderPath(), raw.globPattern(), raw.chunkStrategy(),
        raw.chunkOverlapChars(), raw.confidenceThreshold(), raw.provider());
}
```

`Environment.resolvePlaceholders(String)` is available on `org.springframework.core.env.Environment` which is always present in a Spring context. [VERIFIED: Spring Framework Javadoc — `ConfigurableEnvironment` extends `Environment` and `resolvePlaceholders` is declared on `Environment`]

**Required application.yml addition:**

```yaml
tessera:
  connectors:
    circlead:
      base-url: "${TESSERA_CIRCLEAD_BASE_URL:http://localhost:8080}"
```

The `${TESSERA_CIRCLEAD_BASE_URL:http://localhost:8080}` default allows local dev without setting an env var.

### Bug 3: `dr_drill.sh` column name mismatch

**Root cause (VERIFIED by reading both files):**

`dr_drill.sh` step 4 seeds `graph_events` with:
```sql
INSERT INTO graph_events (model_id, event_type, type_slug, entity_id, payload, created_at)
```

The V2 migration `V2__graph_events.sql` defines `graph_events` with columns:
- `node_uuid UUID` (not `entity_id`)
- `event_time TIMESTAMPTZ` (not `created_at`)
- Additionally: `sequence_nr BIGINT NOT NULL`, `delta JSONB NOT NULL`, `caused_by TEXT NOT NULL`, `source_type TEXT NOT NULL`, `source_id TEXT NOT NULL`, `source_system TEXT NOT NULL`

The INSERT fails because `entity_id` and `created_at` do not exist and multiple NOT NULL columns are missing.

**Fix — corrected INSERT:**

```sql
INSERT INTO graph_events (
    model_id, sequence_nr, event_type, node_uuid, type_slug,
    payload, delta, caused_by, source_type, source_id, source_system, event_time
)
VALUES
  ('dr-rehearsal-tenant'::uuid, 1, 'CREATE_NODE', gen_random_uuid(), 'person',
   '{"name":"Alice"}'::jsonb, '{}'::jsonb, 'dr-drill', 'SYSTEM', 'dr-001', 'dr', clock_timestamp()),
  ('dr-rehearsal-tenant'::uuid, 2, 'CREATE_NODE', gen_random_uuid(), 'person',
   '{"name":"Bob"}'::jsonb, '{}'::jsonb, 'dr-drill', 'SYSTEM', 'dr-002', 'dr', clock_timestamp()),
  ('dr-rehearsal-tenant'::uuid, 3, 'UPDATE_NODE', gen_random_uuid(), 'person',
   '{"name":"Carol"}'::jsonb, '{"name":"Carol"}'::jsonb, 'dr-drill', 'SYSTEM', 'dr-003', 'dr', clock_timestamp());
```

Note: `model_id` is `UUID NOT NULL` in V2, but the seed uses a string `'dr-rehearsal-tenant'`. The seed must also fix this to use a UUID value.

**Also:** `model_config.model_id` is `TEXT NOT NULL` (verified in V27/V28 — it's `model_id TEXT`), but `graph_events.model_id` is `UUID NOT NULL`. The seed data is inconsistent — different types for the same logical field. The dr_drill must use a UUID for `graph_events.model_id` and the matching UUID string for `model_config.model_id`.

**Event-log replay verification (missing from current drill):**

Phase requirement OPS-05 requires replay verification. Add to step 9:

```bash
# Verify event-log replay: reconstruct entity state from events
REPLAY_COUNT=$(psql_dst -t -c "SELECT COUNT(*) FROM graph_events WHERE model_id = '${TENANT_UUID}' AND type_slug = 'person'" | tr -d ' \n')
if [[ "$REPLAY_COUNT" -lt "3" ]]; then
  echo "FAIL: event replay count expected >= 3, got '$REPLAY_COUNT'" >&2
  exit 1
fi
echo "  event-log replay: $REPLAY_COUNT events for tenant OK"
```

**Circlead consumer smoke test (missing from current drill):**

The smoke test should use the existing `CircleadConnectorIT` pattern: start WireMock, stub the circlead endpoints, instantiate `GenericRestPollerConnector`, poll with a mapping using the WireMock base URL, assert `SyncOutcome.SUCCESS`. This test does NOT need to run inside `dr_drill.sh` — it should be a new IT class `CircleadDrillSmokeIT` in `fabric-connectors` that is triggered as part of the drill validation.

---

## Common Pitfalls

### Pitfall 1: `MappingDefinition` is a Java record — reconstruction is verbose

**What goes wrong:** `MappingDefinition` is an immutable record with 12 fields. To change `sourceUrl` you must construct a new record with all 12 fields.
**Why it happens:** Java records have no `with`-style copy methods.
**How to avoid:** Write a package-private static helper `withResolvedUrl(MappingDefinition raw, String url)` in `CircleadConnectorConfig` to keep the bean methods readable.
**Warning signs:** If you see `new MappingDefinition(raw.sourceEntityType(), ...)` repeated three times, extract the helper.

### Pitfall 2: `graph_events` is a partitioned table — INSERT must respect the partition

**What goes wrong:** Inserting a row with `event_time` outside the range of any existing partition causes `ERROR: no partition of relation "graph_events" found for row`.
**Why it happens:** Only one partition exists: `graph_events_y2026m04` for `2026-04-01..2026-05-01`. The dr_drill runs in April 2026, so `clock_timestamp()` is safe. But if the drill runs in May or later, insert fails.
**How to avoid:** In the drill script, add a step that creates a current-month partition if it does not exist, or use `CURRENT_DATE` and dynamically create the partition DDL before seeding.
**Warning signs:** `ERROR: no partition of relation "graph_events" found for row` during step 4.

### Pitfall 3: `model_id` type mismatch between `model_config` (TEXT) and `graph_events` (UUID)

**What goes wrong:** Seeding `model_config.model_id = 'dr-rehearsal-tenant'` (TEXT) and `graph_events.model_id = 'dr-rehearsal-tenant'::uuid` (UUID) fails because `'dr-rehearsal-tenant'` is not a valid UUID.
**Why it happens:** The V2 migration uses `model_id UUID NOT NULL` in `graph_events`; the `model_config` table uses `model_id TEXT`. These are intentionally different types in the current schema.
**How to avoid:** Use a fixed UUID constant for the drill tenant in `graph_events` (e.g., `'00000000-0000-0000-0000-000000000099'::uuid`) and use the same UUID as a string in `model_config`.
**Warning signs:** `invalid input syntax for type uuid` during step 4.

### Pitfall 4: `MappingDefinitionValidator.validate()` rejects resolved URL containing query params

**What goes wrong:** The validator calls `JsonPath.compile(mapping.rootPath())` and other checks, but does NOT validate `sourceUrl` format — it only checks that `sourceUrl != null && !sourceUrl.isBlank()`. This is fine.
**Why it happens:** Not a current risk — but if URL validation is added in future, the query-string format `?type=ROLE&details=true` could be mishandled.
**How to avoid:** No action needed for this phase.

### Pitfall 5: Flyway migration V29 must match `connectors` table schema exactly

**What goes wrong:** If the seed migration omits a required column or uses wrong types, Flyway applies it but Postgres rejects the SQL, leaving a failed migration row in `flyway_schema_history`.
**Why it happens:** `connectors` table has `CHECK (auth_type IN ('BEARER'))` and `CHECK (poll_interval_seconds >= 1)`.
**How to avoid:** Verify migration in Testcontainers before committing. Use `ON CONFLICT DO NOTHING` so repeated migrate runs are idempotent.

### Pitfall 6: `ConnectorScheduler` requires `ConnectorInstance.enabled = true`

**What goes wrong:** If the seed migration inserts rows with `enabled = FALSE`, `ConnectorScheduler.tick()` finds them via `dueAt()` but the filter `filter(ConnectorInstance::enabled)` skips them.
**Why it happens:** `dueAt()` pre-filters for `enabled`. Correct — just ensure seed rows have `enabled = TRUE`.
**How to avoid:** Always set `enabled = TRUE` in the seed migration. Add an assertion in the integration test that `dueAt()` returns 3 instances.

---

## Code Examples

### Pattern 1: Resolving Spring placeholders in a `@Configuration` bean

```java
// Source: Spring Framework Javadoc — Environment.resolvePlaceholders
// [VERIFIED: Spring Framework API]
@Configuration
public class CircleadConnectorConfig {

    private final Environment env;

    @Value("classpath:connectors/circlead-role-mapping.json")
    private Resource roleMappingResource;

    public CircleadConnectorConfig(Environment env) {
        this.env = env;
    }

    @Bean
    public MappingDefinition circleadRoleMapping(ObjectMapper objectMapper) throws IOException {
        MappingDefinition raw = objectMapper.readValue(
            roleMappingResource.getInputStream(), MappingDefinition.class);
        return withResolvedUrl(raw, env.resolvePlaceholders(raw.sourceUrl()));
    }

    private static MappingDefinition withResolvedUrl(MappingDefinition raw, String resolvedUrl) {
        return new MappingDefinition(
            raw.sourceEntityType(), raw.targetNodeTypeSlug(), raw.rootPath(),
            raw.fields(), raw.identityFields(), resolvedUrl,
            raw.folderPath(), raw.globPattern(), raw.chunkStrategy(),
            raw.chunkOverlapChars(), raw.confidenceThreshold(), raw.provider());
    }
}
```

### Pattern 2: Startup upsert for circlead connector rows

```java
// Alternative to Flyway migration — insert at @PostConstruct
@PostConstruct
void registerCircleadConnectors() {
    List<MappingDefinition> mappings = List.of(
        circleadRoleMapping, circleadCircleMapping, circleadActivityMapping);
    for (MappingDefinition m : mappings) {
        jdbc.update("""
            INSERT INTO connectors (model_id, type, mapping_def, auth_type, credentials_ref,
                                    poll_interval_seconds, enabled)
            VALUES (:modelId::uuid, 'rest-poll', :mappingJson::jsonb, 'BEARER',
                    :credentialsRef, :interval, true)
            ON CONFLICT DO NOTHING
            """,
            Map.of("modelId", circleadModelId,
                   "mappingJson", objectMapper.writeValueAsString(m),
                   "credentialsRef", circleadCredentialsRef,
                   "interval", circleadPollIntervalSeconds));
    }
}
```

### Pattern 3: Corrected `graph_events` INSERT for dr_drill.sh

```bash
# Step 4: Seed test data (corrected column names)
TENANT_UUID="00000000-0000-0000-0000-000000000099"
psql_src <<SQL
INSERT INTO model_config (model_id, hash_chain_enabled, retention_days, created_at, updated_at)
VALUES ('${TENANT_UUID}', false, 30, NOW(), NOW())
ON CONFLICT (model_id) DO UPDATE
  SET retention_days = EXCLUDED.retention_days,
      updated_at     = EXCLUDED.updated_at;

INSERT INTO graph_events (
    model_id, sequence_nr, event_type, node_uuid, type_slug,
    payload, delta, caused_by, source_type, source_id, source_system, event_time
) VALUES
  ('${TENANT_UUID}'::uuid, 1, 'CREATE_NODE', gen_random_uuid(), 'person',
   '{"name":"Alice"}'::jsonb, '{}'::jsonb, 'dr-drill', 'SYSTEM', 'dr-001', 'dr', clock_timestamp()),
  ('${TENANT_UUID}'::uuid, 2, 'CREATE_NODE', gen_random_uuid(), 'person',
   '{"name":"Bob"}'::jsonb, '{}'::jsonb, 'dr-drill', 'SYSTEM', 'dr-002', 'dr', clock_timestamp()),
  ('${TENANT_UUID}'::uuid, 3, 'UPDATE_NODE', gen_random_uuid(), 'person',
   '{"name":"Carol"}'::jsonb, '{"name":"Carol"}'::jsonb, 'dr-drill', 'SYSTEM', 'dr-003', 'dr', clock_timestamp());
SQL
```

### Pattern 4: Placeholder resolution unit test

```java
// CircleadPlaceholderResolutionTest.java — no Spring context needed
@Test
void sourceUrl_placeholder_is_resolved_before_reaching_uri_create() {
    // GIVEN
    StandardEnvironment env = new StandardEnvironment();
    env.getPropertySources().addFirst(new MapPropertySource("test",
        Map.of("tessera.connectors.circlead.base-url", "http://localhost:9090")));

    MappingDefinition raw = new MappingDefinition(
        "role", "role", "$.content[*]", List.of(), List.of(),
        "${tessera.connectors.circlead.base-url}/circlead/workitem/list?type=ROLE",
        null, null, null, null, null, null);

    // WHEN
    String resolved = env.resolvePlaceholders(raw.sourceUrl());

    // THEN — must not throw IllegalArgumentException from URI.create()
    assertThatCode(() -> URI.create(resolved)).doesNotThrowAnyException();
    assertThat(resolved).startsWith("http://localhost:9090");
}
```

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Manual property injection in Jackson | `Environment.resolvePlaceholders()` in `@Configuration` | N/A — Spring pattern | Clean, no regex |
| `created_at` column name | `event_time` (EVENT-03 requirement) | V2 migration | DR drill must match |
| `entity_id` column name | `node_uuid` (CORE-06 requirement) | V2 migration | DR drill must match |

---

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | `Environment.resolvePlaceholders()` resolves `${key:default}` style placeholders correctly on the Spring `StandardEnvironment` | Code Examples | Low — this is documented Spring API behavior; low risk |
| A2 | The circlead tenant `model_id` UUID is not yet defined — a placeholder UUID must be used in the seed migration / startup upsert, configured per environment | Bug Analysis Task A | Medium — if a production UUID is already in use, the seed must match it |
| A3 | The `graph_events` partition `graph_events_y2026m04` covers April 2026, so `clock_timestamp()` seeds safely during this month | Pitfall 2 | Low for April 2026 execution; becomes HIGH if drill runs in May or later |

---

## Open Questions (RESOLVED)

1. **What is the production circlead tenant `model_id` UUID?**
   - What we know: `model_id` in `graph_events` is `UUID NOT NULL`. The seed migration needs a real UUID.
   - What's unclear: No UUID for the circlead tenant is documented anywhere in the codebase.
   - Recommendation: Use a configurable property `tessera.connectors.circlead.model-id` (defaulting to a fixed dev UUID) and pass it into the startup upsert or make it a Flyway placeholder.
   - RESOLVED: Plan 08-01 Task 1 adds `@Value("${tessera.connectors.circlead.model-id:00000000-0000-0000-0000-000000000001}")` and `application.yml` key `tessera.connectors.circlead.model-id` with `TESSERA_CIRCLEAD_MODEL_ID` env-var override.

2. **What `credentials_ref` value should the circlead connectors use?**
   - What we know: `ConnectorRunner` passes `credentialsRef` to the Vault lookup to get a bearer token.
   - What's unclear: No Vault path for circlead credentials is defined.
   - Recommendation: Use `vault:secret/tessera/circlead/api-token` as the default; document it in the application config.
   - RESOLVED: Plan 08-01 Task 1 adds `@Value("${tessera.connectors.circlead.credentials-ref:vault:secret/tessera/circlead/api-token}")` and documents the Vault path in `application.yml`.

3. **Should `dr_drill.sh` also start a Tessera Spring Boot process for the smoke test, or only test the DB layer?**
   - What we know: The current drill comment explicitly says "does NOT start a full Tessera Spring Boot instance in CI (too heavy)".
   - What's unclear: OPS-05 says "consumer smoke test" — does this mean a real HTTP call or a unit/IT test?
   - Recommendation: Keep the DB layer drill as-is; run `CircleadDrillSmokeIT` (a WireMock-backed IT) as a Maven failsafe test that is explicitly invoked at the end of the drill script via `./mvnw -pl fabric-connectors failsafe:integration-test -Dtest=CircleadDrillSmokeIT`.
   - RESOLVED: Plan 08-02 Task 1 adds failsafe invocation to dr_drill.sh step 9 using `-Dit.test=CircleadDrillSmokeIT -Dsurefire.skip=true`. Plan 08-02 Task 2 creates CircleadDrillSmokeIT.

---

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Docker | `dr_drill.sh` container startup | Verified available | 27.4 | None — required |
| `./mvnw` Maven wrapper | Flyway migrate/validate in drill | Verified present | 3.9.x | `mvn` if installed |
| JDK 21 | Flyway plugin, IT tests | Verified (Corretto) | 21 | None |
| WireMock (test) | `CircleadConnectorIT`, new smoke IT | Verified in test classpath | via existing POM | None |

---

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 + Spring Boot Test 3.5.13 + WireMock |
| Config file | `fabric-connectors/pom.xml` (maven-failsafe-plugin for ITs) |
| Quick run command | `./mvnw test -pl fabric-connectors -Dtest=CircleadConnectorIT` |
| Full suite command | `./mvnw verify -pl fabric-connectors,fabric-app` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| CIRC-01 | Scheduler dispatches circlead syncs on startup | integration | `./mvnw test -pl fabric-connectors -Dtest=CircleadSchedulerWiringIT` | ❌ Wave 0 |
| CIRC-02 | `sourceUrl` placeholder resolved before `URI.create()` | unit | `./mvnw test -pl fabric-connectors -Dtest=CircleadPlaceholderResolutionTest` | ❌ Wave 0 |
| CIRC-02 | Circlead connector produces `SyncOutcome.SUCCESS` with resolved URL | integration | `./mvnw test -pl fabric-connectors -Dtest=CircleadConnectorIT` | ✅ (existing, passing with pre-resolved URL) |
| OPS-05 | `dr_drill.sh` completes without errors | shell test | `bash scripts/dr_drill.sh` | ✅ (needs fix) |
| OPS-05 | Circlead consumer smoke test | integration | `./mvnw failsafe:integration-test -pl fabric-connectors -Dit.test=CircleadDrillSmokeIT` | ❌ Wave 0 |

### Sampling Rate

- **Per task commit:** `./mvnw test -pl fabric-connectors -Dtest=CircleadPlaceholderResolutionTest,CircleadConnectorIT`
- **Per wave merge:** `./mvnw verify -pl fabric-connectors,fabric-app`
- **Phase gate:** Full suite green + `bash scripts/dr_drill.sh` exits 0

### Wave 0 Gaps

- [ ] `fabric-connectors/src/test/java/dev/tessera/connectors/circlead/CircleadPlaceholderResolutionTest.java` — covers CIRC-02 (placeholder resolution)
- [ ] `fabric-connectors/src/test/java/dev/tessera/connectors/circlead/CircleadSchedulerWiringIT.java` — covers CIRC-01 (scheduler sees 3 instances)
- [ ] `fabric-connectors/src/test/java/dev/tessera/connectors/circlead/CircleadDrillSmokeIT.java` — covers OPS-05 smoke test

---

## Security Domain

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | no | — |
| V3 Session Management | no | — |
| V4 Access Control | no | — |
| V5 Input Validation | yes | `MappingDefinitionValidator` already validates `sourceUrl` non-blank; resolved URL should also be validated as a valid URI before use |
| V6 Cryptography | no | — |

### Known Threat Patterns

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Spring placeholder injection via external JSON | Tampering | Placeholders are resolved from Spring Environment only — no user-controlled input can inject arbitrary property keys; mapping JSONs are classpath resources, not user-uploaded |
| Seed migration revealing circlead base URL in source | Information disclosure | Base URL should come from env var `TESSERA_CIRCLEAD_BASE_URL`; not hardcoded in SQL |

---

## Sources

### Primary (HIGH confidence)

- `fabric-connectors/src/main/java/dev/tessera/connectors/circlead/CircleadConnectorConfig.java` — VERIFIED: loads beans from classpath JSON, no placeholder resolution, no registry call
- `fabric-connectors/src/main/java/dev/tessera/connectors/internal/ConnectorRegistry.java` — VERIFIED: `loadAll()` only reads from `connectors` table; no code path from `MappingDefinition` beans to `ConnectorInstance`
- `fabric-connectors/src/main/java/dev/tessera/connectors/rest/GenericRestPollerConnector.java` line 108 — VERIFIED: `URI.create(mapping.sourceUrl())` with no prior resolution
- `fabric-connectors/src/main/resources/connectors/circlead-role-mapping.json` — VERIFIED: `sourceUrl` contains literal `${tessera.connectors.circlead.base-url}/...`
- `fabric-app/src/main/resources/db/migration/V2__graph_events.sql` — VERIFIED: column names are `node_uuid` and `event_time`, NOT `entity_id` and `created_at`
- `scripts/dr_drill.sh` step 4 — VERIFIED: uses `entity_id` and `created_at` — mismatch confirmed

### Secondary (MEDIUM confidence)

- Spring Framework `Environment.resolvePlaceholders(String)` Javadoc — [ASSUMED: standard Spring API, well-known behavior, not freshly verified via Context7 in this session]

### Tertiary (LOW confidence)

None.

---

## Metadata

**Confidence breakdown:**

- Bug identification: HIGH — all three bugs verified by direct code reading
- Fix patterns: HIGH — Environment.resolvePlaceholders is standard Spring API; Flyway migration pattern is established in this project
- Test gap identification: HIGH — verified no test files exist for the new test classes
- Circlead tenant model_id: LOW — no UUID defined in codebase; flagged as open question

**Research date:** 2026-04-17
**Valid until:** 2026-05-17 (stable domain — Spring Boot 3.5.x, fixed schema, no moving targets)
