---
phase: 05-circlead-integration-production-hardening
plan: "03"
subsystem: event-lifecycle
tags: [retention, snapshot, compaction, shedlock, admin-endpoints, tdd, jwt-tenant-guard]
dependency_graph:
  requires:
    - V28-model-config-lifecycle-migration
    - nyquist-test-stubs-phase5
  provides:
    - event-retention-job
    - event-snapshot-service
    - event-lifecycle-admin-endpoints
  affects:
    - fabric-core
tech_stack:
  added:
    - spring-boot-starter-web (fabric-core pom — required for EventLifecycleController)
    - spring-boot-starter-oauth2-resource-server (fabric-core pom — required for Jwt principal)
  patterns:
    - "@Scheduled(cron) + @SchedulerLock + REQUIRES_NEW for daily sweep jobs"
    - Three-phase compaction via TransactionTemplate.execute() — read TX / write TX / delete TX
    - JWT tenant-match guard on all admin endpoints (isTenantMatch pattern from McpAuditController)
    - HashMap for test fixture maps with nullable values (Map.of() rejects nulls)
key_files:
  created:
    - fabric-core/src/main/java/dev/tessera/core/events/EventRetentionJob.java
    - fabric-core/src/main/java/dev/tessera/core/events/snapshot/SnapshotResult.java
    - fabric-core/src/main/java/dev/tessera/core/events/snapshot/EventSnapshotService.java
    - fabric-core/src/main/java/dev/tessera/core/admin/EventLifecycleController.java
  modified:
    - fabric-core/src/test/java/dev/tessera/core/events/EventRetentionJobTest.java
    - fabric-core/src/test/java/dev/tessera/core/events/snapshot/EventSnapshotServiceTest.java
    - fabric-core/pom.xml
decisions:
  - "spring-boot-starter-web and oauth2-resource-server added to fabric-core pom — fabric-core had no web layer; EventLifecycleController requires ResponseEntity and Jwt; consistent with fabric-connectors and fabric-projections module pattern"
  - "HashMap used for test fixture maps with null snapshot_boundary — Map.of() (ImmutableCollections.MapN) throws NullPointerException on null values; HashMap allows null"
  - "TransactionTemplate injected into EventSnapshotService constructor — allows mocking in unit tests without a real PlatformTransactionManager; tx.execute() calls the callback directly when txManager.getTransaction() returns null"
metrics:
  duration_seconds: 372
  completed_date: "2026-04-17"
  tasks_completed: 2
  files_created: 4
  files_modified: 3
---

# Phase 5 Plan 03: Event-Log Lifecycle Summary

**One-liner:** EventRetentionJob deletes graph_events older than retention_days daily (ShedLock, REQUIRES_NEW), EventSnapshotService compacts event log in three non-blocking transactions, and EventLifecycleController exposes POST /admin/events/snapshot + GET/PUT /admin/events/retention with JWT tenant-match guard.

## Tasks Completed

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 | EventRetentionJob — daily retention sweep | 0122fbc | EventRetentionJob.java, EventRetentionJobTest.java |
| 2 | EventSnapshotService + SnapshotResult + EventLifecycleController | 35f3b9c | SnapshotResult.java, EventSnapshotService.java, EventLifecycleController.java, EventSnapshotServiceTest.java, pom.xml |

## Verification Results

- `./mvnw test -pl fabric-core -Dtest="EventRetentionJobTest,EventSnapshotServiceTest"` — 10 tests run, 0 failures, 0 errors
- EventRetentionJob has `@SchedulerLock(name = "tessera-event-retention", lockAtMostFor = "PT55M")`, `@Scheduled(cron = "0 0 2 * * *")`, `@Transactional(propagation = REQUIRES_NEW)`
- EventSnapshotService uses three separate TransactionTemplate.execute() calls (not one big TX)
- EventLifecycleController has `isTenantMatch(jwt, modelId)` guard on POST /snapshot, GET /retention, PUT /retention
- DELETE SQL contains `event_type != 'SNAPSHOT'` guard (verified by test 5)
- SELECT contains `retention_days IS NOT NULL` (verified by grep)
- `snapshot_boundary` guard uses `COALESCE(:snapshot_boundary, '-infinity'::timestamptz)` in DELETE SQL

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Added spring-boot-starter-web and oauth2-resource-server to fabric-core pom.xml**

- **Found during:** Task 2 (first compile attempt of EventLifecycleController)
- **Issue:** fabric-core had no web layer (`spring-boot-starter` only). `EventLifecycleController` uses `ResponseEntity`, `@RestController`, `@AuthenticationPrincipal Jwt` — all from `spring-webmvc` and `spring-security-oauth2-resource-server`. The plan places the controller in `fabric-core/src/main/java/dev/tessera/core/admin/` but that module lacked the required dependencies.
- **Fix:** Added `spring-boot-starter-web` and `spring-boot-starter-oauth2-resource-server` to `fabric-core/pom.xml`, matching the pattern used in `fabric-connectors` and `fabric-projections`.
- **Files modified:** `fabric-core/pom.xml`
- **Commit:** 35f3b9c

**2. [Rule 1 - Bug] HashMap used for test fixture maps containing null snapshot_boundary**

- **Found during:** Task 1 (test execution after GREEN phase)
- **Issue:** Test fixtures used `Map.of("model_id", id, "retention_days", 30, "snapshot_boundary", null)`. `Map.of()` delegates to `ImmutableCollections.MapN` which calls `Objects.requireNonNull()` on all values, throwing NPE when `snapshot_boundary` is null. Three tests failed with NPE at fixture construction.
- **Fix:** Replaced `Map.of()` with a `configRow()` helper method returning a mutable `HashMap` that allows null values.
- **Files modified:** `EventRetentionJobTest.java`
- **Commit:** 0122fbc

## Known Stubs

None — all deliverables are fully implemented with real logic. The `@Disabled` stubs from Plan 00 for `EventRetentionJobTest` and `EventSnapshotServiceTest` have been replaced with fully implemented tests.

## Threat Surface

Threat mitigations implemented as designed:

- **T-05-03-01 (Elevation of Privilege):** `isTenantMatch(jwt, modelId)` guard on all three EventLifecycleController endpoints — JWT `tenant` claim must equal `model_id`; mismatch returns 403.
- **T-05-03-02 (Tampering):** Snapshot events use `event_type='SNAPSHOT'`; retention job explicitly excludes SNAPSHOT events via `event_type != 'SNAPSHOT'`; `snapshot_boundary` recorded atomically in the same Phase 2 TX as snapshot writes.
- **T-05-03-03 (Denial of Service):** `@SchedulerLock(lockAtMostFor="PT55M")` prevents duplicate runs; per-tenant DELETE (not one global DELETE).
- **T-05-03-04 (Information Disclosure):** GET /admin/events/retention returns only `retention_days` (integer) and `snapshot_boundary` (timestamp) — no entity data; protected by JWT tenant guard.

## Self-Check: PASSED
