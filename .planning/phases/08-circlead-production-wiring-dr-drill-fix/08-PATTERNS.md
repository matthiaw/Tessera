# Phase 08: Circlead Production Wiring & DR Drill Fix - Pattern Map

**Mapped:** 2026-04-17
**Files analyzed:** 6 (3 modified, 3 new)
**Analogs found:** 6 / 6

---

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|-------------------|------|-----------|----------------|---------------|
| `fabric-connectors/src/main/java/dev/tessera/connectors/circlead/CircleadConnectorConfig.java` | config/provider | request-response | itself (current broken state is the subject of the fix) | self |
| `fabric-app/src/main/resources/db/migration/V29__circlead_connectors_seed.sql` | migration | CRUD | `fabric-app/src/main/resources/db/migration/V21__connectors_auth_type_widen.sql` | role-match |
| `fabric-app/src/main/resources/application.yml` | config | — | itself (add one key) | self |
| `scripts/dr_drill.sh` | utility/ops | batch | itself (fix column names + add steps) | self |
| `fabric-connectors/src/test/java/dev/tessera/connectors/circlead/CircleadPlaceholderResolutionTest.java` | test (unit) | request-response | `fabric-connectors/src/test/java/dev/tessera/connectors/rest/MappingDefinitionValidationTest.java` | exact |
| `fabric-connectors/src/test/java/dev/tessera/connectors/circlead/CircleadSchedulerWiringIT.java` | test (integration) | event-driven | `fabric-app/src/test/java/dev/tessera/app/OutboxPollerConditionalIT.java` | role-match |
| `fabric-connectors/src/test/java/dev/tessera/connectors/circlead/CircleadDrillSmokeIT.java` | test (integration) | request-response | `fabric-connectors/src/test/java/dev/tessera/connectors/circlead/CircleadConnectorIT.java` | exact |

---

## Pattern Assignments

### `fabric-connectors/src/main/java/dev/tessera/connectors/circlead/CircleadConnectorConfig.java` (config, CIRC-01/CIRC-02)

**Analog:** itself — the current file is the subject of modification. All patterns needed are drawn from within this file and from `ConnectorRegistry.java`.

**Current imports** (lines 18-24 of `CircleadConnectorConfig.java`):
```java
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.tessera.connectors.MappingDefinition;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
```

**Add these imports** (no new Maven deps — all on classpath):
```java
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.tessera.connectors.MappingDefinition;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
```

**Bug fix: placeholder resolution pattern** (replace each `@Bean` body, add helper):
```java
// Constructor-inject Environment — standard Spring pattern
private final Environment env;

public CircleadConnectorConfig(Environment env) {
    this.env = env;
}

// Each @Bean method: deserialize then resolve, never pass raw to URI.create()
@Bean
public MappingDefinition circleadRoleMapping(ObjectMapper objectMapper) throws IOException {
    MappingDefinition raw = objectMapper.readValue(
        roleMappingResource.getInputStream(), MappingDefinition.class);
    return withResolvedUrl(raw, env.resolvePlaceholders(raw.sourceUrl()));
}

// Package-private helper — avoids repeating 12-arg constructor call 3x
static MappingDefinition withResolvedUrl(MappingDefinition raw, String resolvedUrl) {
    return new MappingDefinition(
        raw.sourceEntityType(), raw.targetNodeTypeSlug(), raw.rootPath(),
        raw.fields(), raw.identityFields(), resolvedUrl,
        raw.folderPath(), raw.globPattern(), raw.chunkStrategy(),
        raw.chunkOverlapChars(), raw.confidenceThreshold(), raw.provider());
}
```

**`MappingDefinition` record signature** (from `MappingDefinition.java` lines 45-58 — all 12 fields in order):
```java
public record MappingDefinition(
    String sourceEntityType,
    String targetNodeTypeSlug,
    String rootPath,
    List<FieldMapping> fields,
    List<String> identityFields,
    String sourceUrl,
    @JsonProperty("folder_path") String folderPath,
    @JsonProperty("glob_pattern") String globPattern,
    @JsonProperty("chunk_strategy") String chunkStrategy,
    @JsonProperty("chunk_overlap_chars") Integer chunkOverlapChars,
    @JsonProperty("confidence_threshold") Double confidenceThreshold,
    String provider)
```

**Startup upsert pattern** (alternative to V29 migration — use `@PostConstruct` + `NamedParameterJdbcTemplate`):

Source: `ConnectorRegistry.java` lines 55-64 shows the JDBC template injection pattern; lines 67-82 show the `@PostConstruct` pattern to follow:
```java
// From ConnectorRegistry.java lines 55-64:
public ConnectorRegistry(
        NamedParameterJdbcTemplate jdbc,
        ObjectMapper objectMapper,
        List<Connector> connectors,
        SyncStatusRepository syncStatusRepo) {
    this.jdbc = jdbc;
    // ...
}

// @PostConstruct pattern (ConnectorRegistry.java lines 66-82):
@PostConstruct
void loadAll() {
    try {
        // ... JDBC query
    } catch (Exception e) {
        LOG.warn("ConnectorRegistry startup load failed (table may not exist yet): {}", e.getMessage());
    }
}
```

Apply same `@PostConstruct` with upsert to `CircleadConnectorConfig`:
```java
// Inject alongside Environment
private final NamedParameterJdbcTemplate jdbc;
private final ObjectMapper objectMapper;

@Value("${tessera.connectors.circlead.model-id:00000000-0000-0000-0000-000000000001}")
private String circleadModelId;

@Value("${tessera.connectors.circlead.credentials-ref:vault:secret/tessera/circlead/api-token}")
private String circleadCredentialsRef;

@Value("${tessera.connectors.circlead.poll-interval-seconds:300}")
private int circleadPollIntervalSeconds;

@PostConstruct
void registerCircleadConnectors() throws Exception {
    List<MappingDefinition> mappings = List.of(
        circleadRoleMapping(objectMapper),
        circleadCircleMapping(objectMapper),
        circleadActivityMapping(objectMapper));
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

---

### `fabric-app/src/main/resources/db/migration/V29__circlead_connectors_seed.sql` (migration, CRUD)

**Analog:** `fabric-app/src/main/resources/db/migration/V21__connectors_auth_type_widen.sql`

**Migration header pattern** (from V21 lines 1-6):
```sql
-- Copyright 2026 Tessera Contributors
-- Licensed under the Apache License, Version 2.0
--
-- Phase 8 / CIRC-01: Seed three circlead connector rows so ConnectorRegistry
-- loads them on startup via loadAll().
```

**`connectors` table constraints to respect** (verified from V21 + codebase reading):
- `CHECK (auth_type IN ('BEARER', 'NONE'))` — use `'BEARER'` for circlead
- `CHECK (poll_interval_seconds >= 1)`
- `credentials_ref` is nullable (after V21 widening)

**Idempotent seed pattern** — use `ON CONFLICT DO NOTHING`:
```sql
INSERT INTO connectors (model_id, type, mapping_def, auth_type, credentials_ref,
                        poll_interval_seconds, enabled)
VALUES (
    '00000000-0000-0000-0000-000000000001'::uuid,
    'rest-poll',
    '{ ... full mapping JSON with resolved URL ... }'::jsonb,
    'BEARER',
    'vault:secret/tessera/circlead/api-token',
    300,
    true
)
ON CONFLICT DO NOTHING;
```

**Note:** The startup-upsert `@PostConstruct` approach in `CircleadConnectorConfig` is preferred over this migration for dev environments (avoids env-specific URLs in SQL). If the V29 migration path is chosen, the `sourceUrl` in the JSONB must contain the literal resolved URL (not the placeholder), obtained from `TESSERA_CIRCLEAD_BASE_URL` env var via a Flyway placeholder.

---

### `fabric-app/src/main/resources/application.yml` (config, CIRC-02)

**Analog:** itself — add one new block under the `tessera:` key.

**Existing `tessera:` block pattern** (lines 32-49 of `application.yml`):
```yaml
tessera:
  version: 0.1.0-SNAPSHOT
  kafka:
    enabled: false
  security:
    field-encryption:
      enabled: false
  auth:
    jwt-signing-key: "${TESSERA_JWT_SIGNING_KEY:}"
    token-ttl-minutes: 15
    bootstrap-token: "${TESSERA_BOOTSTRAP_TOKEN:}"
  extraction:
    model: claude-sonnet-4-5
```

**Add new key** (follow `${ENV_VAR:default}` pattern already used for jwt-signing-key):
```yaml
tessera:
  connectors:
    circlead:
      base-url: "${TESSERA_CIRCLEAD_BASE_URL:http://localhost:8080}"
      model-id: "${TESSERA_CIRCLEAD_MODEL_ID:00000000-0000-0000-0000-000000000001}"
      credentials-ref: "${TESSERA_CIRCLEAD_CREDENTIALS_REF:vault:secret/tessera/circlead/api-token}"
      poll-interval-seconds: 300
```

---

### `scripts/dr_drill.sh` (ops/utility, batch, OPS-05)

**Analog:** itself — the existing script at `scripts/dr_drill.sh`. Fix two bugs in Step 4, add replay verification in Step 9, add smoke test invocation.

**Correct Step 4 INSERT** (replace lines 110-125):

Current broken version:
```bash
INSERT INTO graph_events (model_id, event_type, type_slug, entity_id, payload, created_at)
VALUES
  ('dr-rehearsal-tenant', 'CREATE', 'person', gen_random_uuid(), '{"name":"Alice"}', NOW()),
```

Corrected version — use UUID constant for `model_id`, correct column names per `V2__graph_events.sql`:
```bash
# Step 4: Seed test data
TENANT_UUID="00000000-0000-0000-0000-000000000099"
psql_src <<SQL
-- Seed model_config (model_id is TEXT in model_config per V27 migration)
INSERT INTO model_config (model_id, hash_chain_enabled, retention_days, created_at, updated_at)
VALUES ('${TENANT_UUID}', false, 30, NOW(), NOW())
ON CONFLICT (model_id) DO UPDATE
  SET retention_days = EXCLUDED.retention_days,
      updated_at     = EXCLUDED.updated_at;

-- Seed graph_events with CORRECT column names (V2__graph_events.sql):
-- node_uuid (not entity_id), event_time (not created_at)
-- Partition graph_events_y2026m04 covers 2026-04-01..2026-05-01; clock_timestamp() is safe in April 2026
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

**Corrected Step 9 smoke test** (replace lines 169-184 — fix model_id query to use UUID and add replay count + smoke IT):
```bash
echo "==> [9/9] smoke test: verifying data integrity in destination"
RETENTION=$(psql_dst -t -c "SELECT retention_days FROM model_config WHERE model_id = '${TENANT_UUID}'" | tr -d ' \n')
if [[ "$RETENTION" != "30" ]]; then
  echo "FAIL: model_config.retention_days expected 30, got '${RETENTION}'" >&2
  exit 1
fi
echo "  model_config.retention_days = $RETENTION (expected 30) OK"

EVENT_COUNT=$(psql_dst -t -c "SELECT COUNT(*) FROM graph_events WHERE model_id = '${TENANT_UUID}'::uuid" | tr -d ' \n')
if [[ "$EVENT_COUNT" -lt "3" ]]; then
  echo "FAIL: graph_events row count expected >= 3, got '${EVENT_COUNT}'" >&2
  exit 1
fi
echo "  graph_events row count = $EVENT_COUNT (expected >= 3) OK"

# Event-log replay verification (OPS-05): reconstruct entity state from events
REPLAY_COUNT=$(psql_dst -t -c "SELECT COUNT(*) FROM graph_events WHERE model_id = '${TENANT_UUID}'::uuid AND type_slug = 'person'" | tr -d ' \n')
if [[ "$REPLAY_COUNT" -lt "3" ]]; then
  echo "FAIL: event replay count expected >= 3 person events, got '${REPLAY_COUNT}'" >&2
  exit 1
fi
echo "  event-log replay: $REPLAY_COUNT person events for tenant OK"

# Circlead consumer smoke test (OPS-05): WireMock-backed IT via Maven failsafe
echo "  running circlead consumer smoke test..."
./mvnw -B -ntp -pl fabric-connectors failsafe:integration-test \
  -Dit.test=CircleadDrillSmokeIT \
  -Dfailsafe.useFile=false
echo "  circlead consumer smoke test PASSED"

echo "PASS: DR drill complete — dump/restore/validate/smoke all succeeded"
exit 0
```

**Partition safety warning** — add at the top of Step 4, before the INSERT:
```bash
# Ensure a partition exists for the current month before inserting
CURRENT_PARTITION="graph_events_y$(date +%Ym%m)"
psql_src -c "
DO \$\$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_class WHERE relname = '${CURRENT_PARTITION}'
  ) THEN
    EXECUTE format(
      'CREATE TABLE %I PARTITION OF graph_events FOR VALUES FROM (%L) TO (%L)',
      '${CURRENT_PARTITION}',
      date_trunc(''month'', CURRENT_DATE),
      date_trunc(''month'', CURRENT_DATE) + interval ''1 month''
    );
  END IF;
END
\$\$;
" >/dev/null
```

---

### `fabric-connectors/src/test/java/dev/tessera/connectors/circlead/CircleadPlaceholderResolutionTest.java` (unit test, CIRC-02)

**Analog:** `fabric-connectors/src/test/java/dev/tessera/connectors/rest/MappingDefinitionValidationTest.java`

**Imports pattern** (from `MappingDefinitionValidationTest.java` lines 16-28 — no Spring context, plain JUnit 5 + AssertJ):
```java
package dev.tessera.connectors.circlead;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import dev.tessera.connectors.FieldMapping;
import dev.tessera.connectors.MappingDefinition;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
```

**Core test pattern** (analog: `MappingDefinitionValidationTest.java` `validMapping()` factory + `@Test` methods):
```java
class CircleadPlaceholderResolutionTest {

    @Test
    void sourceUrl_placeholder_resolved_before_uri_create() {
        // GIVEN: StandardEnvironment (no Spring Boot needed)
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test",
            Map.of("tessera.connectors.circlead.base-url", "http://localhost:9090")));

        MappingDefinition raw = new MappingDefinition(
            "role", "role", "$.content[*]", List.of(), List.of(),
            "${tessera.connectors.circlead.base-url}/circlead/workitem/list?type=ROLE&details=true",
            null, null, null, null, null, null);

        // WHEN
        String resolved = env.resolvePlaceholders(raw.sourceUrl());
        MappingDefinition fixed = CircleadConnectorConfig.withResolvedUrl(raw, resolved);

        // THEN: must not throw IllegalArgumentException from URI.create()
        assertThatCode(() -> URI.create(fixed.sourceUrl())).doesNotThrowAnyException();
        assertThat(fixed.sourceUrl()).startsWith("http://localhost:9090/circlead");
    }

    @Test
    void sourceUrl_with_default_value_resolves_correctly() {
        // GIVEN: empty environment — placeholder has :default syntax
        StandardEnvironment env = new StandardEnvironment();
        MappingDefinition raw = new MappingDefinition(
            "role", "role", "$.content[*]", List.of(), List.of(),
            "${tessera.connectors.circlead.base-url:http://localhost:8080}/circlead/workitem/list",
            null, null, null, null, null, null);

        // WHEN
        String resolved = env.resolvePlaceholders(raw.sourceUrl());

        // THEN
        assertThatCode(() -> URI.create(resolved)).doesNotThrowAnyException();
        assertThat(resolved).startsWith("http://localhost:8080");
    }
}
```

---

### `fabric-connectors/src/test/java/dev/tessera/connectors/circlead/CircleadSchedulerWiringIT.java` (integration test, CIRC-01)

**Analog:** `fabric-app/src/test/java/dev/tessera/app/OutboxPollerConditionalIT.java`

**Pattern:** `ApplicationContextRunner` for lightweight context bootstrap — no Testcontainers, no Flyway. Verifies bean presence via `assertThat(ctx).hasSingleBean(...)` / bean count assertions.

**Imports pattern** (from `OutboxPollerConditionalIT.java` lines 16-29):
```java
package dev.tessera.connectors.circlead;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tessera.connectors.internal.ConnectorRegistry;
import dev.tessera.connectors.MappingDefinition;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
// + Mockito for JDBC stub
```

**Core wiring test pattern** (from `OutboxPollerConditionalIT.java` lines 43-64):
```java
/** Minimal config wiring CircleadConnectorConfig's JDBC + ObjectMapper dependencies */
@Configuration
static class MinimalConfig {
    @Bean
    NamedParameterJdbcTemplate namedParameterJdbcTemplate() {
        return org.mockito.Mockito.mock(NamedParameterJdbcTemplate.class);
    }
    @Bean
    ObjectMapper objectMapper() { return new ObjectMapper(); }
    // ... other deps ConnectorRegistry needs
}

private final ApplicationContextRunner runner =
    new ApplicationContextRunner()
        .withUserConfiguration(MinimalConfig.class, CircleadConnectorConfig.class)
        .withPropertyValues("tessera.connectors.circlead.base-url=http://localhost:9090");

@Test
void circlead_connector_config_exposes_three_mapping_definition_beans() {
    runner.run(ctx -> {
        assertThat(ctx).hasSingleBean(CircleadConnectorConfig.class);
        // Verify all three named beans are present
        assertThat(ctx.getBeansOfType(MappingDefinition.class)).hasSize(3);
        MappingDefinition role = ctx.getBean("circleadRoleMapping", MappingDefinition.class);
        assertThat(role.sourceUrl()).doesNotContain("${");
        assertThat(role.sourceUrl()).startsWith("http://localhost:9090");
    });
}
```

---

### `fabric-connectors/src/test/java/dev/tessera/connectors/circlead/CircleadDrillSmokeIT.java` (integration test, OPS-05)

**Analog:** `fabric-connectors/src/test/java/dev/tessera/connectors/circlead/CircleadConnectorIT.java` — exact match in role, data flow, and WireMock setup.

**Imports pattern** (from `CircleadConnectorIT.java` lines 16-41 — copy verbatim):
```java
package dev.tessera.connectors.circlead;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import dev.tessera.connectors.ConnectorState;
import dev.tessera.connectors.FieldMapping;
import dev.tessera.connectors.MappingDefinition;
import dev.tessera.connectors.PollResult;
import dev.tessera.connectors.SyncOutcome;
import dev.tessera.connectors.rest.GenericRestPollerConnector;
import dev.tessera.core.graph.GraphRepository;
import dev.tessera.core.graph.NodeState;
import dev.tessera.core.tenant.TenantContext;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
```

**WireMock setup + emptyRepo pattern** (from `CircleadConnectorIT.java` lines 55-83 — copy verbatim):
```java
@RegisterExtension
static WireMockExtension wm = WireMockExtension.newInstance().build();

private final GraphRepository emptyRepo = new GraphRepository() {
    @Override public Optional<NodeState> findNode(TenantContext ctx, String typeSlug, UUID nodeUuid) { return Optional.empty(); }
    @Override public List<NodeState> queryAll(TenantContext ctx, String typeSlug) { return List.of(); }
    @Override public List<NodeState> queryAllAfter(TenantContext ctx, String typeSlug, long afterSeq, int limit) { return List.of(); }
    @Override public List<Map<String, Object>> executeTenantCypher(TenantContext ctx, String cypher) { return List.of(); }
    @Override public List<NodeState> findShortestPath(TenantContext ctx, UUID fromUuid, UUID toUuid) { return List.of(); }
};
```

**Smoke test body** — prove all three entity types produce `SyncOutcome.SUCCESS` with the resolved URL:
```java
@Test
void drill_smoke_all_three_circlead_entity_types_succeed() {
    // Stub all three endpoints
    for (String type : List.of("ROLE", "CIRCLE", "ACTIVITY")) {
        wm.stubFor(get(urlPathEqualTo("/circlead/workitem/list"))
            .withQueryParam("type", equalTo(type))
            .withQueryParam("details", equalTo("true"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    { "content": [{"id":"drill-001","title":"Drill","status":"ACTIVE"}], "status": 200 }
                    """)));
    }

    GenericRestPollerConnector connector = new GenericRestPollerConnector(emptyRepo);
    for (String type : List.of("ROLE", "CIRCLE", "ACTIVITY")) {
        MappingDefinition mapping = new MappingDefinition(
            type.toLowerCase(), type.toLowerCase(), "$.content[*]",
            List.of(new FieldMapping("circlead_id", "$.id", null, true),
                    new FieldMapping("title", "$.title", null, true)),
            List.of("circlead_id"),
            wm.baseUrl() + "/circlead/workitem/list?type=" + type + "&details=true",
            null, null, null, null, null, null);

        PollResult result = connector.poll(
            Clock.systemUTC(), mapping,
            new ConnectorState(null, null, null, 0L, Map.of("connector_id", "drill-" + type)),
            TenantContext.of(UUID.randomUUID()));

        assertThat(result.outcome())
            .as("Expected SUCCESS for type %s", type)
            .isEqualTo(SyncOutcome.SUCCESS);
    }
}
```

---

## Shared Patterns

### License Header
**Source:** Any existing Java file (e.g., `CircleadConnectorConfig.java` lines 1-15)
**Apply to:** `CircleadPlaceholderResolutionTest.java`, `CircleadSchedulerWiringIT.java`, `CircleadDrillSmokeIT.java`
```java
/*
 * Copyright 2026 Tessera Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * ...
 */
```

### `MappingDefinition` 12-arg constructor order
**Source:** `fabric-connectors/src/main/java/dev/tessera/connectors/MappingDefinition.java` lines 45-58
**Apply to:** All test files and `CircleadConnectorConfig` helper method.

Field order (positional — Java records have no named-arg construction):
1. `sourceEntityType` (String)
2. `targetNodeTypeSlug` (String)
3. `rootPath` (String)
4. `fields` (List<FieldMapping>)
5. `identityFields` (List<String>)
6. `sourceUrl` (String)
7. `folderPath` (String) — pass `null` for REST connectors
8. `globPattern` (String) — pass `null`
9. `chunkStrategy` (String) — pass `null`
10. `chunkOverlapChars` (Integer) — pass `null`
11. `confidenceThreshold` (Double) — pass `null`
12. `provider` (String) — pass `null`

### `graph_events` column names (authoritative)
**Source:** `fabric-app/src/main/resources/db/migration/V2__graph_events.sql` lines 6-28
**Apply to:** `scripts/dr_drill.sh` Step 4 INSERT and Step 9 query.

Required NOT NULL columns for a `CREATE_NODE` event row:
- `model_id UUID NOT NULL`
- `sequence_nr BIGINT NOT NULL`
- `event_type TEXT NOT NULL` (e.g., `'CREATE_NODE'`)
- `node_uuid UUID` (nullable for edge events, provide for node events)
- `type_slug TEXT NOT NULL`
- `payload JSONB NOT NULL`
- `delta JSONB NOT NULL`
- `caused_by TEXT NOT NULL`
- `source_type TEXT NOT NULL`
- `source_id TEXT NOT NULL`
- `source_system TEXT NOT NULL`
- `event_time TIMESTAMPTZ NOT NULL` (NOT `created_at`)

### `model_config` schema
**Source:** `fabric-app/src/main/resources/db/migration/V27__tenant_hash_chain_config.sql` lines 6-10 + `V28__model_config_lifecycle.sql`
**Apply to:** `scripts/dr_drill.sh` Step 4 model_config INSERT.

Current `model_config` columns: `model_id UUID PRIMARY KEY`, `hash_chain_enabled BOOLEAN`, `retention_days INT NULL`, `snapshot_boundary TIMESTAMPTZ NULL`, `created_at TIMESTAMPTZ`, `updated_at TIMESTAMPTZ`.

**Important:** `model_config.model_id` is `UUID` type (V27 defines `model_id UUID PRIMARY KEY`). Use a proper UUID string, not a plain text name like `'dr-rehearsal-tenant'`.

### WireMock extension setup (no-Spring integration test)
**Source:** `fabric-connectors/src/test/java/dev/tessera/connectors/circlead/CircleadConnectorIT.java` lines 55-56
**Apply to:** `CircleadDrillSmokeIT.java`
```java
@RegisterExtension
static WireMockExtension wm = WireMockExtension.newInstance().build();
```

### `tessera:` YAML property key convention
**Source:** `fabric-app/src/main/resources/application.yml` lines 32-49
**Apply to:** new `tessera.connectors.circlead.*` keys in `application.yml`

Pattern: all env-overridable values use `"${ENV_VAR:default}"` syntax with a sensible local-dev default.

---

## No Analog Found

All files have analogs. No entries in this section.

---

## Metadata

**Analog search scope:** `fabric-connectors/src/`, `fabric-app/src/`, `scripts/`
**Files scanned:** 14 source files read directly
**Pattern extraction date:** 2026-04-17
