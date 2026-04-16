-- Phase 1 / Wave 0 / schema_versioning_and_aliases
-- Event-sourced schema evolution with materialized snapshots (D-B2) and
-- property/edge rename translation tables (D-B3).
CREATE TABLE schema_change_event (
    id UUID PRIMARY KEY,
    model_id UUID NOT NULL,
    change_type TEXT NOT NULL,
    payload JSONB NOT NULL,
    caused_by TEXT NOT NULL,
    event_time TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX idx_schema_change_event_model_time ON schema_change_event (model_id, event_time DESC);

CREATE TABLE schema_version (
    model_id UUID NOT NULL,
    version_nr BIGINT NOT NULL,
    snapshot JSONB NOT NULL,
    is_current BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (model_id, version_nr)
);

-- Exactly one current version per model — partial unique index (D-B2).
CREATE UNIQUE INDEX idx_schema_version_current ON schema_version (model_id) WHERE is_current;

CREATE TABLE schema_property_aliases (
    model_id UUID NOT NULL,
    type_slug TEXT NOT NULL,
    old_slug TEXT NOT NULL,
    current_slug TEXT NOT NULL,
    retired_at TIMESTAMPTZ,
    PRIMARY KEY (model_id, type_slug, old_slug)
);

CREATE TABLE schema_edge_type_aliases (
    model_id UUID NOT NULL,
    old_slug TEXT NOT NULL,
    current_slug TEXT NOT NULL,
    retired_at TIMESTAMPTZ,
    PRIMARY KEY (model_id, old_slug)
);
