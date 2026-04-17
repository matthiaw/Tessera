-- Copyright 2026 Tessera Contributors
-- Licensed under the Apache License, Version 2.0
--
-- Phase 2.5 / Plan 01: Extraction review queue for below-threshold candidates (EXTR-07).
-- CONTEXT.md Decision 6: Accept / Reject / Override UUID, single-row API.
CREATE TABLE extraction_review_queue (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    model_id                  UUID NOT NULL,
    connector_id              UUID NOT NULL REFERENCES connectors(id),
    source_document_id        TEXT NOT NULL,
    source_chunk_range        TEXT NOT NULL,
    type_slug                 TEXT NOT NULL,
    extracted_properties      JSONB NOT NULL,
    extraction_confidence     NUMERIC(4,3) NOT NULL,
    extractor_version         TEXT NOT NULL,
    llm_model_id              TEXT NOT NULL,
    resolution_tier           TEXT,
    resolution_score          NUMERIC(6,5),
    created_at                TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    decided_at                TIMESTAMPTZ,
    decision                  TEXT,
    decision_reason           TEXT,
    operator_target_node_uuid UUID
);

-- T-02.5-02: model_id index for tenant-scoped queries.
CREATE INDEX idx_review_queue_model ON extraction_review_queue(model_id);

-- Partial index for pending items (decision IS NULL).
CREATE INDEX idx_review_queue_pending ON extraction_review_queue(model_id, decision)
    WHERE decision IS NULL;
