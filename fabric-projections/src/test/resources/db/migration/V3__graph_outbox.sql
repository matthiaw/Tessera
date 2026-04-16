-- Phase 1 / Wave 0 / graph_outbox
-- Transactional outbox: written in the same TX as graph_events + Cypher.
-- Column shape matches Debezium Outbox Event Router SMT so Phase 4 can swap
-- in Debezium without rewriting the write path.
CREATE TABLE graph_outbox (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    model_id UUID NOT NULL,
    event_id UUID NOT NULL,
    aggregatetype TEXT NOT NULL,
    aggregateid TEXT NOT NULL,
    type TEXT NOT NULL,
    payload JSONB NOT NULL,
    routing_hints JSONB,
    status TEXT NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    delivered_at TIMESTAMPTZ
);

CREATE INDEX idx_graph_outbox_status_created ON graph_outbox (status, created_at);
CREATE INDEX idx_graph_outbox_model_created ON graph_outbox (model_id, created_at DESC);
