-- fabric-core test override: apache/age image does not include pgvector.
-- Production V17 (fabric-app) creates the entity_embeddings table with vector(768) type.
-- This no-op avoids the vector type dependency in fabric-core ITs.
SELECT 1 AS entity_embeddings_skipped_in_test;
