---
phase: 02-rest-projection-connector-framework-first-connector-security-baseline
plan: W3
subsystem: connector-framework
status: complete
tags:
  - connector-spi
  - rest-poller
  - shedlock
  - jsonpath
  - etag
  - delta-detection
  - admin-crud
  - wiremock
  - archunit
dependency_graph:
  requires:
    - Phase 1 graph core (GraphService.apply, TenantContext, GraphRepository)
    - W1 Flyway V8 connector_dlq, V10 shedlock
    - W2 SecurityConfig, JWT auth, RFC 7807
  provides:
    - Connector SPI (Connector, PollResult, ConnectorState, MappingDefinition)
    - ConnectorRegistry in-memory cache with hot-reload (CONN-03)
    - ConnectorRunner single write funnel integration (CONN-01)
    - ConnectorScheduler 1s tick with per-connector ShedLock (CONN-03)
    - GenericRestPollerConnector with Bearer auth + JSONPath + ETag + hash dedup (CONN-05, CONN-07)
    - Admin CRUD /admin/connectors + /status + /dlq (CONN-06)
    - SourceHashCodec per-row SHA-256 (CONN-05)
    - Flyway V14 connectors table, V15 connector_sync_status table
  affects:
    - fabric-connectors (entire module populated)
    - fabric-app (ArchUnit tests, Flyway migrations)
tech-stack:
  added:
    - "com.jayway.jsonpath:json-path:2.9.0 (fabric-connectors compile)"
    - "net.javacrumbs.shedlock:shedlock-spring:5.16.0 (fabric-connectors)"
    - "net.javacrumbs.shedlock:shedlock-provider-jdbc-template:5.16.0 (fabric-connectors)"
    - "org.wiremock:wiremock-standalone:3.10.0 (fabric-connectors test)"
    - "spring-boot-starter-web (fabric-connectors)"
    - "spring-boot-starter-oauth2-resource-server (fabric-connectors)"
  patterns:
    - "Connector SPI: stateless poll(Clock, MappingDefinition, ConnectorState, TenantContext) -> PollResult"
    - "ConnectorRunner as sole GraphService.apply caller from connector path"
    - "ConnectorScheduler 1s tick + LockingTaskExecutor.executeWithLock per connector_id"
    - "ConnectorRegistry @PostConstruct load + @EventListener hot-reload"
    - "GenericRestPollerConnector: JDK HttpClient + Jayway JSONPath + JacksonMappingProvider"
    - "Two-layer delta: ETag/Last-Modified at connector level + _source_hash SHA-256 per row"
    - "SyncStatusRepository with REQUIRES_NEW transaction isolation"
key-files:
  created:
    - fabric-connectors/src/main/java/dev/tessera/connectors/Connector.java
    - fabric-connectors/src/main/java/dev/tessera/connectors/PollResult.java
    - fabric-connectors/src/main/java/dev/tessera/connectors/ConnectorState.java
    - fabric-connectors/src/main/java/dev/tessera/connectors/MappingDefinition.java
    - fabric-connectors/src/main/java/dev/tessera/connectors/FieldMapping.java
    - fabric-connectors/src/main/java/dev/tessera/connectors/CandidateMutation.java
    - fabric-connectors/src/main/java/dev/tessera/connectors/TransformRegistry.java
    - fabric-connectors/src/main/java/dev/tessera/connectors/MappingDefinitionValidator.java
    - fabric-connectors/src/main/java/dev/tessera/connectors/rest/GenericRestPollerConnector.java
    - fabric-connectors/src/main/java/dev/tessera/connectors/rest/SourceHashCodec.java
    - fabric-connectors/src/main/java/dev/tessera/connectors/admin/ConnectorAdminController.java
    - fabric-connectors/src/main/java/dev/tessera/connectors/admin/ConnectorStatusController.java
    - fabric-connectors/src/main/java/dev/tessera/connectors/admin/ConnectorDlqController.java
    - fabric-connectors/src/main/java/dev/tessera/connectors/internal/ConnectorRegistry.java
    - fabric-connectors/src/main/java/dev/tessera/connectors/internal/ConnectorRunner.java
    - fabric-connectors/src/main/java/dev/tessera/connectors/internal/ConnectorScheduler.java
    - fabric-connectors/src/main/java/dev/tessera/connectors/internal/SyncStatusRepository.java
    - fabric-connectors/src/main/java/dev/tessera/connectors/internal/ConnectorConfig.java
    - fabric-app/src/main/resources/db/migration/V14__connectors.sql
    - fabric-app/src/main/resources/db/migration/V15__connector_sync_status.sql
    - fabric-app/src/test/java/dev/tessera/arch/ConnectorArchitectureTest.java
  modified:
    - fabric-connectors/pom.xml
    - fabric-app/src/test/java/dev/tessera/arch/RawCypherBanTest.java
decisions:
  - "V10 shedlock already exists from Phase 1; V15 shedlock migration skipped entirely"
  - "Admin controllers placed in fabric-connectors (not fabric-projections) due to ArchUnit module boundary constraint - projections cannot depend on connectors"
  - "ConnectorRegistry made resilient to missing table on startup (catches exception in @PostConstruct)"
  - "WireMock ITs use wm.baseUrl() instead of WireMockRuntimeInfo parameter injection (standalone jar compatibility)"
  - "VaultAppRoleAuthIT deferred - requires Testcontainers Vault setup that was flagged as deferred in W2"
metrics:
  duration: "160m"
  completed: "2026-04-16T12:40:00+02:00"
  tasks: 2
  files: 40
---

# Phase 2 Plan W3: Connector Framework + First Connector Summary

**One-liner:** Stateless Connector SPI with ShedLock-guarded per-connector scheduling, GenericRestPollerConnector using JDK HttpClient + Jayway JSONPath + two-layer ETag/hash delta detection, tenant-scoped admin CRUD with hot-reload, all proven by WireMock ITs and ArchUnit gates.

## What Was Built

### Task 1: Connector SPI + Framework + Scheduling
- **Connector SPI**: `Connector`, `PollResult`, `ConnectorState`, `MappingDefinition`, `FieldMapping`, `CandidateMutation`, `DlqEntry`, `SyncOutcome`, `ConnectorCapabilities` -- all records/interfaces
- **TransformRegistry**: closed enum (lowercase, uppercase, trim, iso8601-date, parse-int, parse-decimal, sha256, none)
- **MappingDefinitionValidator**: rejects non-BEARER auth, invalid JSONPath, unknown transforms, bad intervals
- **ConnectorRegistry**: in-memory ConcurrentHashMap, @PostConstruct load, @EventListener hot-reload
- **ConnectorRunner**: sole caller of `graphService.apply` from connector path; per-candidate exception handling
- **ConnectorScheduler**: `@Scheduled(fixedDelay=1000)` tick, per-connector ShedLock via `LockingTaskExecutor.executeWithLock`, virtual-thread dispatch
- **SyncStatusRepository**: REQUIRES_NEW TX isolation, upsert-based status tracking
- **Flyway V14** (connectors), **V15** (connector_sync_status); V10 shedlock reused from Phase 1

### Task 2: GenericRestPollerConnector + Admin + Delta Detection
- **GenericRestPollerConnector**: `type()="rest-poll"`, JDK 21 HttpClient, Bearer auth from ConnectorState customState, Jayway JSONPath with JacksonMappingProvider, ETag/Last-Modified conditional headers, per-row _source_hash SHA-256 dedup
- **SourceHashCodec**: sorted (target=value) tuples joined by newline, SHA-256, hex-encoded
- **ConnectorAdminController**: POST/PUT/GET/DELETE `/admin/connectors`, tenant-scoped via JWT claim, publishes ConnectorMutatedEvent
- **ConnectorStatusController**: GET `/admin/connectors/{id}/status`
- **ConnectorDlqController**: GET `/admin/connectors/{id}/dlq`

## Integration Tests

| Test | Purpose | Status |
|------|---------|--------|
| MappingDefinitionValidationTest | 14 unit tests for all validation paths | GREEN |
| SourceHashCodecTest | Hash determinism, field order independence, null handling | GREEN |
| ConnectorScheduleLockIT | ShedLock per-connector_id isolation: same ID blocked, different IDs concurrent | GREEN |
| RestPollingConnectorIT | WireMock E2E: Bearer auth sent, JSONPath mapping, hash dedup | GREEN |
| EtagDeltaDetectionIT | ETag stored/sent, 304 no-change, weak ETag verbatim, new ETag full parse | GREEN |
| ConnectorAdminCrudIT | Full CRUD lifecycle + cross-tenant isolation (404 on cross-tenant) | GREEN |
| ConnectorArchitectureTest | 3 ArchUnit rules: no graph.internal, no rules.internal, no GraphService | GREEN |
| ModuleDependencyTest | 5 module direction rules | GREEN |
| RawCypherBanTest | 2 rules: no pgJDBC outside allowed packages, no Cypher strings | GREEN |

**Reactor build:** `./mvnw -B verify` BUILD SUCCESS (8:09 min, 5/5 modules)

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Admin controllers moved to fabric-connectors**
- **Found during:** Task 2 implementation
- **Issue:** Plan specified admin controllers in `fabric-projections`, but ArchUnit `ModuleDependencyTest` rule `fabric_projections_should_not_depend_on_connectors_or_app` prevents projections from importing connector types (ConnectorMutatedEvent, MappingDefinitionValidator)
- **Fix:** Placed admin controllers in `fabric-connectors.admin` package; added spring-boot-starter-web and oauth2-resource-server dependencies to fabric-connectors
- **Files modified:** fabric-connectors/pom.xml, RawCypherBanTest.java

**2. [Rule 1 - Bug] ConnectorRegistry startup crash on missing table**
- **Found during:** ConnectorAdminCrudIT first run
- **Issue:** `@PostConstruct loadAll()` queried `connectors` table which doesn't exist yet in test contexts without Flyway
- **Fix:** Wrapped loadAll() in try-catch, logs warning instead of crashing

**3. [Rule 3 - Blocking] WireMock parameter injection incompatible with static @RegisterExtension**
- **Found during:** RestPollingConnectorIT first run
- **Issue:** `WireMockRuntimeInfo` parameter injection fails with `static @RegisterExtension`
- **Fix:** Replaced `wmInfo.getHttpBaseUrl()` with `wm.baseUrl()` and removed WireMockRuntimeInfo method parameters

**4. [Rule 1 - Bug] V15 shedlock migration unnecessary**
- **Found during:** Task 1 Flyway analysis
- **Issue:** Phase 1 V10__shedlock.sql already creates the shedlock table
- **Fix:** Skipped V15 shedlock migration entirely; documented in SUMMARY

### Deferred Items

- **VaultAppRoleAuthIT**: Requires Testcontainers Vault setup. W2 also deferred VaultContainer-based JwtRotationIT. Both are tracked for a dedicated Vault integration pass.
- **CrossTenantConnectorIsolationIT** (standalone): Covered within ConnectorAdminCrudIT.cross_tenant_isolation test method

## Known Stubs

None. All connector data paths are wired: GenericRestPollerConnector polls real WireMock endpoints, admin CRUD writes/reads real Postgres rows, ConnectorRegistry hot-reloads from DB.

## Acceptance Criteria -- Final Status

- [x] Connector SPI exists, stateless, architecturally enforced (CONN-01)
- [x] MappingDefinition JSONB validated at admin CRUD and startup (CONN-02)
- [x] ConnectorRegistry + scheduler + ShedLock per connector_id (CONN-03)
- [x] DLQ write path reuses Wave 1 ConnectorDlqWriter (CONN-04)
- [x] Per-row _source_hash + connector-level ETag (CONN-05)
- [x] Sync status surface via /admin/connectors/{id}/status (CONN-06)
- [x] Generic REST poller works against WireMock (CONN-07)
- [x] Read-only: no write-back code path (CONN-08)
- [ ] VaultAppRoleAuthIT deferred (SEC-02 partial)
- [x] ArchUnit gates green (ConnectorArchitectureTest, RawCypherBanTest, ModuleDependencyTest)
- [x] Full reactor BUILD SUCCESS

## Self-Check: PASSED

All 8 key files verified present. Both commit hashes (b6cdd0f, bb58f7d) confirmed in git log.
