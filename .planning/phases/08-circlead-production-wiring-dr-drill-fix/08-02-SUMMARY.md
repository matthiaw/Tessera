---
phase: 08-circlead-production-wiring-dr-drill-fix
plan: "02"
subsystem: scripts, fabric-connectors
tags: [dr-drill, ops, smoke-test, wiremock, circlead]
dependency_graph:
  requires: [08-01]
  provides: [OPS-05, CIRC-01, CIRC-02]
  affects: [dr_drill.sh, CircleadDrillSmokeIT]
tech_stack:
  added: []
  patterns:
    - "Dynamic partition existence check via pg_class before INSERT to avoid partition-not-found errors"
    - "UUID cast (::uuid) for model_id in all Postgres queries — model_config.model_id is UUID PRIMARY KEY"
    - "Maven failsafe invocation of *IT.java files from shell scripts via -Dit.test= flag"
    - "WireMock loop-stub pattern for multi-type smoke tests sharing a single endpoint path"
key_files:
  created:
    - fabric-connectors/src/test/java/dev/tessera/connectors/circlead/CircleadDrillSmokeIT.java
  modified:
    - scripts/dr_drill.sh
decisions:
  - "TENANT_UUID=00000000-0000-0000-0000-000000000099 UUID literal replaces TEST_TENANT text string — model_config.model_id is UUID PRIMARY KEY per V27; text literal caused type mismatch"
  - "Dynamic partition via pg_class check in DO block — production month may differ from the hardcoded 2026m04 partition in V2 migration; guard is idempotent"
  - "Loop-stub WireMock pattern in CircleadDrillSmokeIT — one test method covers all three types, reducing boilerplate while proving each independently"
  - "Two separate psql heredocs in Step 4 (partition block + data block) — the partition DO block must use unquoted heredoc (variable expansion) while keeping clear separation from the data seed block"
metrics:
  duration_minutes: 15
  completed_date: "2026-04-17"
  tasks_completed: 2
  files_changed: 2
---

# Phase 08 Plan 02: DR Drill Fix + CircleadDrillSmokeIT Summary

**One-liner:** Fix DR drill column name mismatch (`entity_id` -> `node_uuid`, `created_at` -> `event_time`), add UUID model_id, dynamic partition safety, event-log replay verification, and WireMock-backed circlead smoke test.

## What Was Built

Fixed two broken areas of the DR drill:

1. **dr_drill.sh Step 4 — Column name fix:** The existing INSERT used `entity_id` and `created_at` which do not exist in V2__graph_events.sql. Replaced with the correct schema: `node_uuid`, `event_time`, plus all required NOT NULL columns (`sequence_nr`, `delta`, `caused_by`, `source_type`, `source_id`, `source_system`).

2. **dr_drill.sh — UUID model_id:** `TEST_TENANT="dr-rehearsal-tenant"` was a text value used as `model_config.model_id`, which is `UUID PRIMARY KEY`. Replaced with `TENANT_UUID="00000000-0000-0000-0000-000000000099"` with `::uuid` casts in all Postgres queries.

3. **dr_drill.sh — Dynamic partition safety:** Added a `DO $$ ... $$` block before seeding that creates `graph_events_y$(date +%Ym%m)` if it does not yet exist. The V2 migration only creates the April 2026 partition — any other month would fail without this guard.

4. **dr_drill.sh Step 9 — Event-log replay verification (OPS-05):** Added `REPLAY_COUNT` check ensuring at least 3 `person` type_slug events survive dump/restore.

5. **dr_drill.sh Step 9 — Circlead smoke test invocation (OPS-05):** Added `./mvnw -pl fabric-connectors failsafe:integration-test -Dit.test=CircleadDrillSmokeIT` invocation.

6. **CircleadDrillSmokeIT.java:** WireMock-backed integration test (no Spring context, no Testcontainers) that proves all three circlead entity types (ROLE, CIRCLE, ACTIVITY) produce `SyncOutcome.SUCCESS`. Uses the same `emptyRepo` anonymous `GraphRepository` pattern as `CircleadConnectorIT`. Single test method loops over all three types for concise coverage.

## Commits

| Hash | Type | Description |
|------|------|-------------|
| 361a313 | fix | dr_drill.sh column names, partition safety, replay verification, smoke test |
| ef4afac | test | CircleadDrillSmokeIT WireMock smoke test for OPS-05 |

## Deviations from Plan

None — plan executed exactly as written.

## Threat Surface Scan

No new trust boundaries introduced. T-08-04 and T-08-05 from the plan's threat model remain the applicable entries:
- Seed data is synthetic, in ephemeral Docker containers destroyed after drill
- Partition creation is idempotent and runs at most once per month

## Self-Check: PASSED

| Item | Status |
|------|--------|
| scripts/dr_drill.sh contains node_uuid | FOUND |
| scripts/dr_drill.sh contains event_time | FOUND |
| scripts/dr_drill.sh contains TENANT_UUID | FOUND |
| scripts/dr_drill.sh entity_id count = 0 | FOUND (0 occurrences) |
| scripts/dr_drill.sh contains failsafe:integration-test | FOUND |
| scripts/dr_drill.sh contains CircleadDrillSmokeIT | FOUND |
| CircleadDrillSmokeIT.java file exists | FOUND |
| CircleadDrillSmokeIT test passes | PASSED (1 test, 0 failures) |
| Commit 361a313 (fix) | FOUND |
| Commit ef4afac (test) | FOUND |
