-- fabric-rules test no-op: V26 WAL limit (ALTER SYSTEM) not applicable in Testcontainers
SELECT 1 AS wal_limit_skipped_in_rules_test;
