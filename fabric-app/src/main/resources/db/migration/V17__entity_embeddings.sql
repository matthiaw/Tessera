-- Copyright 2026 Tessera Contributors
-- Licensed under the Apache License, Version 2.0
--
-- Phase 2.5 / Plan 01: Entity embeddings table for pgvector similarity search.
-- Single shared table for all entity types (CONTEXT.md Decision 7).
-- Default dimension 768 (Ollama nomic-embed-text).
CREATE TABLE entity_embeddings (
    node_uuid       UUID NOT NULL,
    model_id        UUID NOT NULL,
    embedding_model TEXT NOT NULL,
    embedding       vector(768),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (node_uuid, model_id)
);

-- HNSW index for cosine similarity search (EXTR-05, EXTR-08).
CREATE INDEX idx_entity_embeddings_hnsw
    ON entity_embeddings USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
