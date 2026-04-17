-- Phase 4 / Debezium integration / D-B4: add published flag to graph_outbox
-- The published column coordinates the outbox poller with Debezium CDC.
-- When tessera.kafka.enabled=true the OutboxPoller is disabled and Debezium
-- reads all rows; when false the in-process poller marks rows published=true
-- after delivery so the partial index keeps undelivered rows cheap to scan.
ALTER TABLE graph_outbox ADD COLUMN IF NOT EXISTS published BOOLEAN NOT NULL DEFAULT false;

-- Partial index on unpublished rows only — keeps the poller's polling query
-- cheap regardless of how large graph_outbox grows (only undelivered rows
-- are visible to this index, which is typically a small working set).
CREATE INDEX IF NOT EXISTS idx_graph_outbox_unpublished
    ON graph_outbox (model_id, created_at)
    WHERE published = false;
