-- Copyright 2026 Tessera Contributors
-- Licensed under the Apache License, Version 2.0
--
-- IT override: entity_embeddings table uses vector(768) type which requires pgvector.
-- Skipped in IT context since the apache/age test image does not include pgvector.
SELECT 1 AS entity_embeddings_skipped_in_test;
