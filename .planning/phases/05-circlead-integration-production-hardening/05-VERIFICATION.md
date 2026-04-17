---
phase: 05-circlead-integration-production-hardening
verified: 2026-04-17T11:17:08Z
status: gaps_found
score: 2/4 success criteria fully verified
gaps:
  - truth: "Spring Boot Actuator health endpoint reports the status of Postgres, AGE, Vault, and every registered connector"
    status: partial
    reason: "Vault health indicator is absent. No spring-cloud-vault dependency in any pom.xml. Spring Boot Actuator cannot auto-configure a Vault health indicator without spring-cloud-starter-vault-config on the classpath. Postgres (auto-config), AGE (AgeGraphHealthIndicator), and Connector (ConnectorHealthIndicator) are present. Three of the four named components are covered; Vault is not."
    artifacts:
      - path: "fabric-app/pom.xml"
        issue: "spring-cloud-starter-vault-config (or equivalent) is absent — no Vault health auto-configuration possible"
    missing:
      - "Add spring-cloud-starter-vault-config to fabric-app/pom.xml (or add a VaultHealthIndicator @Component with @ConditionalOnProperty to degrade gracefully when Vault is not configured in MVP)"
  - truth: "A full DR drill (dump → restore → replay → consumer smoke test against circlead) is rehearsed end-to-end and documented, and the whole milestone-1 scope (all prior phases) remains green on CI"
    status: partial
    reason: "scripts/dr_drill.sh covers dump, restore, and Flyway validate/data-integrity, but two elements of the roadmap SC4 contract are absent: (1) no event-log replay step — the script does not replay graph_events against a running Tessera instance; (2) no circlead consumer smoke test — the script validates the Tessera DB layer only, with no step that exercises circlead reading from Tessera's REST or MCP projections. D-D1 in 05-CONTEXT.md intentionally scoped the automated script to the DB layer and deferred the API smoke test to a manual IONOS VPS runbook (docs/DR-DRILL.md section 5.8), but that runbook tests Tessera self-health, not a circlead consumer. The 'milestone-1 remains green on CI' part is satisfied by the existing CI verify job."
    artifacts:
      - path: "scripts/dr_drill.sh"
        issue: "No event-log replay step and no circlead consumer smoke test step — covers only dump/restore/Flyway validate/DB data integrity"
      - path: "docs/DR-DRILL.md"
        issue: "IONOS VPS section 5.8 tests Tessera self-health (actuator/health, entity endpoint); no circlead consumer step documented"
    missing:
      - "Add an event-log replay step to scripts/dr_drill.sh (or document it explicitly in DR-DRILL.md as a required manual step with example psql or EventLog queries)"
      - "Add a circlead consumer smoke test step: either a curl call to a running Tessera REST endpoint returning circlead entity data, or document the end-to-end check in DR-DRILL.md with explicit instructions"
---

# Phase 5: Circlead Integration & Production Hardening — Verification Report

**Phase Goal:** Prove the whole stack against the first real consumer (circlead) without a big-bang migration, and harden operations with observability, snapshots, retention, and a rehearsed DR drill so Tessera is safe to run on IONOS VPS.
**Verified:** 2026-04-17T11:17:08Z
**Status:** gaps_found
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths (from Roadmap Success Criteria)

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| SC1 | circlead reads Role, Circle, Activity from Tessera via REST and MCP in parallel with its own JPA model; mapping round-trips cleanly; circlead continues functioning when Tessera is unavailable | VERIFIED | 3 MappingDefinition JSONs (circlead-role/circle/activity-mapping.json) with circlead_id identity field; CircleadConnectorConfig wires them; 4 CircleadConnectorIT tests pass against WireMock; docs/circlead-mapping.md section 5 documents Spring Retry graceful degradation pattern |
| SC2 | Operator can view Prometheus/OTel metrics for ingest rate, rule evaluations, conflict count, outbox lag, replication slot lag, SHACL validation time; Actuator health reports Postgres, AGE, Vault, and every connector | FAILED | TesseraMetrics.java registers all 6 meters (verified); /actuator/prometheus exposed; Postgres (auto-config), AGE (AgeGraphHealthIndicator), Connector (ConnectorHealthIndicator) health present — but Vault health is absent (no spring-cloud-vault in pom.xml, no VaultHealthIndicator) |
| SC3 | Operator can configure per-tenant event-log retention and trigger per-tenant snapshot that compacts the event log while preserving temporal queries above snapshot boundary | VERIFIED | EventRetentionJob (daily cron, REQUIRES_NEW, ShedLock, retention_days IS NOT NULL guard, SNAPSHOT event exclusion); EventSnapshotService (three-phase TransactionTemplate compaction, snapshot_boundary written atomically); EventLifecycleController (POST /admin/events/snapshot, GET/PUT /admin/events/retention with JWT tenant-match guard); V28 migration adds retention_days and snapshot_boundary columns |
| SC4 | Full DR drill (dump → restore → replay → consumer smoke test against circlead) rehearsed end-to-end, documented, and milestone-1 scope remains green on CI | FAILED | scripts/dr_drill.sh covers dump/restore/Flyway validate/DB data integrity (9 steps, executable); CI dr-drill job added with needs:verify and if:push; docs/DR-DRILL.md has IONOS VPS section 5 with actuator/health check. However: (1) no event-log replay step in script or docs; (2) no circlead consumer smoke test step — section 5.8 checks Tessera self-health only, not a circlead consumer calling Tessera |

**Score:** 2/4 success criteria fully verified

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `fabric-app/src/main/resources/db/migration/V28__model_config_lifecycle.sql` | Lifecycle columns for event retention and snapshots | VERIFIED | Contains ALTER TABLE model_config ADD COLUMN IF NOT EXISTS retention_days INT NULL, snapshot_boundary TIMESTAMPTZ NULL |
| `fabric-app/pom.xml` | Micrometer Prometheus and OTel dependencies | VERIFIED | micrometer-registry-prometheus at line 51, micrometer-tracing-bridge-otel at line 55 |
| `fabric-app/src/main/resources/application.yml` | Prometheus endpoint exposed | VERIFIED | include: health,info,prometheus; prometheus.access: unrestricted |
| `fabric-app/src/test/java/dev/tessera/app/metrics/TesseraMetricsTest.java` | 9 tests for TesseraMetrics, @Disabled removed | VERIFIED | 9 @Test methods, 0 @Disabled |
| `fabric-app/src/test/java/dev/tessera/app/health/AgeGraphHealthIndicatorTest.java` | 3 tests, @Disabled removed | VERIFIED | 3 @Test methods, 0 @Disabled |
| `fabric-app/src/test/java/dev/tessera/app/health/ConnectorHealthIndicatorTest.java` | 4 tests, @Disabled removed | VERIFIED | 4 @Test methods, 0 @Disabled |
| `fabric-app/src/main/java/dev/tessera/app/metrics/TesseraMetrics.java` | 6 Micrometer meters (3 counters, 2 gauges, 1 timer) | VERIFIED | @Component, MeterRegistry constructor injection, all 6 metric names present, recordIngest/recordRuleEvaluation/recordConflict public methods |
| `fabric-app/src/main/java/dev/tessera/app/health/AgeGraphHealthIndicator.java` | AGE graph health check | VERIFIED | @Component("ageGraph"), extends AbstractHealthIndicator, queries ag_catalog.ag_graph |
| `fabric-app/src/main/java/dev/tessera/app/health/ConnectorHealthIndicator.java` | Per-connector health status | VERIFIED | @Component("connectors"), extends AbstractHealthIndicator, queries connector_sync_status |
| `fabric-connectors/src/main/resources/connectors/circlead-role-mapping.json` | Role mapping with circlead_id | VERIFIED | Contains circlead_id identity field |
| `fabric-connectors/src/main/resources/connectors/circlead-circle-mapping.json` | Circle mapping with circlead_id | VERIFIED | Contains circlead_id identity field |
| `fabric-connectors/src/main/resources/connectors/circlead-activity-mapping.json` | Activity mapping with circlead_id | VERIFIED | Contains circlead_id identity field |
| `fabric-connectors/src/main/java/dev/tessera/connectors/circlead/CircleadConnectorConfig.java` | Spring @Configuration wiring 3 mapping beans | VERIFIED | @Configuration, 3 @Bean methods, @Value("classpath:connectors/circlead-*") resource loading |
| `fabric-connectors/src/test/java/dev/tessera/connectors/circlead/CircleadConnectorIT.java` | 4 WireMock tests, @Disabled removed | VERIFIED | WireMockExtension, GenericRestPollerConnector, 4 @Test methods, 0 @Disabled |
| `docs/circlead-mapping.md` | Field mapping documentation + graceful degradation | VERIFIED | 292 lines; covers Role/Circle/Activity field tables, edge types, connector config, Spring Retry pattern (Section 5) |
| `fabric-core/src/main/java/dev/tessera/core/events/EventRetentionJob.java` | Daily retention sweep with ShedLock | VERIFIED | @Scheduled(cron="0 0 2 * * *"), @SchedulerLock("tessera-event-retention"), @Transactional(REQUIRES_NEW), retention_days IS NOT NULL, event_type != 'SNAPSHOT', COALESCE snapshot_boundary guard |
| `fabric-core/src/main/java/dev/tessera/core/events/snapshot/EventSnapshotService.java` | Three-phase compaction | VERIFIED | TransactionTemplate, three separate tx.execute() calls, INSERT SNAPSHOT events, UPDATE model_config.snapshot_boundary, DELETE pre-boundary non-SNAPSHOT events |
| `fabric-core/src/main/java/dev/tessera/core/events/snapshot/SnapshotResult.java` | Snapshot result record | VERIFIED | public record SnapshotResult(Instant boundary, int eventsWritten, int eventsDeleted) |
| `fabric-core/src/main/java/dev/tessera/core/admin/EventLifecycleController.java` | Admin endpoints with tenant guard | VERIFIED | @RequestMapping("/admin/events"), isTenantMatch JWT guard, snapshotService.compact() wired |
| `scripts/dr_drill.sh` | End-to-end DR rehearsal | PARTIAL | Executable, set -euo pipefail, pg_dump, pg_restore, Flyway migrate/validate; missing: event replay step, circlead consumer smoke test |
| `docs/DR-DRILL.md` | DR documentation with IONOS VPS steps | PARTIAL | IONOS section 5 present with actuator/health check; no circlead consumer smoke test step |
| `.github/workflows/ci.yml` | CI dr-drill job | VERIFIED | dr-drill job with needs:verify, if:github.event_name=='push', timeout-minutes:20, runs scripts/dr_drill.sh |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|----|--------|---------|
| V28 migration | model_config table | ALTER TABLE ADD COLUMN | WIRED | Line 2: `ALTER TABLE model_config ADD COLUMN IF NOT EXISTS retention_days INT NULL, snapshot_boundary TIMESTAMPTZ NULL` |
| TesseraMetrics | MeterRegistry | constructor injection | WIRED | `public TesseraMetrics(MeterRegistry registry, NamedParameterJdbcTemplate jdbc)` |
| AgeGraphHealthIndicator | /actuator/health | @Component("ageGraph") auto-discovery | WIRED | `@Component("ageGraph")` on AbstractHealthIndicator subclass |
| EventRetentionJob | model_config.retention_days | SELECT WHERE retention_days IS NOT NULL | WIRED | Line 63: `+ " WHERE retention_days IS NOT NULL"` |
| EventSnapshotService | model_config.snapshot_boundary | UPDATE model_config SET snapshot_boundary | WIRED | Line 84: `"UPDATE model_config SET snapshot_boundary = :boundary"` |
| EventLifecycleController | EventSnapshotService | snapshotService.compact(modelId) | WIRED | Line 83: `SnapshotResult result = snapshotService.compact(modelId)` |
| CircleadConnectorConfig | MappingDefinition | classpath resource loading | WIRED | `@Value("classpath:connectors/circlead-role-mapping.json")` on 3 resources |
| CircleadConnectorIT | GenericRestPollerConnector | poll() with MappingDefinition | WIRED | Line 30: `import dev.tessera.connectors.rest.GenericRestPollerConnector`, used in all 4 tests |
| CI workflow | scripts/dr_drill.sh | CI job invocation | WIRED | Line 104: `run: scripts/dr_drill.sh` |

### Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | Status |
|----------|--------------|--------|-------------------|--------|
| TesseraMetrics (gauges) | tessera.outbox.lag, tessera.replication.slot.lag | Gauge lambda queries graph_outbox and pg_replication_slots via NamedParameterJdbcTemplate | Yes (with null-guard for unit tests) | FLOWING |
| AgeGraphHealthIndicator | graphs list | SELECT name FROM ag_catalog.ag_graph via NamedParameterJdbcTemplate | Yes (query result) | FLOWING |
| ConnectorHealthIndicator | connector rows | SELECT connectors JOIN connector_sync_status WHERE enabled=TRUE | Yes (query result) | FLOWING |
| EventRetentionJob | tenant retention config | SELECT model_id, retention_days, snapshot_boundary FROM model_config WHERE retention_days IS NOT NULL | Yes (real DB query) | FLOWING |
| EventSnapshotService | entity list | SELECT DISTINCT ON (node_uuid) from graph_events | Yes (real DB query) | FLOWING |

### Behavioral Spot-Checks

Step 7b: SKIPPED — no runnable entry points available (Spring Boot app class absent, fabric-app pom.xml comment at line 87 confirms: "no main class yet, @SpringBootApplication lands in a later plan"). The test suites serve as the verification mechanism instead.

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|------------|-------------|--------|----------|
| CIRC-01 | 05-02-PLAN.md | circlead reads from Tessera via REST and MCP in parallel, no big-bang migration | SATISFIED | CircleadConnectorIT 4 tests pass; mapping JSONs + Config present; docs cover parallel operation |
| CIRC-02 | 05-02-PLAN.md | Mapping from circlead entities round-trips cleanly | SATISFIED | 3 MappingDefinition JSONs with field-level mappings; circlead-mapping.md Section 2 documents round-trip |
| CIRC-03 | 05-02-PLAN.md | circlead continues to function if Tessera unavailable | SATISFIED | docs/circlead-mapping.md Section 5 documents Spring Retry @Retryable pattern (circlead-side implementation guidance) |
| OPS-01 | 05-00-PLAN.md, 05-01-PLAN.md | Prometheus/OTel metrics for ingest rate, rule evaluations, conflict count, outbox lag, replication slot lag, SHACL validation time | SATISFIED | TesseraMetrics.java registers all 6 meters; 9 tests pass; prometheus endpoint exposed |
| OPS-02 | 05-01-PLAN.md | Actuator health exposes Postgres, AGE, Vault, and connector health | BLOCKED | Postgres (auto-config): present; AGE (AgeGraphHealthIndicator): present; Connector (ConnectorHealthIndicator): present; Vault: absent — no spring-cloud-vault dependency |
| OPS-03 | 05-03-PLAN.md | Per-tenant snapshot compacts event log for long-lived tenants | SATISFIED | EventSnapshotService three-phase compaction; 5 tests pass; POST /admin/events/snapshot endpoint |
| OPS-04 | 05-03-PLAN.md | Per-tenant event-log retention configurable | SATISFIED | EventRetentionJob daily sweep; V28 migration adds retention_days column; GET/PUT /admin/events/retention endpoints |
| OPS-05 | 05-04-PLAN.md | DR drill rehearsed end-to-end: dump → restore → replay → consumer smoke test | BLOCKED | dump/restore/Flyway validate: present; event replay step: absent; circlead consumer smoke test: absent |

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|---------|--------|
| `fabric-core/src/main/java/dev/tessera/core/events/snapshot/EventSnapshotService.java` | 144, 156 | `return null` | Info | Required TransactionCallback return type for void-like operations — not a stub pattern |
| `fabric-app/pom.xml` | 87-90 | `<skip>true</skip>` on spring-boot-maven-plugin | Info | Intentional: no @SpringBootApplication class yet; documented in comment; not a stub |

No blockers or warnings found. The two Info items are intentional design decisions, not stubs.

### Human Verification Required

None required for automated checks. The following items are noted as deployment-time concerns not resolvable programmatically:

1. **Vault health at runtime**
   **Test:** Deploy with spring-cloud-starter-vault-config on classpath; call GET /actuator/health
   **Expected:** {"status":"UP"} with vault component present
   **Why human:** Requires a running Vault instance and deployed Spring Boot app; no @SpringBootApplication class exists yet in this phase

2. **circlead connector end-to-end against a real circlead instance**
   **Test:** Configure tessera.connectors.circlead.base-url pointing to a running circlead instance; trigger a connector sync; query GET /api/v1/{model}/entities/role
   **Expected:** Roles from circlead appear as graph nodes in Tessera
   **Why human:** Requires a running circlead deployment; out of scope for automated test environment

### Gaps Summary

Two gaps block full goal achievement:

**Gap 1: OPS-02 — Vault health indicator missing**
The roadmap requires the health endpoint to report Postgres, AGE, Vault, and connector status. Postgres, AGE, and connector health are all present. Vault health is absent because spring-cloud-starter-vault-config is not in fabric-app/pom.xml, and no custom VaultHealthIndicator was created. The phase context (D-B2) noted Vault as "existing" (implying Spring Cloud Vault was already on the classpath), but that assumption was incorrect. Resolution: add spring-cloud-starter-vault-config to fabric-app/pom.xml, or add a minimal @ConditionalOnProperty VaultHealthIndicator that gracefully degrades when Vault is not configured for the MVP.

**Gap 2: OPS-05 — DR drill missing replay and circlead consumer smoke test**
The roadmap SC4 and OPS-05 requirement specify: dump → restore → replay → consumer smoke test. The DR drill script delivers dump → restore → Flyway validate → DB data integrity. Two elements are absent: (a) event-log replay (replaying graph_events against a live Tessera instance to verify temporal queries work after restore); (b) a circlead consumer smoke test (verifying circlead can successfully read from Tessera's REST or MCP endpoints after restore). The phase plan's D-D1 intentionally scoped the automated script to the DB layer, but this represents a deviation from the roadmap contract. Resolution: add at minimum a documented manual replay step and a circlead consumer test to DR-DRILL.md, or extend the script's smoke test section to cover these steps when Tessera is running.

These two gaps are related to OPS-02 and OPS-05 respectively and are independent — they can be addressed in separate plan iterations.

---

_Verified: 2026-04-17T11:17:08Z_
_Verifier: Claude (gsd-verifier)_
