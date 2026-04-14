---
phase: 00-foundations-risk-burndown
plan: 05
subsystem: ci-and-dr
tags: [foundations, ci, dump-restore, found-05, crit-1, d-04, d-06, d-07, d-11]
requires:
  - SeedGenerator (plan 00-04)
  - scripts/check_regression.sh (plan 00-04)
  - .planning/benchmarks/baseline-100000.json (plan 00-04)
  - docker-compose.yml digest (plan 00-02)
provides:
  - scripts/verify_queries.sh
  - scripts/dump_restore_rehearsal.sh
  - fabric-core/src/test/java/dev/tessera/core/bench/SeedDriver.java
  - .github/workflows/ci.yml
  - .github/workflows/nightly.yml
affects:
  - CI posture (push vs nightly cadence now enforced)
  - CRIT-1 escape hatch (proven end-to-end against 100k)
tech-stack:
  added: []
  patterns:
    - "AGE-aware DR: recreate graph + labels in fixed order, COPY label data; plain pg_dump -Fc alone is insufficient"
    - "Digest single-source-of-truth: docker-compose.yml is parsed by shell scripts"
    - "verify_queries.sh honours \\$PSQL env var so it runs from docker-exec wrappers"
key-files:
  created:
    - scripts/verify_queries.sh
    - scripts/dump_restore_rehearsal.sh
    - fabric-core/src/test/java/dev/tessera/core/bench/SeedDriver.java
    - .github/workflows/ci.yml
    - .github/workflows/nightly.yml
  modified: []
decisions:
  - "pg_dump -Fc alone cannot round-trip an AGE graph — create_graph() marks the graph schema and ag_catalog.ag_graph / ag_catalog.ag_label rows as extension-owned, so pg_dump silently excludes them. The runbook instead recreates the graph + labels in deterministic order and COPYs per-label data. Label-id determinism is the key invariant: SeedGenerator creates Person/Org/Doc/Tag/RELATES in that fixed order, the rehearsal does the same, and AGE assigns matching sequential ag_label.id values — so the graphid values embedded in edge start_id / end_id remain valid after COPY."
  - "CI is split into a fast push loop (verify + 100k JMH + regression) and a heavy nightly loop (1M JMH + DR rehearsal), enforcing D-04."
  - "Both workflows declare permissions: contents: read (least privilege, T-00-22)."
metrics:
  duration_minutes: ~30
  tasks_completed: 4
  tasks_total: 4
  completed: 2026-04-14
---

# Phase 0 Plan 05: CI + Dump/Restore Rehearsal Summary

Wired Phase 0's two CI cadences and proved the FOUND-05 / CRIT-1 AGE dump/restore runbook end-to-end against the 100k dataset.

## Tasks

| # | Task | Commit | Files |
|---|------|--------|-------|
| 1 | verify_queries.sh fixed query suite | e993d07 | scripts/verify_queries.sh |
| 2 | dump_restore_rehearsal.sh + SeedDriver + verify_queries fixes | f745f8b | scripts/dump_restore_rehearsal.sh, fabric-core/src/test/java/dev/tessera/core/bench/SeedDriver.java, scripts/verify_queries.sh |
| 3 | ci.yml push workflow | 21d006c | .github/workflows/ci.yml |
| 4 | nightly.yml | 33a810e | .github/workflows/nightly.yml |

## End-to-End Verification (FOUND-05 success criterion)

`scripts/dump_restore_rehearsal.sh` runs green locally against the 100k dataset. Sample run:

```
==> [1/7] starting source container on :55432
==> [2/7] CREATE EXTENSION age on source
==> [3/7] seeding 100000 nodes via SeedDriver ... BUILD SUCCESS (9.6s)
==> [4/7] running verify_queries.sh against source (pre-dump snapshot)
==> [5/7] pg_dump public schema + COPY graph labels out of source
    COPY 25000 (Person)  COPY 25000 (Org)  COPY 25000 (Doc)  COPY 25000 (Tag)
    COPY 399990 (RELATES)
==> [6/7] starting destination container on :55433 and rebuilding graph
    create_graph('tessera_bench')
    create_vlabel Person/Org/Doc/Tag  +  create_elabel RELATES  (in SeedGenerator order)
    COPY 25000 * 4 + COPY 399990
==> [7/7] running verify_queries.sh against destination (post-restore snapshot)
PASS: dump/restore rehearsal — pre and post query suites match (FOUND-05 / CRIT-1)
```

Diff between pre-dump and post-restore query suites is **byte-identical**, i.e. D-07 passes.

`./mvnw -B -ntp verify` stays green after adding SeedDriver:

```
Reactor Summary:
  Tessera Parent ..................................... SUCCESS
  Tessera :: fabric-core ............................. SUCCESS (29.9s)
  Tessera :: fabric-rules ............................ SUCCESS
  Tessera :: fabric-projections ...................... SUCCESS
  Tessera :: fabric-connectors ....................... SUCCESS
  Tessera :: fabric-app .............................. SUCCESS
BUILD SUCCESS — 37.0s
```

Both workflow YAMLs parse cleanly (`python3 -c "import yaml; yaml.safe_load(...)"`).

## CI Plumbing

### `.github/workflows/ci.yml` (push + PR to main)

- `verify` job: `./mvnw -B -ntp verify`.
- `benchmark-100k` job (`needs: verify`): runs `./mvnw -B -ntp -pl fabric-core -Pjmh -Djmh.dataset=100000 verify`, then `scripts/check_regression.sh 100000`. Uploads the benchmark JSON as an artifact on success or failure.
- `permissions: contents: read`. `concurrency: ci-${{ github.ref }}` with `cancel-in-progress: true` — cancels stale pushes on the same branch.

### `.github/workflows/nightly.yml` (cron `0 3 * * *` + `workflow_dispatch`)

- `benchmark-1m`: JMH on 1M dataset + `scripts/check_regression.sh 1000000`. 90-minute timeout.
- `dump-restore-rehearsal`: runs `scripts/dump_restore_rehearsal.sh`. 60-minute timeout. On failure captures `docker logs tessera-dump-src` and `tessera-dump-dst`. Per D-07, any divergence between pre/post query suites hard-fails this job.
- Concurrency group `nightly` with `cancel-in-progress: false` so a manual dispatch does not kill an in-flight scheduled run.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] AGE Cypher rejects `ORDER BY <alias>` in verify_queries.sh Q3**

- **Found during:** Task 2 (first rehearsal run).
- **Issue:** `MATCH (n:Person) RETURN n.idx AS i ORDER BY i ASC LIMIT 10` failed with `could not find rte for i` — Cypher/AGE does not resolve the projected alias in ORDER BY.
- **Fix:** Switched to `ORDER BY n.idx ASC`. Result is identical but AGE-parseable.
- **Files:** `scripts/verify_queries.sh` (folded into commit f745f8b).

**2. [Rule 3 - Blocking] Host `psql` not guaranteed to exist**

- **Found during:** Task 2 first rehearsal run — macOS dev loop did not have `psql` on PATH, so `scripts/verify_queries.sh` exploded before ever running a query.
- **Fix:** `verify_queries.sh` now honours a `PSQL` env var that can hold a command wrapper. The rehearsal sets `PSQL="docker exec -i -e PGPASSWORD=tessera <container> psql"` + `PGHOST=127.0.0.1 PGPORT=5432`, so the host only needs Docker. psql still works as the default.
- **Files:** `scripts/verify_queries.sh` (folded into commit f745f8b).

**3. [Rule 2 - Missing critical functionality] Plain `pg_dump -Fc` does NOT round-trip AGE graphs**

- **Found during:** Task 2 post-restore verify (`ERROR: graph with oid 16985 does not exist`).
- **Root cause:** `create_graph()` creates a Postgres schema (and the associated `ag_catalog.ag_graph` / `ag_catalog.ag_label` rows) with extension-owned dependencies. `pg_dump` therefore EXCLUDES the whole graph schema and the catalog rows from its output — the restored destination has an empty `ag_graph` table, no `tessera_bench` schema, and every Cypher query fails. This is the CRIT-1 failure mode live-fire: AGE blocks in-place `pg_upgrade` because the same machinery that makes the upgrade impossible also makes `pg_dump` insufficient on its own.
- **Fix:** Replaced the naive `pg_dump -Fc | pg_restore` flow with an AGE-aware runbook:
  1. `pg_dump -Fc -n public` still runs (for realism + to exercise the `pg_restore` path even though the benchmark graph is not in `public`).
  2. For each label, `COPY <graph>."<label>" TO <file>` out of source.
  3. On the destination: `CREATE EXTENSION age`, then `create_graph('tessera_bench')` followed by `create_vlabel` / `create_elabel` in the **same fixed order** as `SeedGenerator` (Person, Org, Doc, Tag, RELATES). This guarantees `ag_label.id` values match between source and destination so the graphid values embedded in edge `start_id`/`end_id` stay valid.
  4. `COPY <graph>."<label>" FROM <file>` into the destination for every label.
  5. Rebuild SeedGenerator's MIN-2 indexes (GIN on vertex properties, btree on edge `start_id`/`end_id`) and `ANALYZE`.
- **Files:** `scripts/dump_restore_rehearsal.sh`.
- **Impact:** This deviation is the actual Phase 0 output. The Plan assumed `pg_dump -Fc` + `pg_restore` would work; reality is harder and the rehearsal script now encodes the supported AGE DR workflow. This is also documented inline in the script as the CRIT-1 runbook — contributors reading the script learn WHY it takes the shape it does.

### Deferred Items

**Sequence bump after restore.** AGE's per-label sequences stay at 1 after COPY. A real DR cutover that accepts new `CREATE` statements would collide on graphid. The rehearsal does not exercise insertions post-restore (verify_queries.sh is read-only), so this is flagged inline in the script as a Phase 1 follow-up. The correct fix is a `setval(seq, max_id)` per label where `max_id` is the 48-bit sequence slice projected out of the graphid column — worth a dedicated unit of work with a test, not a one-liner squeezed into Phase 0.

### Auth gates

None — the rehearsal and workflows use only local `tessera/tessera` credentials (Phase 2 will swap to Vault per SEC-02).

## Threat Flags

None — this plan touches only the CI and rehearsal shell layer; no new network endpoints, no new authn paths, no new schema. Runs under `permissions: contents: read`.

## Self-Check: PASSED

Files created (verified with `test -f`):
- FOUND: `scripts/verify_queries.sh`
- FOUND: `scripts/dump_restore_rehearsal.sh`
- FOUND: `fabric-core/src/test/java/dev/tessera/core/bench/SeedDriver.java`
- FOUND: `.github/workflows/ci.yml`
- FOUND: `.github/workflows/nightly.yml`
- FOUND: `.planning/phases/00-foundations-risk-burndown/00-05-SUMMARY.md`

Commits (verified with `git log --oneline`):
- FOUND: e993d07 feat(00-05): verify_queries.sh fixed query suite (D-06)
- FOUND: f745f8b feat(00-05): dump_restore_rehearsal.sh + SeedDriver (FOUND-05 / CRIT-1)
- FOUND: 21d006c feat(00-05): ci.yml push workflow — verify + 100k JMH + regression (D-04, D-11)
- FOUND: 33a810e feat(00-05): nightly.yml — 1M JMH + dump/restore rehearsal (FOUND-05, D-07)

End-to-end checks:
- FOUND: `scripts/dump_restore_rehearsal.sh` exits 0 locally (100k dataset, pre/post byte-identical).
- FOUND: `./mvnw -B -ntp verify` exits 0.
- FOUND: Both workflow YAMLs parse via `yaml.safe_load`.
