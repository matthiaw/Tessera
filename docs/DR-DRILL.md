# Tessera Disaster Recovery Drill

<!--
  Copyright 2026 Tessera Contributors
  Licensed under the Apache License, Version 2.0
-->

## 1. Purpose

The DR drill proves that the full disaster recovery cycle works end-to-end before
production requires it. Running `scripts/dr_drill.sh` rehearses:

1. **Flyway migrate** — schema is applied cleanly from scratch
2. **Data seeding** — representative rows exist in `model_config` and `graph_events`
3. **`pg_dump -Fc`** of the `public` schema (contains all relational tables)
4. **`pg_restore`** into a fresh container
5. **Flyway validate** — migration checksums on the restored DB match the codebase
6. **Smoke test** — row-level data integrity confirmed via `psql` queries

This drill does **not** start a full Tessera Spring Boot instance (too heavy for CI
ephemeral containers). The database layer is the source of truth; if dump, restore,
and Flyway validate pass, the application can start cleanly against the restored DB.

Run the drill:
- Before every production deployment
- After any Flyway migration is merged
- After upgrading the PostgreSQL or Apache AGE image
- On a scheduled basis (monthly at minimum; CI runs on every push to `main`)

---

## 2. Prerequisites

| Requirement | Notes |
|-------------|-------|
| Docker 20.10+ | Must be running; `docker ps` should succeed |
| `./mvnw` Maven Wrapper | Present in the repository root |
| JDK 21 (Corretto) | `JAVA_HOME` must point to JDK 21 |
| ~2 GB free disk | For the custom dump file and container layers |
| Ports 56432, 56433 free | Overridable via `DR_SRC_PORT` / `DR_DST_PORT` env vars |

---

## 3. Running Locally

```bash
# From the repository root:
./scripts/dr_drill.sh
```

Optional environment variables:

```bash
DR_SRC_PORT=56432   # source container port (default: 56432)
DR_DST_PORT=56433   # destination container port (default: 56433)
```

### Expected output

```
dr_drill: using image apache/age@sha256:16aa423d...
==> [1/9] starting source container on :56432
==> [2/9] loading AGE extension on source
==> [3/9] running Flyway migrate against source
==> [4/9] seeding test data on source
==> [5/9] pg_dump public schema from source
==> [6/9] starting destination container on :56433
==> [7/9] pg_restore into destination
==> [8/9] running Flyway validate against destination
==> [9/9] smoke test: verifying data integrity in destination
  model_config.retention_days = 30 (expected 30) OK
  graph_events row count = 3 (expected >= 3) OK
PASS: DR drill complete — dump/restore/validate/smoke all succeeded
```

Any non-zero exit code is a failure. The script uses `set -euo pipefail`, so any
unexpected error stops execution immediately.

---

## 4. Running in CI

The CI pipeline (`.github/workflows/ci.yml`) includes a `dr-drill` job that runs
`scripts/dr_drill.sh` automatically on every push to `main` after the full Maven
verify passes:

```yaml
dr-drill:
  name: DR drill rehearsal
  runs-on: ubuntu-latest
  needs: verify
  if: github.event_name == 'push'
  timeout-minutes: 20
```

The job:
- Only runs on `push` events (not pull requests) to avoid Docker-in-Docker issues
- Requires the `verify` job to pass first (all tests + Spotless + license headers)
- Has a 20-minute timeout — generous for container startup + dump/restore cycle
- Uses `ubuntu-latest` which ships with Docker pre-installed

CI does **not** upload the dump file as a build artifact (avoids accidental data
exposure per T-05-04-02).

---

## 5. IONOS VPS Deployment

This section describes the full DR procedure for the production environment on IONOS VPS.

### 5.1 Pre-requisites (on the VPS)

```bash
ssh user@<ionos-vps-ip>
docker ps                      # Verify Tessera stack is running
df -h /var/lib/docker          # Verify at least 3 GB free
```

### 5.2 Take a dump from the running database

```bash
# Identify the running postgres container
CONTAINER=$(docker ps --filter "name=tessera-postgres" --format "{{.Names}}")

# Dump the public schema (contains all Tessera relational tables)
docker exec -e PGPASSWORD=tessera "$CONTAINER" \
  pg_dump -U tessera -d tessera -n public -Fc \
  -f /tmp/tessera_dump_$(date +%Y%m%dT%H%M%S).custom

# Copy dump file out of the container
docker cp "$CONTAINER:/tmp/tessera_dump_*.custom" /home/user/backups/
```

### 5.3 Stop Tessera application

```bash
cd /home/user/tessera
docker compose stop tessera
```

### 5.4 Transfer dump to new VPS or fresh container

**Option A — same host, fresh container:**
```bash
# Start a temporary fresh AGE container on a different port
docker run -d --name tessera-restore-target \
  -e POSTGRES_USER=tessera -e POSTGRES_PASSWORD=tessera -e POSTGRES_DB=tessera \
  -p 55442:5432 \
  apache/age@sha256:16aa423d20a31aed36a3313244bf7aa00731325862f20ed584510e381f2feaed

# Wait for readiness
for i in $(seq 1 60); do
  docker exec -e PGPASSWORD=tessera tessera-restore-target \
    psql -h 127.0.0.1 -U tessera -d tessera -c 'SELECT 1' >/dev/null 2>&1 && break
  sleep 1
done
```

**Option B — new VPS:**
```bash
# Copy the dump file to the new VPS
scp /home/user/backups/tessera_dump_*.custom user@<new-vps-ip>:/home/user/backups/
```

### 5.5 Restore into target

```bash
# Copy dump into the target container
docker cp /home/user/backups/tessera_dump_*.custom tessera-restore-target:/tmp/tessera_dump.custom

# Load AGE extension (required before restore)
docker exec -e PGPASSWORD=tessera tessera-restore-target \
  psql -h 127.0.0.1 -U tessera -d tessera \
  -c "CREATE EXTENSION IF NOT EXISTS age"

# Restore
docker exec -e PGPASSWORD=tessera tessera-restore-target \
  pg_restore -U tessera -d tessera \
  --clean --if-exists --no-owner --schema=public \
  /tmp/tessera_dump.custom
```

### 5.6 Run Flyway validate

Validate that migration checksums on the restored DB match the current codebase:

```bash
cd /home/user/tessera
./mvnw -B -ntp -pl fabric-app flyway:validate \
  -Dflyway.url="jdbc:postgresql://localhost:55442/tessera" \
  -Dflyway.user=tessera \
  -Dflyway.password=tessera
```

A clean `BUILD SUCCESS` confirms the restored schema is fully consistent with the
deployed application code.

### 5.7 Start Tessera against the restored database

Update `docker-compose.yml` (or `.env`) to point to the restored container/host,
then start the application:

```bash
docker compose start tessera
```

### 5.8 Verify application health

```bash
# Wait ~15s for Spring Boot startup
sleep 15

# Actuator health — expect {"status":"UP"}
curl -sf http://localhost:8080/actuator/health | jq .

# Entity endpoint — expect a non-empty or empty 200 (not 500)
curl -sf -H "Authorization: Bearer <token>" \
  http://localhost:8080/api/v1/<model>/entities/<type> | jq .
```

Both endpoints returning non-5xx responses confirm the restored stack is operational.

---

## 6. Troubleshooting

### AGE extension not loaded

**Symptom:** `ERROR: extension "age" does not exist`

**Fix:** The base image must be `apache/age:...` (not plain `postgres`). Verify the
image digest in `docker/age-pgvector/Dockerfile` matches `AgePostgresContainer.AGE_IMAGE_DIGEST`.

---

### Flyway checksum mismatch on validate

**Symptom:**
```
Migration checksum mismatch for migration version 12
-> Applied to database : 1234567890
-> Resolved locally    : 9876543210
```

**Cause:** The dump was taken from a DB that had been migrated with a different version
of a migration script (someone repaired or modified a migration after applying it).

**Fix:** The deployed migration scripts must exactly match the dump. Either:
1. Re-dump from a DB that matches the current codebase, or
2. Run `flyway:repair` to update stored checksums (only if you own the migration history)

---

### Port conflicts

**Symptom:** `docker: Error response from daemon: Bind for 0.0.0.0:56432 failed: port is already allocated`

**Fix:**
```bash
DR_SRC_PORT=57432 DR_DST_PORT=57433 ./scripts/dr_drill.sh
```

---

### Flyway cannot find migrations

**Symptom:** `Unable to determine URL for classpath location: db/migration`

**Fix:** The Flyway `locations` property requires the `fabric-app` module to be compiled:

```bash
./mvnw -B -ntp -pl fabric-app compile -DskipTests
./scripts/dr_drill.sh
```

---

### Smoke test fails with unexpected row count

**Symptom:** `FAIL: graph_events row count expected >= 3, got '0'`

**Cause:** The `graph_events` table was empty before the dump (e.g., retention job
already purged the seed rows, or Flyway migrate ran but seed step 4 failed silently).

**Fix:** Check the `[4/9]` step output and the container logs for SQL errors.

---

## 7. Expected Output (Full Successful Run)

```
dr_drill: using image apache/age@sha256:16aa423d20a31aed36a3313244bf7aa00731325862f20ed584510e381f2feaed
==> [1/9] starting source container on :56432
==> [2/9] loading AGE extension on source
==> [3/9] running Flyway migrate against source
[INFO] Successfully applied N migrations to schema "public"
==> [4/9] seeding test data on source
==> [5/9] pg_dump public schema from source
==> [6/9] starting destination container on :56433
==> [7/9] pg_restore into destination
note: pg_restore reported warnings (expected for empty schemas)
==> [8/9] running Flyway validate against destination
[INFO] Successfully validated N migrations (execution time ...) 
==> [9/9] smoke test: verifying data integrity in destination
  model_config.retention_days = 30 (expected 30) OK
  graph_events row count = 3 (expected >= 3) OK
PASS: DR drill complete — dump/restore/validate/smoke all succeeded
```

Exit code 0 = PASS. Any non-zero exit code = FAIL (investigate the step that printed
`FAIL:` and consult section 6 above).
