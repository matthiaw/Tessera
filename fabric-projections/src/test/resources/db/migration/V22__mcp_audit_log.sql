-- Phase 3 / MCP Projection / D-C3: per-invocation audit log
CREATE TABLE mcp_audit_log (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    model_id    UUID NOT NULL,
    agent_id    TEXT NOT NULL,
    tool_name   TEXT NOT NULL,
    arguments   JSONB NOT NULL DEFAULT '{}',
    outcome     TEXT NOT NULL,
    duration_ms BIGINT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX idx_mcp_audit_model_created
    ON mcp_audit_log (model_id, created_at DESC);
CREATE INDEX idx_mcp_audit_model_agent_created
    ON mcp_audit_log (model_id, agent_id, created_at DESC);
