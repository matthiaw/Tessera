-- Phase 4 / Hash-chained audit / D-C1/C4: add prev_hash to graph_events
-- Each event in the chain stores the SHA-256 hash of the previous event's
-- payload to form a tamper-evident audit trail (AUDIT-01).
-- prev_hash is NULL for the first event in a tenant's chain.
ALTER TABLE graph_events ADD COLUMN IF NOT EXISTS prev_hash VARCHAR(64);

-- Index for efficient prev_hash lookup when appending a new event.
-- The hash-chain appender needs SELECT ... FOR UPDATE on the most recent row
-- per tenant (Pitfall 2 mitigation: DESC index avoids a full partition scan
-- to find the tail of the chain). The partitioned table requires the index to
-- be created on the parent; Postgres propagates it to child partitions.
CREATE INDEX IF NOT EXISTS idx_graph_events_model_seq_desc
    ON graph_events (model_id, sequence_nr DESC);
