-- Phase 2 / Wave 1 / 02-W1-02: connector_dlq column augmentation (CONTEXT Decision 14).
--
-- Phase 1 V8 created `connector_dlq` for the circuit-breaker drop path with
-- columns (id, model_id, connector_id, reason, raw_payload, created_at).
-- Wave 1 extends the table so it can also carry the validation / rule-reject
-- DLQ rows written same-TX-REQUIRES_NEW by GraphServiceImpl when a
-- connector-origin mutation fails SHACL or a rule rejection.
--
-- Additions:
--   - rejection_reason : short machine code (kept NULL for existing V8 rows,
--     NOT NULL on new writes enforced in Java)
--   - rejection_detail : human-readable message
--   - rule_id          : TEXT id of the rejecting rule (NULL for SHACL failures).
--                        TEXT, not UUID, because the rule-engine ruleId surface
--                        (RuleRejectException.ruleId, ConflictRecord.ruleId) is
--                        already TEXT across Phase 1 — a UUID column here would
--                        force string-to-UUID parsing at every write site.
--   - origin_change_id : echo-loop tracking
--
-- The existing `reason` column stays as-is so Phase 1 tests continue to pass;
-- Wave 1 writers populate `rejection_reason` and mirror a short summary into
-- `reason` so operator tooling that reads the V8 column keeps working.
--
-- V8 named the JSONB payload column `raw_payload`. Wave 1 keeps writing to
-- the same column via `raw_payload` — no rename.

ALTER TABLE connector_dlq
    ADD COLUMN IF NOT EXISTS rejection_reason TEXT,
    ADD COLUMN IF NOT EXISTS rejection_detail TEXT,
    ADD COLUMN IF NOT EXISTS rule_id          TEXT,
    ADD COLUMN IF NOT EXISTS origin_change_id TEXT;
