#!/usr/bin/env bash
# Copyright 2026 Tessera Contributors
# Licensed under the Apache License, Version 2.0
#
# FOUND-05 / D-06: Fixed query suite — runs identically before pg_dump and
# after pg_restore. Any divergence between pre/post output is the failure
# signal (D-07 hard-fail in scripts/dump_restore_rehearsal.sh).
#
# CRIT-3 instrumentation: Q1 counts nodes by label — the aggregation shape
# that trips AGE's known aggregation cliff. Keeping it in the verify suite
# means any regression in that shape is caught by the rehearsal as well as
# by the JMH harness.
#
# Deliberately NO whole-graph hash (too fragile to ordering / agtype text
# serialisation). Explicit, sortable, line-stable query outputs instead.
# No `now()`, no `current_timestamp`, no autovacuum-dependent stats —
# every row must be reproducible byte-for-byte between containers.
#
# Usage:
#   PGHOST=... PGPORT=... PGUSER=... PGPASSWORD=... PGDATABASE=... \
#     scripts/verify_queries.sh <output-file>

set -euo pipefail

OUT="${1:?usage: verify_queries.sh <output-file>}"
: "${PGHOST:=localhost}"
: "${PGPORT:=5432}"
: "${PGUSER:=tessera}"
: "${PGDATABASE:=tessera}"
export PGPASSWORD="${PGPASSWORD:-tessera}"

PSQL_ARGS=(-h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -At -X -q -v ON_ERROR_STOP=1)

run_cypher() {
  # Prime AGE session and run a Cypher fragment. LOAD 'age' is per-session,
  # so every psql invocation must re-prime before hitting ag_catalog.
  local cypher="$1"
  local return_spec="$2"
  psql "${PSQL_ARGS[@]}" <<SQL
LOAD 'age';
SET search_path = ag_catalog, "\$user", public;
SELECT * FROM cypher('tessera_bench', \$\$ ${cypher} \$\$) AS (${return_spec});
SQL
}

{
  echo "# Q1: count by label (CRIT-3 aggregation instrumentation)"
  # Sort the output so row ordering out of AGE is not part of the signal.
  run_cypher "MATCH (n) RETURN labels(n) AS l, count(*) AS c" "l agtype, c agtype" | sort

  echo "# Q2: count of RELATES edges"
  run_cypher "MATCH ()-[r:RELATES]->() RETURN count(r) AS c" "c agtype"

  echo "# Q3: deterministic point-lookup — first 10 Person nodes by idx"
  run_cypher "MATCH (n:Person) RETURN n.idx AS i ORDER BY i ASC LIMIT 10" "i agtype"

  echo "# Q4: 2-hop reach from node idx=0 (Person)"
  run_cypher "MATCH (n:Person {idx: 0})-[:RELATES]->()-[:RELATES]->(m) RETURN count(DISTINCT m) AS c" "c agtype"

  echo "# Q5: graph metadata (tessera_bench must exist)"
  psql "${PSQL_ARGS[@]}" -c "SELECT name FROM ag_catalog.ag_graph WHERE name = 'tessera_bench'"
} > "$OUT"

echo "verify_queries.sh: wrote $OUT ($(wc -l < "$OUT") lines)"
