#!/usr/bin/env bash
# Copyright 2026 Tessera Contributors
# Licensed under the Apache License, Version 2.0
#
# DR Drill: End-to-end disaster recovery rehearsal for Tessera.
#
# Implements D-D1, D-D2, D-D3 from Phase 5 Plan 04.
#
# Rehearses the full DR cycle:
#   1. Start source Postgres+AGE container (SRC)
#   2. Wait for TCP readiness
#   3. Run Flyway migrate against SRC
#   4. Seed test data (model_config + graph_events)
#   5. pg_dump public schema from SRC
#   6. Start destination Postgres+AGE container (DST)
#   7. pg_restore into DST
#   8. Flyway validate against DST (verifies migration checksums match)
#   9. Smoke test: verify data integrity in DST
#
# Note: This drill validates the DB layer (dump+restore+Flyway validate+data
# integrity). It does NOT start a full Tessera Spring Boot instance in CI
# (too heavy — requires all services). The DB layer is the authoritative source
# of truth per Tessera's architecture.
#
# Usage: scripts/dr_drill.sh
# Requires: docker, ./mvnw, JDK 21

set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

# ---------------------------------------------------------------------------
# Image digest — single source of truth is docker/age-pgvector/Dockerfile.
# Bumping it in one place auto-rolls this rehearsal.
# ---------------------------------------------------------------------------
DIGEST=$(grep -oE 'apache/age@sha256:[a-f0-9]{64}' docker/age-pgvector/Dockerfile | head -1 || true)
if [[ -z "$DIGEST" ]]; then
  echo "FAIL: could not extract apache/age sha256 digest from docker/age-pgvector/Dockerfile" >&2
  exit 1
fi
echo "dr_drill: using image $DIGEST"

SRC_NAME="tessera-dr-src"
DST_NAME="tessera-dr-dst"
SRC_PORT="${DR_SRC_PORT:-56432}"
DST_PORT="${DR_DST_PORT:-56433}"
TEST_TENANT="dr-rehearsal-tenant"

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
# Step 1: Start source container (SRC)
# ---------------------------------------------------------------------------
echo "==> [1/9] starting source container on :$SRC_PORT"
start_container "$SRC_NAME" "$SRC_PORT"

# ---------------------------------------------------------------------------
# Step 2: TCP readiness already verified by start_container above.
#         Load AGE extension on SRC.
# ---------------------------------------------------------------------------
echo "==> [2/9] loading AGE extension on source"
psql_src -c "CREATE EXTENSION IF NOT EXISTS age" \
         -c "LOAD 'age'" \
         -c "SET search_path = ag_catalog, public" >/dev/null

# ---------------------------------------------------------------------------
# Step 3: Flyway migrate against SRC
# ---------------------------------------------------------------------------
echo "==> [3/9] running Flyway migrate against source"
./mvnw -B -ntp -pl fabric-app flyway:migrate \
  -Dflyway.url="jdbc:postgresql://localhost:${SRC_PORT}/tessera" \
  -Dflyway.user=tessera \
  -Dflyway.password=tessera \
  -Dflyway.locations=classpath:db/migration

# ---------------------------------------------------------------------------
# Step 4: Seed test data
# ---------------------------------------------------------------------------
echo "==> [4/9] seeding test data on source"
psql_src <<'SQL'
-- Seed model_config for test tenant
INSERT INTO model_config (model_id, hash_chain_enabled, retention_days, created_at, updated_at)
VALUES ('dr-rehearsal-tenant', false, 30, NOW(), NOW())
ON CONFLICT (model_id) DO UPDATE
  SET hash_chain_enabled = EXCLUDED.hash_chain_enabled,
      retention_days     = EXCLUDED.retention_days,
      updated_at         = EXCLUDED.updated_at;

-- Seed a few graph_events rows for the test tenant
INSERT INTO graph_events (model_id, event_type, type_slug, entity_id, payload, created_at)
VALUES
  ('dr-rehearsal-tenant', 'CREATE', 'person', gen_random_uuid(), '{"name":"Alice"}', NOW()),
  ('dr-rehearsal-tenant', 'CREATE', 'person', gen_random_uuid(), '{"name":"Bob"}',   NOW()),
  ('dr-rehearsal-tenant', 'UPDATE', 'person', gen_random_uuid(), '{"name":"Carol"}', NOW());
SQL

# ---------------------------------------------------------------------------
# Step 5: pg_dump public schema from SRC
# ---------------------------------------------------------------------------
echo "==> [5/9] pg_dump public schema from source"
docker exec -e PGPASSWORD=tessera "$SRC_NAME" \
  pg_dump -U tessera -d tessera -n public -Fc -f /tmp/tessera_dump.custom
docker cp "$SRC_NAME:/tmp/tessera_dump.custom" "$WORKDIR/tessera_dump.custom"

# ---------------------------------------------------------------------------
# Step 6: Start destination container (DST)
# ---------------------------------------------------------------------------
echo "==> [6/9] starting destination container on :$DST_PORT"
start_container "$DST_NAME" "$DST_PORT"

# Load AGE extension on DST
psql_dst -c "CREATE EXTENSION IF NOT EXISTS age" \
         -c "LOAD 'age'" \
         -c "SET search_path = ag_catalog, public" >/dev/null

# ---------------------------------------------------------------------------
# Step 7: pg_restore into DST
# ---------------------------------------------------------------------------
echo "==> [7/9] pg_restore into destination"
docker cp "$WORKDIR/tessera_dump.custom" "$DST_NAME:/tmp/tessera_dump.custom"
docker exec -e PGPASSWORD=tessera "$DST_NAME" \
  pg_restore -U tessera -d tessera --clean --if-exists --no-owner --schema=public \
  /tmp/tessera_dump.custom \
  || echo "note: pg_restore reported warnings (expected for empty schemas)"

# ---------------------------------------------------------------------------
# Step 8: Flyway validate against DST (verifies migration checksums match)
# ---------------------------------------------------------------------------
echo "==> [8/9] running Flyway validate against destination"
./mvnw -B -ntp -pl fabric-app flyway:validate \
  -Dflyway.url="jdbc:postgresql://localhost:${DST_PORT}/tessera" \
  -Dflyway.user=tessera \
  -Dflyway.password=tessera \
  -Dflyway.locations=classpath:db/migration

# ---------------------------------------------------------------------------
# Step 9: Smoke test — verify data integrity in DST
# ---------------------------------------------------------------------------
echo "==> [9/9] smoke test: verifying data integrity in destination"
RETENTION=$(psql_dst -t -c "SELECT retention_days FROM model_config WHERE model_id = 'dr-rehearsal-tenant'" | tr -d ' \n')
if [[ "$RETENTION" != "30" ]]; then
  echo "FAIL: model_config.retention_days expected 30, got '${RETENTION}'" >&2
  exit 1
fi
echo "  model_config.retention_days = $RETENTION (expected 30) OK"

EVENT_COUNT=$(psql_dst -t -c "SELECT COUNT(*) FROM graph_events WHERE model_id = 'dr-rehearsal-tenant'" | tr -d ' \n')
if [[ "$EVENT_COUNT" -lt "3" ]]; then
  echo "FAIL: graph_events row count expected >= 3, got '${EVENT_COUNT}'" >&2
  exit 1
fi
echo "  graph_events row count = $EVENT_COUNT (expected >= 3) OK"

echo "PASS: DR drill complete — dump/restore/validate/smoke all succeeded"
exit 0
