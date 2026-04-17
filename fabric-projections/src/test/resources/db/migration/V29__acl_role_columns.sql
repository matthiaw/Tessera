-- Phase 10: Per-property and per-type role ACLs (D-01, D-04)
-- NULL = visible to all authenticated callers (D-02)

ALTER TABLE schema_properties
    ADD COLUMN IF NOT EXISTS read_roles  TEXT[] NULL,
    ADD COLUMN IF NOT EXISTS write_roles TEXT[] NULL;

ALTER TABLE schema_node_types
    ADD COLUMN IF NOT EXISTS read_roles  TEXT[] NULL,
    ADD COLUMN IF NOT EXISTS write_roles TEXT[] NULL;

CREATE INDEX idx_schema_properties_read_roles ON schema_properties USING GIN (read_roles)
    WHERE read_roles IS NOT NULL;
CREATE INDEX idx_schema_node_types_read_roles ON schema_node_types USING GIN (read_roles)
    WHERE read_roles IS NOT NULL;
