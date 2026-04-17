-- fabric-rules test no-op: apache/age image does not include pgvector
SELECT 1 AS pgvector_skipped_in_rules_test;
