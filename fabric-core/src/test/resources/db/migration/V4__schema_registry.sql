-- Phase 1 / Wave 0 / schema_registry
-- Typed Postgres tables for core queryable attributes + JSONB for flexible
-- validation / default / enum shapes (D-B1).
CREATE TABLE schema_node_types (
    id UUID PRIMARY KEY,
    model_id UUID NOT NULL,
    name TEXT NOT NULL,
    slug TEXT NOT NULL,
    label TEXT NOT NULL,
    description TEXT,
    builtin BOOLEAN NOT NULL DEFAULT false,
    source_system TEXT,
    deprecated_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (model_id, slug)
);

CREATE TABLE schema_properties (
    id UUID PRIMARY KEY,
    model_id UUID NOT NULL,
    type_slug TEXT NOT NULL,
    name TEXT NOT NULL,
    slug TEXT NOT NULL,
    data_type TEXT NOT NULL,
    required BOOLEAN NOT NULL DEFAULT false,
    default_value JSONB,
    validation_rules JSONB,
    enum_values JSONB,
    reference_target TEXT,
    source_path TEXT,
    deprecated_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (model_id, type_slug, slug)
);

CREATE TABLE schema_edge_types (
    id UUID PRIMARY KEY,
    model_id UUID NOT NULL,
    name TEXT NOT NULL,
    slug TEXT NOT NULL,
    edge_label TEXT NOT NULL,
    inverse_name TEXT,
    source_type_slug TEXT NOT NULL,
    target_type_slug TEXT NOT NULL,
    cardinality TEXT NOT NULL,
    properties_schema JSONB,
    deprecated_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (model_id, slug)
);

CREATE INDEX idx_schema_properties_model_type ON schema_properties (model_id, type_slug);
CREATE INDEX idx_schema_edge_types_model ON schema_edge_types (model_id);
