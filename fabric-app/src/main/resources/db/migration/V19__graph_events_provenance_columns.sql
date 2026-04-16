-- Copyright 2026 Tessera Contributors
-- Licensed under the Apache License, Version 2.0
--
-- Phase 2.5 / Plan 01: Add provenance columns to graph_events for extraction traceability (EXTR-04).
-- Nullable: structured-connector events leave these NULL.
ALTER TABLE graph_events ADD COLUMN source_document_id TEXT;
ALTER TABLE graph_events ADD COLUMN source_chunk_range TEXT;
