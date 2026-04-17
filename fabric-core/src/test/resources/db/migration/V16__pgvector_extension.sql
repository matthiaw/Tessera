-- fabric-core test override: apache/age image does not include pgvector.
-- Production V16 (fabric-app) uses CREATE EXTENSION IF NOT EXISTS vector.
-- This no-op avoids the extension requirement in fabric-core ITs.
SELECT 1 AS pgvector_skipped_in_test;
