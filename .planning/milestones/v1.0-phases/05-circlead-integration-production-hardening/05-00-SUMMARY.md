---
phase: 05-circlead-integration-production-hardening
plan: "00"
subsystem: foundation
tags: [migration, prometheus, observability, nyquist, test-stubs]
dependency_graph:
  requires: []
  provides:
    - V28-model-config-lifecycle-migration
    - micrometer-prometheus-dependency
    - micrometer-otel-tracing-dependency
    - prometheus-actuator-endpoint
    - nyquist-test-stubs-phase5
  affects:
    - fabric-app
    - fabric-core
    - fabric-connectors
    - fabric-rules
tech_stack:
  added:
    - io.micrometer:micrometer-registry-prometheus (BOM-managed)
    - io.micrometer:micrometer-tracing-bridge-otel (BOM-managed)
  patterns:
    - Flyway ALTER TABLE ADD COLUMN IF NOT EXISTS for idempotent schema extensions
    - @Disabled JUnit 5 stub pattern for Nyquist compliance before implementation
    - No-op SELECT 1 test migrations for modules with migration gaps
key_files:
  created:
    - fabric-app/src/main/resources/db/migration/V28__model_config_lifecycle.sql
    - fabric-core/src/test/resources/db/migration/V28__model_config_lifecycle.sql
    - fabric-projections/src/test/resources/db/migration/V28__model_config_lifecycle.sql
    - fabric-rules/src/test/resources/db/migration/V14__connectors.sql (no-op)
    - fabric-rules/src/test/resources/db/migration/V15__connector_sync_status.sql (no-op)
    - fabric-rules/src/test/resources/db/migration/V16__pgvector_extension.sql (no-op)
    - fabric-rules/src/test/resources/db/migration/V17__entity_embeddings.sql (no-op)
    - fabric-rules/src/test/resources/db/migration/V18__extraction_review_queue.sql (no-op)
    - fabric-rules/src/test/resources/db/migration/V19__graph_events_provenance_columns.sql (no-op)
    - fabric-rules/src/test/resources/db/migration/V20__schema_embedding_flags.sql (no-op)
    - fabric-rules/src/test/resources/db/migration/V21__connectors_auth_type_widen.sql (no-op)
    - fabric-rules/src/test/resources/db/migration/V22__mcp_audit_log.sql (no-op)
    - fabric-rules/src/test/resources/db/migration/V23__mcp_agent_quotas.sql (no-op)
    - fabric-rules/src/test/resources/db/migration/V24__outbox_published_flag.sql (no-op)
    - fabric-rules/src/test/resources/db/migration/V25__hash_chain_audit.sql (no-op)
    - fabric-rules/src/test/resources/db/migration/V26__replication_slot_wal_limit.sql (no-op)
    - fabric-rules/src/test/resources/db/migration/V27__tenant_hash_chain_config.sql (model_config CREATE TABLE)
    - fabric-rules/src/test/resources/db/migration/V28__model_config_lifecycle.sql
    - fabric-app/src/test/java/dev/tessera/app/metrics/TesseraMetricsTest.java
    - fabric-app/src/test/java/dev/tessera/app/health/AgeGraphHealthIndicatorTest.java
    - fabric-app/src/test/java/dev/tessera/app/health/ConnectorHealthIndicatorTest.java
    - fabric-core/src/test/java/dev/tessera/core/events/EventRetentionJobTest.java
    - fabric-core/src/test/java/dev/tessera/core/events/snapshot/EventSnapshotServiceTest.java
    - fabric-connectors/src/test/java/dev/tessera/connectors/circlead/CircleadConnectorIT.java
  modified:
    - fabric-app/pom.xml
    - fabric-app/src/main/resources/application.yml
decisions:
  - V28 uses ADD COLUMN IF NOT EXISTS — idempotent on re-run (ADR-aligned)
  - prometheus.access: unrestricted is deliberate for unauthenticated Prometheus scraper; network-level firewall on IONOS VPS restricts scraper IP (T-05-00-01 accepted risk)
  - fabric-rules test resources were missing V14-V27 no-op migrations; V27 (model_config CREATE TABLE) added as real DDL since V28 ALTER TABLE depends on it
metrics:
  duration_seconds: 256
  completed_date: "2026-04-17"
  tasks_completed: 2
  files_created: 24
  files_modified: 2
---

# Phase 5 Plan 00: Wave 0 Foundation Summary

**One-liner:** V28 migration adds lifecycle columns to model_config, Prometheus/OTel bridge added to pom.xml with /actuator/prometheus endpoint, and 6 @Disabled Nyquist stubs created for all Phase 5 production classes.

## Tasks Completed

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 | V28 migration + POM dependencies + application.yml | 579f807 | V28 in 4 dirs, pom.xml, application.yml (+15 fabric-rules no-ops) |
| 2 | Test stubs for all Phase 5 production classes | e973634 | 6 @Disabled test stub files |

## Verification Results

- V28 migration exists in all 4 directories with identical `retention_days` and `snapshot_boundary` columns
- `grep -c "micrometer" fabric-app/pom.xml` returns 2 (micrometer-registry-prometheus, micrometer-tracing-bridge-otel)
- application.yml exposes `prometheus` endpoint with `access: unrestricted`
- All 6 test stubs compile cleanly (`mvnw compile -pl fabric-app,fabric-core,fabric-connectors -DskipTests -Dspotless.check.skip=true` — BUILD SUCCESS)
- All 6 stubs contain `@Disabled` annotation

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Added V14-V27 no-op migrations to fabric-rules test resources**

- **Found during:** Task 1
- **Issue:** fabric-rules test migration directory only had migrations up to V13. V28 ALTER TABLE model_config would fail at Flyway runtime because model_config table (V27) did not exist in fabric-rules test DB. Without V14-V27, any fabric-rules IT running Flyway would fail with "table model_config does not exist".
- **Fix:** Added V14-V25 as `SELECT 1` no-ops matching the pattern used in fabric-core test resources (V16, V17, V26 are already no-ops there). V27 added as the real CREATE TABLE model_config DDL since V28 depends on it. V28 copied identically.
- **Files modified:** 15 new files in fabric-rules/src/test/resources/db/migration/
- **Commit:** 579f807

## Known Stubs

The 6 test stubs created in this plan are intentional placeholders — each is annotated with `@Disabled("Phase 5 Wave N — stub for Nyquist compliance")`. They will be activated and implemented in waves 1-4 of Phase 5:

| Stub | File | Wave |
|------|------|------|
| TesseraMetricsTest | fabric-app/.../metrics/TesseraMetricsTest.java | Wave 1 (OPS-01) |
| AgeGraphHealthIndicatorTest | fabric-app/.../health/AgeGraphHealthIndicatorTest.java | Wave 2 (OPS-02) |
| ConnectorHealthIndicatorTest | fabric-app/.../health/ConnectorHealthIndicatorTest.java | Wave 2 (OPS-02) |
| EventRetentionJobTest | fabric-core/.../events/EventRetentionJobTest.java | Wave 3 (OPS-04) |
| EventSnapshotServiceTest | fabric-core/.../events/snapshot/EventSnapshotServiceTest.java | Wave 3 (OPS-03) |
| CircleadConnectorIT | fabric-connectors/.../circlead/CircleadConnectorIT.java | Wave 4 (CIRC-01) |

These stubs do not prevent the plan's goal from being achieved — the goal is to create the stubs themselves.

## Threat Surface

No new network endpoints, auth paths, or schema changes at trust boundaries beyond those documented in the plan's threat model (T-05-00-01: /actuator/prometheus with unrestricted access — mitigated by network-level firewall on IONOS VPS).

## Self-Check: PASSED
