-- Copyright 2026 Tessera Contributors
-- Licensed under the Apache License, Version 2.0
--
-- IT override: pgvector extension is not available in the apache/age test image.
-- The vector extension and entity_embeddings table are skipped in IT context.
SELECT 1 AS pgvector_skipped_in_test;
