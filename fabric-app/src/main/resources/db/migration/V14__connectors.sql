-- Phase 2 / Wave 3 / 02-W3-01: Connector instances table.
-- CONTEXT Decision 7: DB-backed connector lifecycle with admin CRUD.
-- auth_type restricted to BEARER only in Phase 2 (Decision 3).
CREATE TABLE connectors (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    model_id              UUID NOT NULL,
    type                  TEXT NOT NULL,
    mapping_def           JSONB NOT NULL,
    auth_type             TEXT NOT NULL CHECK (auth_type IN ('BEARER')),
    credentials_ref       TEXT NOT NULL,
    poll_interval_seconds INT NOT NULL CHECK (poll_interval_seconds >= 1),
    enabled               BOOLEAN NOT NULL DEFAULT TRUE,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX idx_connectors_model ON connectors(model_id);
