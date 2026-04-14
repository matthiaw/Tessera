---
phase: 00-foundations-risk-burndown
plan: 02
subsystem: local-dev-stack
tags: [docker-compose, apache-age, postgres-16, FOUND-02, D-08, D-09]
requires: []
provides:
  - single-service docker-compose.yml (postgres-age only)
  - sha256-pinned apache/age image (two of three enforcement sites landed)
  - .env.example for local dev defaults
  - README quick-start
affects:
  - README.md
tech_stack_added: []
patterns: [digest-pinning, compose-v2-healthcheck]
key_files_created:
  - docker-compose.yml
  - .env.example
  - .planning/phases/00-foundations-risk-burndown/00-02-SUMMARY.md
key_files_modified:
  - README.md
decisions:
  - Used upstream tag `release_PG16_1.6.0` instead of the plan's referenced `PG16_latest` (that floating tag no longer exists on Docker Hub).
  - Digest pin policy (D-09) preserved unchanged — the digest, not the tag, is what flows into the image field.
metrics:
  tasks_completed: 3
  duration_minutes: ~6
  completed: 2026-04-13
---

# Phase 0 Plan 02: Local Dev Stack Summary

**One-liner:** Digest-pinned `docker-compose.yml` + `.env.example` + README quick-start delivering a single-service Postgres 16 + Apache AGE 1.6 stack for `docker compose up -d` bring-up, with the AGE image pinned by sha256 at two of the three D-09 enforcement sites (the third — Testcontainers helper — lands in plan 00-03).

## Resolved digest

```
apache/age@sha256:16aa423d20a31aed36a3313244bf7aa00731325862f20ed584510e381f2feaed
```

- **Source:** `docker pull apache/age:release_PG16_1.6.0 && docker image inspect --format '{{index .RepoDigests 0}}' apache/age:release_PG16_1.6.0`
- **Tag note:** The plan referenced `apache/age:PG16_latest`, but that tag is no longer published upstream (`docker pull apache/age:PG16_latest` → `not found`). The actual current AGE 1.6.0 PG16 release tag on Docker Hub is `release_PG16_1.6.0`. The sha256 digest above is the canonical one and is what flows into `image:` — the tag you used to resolve it is irrelevant once pinned.
- **Plan 00-03 must copy this digest verbatim** into `fabric-core/src/test/java/dev/tessera/core/support/AgePostgresContainer.java`.

## Tasks executed

| # | Name | Commit | Files |
|---|------|--------|-------|
| 1 | Resolve AGE sha256 digest | (no file written; digest embedded in Task 2) | — |
| 2 | docker-compose.yml + .env.example | `c82c146` | `docker-compose.yml`, `.env.example` |
| 3 | README quick-start with digest | `1fbbcc6` | `README.md` |

## Verification

- `grep -E '^[[:space:]]+image: apache/age@sha256:[a-f0-9]{64}$' docker-compose.yml` → match
- No `:PG16_latest`, no `:latest`, no `vault`, no `CREATE EXTENSION` strings in `docker-compose.yml`
- `docker compose config -q` parses cleanly
- README contains `sha256:[a-f0-9]{64}`, `docker compose up -d`, `./mvnw -B verify`, `three enforcement sites`, and all five `fabric-*` module names
- `diff <(grep -oE 'sha256:[a-f0-9]{64}' docker-compose.yml | sort -u) <(grep -oE 'sha256:[a-f0-9]{64}' README.md | sort -u)` → empty (digests match)

## Deviations from Plan

### Auto-fixed issues

**1. [Rule 3 — Blocker] Upstream tag `PG16_latest` no longer exists**

- **Found during:** Task 1
- **Issue:** Plan's example command `docker pull apache/age:PG16_latest` fails with `not found` from Docker Hub. Upstream AGE dropped the `PG16_latest` floating-tag convention; current release tags follow `release_PG<N>_<version>`.
- **Fix:** Pulled `apache/age:release_PG16_1.6.0` instead (confirmed via enumeration of `release_PG16_1.5.0`, `release_PG16_1.6.0`, `PG16_latest`, `latest`). Extracted the sha256 digest from that release tag. Digest is used verbatim in `docker-compose.yml` and `README.md`. D-09's substantive requirement (digest, not tag) is fully preserved — the tag only serves as a resolution path.
- **Files modified:** `docker-compose.yml`, `README.md`
- **Commits:** `c82c146`, `1fbbcc6`
- **Downstream note added:** README "Image pinning" section documents the `release_PG16_1.6.0` resolution command so contributors are not confused by the plan's stale reference.

No other deviations. Vault stayed deferred (D-08). No init-SQL mounted into the container (D-10 respected — AGE extension creation belongs to Flyway V1 in plan 00-03). No `tessera` app service added (Phase 0 DB substrate only).

## Known Stubs

None. All three files are wired end-to-end:

- `docker-compose.yml` is directly usable by `docker compose up -d`
- `.env.example` is documentation (expected to be copied to `.env` if overrides needed)
- README quick-start references real files that exist in this commit

## Threat Flags

None. Plan's threat register (T-00-06 through T-00-09) is fully addressed:

- **T-00-06** (apache/age image tampering): mitigated by the sha256 digest in `docker-compose.yml` and README (two of three sites — plan 00-03 lands the third).
- **T-00-09** (typo in image reference): mitigated by verification regex `apache/age@sha256:[a-f0-9]{64}` — any drift from the 64-hex format fails build.
- **T-00-07 / T-00-08** accepted per plan (local-dev defaults; production hardening is Phase 5 scope).

## Self-Check: PASSED

**Files:**
- FOUND: `/Users/matthiaswegner/Programmming/GitHub/Tessera/docker-compose.yml`
- FOUND: `/Users/matthiaswegner/Programmming/GitHub/Tessera/.env.example`
- FOUND: `/Users/matthiaswegner/Programmming/GitHub/Tessera/README.md` (modified)
- FOUND: `/Users/matthiaswegner/Programmming/GitHub/Tessera/.planning/phases/00-foundations-risk-burndown/00-02-SUMMARY.md` (this file, pending commit)

**Commits:**
- FOUND: `c82c146` — feat(00-02): add digest-pinned docker-compose stack
- FOUND: `1fbbcc6` — docs(00-02): README quick-start with digest-pinned AGE image

**Cross-file digest equality:** PASS — same sha256 in docker-compose.yml and README.md.
