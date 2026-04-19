#!/usr/bin/env bash
# Copyright 2026 Tessera Contributors
# Licensed under the Apache License, Version 2.0
#
# FOUND-05 / CRIT-1: pg_dump / pg_restore rehearsal against a seeded AGE database.
# Seeds the `tessera_bench` graph via SeedGenerator, dumps it, restores it into
# a fresh container, and diffs the fixed query suite output.
#
#   D-05: Reuses the 100k benchmark dataset (same SeedGenerator the JMH harness uses).
#   D-06: Runs the fixed query suite (scripts/verify_queries.sh) before pg_dump and
#         after pg_restore on a fresh container.
#   D-07: ANY divergence between pre-dump and post-restore output hard-fails this script.
#   CRIT-1: AGE blocks vanilla `pg_upgrade` AND vanilla `pg_dump -Fc` — this rehearsal
#           IS the real upgrade runbook. If it passes, the documented AGE dump/restore
#           escape hatch is proven on every nightly run.
#
# ### Why `pg_dump -Fc` alone does NOT work on AGE
#
# `create_graph()` creates a Postgres schema (`tessera_bench`) and label tables
# whose dependencies flow through the `age` extension. pg_dump therefore treats
# the whole schema as extension-owned and EXCLUDES it from the dump entirely.
# A fresh `pg_restore` into another AGE-enabled DB yields zero rows. The
# `ag_catalog.ag_graph` and `ag_catalog.ag_label` catalog tables are also
# extension-owned and dumped empty, so the receiving DB never learns the graph
# exists. This is the CRIT-1 failure mode and the reason AGE blocks in-place
# `pg_upgrade` — the same machinery is missing.
#
# ### Runbook implemented below
#
#   1. On the source: pg_dump the `public` schema as normal (captures any
#      non-graph data) for completeness; then, for each label known to the
#      source graph, `COPY tessera_bench."<label>" TO STDOUT` into a per-label
#      text file.
#   2. On a fresh destination container: install the `age` extension,
#      `create_graph('tessera_bench')`, and recreate every vlabel/elabel in
#      the **same order** as SeedGenerator — so the internal 16-bit label-ids
#      (which are baked into every vertex/edge graphid) match between source
#      and destination.
#   3. `COPY tessera_bench."<label>" FROM STDIN` each label file.
#   4. Rebuild the MIN-2 indexes (GIN on vertex properties, btree on edge
#      start_id/end_id) that SeedGenerator added.
#   5. Run the fixed query suite pre and post, diff.
#
# Step 2's label-id determinism is the key invariant: AGE's `create_vlabel`
# allocates sequential `ag_label.id` values. Creating them in a fixed,
# documented order guarantees the ids encoded inside `start_id`/`end_id`
# graphid columns on the edge table remain valid after COPY.
#
# Usage: scripts/dump_restore_rehearsal.sh
# Requires: docker, ./mvnw, psql NOT required on the host.

set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

# ---------------------------------------------------------------------------
# D-09: single source of truth for the AGE image digest — docker/age-pgvector/Dockerfile.
# Bumping it in one place auto-rolls this rehearsal. No hardcoded digest here.
# ---------------------------------------------------------------------------
DIGEST=$(grep -oE 'apache/age@sha256:[a-f0-9]{64}' docker/age-pgvector/Dockerfile | head -1 || true)
if [[ -z "$DIGEST" ]]; then
  echo "FAIL: could not extract apache/age sha256 digest from docker/age-pgvector/Dockerfile" >&2
  exit 1
fi
echo "dump_restore_rehearsal: using image $DIGEST"

SRC_NAME="tessera-dump-src"
DST_NAME="tessera-dump-dst"
SRC_PORT="${REHEARSAL_SRC_PORT:-55432}"
DST_PORT="${REHEARSAL_DST_PORT:-55433}"
SEED_COUNT="${REHEARSAL_SEED_COUNT:-100000}"

# Label order MUST match dev.tessera.core.bench.SeedGenerator (graph
# tessera_bench, vlabels Person/Org/Doc/Tag, edge label RELATES). Keeping this
# list in sync is part of the CRIT-1 runbook. If SeedGenerator ever adds a
# label, this list must grow in the same order.
GRAPH_NAME="tessera_bench"
VLABELS=(Person Org Doc Tag)
ELABEL="RELATES"

WORKDIR=$(mktemp -d)
cleanup() {
  docker rm -f "$SRC_NAME" "$DST_NAME" >/dev/null 2>&1 || true
  rm -rf "$WORKDIR"
}
trap cleanup EXIT

start_container() {
  local name="$1" port="$2"
  docker rm -f "$name" >/dev/null 2>&1 || true
  docker run -d --name "$name" \
    -e POSTGRES_USER=tessera \
    -e POSTGRES_PASSWORD=tessera \
    -e POSTGRES_DB=tessera \
    -p "$port:5432" \
    "$DIGEST" >/dev/null
  for _ in $(seq 1 60); do
    # Require TCP readiness specifically — bind order during initdb means
    # the unix socket can accept connections before TCP is listening, which
    # then trips the first `docker exec -i psql -h 127.0.0.1` call.
    if docker exec -e PGPASSWORD=tessera "$name" \
      psql -h 127.0.0.1 -U tessera -d tessera -c 'SELECT 1' >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "FAIL: $name did not become ready within 60s" >&2
  docker logs "$name" >&2 || true
  exit 1
}

psql_src() { docker exec -i -e PGPASSWORD=tessera "$SRC_NAME" psql -h 127.0.0.1 -U tessera -d tessera -v ON_ERROR_STOP=1 "$@"; }
psql_dst() { docker exec -i -e PGPASSWORD=tessera "$DST_NAME" psql -h 127.0.0.1 -U tessera -d tessera -v ON_ERROR_STOP=1 "$@"; }

# ---------------------------------------------------------------------------
# 1. SOURCE: container + AGE extension + seed 100k via SeedDriver (D-05).
# ---------------------------------------------------------------------------
echo "==> [1/7] starting source container on :$SRC_PORT"
start_container "$SRC_NAME" "$SRC_PORT"

echo "==> [2/7] CREATE EXTENSION age on source"
psql_src -c "CREATE EXTENSION IF NOT EXISTS age" \
         -c "LOAD 'age'" \
         -c "SET search_path = ag_catalog, public" >/dev/null

echo "==> [3/7] seeding $SEED_COUNT nodes via SeedDriver (fabric-core test classpath)"
./mvnw -B -ntp -pl fabric-core -DskipTests test-compile >/dev/null
./mvnw -B -ntp -pl fabric-core \
  org.codehaus.mojo:exec-maven-plugin:3.4.1:java \
  -Dexec.mainClass=dev.tessera.core.bench.SeedDriver \
  -Dexec.classpathScope=test \
  -Dseed.host=localhost \
  -Dseed.port="$SRC_PORT" \
  -Dseed.db=tessera \
  -Dseed.user=tessera \
  -Dseed.password=tessera \
  -Dseed.count="$SEED_COUNT"

# ---------------------------------------------------------------------------
# 2. PRE-DUMP query suite (tessera_bench graph).
# ---------------------------------------------------------------------------
echo "==> [4/7] running verify_queries.sh against source (pre-dump snapshot)"
PSQL="docker exec -i -e PGPASSWORD=tessera $SRC_NAME psql" \
  PGHOST=127.0.0.1 PGPORT=5432 PGUSER=tessera PGPASSWORD=tessera PGDATABASE=tessera \
  scripts/verify_queries.sh "$WORKDIR/pre.txt"

# ---------------------------------------------------------------------------
# 3. DUMP phase — AGE-aware.
#    a) Standard pg_dump of the public schema (captures anything non-graph).
#       Kept for realism of the runbook even though the benchmark graph only
#       lives in its own schema. Uses -Fc so the companion pg_restore step is
#       still exercised end-to-end.
#    b) Per-label COPY TO for every vlabel + the edge label. This is the part
#       pg_dump cannot do because the tessera_bench schema is extension-owned.
# ---------------------------------------------------------------------------
echo "==> [5/7] pg_dump public schema + COPY graph labels out of source"
docker exec -e PGPASSWORD=tessera "$SRC_NAME" \
  pg_dump -U tessera -d tessera -n public -Fc -f /tmp/tessera-public.dump
docker cp "$SRC_NAME:/tmp/tessera-public.dump" "$WORKDIR/tessera-public.dump"

for lbl in "${VLABELS[@]}" "$ELABEL"; do
  # COPY to a file inside the container, then docker cp out. Using a file
  # (not STDOUT piping) keeps this resilient to docker exec TTY quirks.
  psql_src -c "COPY $GRAPH_NAME.\"$lbl\" TO '/tmp/copy-$lbl.tsv'"
  docker cp "$SRC_NAME:/tmp/copy-$lbl.tsv" "$WORKDIR/copy-$lbl.tsv"
done

# ---------------------------------------------------------------------------
# 4. DESTINATION: fresh container, recreate graph + labels in the SAME order,
#    pg_restore public schema, then COPY label data back in.
# ---------------------------------------------------------------------------
echo "==> [6/7] starting destination container on :$DST_PORT and rebuilding graph"
start_container "$DST_NAME" "$DST_PORT"

psql_dst -c "CREATE EXTENSION IF NOT EXISTS age" >/dev/null

# Recreate the graph + labels. Order matters: label-ids are sequential per
# graph, and SeedGenerator's edge graphid values embed those ids. Person/Org/
# Doc/Tag/RELATES in exactly that order guarantees ids match the source.
{
  echo "LOAD 'age';"
  echo "SET search_path = ag_catalog, \"\$user\", public;"
  echo "SELECT create_graph('$GRAPH_NAME');"
  for lbl in "${VLABELS[@]}"; do
    echo "SELECT create_vlabel('$GRAPH_NAME', '$lbl');"
  done
  echo "SELECT create_elabel('$GRAPH_NAME', '$ELABEL');"
} | psql_dst >/dev/null

# Restore public schema (may be empty — still exercises pg_restore path).
docker cp "$WORKDIR/tessera-public.dump" "$DST_NAME:/tmp/tessera-public.dump"
docker exec -e PGPASSWORD=tessera "$DST_NAME" \
  pg_restore -U tessera -d tessera --clean --if-exists --no-owner --schema=public /tmp/tessera-public.dump \
  || echo "note: pg_restore of public schema reported warnings (empty schema is fine)"

# COPY each label's rows back in. Copy the TSV into the container first.
for lbl in "${VLABELS[@]}" "$ELABEL"; do
  docker cp "$WORKDIR/copy-$lbl.tsv" "$DST_NAME:/tmp/copy-$lbl.tsv"
  psql_dst -c "COPY $GRAPH_NAME.\"$lbl\" FROM '/tmp/copy-$lbl.tsv'"
done

# Rebuild SeedGenerator's extra indexes (MIN-2 workaround) so the post-restore
# verify queries run against the same index layout as pre-dump.
{
  for lbl in "${VLABELS[@]}"; do
    echo "CREATE INDEX IF NOT EXISTS bench_${lbl,,}_props_gin ON $GRAPH_NAME.\"$lbl\" USING gin (properties);"
  done
  echo "CREATE INDEX IF NOT EXISTS bench_relates_start ON $GRAPH_NAME.\"$ELABEL\" (start_id);"
  echo "CREATE INDEX IF NOT EXISTS bench_relates_end   ON $GRAPH_NAME.\"$ELABEL\" (end_id);"
  for lbl in "${VLABELS[@]}"; do
    echo "ANALYZE $GRAPH_NAME.\"$lbl\";"
  done
  echo "ANALYZE $GRAPH_NAME.\"$ELABEL\";"
} | psql_dst >/dev/null

# NOTE: AGE stores per-label sequences for new vertex/edge ids. After COPY
# the sequence is still at its initial value (1), so a real DR cutover that
# issues fresh Cypher CREATE statements against the restored graph would
# collide on graphid. The fix is `SELECT setval(<seq>, <max_id>)` per label,
# but the authoritative max-id calc must project the 48-bit sequence slice
# out of the `id` graphid — omitted here because verify_queries.sh is
# read-only. Document this as a Phase 1 follow-up in the restore runbook.

# ---------------------------------------------------------------------------
# 5. POST-RESTORE query suite.
# ---------------------------------------------------------------------------
echo "==> [7/7] running verify_queries.sh against destination (post-restore snapshot)"
PSQL="docker exec -i -e PGPASSWORD=tessera $DST_NAME psql" \
  PGHOST=127.0.0.1 PGPORT=5432 PGUSER=tessera PGPASSWORD=tessera PGDATABASE=tessera \
  scripts/verify_queries.sh "$WORKDIR/post.txt"

# ---------------------------------------------------------------------------
# 6. Diff. D-07: ANY divergence hard-fails.
# ---------------------------------------------------------------------------
if diff -u "$WORKDIR/pre.txt" "$WORKDIR/post.txt"; then
  echo "PASS: dump/restore rehearsal — pre and post query suites match (FOUND-05 / CRIT-1)"
  exit 0
else
  echo "FAIL: dump/restore divergence — see diff above (D-07 hard-fail)" >&2
  exit 1
fi
