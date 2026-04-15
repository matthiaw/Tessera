-- Phase 1 / Wave 0 / reconciliation_rules
-- Per ADR-7 §RULE-04: hybrid Java-classes-plus-DB-activation model. Rule logic
-- lives as Java beans; this table carries per-tenant enable/disable, priority
-- override, and per-tenant parameters. RuleRepository joins beans by rule_id.
CREATE TABLE reconciliation_rules (
    id UUID PRIMARY KEY,
    model_id UUID NOT NULL,
    rule_id TEXT NOT NULL,              -- matches Rule.id() in Java
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    priority_override INTEGER,          -- NULL = use Rule.priority() default
    parameters JSONB,                   -- rule-specific per-tenant config
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by TEXT NOT NULL,
    UNIQUE (model_id, rule_id)
);

CREATE INDEX idx_reconciliation_rules_model ON reconciliation_rules (model_id);
