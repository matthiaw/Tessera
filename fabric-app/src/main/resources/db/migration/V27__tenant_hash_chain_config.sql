-- Phase 4 / Hash-chain tenant opt-in / D-C1: per-tenant hash-chain configuration
-- hash_chain_enabled allows each tenant to independently opt in to hash-chained
-- audit logging. When false (default) the hash-chain appender skips prev_hash
-- computation, keeping the write path cheap for tenants that do not need it.
CREATE TABLE IF NOT EXISTS model_config (
    model_id           UUID        PRIMARY KEY,
    hash_chain_enabled BOOLEAN     NOT NULL DEFAULT false,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Comment for documentation clarity
COMMENT ON TABLE model_config IS 'Per-tenant feature flags and configuration knobs';
COMMENT ON COLUMN model_config.hash_chain_enabled IS 'D-C1: opt-in to SHA-256 hash-chained audit log rows in graph_events';
