-- Phase 1 / Wave 0 / source_authority
-- Runtime-editable per-tenant × per-type × per-property source priority.
-- D-C2 exact DDL. RuleEngine RECONCILE chain consults this table (Caffeine-cached).
CREATE TABLE source_authority (
    model_id UUID NOT NULL,
    type_slug TEXT NOT NULL,
    property_slug TEXT NOT NULL,
    priority_order TEXT[] NOT NULL,          -- e.g. ['crm', 'hr_system', 'obsidian']
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by TEXT NOT NULL,
    PRIMARY KEY (model_id, type_slug, property_slug)
);
