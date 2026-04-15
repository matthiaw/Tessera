-- Phase 1 / Wave 0 / reconciliation_conflicts
-- D-C3 exact DDL: contested-property decisions persisted for v2+ operator UI.
CREATE TABLE reconciliation_conflicts (
    id UUID PRIMARY KEY,
    model_id UUID NOT NULL,
    event_id UUID NOT NULL,
    type_slug TEXT NOT NULL,
    node_uuid UUID NOT NULL,
    property_slug TEXT NOT NULL,
    losing_source_id TEXT NOT NULL,
    losing_source_system TEXT NOT NULL,
    losing_value JSONB NOT NULL,
    winning_source_id TEXT NOT NULL,
    winning_source_system TEXT NOT NULL,
    winning_value JSONB NOT NULL,
    rule_id TEXT NOT NULL,
    reason TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT conflict_tenant_check CHECK (model_id IS NOT NULL)
);

CREATE INDEX idx_reconciliation_conflicts_node ON reconciliation_conflicts (model_id, node_uuid);
CREATE INDEX idx_reconciliation_conflicts_property ON reconciliation_conflicts (model_id, type_slug, property_slug);
CREATE INDEX idx_reconciliation_conflicts_losing_system ON reconciliation_conflicts (model_id, losing_source_system);
CREATE INDEX idx_reconciliation_conflicts_created ON reconciliation_conflicts (model_id, created_at DESC);
