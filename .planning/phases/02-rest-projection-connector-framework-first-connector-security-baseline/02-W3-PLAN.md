---
phase: 02-rest-projection-connector-framework-first-connector-security-baseline
plan: W3
type: execute
wave: 4
depends_on: [W0, W1, W2]
files_modified:
  - fabric-connectors/pom.xml
  - fabric-app/src/main/resources/db/migration/V13__connectors.sql
  - fabric-app/src/main/resources/db/migration/V14__connector_sync_status.sql
  - fabric-app/src/main/resources/db/migration/V15__shedlock.sql
  - fabric-connectors/src/main/java/dev/tessera/connectors/Connector.java
  - fabric-connectors/src/main/java/dev/tessera/connectors/ConnectorCapabilities.java
  - fabric-connectors/src/main/java/dev/tessera/connectors/PollResult.java
  - fabric-connectors/src/main/java/dev/tessera/connectors/ConnectorState.java
  - fabric-connectors/src/main/java/dev/tessera/connectors/MappingDefinition.java
  - fabric-connectors/src/main/java/dev/tessera/connectors/TransformRegistry.java
  - fabric-connectors/src/main/java/dev/tessera/connectors/internal/ConnectorRegistry.java
  - fabric-connectors/src/main/java/dev/tessera/connectors/internal/ConnectorRunner.java
  - fabric-connectors/src/main/java/dev/tessera/connectors/internal/ConnectorScheduler.java
  - fabric-connectors/src/main/java/dev/tessera/connectors/internal/SyncStatusRepository.java
  - fabric-connectors/src/main/java/dev/tessera/connectors/rest/GenericRestPollerConnector.java
  - fabric-connectors/src/main/java/dev/tessera/connectors/rest/SourceHashCodec.java
  - fabric-projections/src/main/java/dev/tessera/projections/rest/admin/ConnectorAdminController.java
  - fabric-projections/src/main/java/dev/tessera/projections/rest/admin/ConnectorStatusController.java
  - fabric-projections/src/main/java/dev/tessera/projections/rest/admin/ConnectorDlqController.java
  - fabric-connectors/src/test/java/dev/tessera/connectors/rest/RestPollingConnectorIT.java
  - fabric-connectors/src/test/java/dev/tessera/connectors/rest/EtagDeltaDetectionIT.java
  - fabric-connectors/src/test/java/dev/tessera/connectors/internal/ConnectorScheduleLockIT.java
  - fabric-connectors/src/test/java/dev/tessera/connectors/rest/MappingDefinitionValidationTest.java
  - fabric-projections/src/test/java/dev/tessera/projections/rest/admin/ConnectorAdminCrudIT.java
  - fabric-projections/src/test/java/dev/tessera/projections/rest/admin/CrossTenantConnectorIsolationIT.java
  - fabric-connectors/src/test/java/dev/tessera/connectors/vault/VaultAppRoleAuthIT.java
  - fabric-app/src/test/java/dev/tessera/app/arch/ConnectorArchitectureTest.java
autonomous: true
requirements:
  - CONN-01
  - CONN-02
  - CONN-03
  - CONN-04
  - CONN-05
  - CONN-06
  - CONN-07
  - CONN-08
  - SEC-02

must_haves:
  truths:
    - "A Connector SPI exists in fabric-connectors with poll(Clock, MappingDefinition, ConnectorState, TenantContext) -> PollResult; the interface is stateless and never calls GraphService directly"
    - "ConnectorRunner is the only caller of GraphService.apply from the connector path; ArchUnit gate enforces this"
    - "ConnectorScheduler ticks every 1s and dispatches due connectors via LockingTaskExecutor.executeWithLock with per-connector_id lock names"
    - "GenericRestPollerConnector uses Bearer token auth (from Vault at secret/tessera/connectors/{id}/bearer_token), JSONPath mapping via Jayway 2.9.0 with JacksonMappingProvider, ETag/Last-Modified + per-row _source_hash delta detection"
    - "Admin /api/v1/admin/connectors CRUD endpoints scoped to tenant claim, hot-reload via ApplicationEventPublisher, credentials never persisted in Postgres (only Vault path in credentials_ref)"
    - "/api/v1/admin/connectors/{id}/status returns last_success_at, dlq_count, events_processed per (connector_id, model_id)"
    - "/api/v1/admin/connectors/{id}/dlq lists DLQ rows scoped to the caller's tenant"
    - "MappingDefinition validation rejects any auth_type != BEARER and any JSONPath that fails to compile"
  artifacts:
    - path: fabric-connectors/src/main/java/dev/tessera/connectors/Connector.java
      provides: "Connector SPI — stable shape shared by structured (Phase 2) and unstructured (Phase 2.5) connectors"
      contains: "interface Connector"
    - path: fabric-connectors/src/main/java/dev/tessera/connectors/rest/GenericRestPollerConnector.java
      provides: "First concrete connector — generic REST poller with Bearer auth + ETag + hash delta"
    - path: fabric-app/src/main/resources/db/migration/V13__connectors.sql
      provides: "connectors table (id, model_id, type, mapping_def JSONB, auth_type, credentials_ref, poll_interval_seconds, enabled)"
    - path: fabric-connectors/src/main/java/dev/tessera/connectors/internal/ConnectorScheduler.java
      provides: "Single central tick dispatching via ShedLock per connector_id"
      contains: "@Scheduled(fixedDelay"
  key_links:
    - from: fabric-connectors/src/main/java/dev/tessera/connectors/internal/ConnectorRunner.java
      to: fabric-core/src/main/java/dev/tessera/core/graph/GraphService.java
      via: "Runner calls graphService.apply for each CandidateMutation in PollResult.candidates()"
      pattern: "graphService.apply"
    - from: fabric-connectors/src/main/java/dev/tessera/connectors/internal/ConnectorScheduler.java
      to: "ShedLock LockingTaskExecutor"
      via: "executeWithLock with connector-{id} lock name"
      pattern: "LockingTaskExecutor.executeWithLock"
    - from: fabric-connectors/src/main/java/dev/tessera/connectors/rest/GenericRestPollerConnector.java
      to: "Vault secret/tessera/connectors/{id}/bearer_token"
      via: "Spring Cloud Vault credentials_ref resolution"
      pattern: "secret/tessera/connectors"
---

<objective>
Land the connector framework and the first concrete connector. Four concerns across two task blocks:

1. **Connector SPI + runtime + scheduling** — `Connector` interface, `PollResult`/`ConnectorState` records, `MappingDefinition`+`TransformRegistry`, `ConnectorRegistry` in-memory cache, `ConnectorRunner` (the ONLY caller of `GraphService.apply` from the connector path), `ConnectorScheduler` central 1-second tick dispatching via `LockingTaskExecutor.executeWithLock` with per-`connector_id` ShedLock, Flyway V13 (connectors), V14 (connector_sync_status), V15 (shedlock).
2. **First concrete connector** — `GenericRestPollerConnector` using JDK 21 `HttpClient`, Bearer token auth from Vault, Jayway JSONPath 2.9.0 with `JacksonMappingProvider`, closed transform registry (`lowercase`, `uppercase`, `trim`, `iso8601-date`, `parse-int`, `parse-decimal`), two-layer delta detection (`If-None-Match` / `If-Modified-Since` at connector level + per-row `SHA-256` hash at `_source_hash` level).
3. **Admin endpoints** — `/api/v1/admin/connectors` CRUD + `/status` + `/dlq`, tenant-scoped, hot-reload via `ApplicationEventPublisher`.
4. **Verification** — WireMock-backed end-to-end IT, ETag 304 test, per-row hash dedup test, ShedLock per-connector isolation, cross-tenant connector isolation, `VaultAppRoleAuthIT` using `hashicorp/vault:1.15`, ArchUnit gate proving `fabric-connectors` never imports `fabric-core.graph.internal`.

Purpose: complete the Phase 2 promise — "generic REST poller pulls from a mock endpoint, applies delta detection, lands nodes via GraphService.apply, exposes sync status."

Output: a fully working structured connector ready for Phase 2.5 (unstructured extraction) to plug in alongside it.
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@.planning/phases/02-rest-projection-connector-framework-first-connector-security-baseline/02-CONTEXT.md
@.planning/phases/02-rest-projection-connector-framework-first-connector-security-baseline/02-RESEARCH.md
@.planning/phases/02-rest-projection-connector-framework-first-connector-security-baseline/02-W1-PLAN.md
@.planning/phases/02-rest-projection-connector-framework-first-connector-security-baseline/02-W2-PLAN.md
@fabric-core/src/main/java/dev/tessera/core/graph/GraphService.java
@fabric-core/src/main/java/dev/tessera/core/graph/GraphMutation.java
@fabric-core/src/main/java/dev/tessera/core/connector/dlq/ConnectorDlqWriter.java
@fabric-app/src/test/java/dev/tessera/app/arch/ModuleDependencyTest.java
</context>

<interfaces>
```java
// Connector SPI (Q2, verbatim)
public interface Connector {
    String type();
    ConnectorCapabilities capabilities();
    PollResult poll(Clock clock, MappingDefinition mapping,
                    ConnectorState state, TenantContext tenant);
}

public record PollResult(List<CandidateMutation> candidates,
                         ConnectorState nextState,
                         SyncOutcome outcome,
                         List<DlqEntry> dlq) {
    public static PollResult unchanged(ConnectorState state) { /* 304 case */ }
}

public record ConnectorState(String cursor, String etag, Instant lastModified, long lastSequence) {}

public record MappingDefinition(
    String sourceEntityType, String targetNodeTypeSlug, String rootPath,
    List<FieldMapping> fields, List<String> identityFields,
    String syncStrategy, int pollIntervalSeconds, String reconciliationChain) {}

public record FieldMapping(String target, String sourcePath, String transform, boolean required) {}

// Runner
public class ConnectorRunner {
    void runOnce(ConnectorInstance instance) {
        PollResult r = instance.connector().poll(clock, instance.mapping(), instance.state(), instance.tenant());
        for (CandidateMutation c : r.candidates()) {
            graphService.apply(c.toMutation());  // single write funnel
        }
        writeDlq(r.dlq());
        syncStatusRepo.update(instance, r.outcome(), r.nextState());
    }
}

// Scheduler (Q9)
@Scheduled(fixedDelay = 1000L)
public void tick() {
    for (var inst : registry.dueAt(clock.instant())) {
        lockingTaskExecutor.executeWithLock(
            () -> runner.runOnce(inst),
            new LockConfiguration(now, "connector-" + inst.id(),
                Duration.ofSeconds(inst.pollIntervalSeconds() * 3L),
                Duration.ofMillis(100)));
    }
}
```

Flyway V13 connectors table:
```sql
CREATE TABLE connectors (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  model_id UUID NOT NULL,
  type TEXT NOT NULL,                       -- e.g. 'rest-poll'
  mapping_def JSONB NOT NULL,
  auth_type TEXT NOT NULL CHECK (auth_type IN ('BEARER')),
  credentials_ref TEXT NOT NULL,            -- Vault path
  poll_interval_seconds INT NOT NULL CHECK (poll_interval_seconds >= 1),
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX idx_connectors_model ON connectors(model_id);
```

V14 connector_sync_status:
```sql
CREATE TABLE connector_sync_status (
  connector_id UUID PRIMARY KEY REFERENCES connectors(id) ON DELETE CASCADE,
  model_id UUID NOT NULL,
  last_poll_at TIMESTAMPTZ NULL,
  last_success_at TIMESTAMPTZ NULL,
  last_outcome TEXT NULL,                   -- SUCCESS / PARTIAL / FAILED
  last_etag TEXT NULL,
  last_modified TIMESTAMPTZ NULL,
  events_processed BIGINT NOT NULL DEFAULT 0,
  dlq_count BIGINT NOT NULL DEFAULT 0,
  next_poll_at TIMESTAMPTZ NULL,
  state_blob JSONB NULL
);
```

V15 shedlock (standard shape from ShedLock docs) — NB: Phase 1 V10 may have already created shedlock for circuit breaker work; verify and skip/ALTER if so.
</interfaces>

<tasks>

<task type="auto" tdd="true">
  <name>Task 02-W3-01: Connector SPI + V13/V14/V15 + ConnectorRegistry + Runner + Scheduler + ArchUnit gate (CONN-01, CONN-02, CONN-03, CONN-06, CONN-08)</name>
  <files>
    fabric-connectors/pom.xml,
    fabric-app/src/main/resources/db/migration/V13__connectors.sql,
    fabric-app/src/main/resources/db/migration/V14__connector_sync_status.sql,
    fabric-app/src/main/resources/db/migration/V15__shedlock.sql,
    fabric-connectors/src/main/java/dev/tessera/connectors/Connector.java,
    fabric-connectors/src/main/java/dev/tessera/connectors/ConnectorCapabilities.java,
    fabric-connectors/src/main/java/dev/tessera/connectors/PollResult.java,
    fabric-connectors/src/main/java/dev/tessera/connectors/ConnectorState.java,
    fabric-connectors/src/main/java/dev/tessera/connectors/MappingDefinition.java,
    fabric-connectors/src/main/java/dev/tessera/connectors/TransformRegistry.java,
    fabric-connectors/src/main/java/dev/tessera/connectors/internal/ConnectorRegistry.java,
    fabric-connectors/src/main/java/dev/tessera/connectors/internal/ConnectorRunner.java,
    fabric-connectors/src/main/java/dev/tessera/connectors/internal/ConnectorScheduler.java,
    fabric-connectors/src/main/java/dev/tessera/connectors/internal/SyncStatusRepository.java,
    fabric-connectors/src/test/java/dev/tessera/connectors/internal/ConnectorScheduleLockIT.java,
    fabric-connectors/src/test/java/dev/tessera/connectors/rest/MappingDefinitionValidationTest.java,
    fabric-app/src/test/java/dev/tessera/app/arch/ConnectorArchitectureTest.java
  </files>
  <read_first>
    - .planning/phases/02-.../02-RESEARCH.md §Q2 (Connector SPI), §Q3 (MappingDefinition/JSONPath), §Q9 (ShedLock dynamic)
    - .planning/phases/02-.../02-CONTEXT.md Decisions 3, 7, 9
    - fabric-app/src/main/resources/db/migration/V10__shedlock.sql (Phase 1 — CHECK if exists; rename V15 to skip if so)
    - fabric-app/src/test/java/dev/tessera/app/arch/ModuleDependencyTest.java
  </read_first>
  <behavior>
    - `Connector` interface is stateless (no `@Autowired` on implementations except for HttpClient builders); `poll` is a pure function of its inputs.
    - `ConnectorRunner.runOnce(instance)` is the single integration point: it invokes `connector.poll`, iterates `candidates`, for each calls `graphService.apply(candidate.toMutation())` (Phase 1 single write funnel), writes DLQ entries via `ConnectorDlqWriter` (REQUIRES_NEW from Wave 1), updates `connector_sync_status`.
    - `ConnectorScheduler` runs `@Scheduled(fixedDelay=1000L)` and calls `registry.dueAt(clock.instant())` which returns connectors whose `next_poll_at <= now` (NOT `lastPollAt + k*interval` — prevents overdue-backlog storm per Q9 pitfall). For each due instance, dispatch via `lockingTaskExecutor.executeWithLock(runnable, new LockConfiguration(now, "connector-" + id, atMostFor=3×interval, atLeastFor=100ms))`.
    - `ConnectorRegistry` is a `@Component` holding an in-memory `ConcurrentHashMap<UUID, ConnectorInstance>`, loaded from `connectors` table on startup and on `ApplicationEventPublisher` events published by the admin controllers (Task 02-W3-02). `ConnectorInstance` bundles `{row, connector (looked up by type), parsed MappingDefinition, TenantContext}`.
    - `MappingDefinition` is a record; `MappingDefinitionValidationTest` asserts: rejects `auth_type != BEARER`, rejects unparseable JSONPath in `rootPath` or any `sourcePath`, rejects unknown transform names, rejects `poll_interval_seconds < 1`, rejects empty `identityFields`. Validation happens in `ConnectorRegistry.validate(mappingDef, authType, pollIntervalSeconds)` called from both startup loader and admin CRUD.
    - `ConnectorScheduleLockIT`: declare two connectors with same `poll_interval_seconds=2`, verify (via a mock `Connector` implementation that records call counts) that when two scheduler instances race (simulated via two `ConnectorScheduler` beans against the same DB), ShedLock prevents double-polling — each connector-id is polled exactly once per interval. Use a `CountDownLatch` + timer-bounded wait.
    - `ConnectorArchitectureTest` ArchUnit rules:
      - `classes().that().resideInAPackage("dev.tessera.connectors..").should().notDependOnClassesThat().resideInAPackage("dev.tessera.core.graph.internal..")`
      - `classes().that().implement(Connector.class).should().notDependOnClassesThat().haveSimpleName("GraphService")` — connectors MUST NOT call GraphService; only ConnectorRunner may.
      - `classes().that().resideInAPackage("dev.tessera.connectors..").should().notDependOnClassesThat().resideInAPackage("dev.tessera.rules..internal..")`
  </behavior>
  <action>
    1. `fabric-connectors/pom.xml`: add dependencies `com.jayway.jsonpath:json-path:2.9.0` (scope compile, moved from test), `net.javacrumbs.shedlock:shedlock-spring:5.16.0`, `net.javacrumbs.shedlock:shedlock-provider-jdbc-template:5.16.0`, `fabric-core` (already present). WireMock test scope (3.10.x).
    2. V13, V14, V15 SQL per interfaces block above. **Verify V10 shedlock:** grep `fabric-app/src/main/resources/db/migration/V10__shedlock.sql` — if Phase 1 already created the ShedLock table, renumber this migration to a later version or convert to an idempotent `CREATE TABLE IF NOT EXISTS`. Phase 1 01-VERIFICATION mentions V1..V10 including shedlock, so V15 in this wave should be `CREATE TABLE IF NOT EXISTS shedlock (...)` — or skipped entirely if V10 is definitive. Document the choice in the SUMMARY.
    3. Connector SPI records and interface verbatim from Q2/interfaces block.
    4. `TransformRegistry.java`: static `Object apply(String transform, Object raw)` with a closed switch: `lowercase`, `uppercase`, `trim`, `iso8601-date`, `parse-int`, `parse-decimal`, or `null` (passthrough). Unknown transform throws `IllegalArgumentException` — this is caught at MappingDefinition validation time, not runtime.
    5. `ConnectorRegistry.java`: `@Component` with `@EventListener(ConnectorMutatedEvent.class) reload(UUID)` method. Startup `@PostConstruct` loads all rows, validates each via `validate(...)`, constructs `ConnectorInstance` map. `dueAt(now)` returns instances where `nextPollAt <= now AND enabled=true`.
    6. `ConnectorRunner.java`: the ONLY place in fabric-connectors that calls `graphService.apply`. Iterates `candidates`, catches per-candidate exceptions (so one bad row doesn't poison the batch), routes exceptions to DLQ, updates `events_processed += successful_count`, `dlq_count += dlq.size()`, sets `next_poll_at = now + pollIntervalSeconds`.
    7. `ConnectorScheduler.java`: `@Component` with `@Scheduled(fixedDelay=1000L)` `tick()`. Constructor injects `ConnectorRegistry`, `LockingTaskExecutor`, `ConnectorRunner`, `Clock`. Dispatches via `executeWithLock` per Q9 verbatim. Add a virtual-thread submit via `Executors.newVirtualThreadPerTaskExecutor()` so slow connectors don't block the 1-sec tick.
    8. `SyncStatusRepository.java`: JDBC CRUD for `connector_sync_status`. `updateAfterPoll(id, model, outcome, etag, lastModified, eventsDelta, dlqDelta, nextPollAt, stateBlob)`. Runs in a SEPARATE transaction from the graph mutation (`Propagation.REQUIRES_NEW`) so sync-status bookkeeping doesn't couple to the write funnel (Q9 pitfall).
    9. `ConnectorScheduleLockIT.java`: Testcontainers AGE+Flyway. Two scheduler instances simulated in the same JVM (manually instantiate two `ConnectorScheduler` beans sharing the same `LockProvider`). Seed one connector. Let both schedulers tick concurrently for 5 seconds. Assert the mock `Connector.poll` was called exactly `5 / pollInterval` times, not `2 * 5 / pollInterval`. Cross-tenant variant: seed two connectors for two tenants and assert they run independently.
    10. `MappingDefinitionValidationTest.java`: unit tests (no DB). Cover all rejection cases listed above. Valid-case also included.
    11. `ConnectorArchitectureTest.java` in `fabric-app/src/test/java/dev/tessera/app/arch/`: ArchUnit rules above, added alongside existing Phase 1 ModuleDependencyTest.
    12. TDD order: ConnectorArchitectureTest first (RED — package doesn't exist yet; then passes trivially). Then MappingDefinitionValidationTest (RED then GREEN). Then ConnectorScheduleLockIT (the hard one — expect iteration).
  </action>
  <verify>
    <automated>./mvnw -pl fabric-connectors -Dit.test=ConnectorScheduleLockIT -Dtest=MappingDefinitionValidationTest verify && ./mvnw -pl fabric-app -Dtest=ConnectorArchitectureTest test</automated>
  </verify>
  <acceptance_criteria>
    - `./mvnw -pl fabric-connectors -Dit.test=ConnectorScheduleLockIT verify` exits 0
    - `./mvnw -pl fabric-connectors -Dtest=MappingDefinitionValidationTest test` exits 0
    - `./mvnw -pl fabric-app -Dtest=ConnectorArchitectureTest test` exits 0
    - `grep -q "executeWithLock" fabric-connectors/src/main/java/dev/tessera/connectors/internal/ConnectorScheduler.java` succeeds
    - `grep -q "graphService.apply" fabric-connectors/src/main/java/dev/tessera/connectors/internal/ConnectorRunner.java` succeeds
    - `grep -rn "graph.internal" fabric-connectors/src/main/java` returns zero hits
    - `./mvnw -B verify` green
  </acceptance_criteria>
  <done>
    Connector SPI + scheduler + runner + registry landed. ShedLock per-connector_id isolation proven. ArchUnit enforces the single-funnel invariant.
  </done>
</task>

<task type="auto" tdd="true">
  <name>Task 02-W3-02: GenericRestPollerConnector + admin endpoints + Vault integration (CONN-04, CONN-05, CONN-07, SEC-02)</name>
  <files>
    fabric-connectors/src/main/java/dev/tessera/connectors/rest/GenericRestPollerConnector.java,
    fabric-connectors/src/main/java/dev/tessera/connectors/rest/SourceHashCodec.java,
    fabric-projections/src/main/java/dev/tessera/projections/rest/admin/ConnectorAdminController.java,
    fabric-projections/src/main/java/dev/tessera/projections/rest/admin/ConnectorStatusController.java,
    fabric-projections/src/main/java/dev/tessera/projections/rest/admin/ConnectorDlqController.java,
    fabric-connectors/src/test/java/dev/tessera/connectors/rest/RestPollingConnectorIT.java,
    fabric-connectors/src/test/java/dev/tessera/connectors/rest/EtagDeltaDetectionIT.java,
    fabric-connectors/src/test/java/dev/tessera/connectors/vault/VaultAppRoleAuthIT.java,
    fabric-projections/src/test/java/dev/tessera/projections/rest/admin/ConnectorAdminCrudIT.java,
    fabric-projections/src/test/java/dev/tessera/projections/rest/admin/CrossTenantConnectorIsolationIT.java
  </files>
  <read_first>
    - .planning/phases/02-.../02-RESEARCH.md §Q3 (JSONPath + transforms), §Q6 (ETag/Last-Modified + _source_hash), §Q10 (VaultContainer)
    - .planning/phases/02-.../02-CONTEXT.md Decisions 3, 7
    - fabric-projections/src/main/java/dev/tessera/projections/rest/security/SecurityConfig.java (from W2)
    - fabric-core/src/main/java/dev/tessera/core/connector/dlq/ConnectorDlqWriter.java (from W1)
  </read_first>
  <behavior>
    - `GenericRestPollerConnector`:
      - `type() == "rest-poll"`.
      - `poll(...)`: (1) resolves Bearer token from Vault via `spring.cloud.vault` at path `secret/tessera/connectors/{instance.id}/bearer_token` (injected via a `VaultTemplate` lookup helper — credentials NEVER touch the connectors row, only the `credentials_ref` path). (2) Builds JDK 21 `HttpClient` with conditional headers: `If-None-Match` from `state.etag()`, `If-Modified-Since` from `state.lastModified()` formatted as RFC 1123. (3) If response is 304 → `PollResult.unchanged(state)`, zero candidates. (4) If 200 → parses body via Jayway JSONPath with `JacksonMappingProvider` (Q3 pitfall). (5) Iterates `rootPath`, for each row: extracts fields via FieldMapping, applies TransformRegistry, computes `_source_hash` via `SourceHashCodec`, looks up existing node's `_source_hash` property (via `GraphRepository.findNode`), if matches → SKIP (per-row dedup per Q6). (6) Otherwise emits a `CandidateMutation` with `originConnectorId=instance.id()` and `originChangeId=newUUID`. (7) Returns `PollResult(candidates, newState(etag=responseEtag, lastModified=responseLM, nextSequence=max), SUCCESS, dlq)`. (8) On `IOException` → returns `PollResult(emptyList, state, FAILED, emptyList)`.
      - Weak ETags (`W/"..."`) passed through verbatim (Q6 pitfall).
    - `SourceHashCodec.hash(FieldMapping[] fields, Map<String,Object> rowValues) -> String`: SHA-256 over a sorted path-value tuple list (Q6 canonicalization pitfall). NOT Jackson JSON canonicalization — sorted tuple list avoids version drift.
    - Admin endpoints under `/api/v1/admin/connectors` (consistent with W2 admin prefix):
      - `POST /` — create connector, body `{type, mappingDef, authType, credentialsRef, pollIntervalSeconds}`. Validates via `ConnectorRegistry.validate`. model_id from JWT `tenant` claim. Publishes `ConnectorMutatedEvent(id)` so registry hot-reloads. 201 + connector row.
      - `PUT /{id}` — update; tenant scope check (caller's tenant must own row); publishes event.
      - `GET /` — list tenant's connectors.
      - `GET /{id}` — fetch; 404 if cross-tenant.
      - `DELETE /{id}` — delete; cascades connector_sync_status; publishes event.
    - `ConnectorStatusController` GET `/api/v1/admin/connectors/{id}/status` — returns `{lastSuccessAt, lastOutcome, eventsProcessed, dlqCount, nextPollAt}` from `connector_sync_status`. Tenant scope check (404 if cross-tenant). CONN-06.
    - `ConnectorDlqController` GET `/api/v1/admin/connectors/{id}/dlq` — paginated list of DLQ rows (reuse cursor codec from W2). Tenant scope check.
    - `RestPollingConnectorIT`: WireMock 3.10 stub serving `/api/customers` with a fixed JSON body. Configure a `rest-poll` connector with mapping targeting type `customer`. Inject Bearer token via a VaultContainer seeded with `secret/tessera/connectors/<id>/bearer_token`. Trigger `ConnectorRunner.runOnce(instance)` directly (or wait 2 ticks). Assert: (a) WireMock received Bearer-authenticated request; (b) graph contains customer nodes with mapped properties; (c) `connector_sync_status` updated with `events_processed=N`; (d) next call with same WireMock state → skipped via per-row hash, zero new events.
    - `EtagDeltaDetectionIT`: WireMock returns `ETag: "v1"` on first call, `304` on second call with `If-None-Match: "v1"`. Assert: second poll returns zero candidates and does NOT hit the JSON body parser. Third call after WireMock reconfigured to `ETag: "v2"` → full parse path. Weak-ETag variant also covered.
    - `ConnectorAdminCrudIT`: full Spring + WireMock + Vault. Create a connector via POST, assert it appears in GET list, assert `ConnectorMutatedEvent` fired and registry picks it up (check via `ConnectorRegistry.dueAt`). Update poll interval, assert registry reflects. Delete, assert gone.
    - `CrossTenantConnectorIsolationIT`: seed two tenants with one connector each. As tenant A, GET /api/v1/admin/connectors → only A's connector. GET /api/v1/admin/connectors/{B-id} → 404. PUT {B-id} → 404. DELETE {B-id} → 404.
    - `VaultAppRoleAuthIT`: uses VaultContainer with AppRole enabled, Spring Cloud Vault configured with role-id/secret-id, asserts a bearer token can be resolved from a seeded connector path. This is the dedicated AppRole coverage IT per Q10 recommendation (other ITs use token auth for simplicity).
  </behavior>
  <action>
    1. `SourceHashCodec.java`: method `String hash(List<FieldMapping> fields, Map<String,Object> values)`. Build `List<String> tuples = fields.stream().sorted(Comparator.comparing(FieldMapping::target)).map(f -> f.target() + "=" + Objects.toString(values.get(f.target()), "")).toList();` then SHA-256 the `\n`-joined string, base64-encode.
    2. `GenericRestPollerConnector.java`: implement `Connector`. Constructor takes `HttpClient httpClient` (shared), `VaultTemplate vaultTemplate`, `GraphRepository graphRepository` (for per-row hash lookup — read-only; allowed because GraphRepository is public API, not `graph.internal`). Bearer token resolution: `vaultTemplate.read("secret/tessera/connectors/" + instanceId + "/bearer_token").getData().get("bearer_token")`. CandidateMutation construction reuses the Phase 1 type.
    3. `ConnectorAdminController.java` in `fabric-projections`: `@RestController @RequestMapping("/api/v1/admin/connectors")`. Each method extracts JWT tenant, scopes the JDBC query, publishes `ConnectorMutatedEvent(id)` via `ApplicationEventPublisher`. Validation errors → 400 problem+json (reuses W2 advice). Non-owned connector → 404 (not 403).
    4. `ConnectorStatusController.java` + `ConnectorDlqController.java`: slim GET-only controllers with tenant scoping and cursor pagination on DLQ listing.
    5. `RestPollingConnectorIT.java`: WireMock rule via `@RegisterExtension WireMockExtension`, VaultContainer (token auth), AgePostgresContainer. Slice `@SpringBootTest` wiring only the connector runtime + fabric-core graph slice. Seed WireMock with `GET /api/customers -> 200 + JSON body + ETag: "v1"`. Create a connector row in V13, manually trigger `runner.runOnce(...)`. Three assertions as behaviour.
    6. `EtagDeltaDetectionIT.java`: reuse RestPollingConnectorIT's setup, add WireMock scenarios for 304 branch.
    7. `ConnectorAdminCrudIT.java` + `CrossTenantConnectorIsolationIT.java`: REST-assured against the running Spring context. Issue JWTs via the `/api/v1/admin/tokens/issue` endpoint from W2 for two distinct tenants. Full CRUD flow + isolation fuzz.
    8. `VaultAppRoleAuthIT.java`: VaultContainer with `auth enable approle` + `write auth/approle/role/tessera-it ...` init commands per Q10 verbatim. Configure Spring Cloud Vault via DynamicPropertySource to use `role-id` + `secret-id` from the container. Assert a connector-creds read succeeds against a seeded path.
    9. TDD order: SourceHashCodec unit test first, then GenericRestPollerConnector with EtagDeltaDetectionIT (easier to mock), then RestPollingConnectorIT (full E2E), then admin CRUD ITs, then VaultAppRoleAuthIT last.
  </action>
  <verify>
    <automated>./mvnw -pl fabric-connectors,fabric-projections -Dit.test='RestPollingConnectorIT,EtagDeltaDetectionIT,ConnectorAdminCrudIT,CrossTenantConnectorIsolationIT,VaultAppRoleAuthIT' verify</automated>
  </verify>
  <acceptance_criteria>
    - All five ITs exit 0
    - `grep -q "If-None-Match" fabric-connectors/src/main/java/dev/tessera/connectors/rest/GenericRestPollerConnector.java` succeeds
    - `grep -q "JacksonMappingProvider" fabric-connectors/src/main/java/dev/tessera/connectors/rest/GenericRestPollerConnector.java` succeeds
    - `grep -q "secret/tessera/connectors" fabric-connectors/src/main/java/dev/tessera/connectors/rest/GenericRestPollerConnector.java` succeeds
    - `grep -q "_source_hash" fabric-connectors/src/main/java/dev/tessera/connectors/rest/SourceHashCodec.java` succeeds
    - `grep -q "ApplicationEventPublisher\\|publishEvent" fabric-projections/src/main/java/dev/tessera/projections/rest/admin/ConnectorAdminController.java` succeeds
    - `grep -rn "graph.internal" fabric-connectors/src/main/java fabric-projections/src/main/java/dev/tessera/projections/rest/admin` returns zero hits
    - `./mvnw -B verify` green reactor-wide
    - ArchUnit remains green (RawCypherBanTest, ProjectionsModuleDependencyTest, ConnectorArchitectureTest)
    - Phase 1 suites remain green (no regressions)
  </acceptance_criteria>
  <done>
    Generic REST poller ingests WireMock data via Bearer auth pulled from Vault, applies ETag + per-row hash delta detection, lands nodes via the write funnel, exposes sync status + DLQ listings to tenant-scoped admins, and is fully hot-reloadable.
  </done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| Source system → GenericRestPollerConnector | Untrusted REST response body flows into JSONPath mapping |
| Vault → connector credentials | Bearer tokens flow via Spring Cloud Vault; never stored in Postgres |
| Admin caller → /api/v1/admin/connectors | Tenant-scoped CRUD; cross-tenant access must 404 |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-02-W3-01 | Tampering | Malicious source row bypassing validation | mitigate | All candidates flow through `graphService.apply` → SHACL + rules; no direct Cypher |
| T-02-W3-02 | Information Disclosure | Bearer token leak in logs | mitigate | Logback scrubber for `Authorization: Bearer`; verified by a log-grep IT (add in RestPollingConnectorIT) |
| T-02-W3-03 | Elevation of Privilege | Connector using wrong Vault path | mitigate | Vault policy per connector path; `credentials_ref` validated against `secret/tessera/connectors/{id}/*` pattern at CRUD time |
| T-02-W3-04 | Information Disclosure | Cross-tenant connector enumeration | mitigate | CrossTenantConnectorIsolationIT fuzz; 404 on cross-tenant GET/PUT/DELETE |
| T-02-W3-05 | DoS | Runaway connector | mitigate | Reuses Phase 1 `WriteRateCircuitBreaker` via `graphService.apply` |
| T-02-W3-06 | Tampering | ShedLock expiry mid-poll → double-fetch | mitigate | atMostFor = 3 × pollInterval (Q9 pitfall) |
| T-02-W3-07 | Repudiation | Connector silently skipping rows | mitigate | DLQ writes are REQUIRES_NEW; events_processed/dlq_count tracked in sync_status |
</threat_model>

<verification>
`./mvnw -B verify` green. All seven ITs in this wave green. ArchUnit gates green (including new ConnectorArchitectureTest). Full Phase 2 success criteria #1-5 satisfied end-to-end.
</verification>

<success_criteria>
- CONN-01 SPI exists, stateless, architecturally enforced
- CONN-02 MappingDefinition JSONB validated at admin CRUD
- CONN-03 ConnectorRegistry + scheduler + ShedLock per connector_id
- CONN-04 DLQ write path exercised end-to-end (reuses Wave 1 DlqWriter)
- CONN-05 per-row `_source_hash` + connector-level ETag
- CONN-06 sync status surface via /status endpoint
- CONN-07 generic REST poller works against WireMock
- CONN-08 read-only (no write-back code path added; runner never calls `Connector` for writes)
- SEC-02 Vault secrets loaded via Spring Cloud Vault Config Data API; AppRole IT green
- Phase 2 end-to-end: schema flip → /v3/api-docs reflects + connector ingests → REST GET returns → tenant isolation proven → error shape consistent
</success_criteria>

<output>
After completion, create `.planning/phases/02-rest-projection-connector-framework-first-connector-security-baseline/02-W3-SUMMARY.md`.
</output>
