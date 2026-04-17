-- Phase 5 / OPS-03 / OPS-04: event-log lifecycle columns for model_config
ALTER TABLE model_config
    ADD COLUMN IF NOT EXISTS retention_days      INT         NULL,
    ADD COLUMN IF NOT EXISTS snapshot_boundary   TIMESTAMPTZ NULL;
COMMENT ON COLUMN model_config.retention_days IS 'OPS-04: days to retain events; NULL means retain forever';
COMMENT ON COLUMN model_config.snapshot_boundary IS 'OPS-03: earliest event_time still available via temporal replay; events below this were compacted';
