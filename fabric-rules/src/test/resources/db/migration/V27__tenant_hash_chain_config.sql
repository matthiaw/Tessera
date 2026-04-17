-- Phase 4 / Hash-chain tenant opt-in / D-C1: per-tenant hash-chain configuration
-- Required in fabric-rules tests to support V28 ALTER TABLE model_config
CREATE TABLE IF NOT EXISTS model_config (
    model_id           UUID        PRIMARY KEY,
    hash_chain_enabled BOOLEAN     NOT NULL DEFAULT false,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
