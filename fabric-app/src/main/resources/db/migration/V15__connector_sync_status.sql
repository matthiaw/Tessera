-- Phase 2 / Wave 3 / 02-W3-01: Per-connector sync status tracking.
-- CONTEXT Decision 19: dedicated /admin/connectors/{id}/status endpoint.
CREATE TABLE connector_sync_status (
    connector_id          UUID PRIMARY KEY REFERENCES connectors(id) ON DELETE CASCADE,
    model_id              UUID NOT NULL,
    last_poll_at          TIMESTAMPTZ NULL,
    last_success_at       TIMESTAMPTZ NULL,
    last_outcome          TEXT NULL,
    last_etag             TEXT NULL,
    last_modified         TEXT NULL,
    events_processed      BIGINT NOT NULL DEFAULT 0,
    dlq_count             BIGINT NOT NULL DEFAULT 0,
    next_poll_at          TIMESTAMPTZ NULL,
    state_blob            JSONB NULL
);
