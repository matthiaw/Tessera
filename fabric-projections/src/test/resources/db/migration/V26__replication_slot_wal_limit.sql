-- Phase 4 / Debezium WAL safety / D-D2: cap replication slot WAL retention
-- NOTE: In Testcontainers environments ALTER SYSTEM is a no-op equivalent.
-- The Docker Compose postgres user is superuser; in tests we skip the system
-- parameter change as it is not meaningful in ephemeral containers.
-- Production environments should apply V26__replication_slot_wal_limit.sql from fabric-app.
SELECT 1 AS wal_limit_skipped_in_test;
