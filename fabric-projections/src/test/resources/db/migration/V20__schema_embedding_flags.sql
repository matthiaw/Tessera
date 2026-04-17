-- Copyright 2026 Tessera Contributors
-- Licensed under the Apache License, Version 2.0
--
-- Phase 2.5 / Plan 01: Schema Registry embedding flags (CONTEXT.md Decision 7).
-- Per-type opt-in for embedding generation.
ALTER TABLE schema_node_types
    ADD COLUMN embedding_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN embedding_model TEXT;
