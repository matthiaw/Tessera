-- fabric-rules test no-op: V24 outbox published flag not needed in fabric-rules ITs
SELECT 1 AS outbox_published_flag_skipped_in_rules_test;
