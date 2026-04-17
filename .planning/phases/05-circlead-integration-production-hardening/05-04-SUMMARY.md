---
phase: 05-circlead-integration-production-hardening
plan: "04"
subsystem: dr-drill-ci
tags: [disaster-recovery, pg_dump, pg_restore, flyway, ci-pipeline, ionos, smoke-test]
dependency_graph:
  requires:
    - dump_restore_rehearsal-pattern
    - ci-yml-verify-job
  provides:
    - dr-drill-script
    - dr-drill-documentation
    - ci-dr-drill-job
  affects:
    - scripts/
    - docs/
    - .github/workflows/
tech_stack:
  added: []
  patterns:
    - "9-step DR rehearsal: start SRC → TCP poll → Flyway migrate → seed → pg_dump → start DST → pg_restore → Flyway validate → smoke test"
    - "Digest sourced from docker/age-pgvector/Dockerfile (grep pattern; build-context projects carry digest in Dockerfile, not docker-compose.yml)"
    - "CI dr-drill job: needs verify, if push, timeout-minutes 20"
key_files:
  created:
    - scripts/dr_drill.sh
    - docs/DR-DRILL.md
  modified:
    - .github/workflows/ci.yml
decisions:
  - "AGE image digest sourced from docker/age-pgvector/Dockerfile rather than docker-compose.yml — docker-compose.yml uses a build context (not a direct image reference); the pinned digest lives in the Dockerfile's FROM line; same grep pattern as dump_restore_rehearsal.sh adapted to the correct file"
  - "DR drill validates DB layer only (no Spring Boot startup in CI) — sufficient for OPS-05 rehearsal and avoids Vault/Ollama/Kafka service dependencies in CI"
  - "Flyway migrate/validate invoked via ./mvnw -pl fabric-app with jdbc URL flags — avoids starting the full app; targets fabric-app module which owns the authoritative migration scripts"
metrics:
  duration_seconds: 128
  completed_date: "2026-04-17"
  tasks_completed: 2
  files_created: 2
  files_modified: 1
---

# Phase 5 Plan 04: DR Drill and CI Extension Summary

**One-liner:** 9-step DR drill script (start SRC, Flyway migrate, seed, pg_dump, start DST, pg_restore, Flyway validate, smoke test) with IONOS VPS runbook documentation and a CI dr-drill job triggered on push after verify passes.

## Tasks Completed

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 | DR drill script + documentation | 1b69391 | scripts/dr_drill.sh, docs/DR-DRILL.md |
| 2 | CI pipeline extension with DR drill job | a5ed30a | .github/workflows/ci.yml |

## Verification Results

- `test -x scripts/dr_drill.sh` — PASS (executable bit set)
- `grep "set -euo pipefail" scripts/dr_drill.sh` — MATCH
- `grep "pg_dump" scripts/dr_drill.sh` — MATCH
- `grep "pg_restore" scripts/dr_drill.sh` — MATCH
- `grep -i "flyway" scripts/dr_drill.sh` — MATCH (flyway:migrate + flyway:validate)
- `grep "IONOS" docs/DR-DRILL.md` — MATCH (section 5: IONOS VPS Deployment)
- `grep "actuator/health" docs/DR-DRILL.md` — MATCH (step 5.8)
- `grep "Troubleshooting" docs/DR-DRILL.md` — MATCH (section 6)
- `grep -c "dr-drill\|dr_drill" .github/workflows/ci.yml` — 2 (job name + script reference)
- `grep "needs: verify" .github/workflows/ci.yml` — MATCH
- `grep "github.event_name == 'push'" .github/workflows/ci.yml` — MATCH
- Existing verify and benchmark-100k jobs: unchanged

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Digest sourced from Dockerfile instead of docker-compose.yml**

- **Found during:** Task 1 (reading codebase context before writing)
- **Issue:** The plan specified grepping `docker-compose.yml` for the AGE image digest (same pattern as `dump_restore_rehearsal.sh`). However, this project uses a build context in `docker-compose.yml` (`build: context: docker/age-pgvector`) rather than a direct image reference. The pinned `apache/age@sha256:...` digest lives in `docker/age-pgvector/Dockerfile`'s `FROM` line.
- **Fix:** The grep pattern was applied to `docker/age-pgvector/Dockerfile` instead of `docker-compose.yml`. This is consistent with `AgePostgresContainer.java` which hard-codes the same digest as a constant.
- **Files modified:** scripts/dr_drill.sh (grep target path only)
- **Commit:** 1b69391

## Known Stubs

None — both deliverables are fully implemented.

## Threat Surface

Threat mitigations implemented as designed:

- **T-05-04-01 (Tampering):** Script runs in ephemeral containers (mktemp + trap cleanup); no production data involved; used only in CI and local dev.
- **T-05-04-02 (Information Disclosure):** DR drill does not upload dump files as CI artifacts (`docs/DR-DRILL.md` step 4 explicitly notes this); containers destroyed on exit via `trap cleanup EXIT`.
- **T-05-04-03 (Denial of Service):** `timeout-minutes: 20` prevents runaway CI jobs; `if: github.event_name == 'push'` prevents PR abuse; job only runs after `verify` passes.

## Self-Check: PASSED
