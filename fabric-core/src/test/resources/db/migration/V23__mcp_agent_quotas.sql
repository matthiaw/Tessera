-- Phase 3 / MCP Projection / D-C2: per-agent write quota
CREATE TABLE mcp_agent_quotas (
    agent_id        TEXT NOT NULL,
    model_id        UUID NOT NULL,
    writes_per_hour INT  NOT NULL DEFAULT 0,
    writes_per_day  INT  NOT NULL DEFAULT 0,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (agent_id, model_id)
);
