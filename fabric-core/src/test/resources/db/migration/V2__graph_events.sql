-- Phase 1 / Wave 0 / graph_events
-- Event log: append-only, monthly-partitioned. Authoritative source of truth for
-- temporal replay (EVENT-06) and audit history (EVENT-07). Every mutation through
-- GraphService.apply writes exactly one row here in the same TX as the Cypher
-- write and the outbox row (EVENT-04).
CREATE TABLE graph_events (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    model_id UUID NOT NULL,
    sequence_nr BIGINT NOT NULL,
    event_type TEXT NOT NULL,          -- CREATE_NODE | UPDATE_NODE | TOMBSTONE_NODE | CREATE_EDGE | TOMBSTONE_EDGE
    node_uuid UUID,                    -- nullable for edge events
    edge_uuid UUID,                    -- nullable for node events
    type_slug TEXT NOT NULL,
    payload JSONB NOT NULL,            -- full post-state for EVENT-06 temporal replay
    delta JSONB NOT NULL,              -- changed-fields diff (EVENT-03)
    caused_by TEXT NOT NULL,
    source_type TEXT NOT NULL,         -- STRUCTURED | EXTRACTION | MANUAL | SYSTEM
    source_id TEXT NOT NULL,
    source_system TEXT NOT NULL,
    confidence NUMERIC(4,3) NOT NULL DEFAULT 1.0,
    extractor_version TEXT,            -- Phase 2.5 populates
    llm_model_id TEXT,                 -- Phase 2.5 populates
    origin_connector_id TEXT,          -- RULE-08: echo-loop prevention
    origin_change_id TEXT,             -- RULE-08
    event_time TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),  -- CORE-08 Tessera-owned
    CONSTRAINT graph_events_tenant_check CHECK (model_id IS NOT NULL),
    PRIMARY KEY (id, event_time)
) PARTITION BY RANGE (event_time);

-- Partitioned-table constraint: unique indexes must include all partition-key
-- columns. event_time is included so Postgres accepts the index; per-tenant
-- sequence allocation still guarantees (model_id, sequence_nr) uniqueness in
-- practice (gaps acceptable, double-allocation caught by this index).
CREATE UNIQUE INDEX idx_graph_events_model_seq ON graph_events (model_id, sequence_nr, event_time);
CREATE INDEX idx_graph_events_node_uuid ON graph_events (node_uuid) WHERE node_uuid IS NOT NULL;
CREATE INDEX idx_graph_events_model_type_time ON graph_events (model_id, type_slug, event_time DESC);

-- Initial monthly partition (MOD-6). Subsequent partitions are created by a
-- @Scheduled job or pg_partman — Wave 1 task, not Wave 0.
CREATE TABLE graph_events_y2026m04 PARTITION OF graph_events
    FOR VALUES FROM ('2026-04-01') TO ('2026-05-01');
