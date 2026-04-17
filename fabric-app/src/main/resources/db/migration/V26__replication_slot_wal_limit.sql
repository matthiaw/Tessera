-- Phase 4 / Debezium WAL safety / D-D2: cap replication slot WAL retention
-- Without this cap a stalled or lagging Debezium connector causes WAL to grow
-- unbounded, potentially filling the disk and crashing the Postgres instance.
-- 2 GB provides a buffer long enough for normal Debezium catch-up after a
-- connector restart while preventing runaway WAL accumulation.
--
-- IMPORTANT: ALTER SYSTEM requires superuser. The Docker Compose postgres user
-- is superuser by default. Risk T-04-E1 accepted (dev environment only).
-- In production, set this via postgres.conf or a managed parameter group.
ALTER SYSTEM SET max_slot_wal_keep_size = '2GB';

-- Reload configuration so the setting takes effect without a restart.
SELECT pg_reload_conf();
