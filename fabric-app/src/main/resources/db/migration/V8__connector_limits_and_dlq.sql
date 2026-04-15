-- Phase 1 / Wave 0 / connector_limits_and_dlq
-- Write-amplification circuit breaker per-tenant override (D-D2) plus DLQ for
-- events shed when a connector trips (D-D3).
CREATE TABLE connector_limits (
    model_id UUID NOT NULL,
    connector_id TEXT NOT NULL,
    window_seconds INT NOT NULL DEFAULT 30,
    threshold INT NOT NULL DEFAULT 500,
    PRIMARY KEY (model_id, connector_id)
);

CREATE TABLE connector_dlq (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    model_id UUID NOT NULL,
    connector_id TEXT NOT NULL,
    reason TEXT NOT NULL,
    raw_payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX idx_connector_dlq_model_connector_created
    ON connector_dlq (model_id, connector_id, created_at DESC);
