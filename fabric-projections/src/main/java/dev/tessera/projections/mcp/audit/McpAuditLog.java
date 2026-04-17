/*
 * Copyright 2026 Tessera Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.tessera.projections.mcp.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.tessera.core.tenant.TenantContext;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * MCP-09 / D-C3: writes one row to {@code mcp_audit_log} for every MCP tool
 * invocation. Called by {@code SpringAiMcpAdapter} after each tool call.
 *
 * <p>The audit log is append-only — there is no delete path — which ensures
 * every agent action is permanently recorded (T-03-11 repudiation mitigation).
 */
@Service
public class McpAuditLog {

    private static final Logger log = LoggerFactory.getLogger(McpAuditLog.class);

    private static final String INSERT =
            """
            INSERT INTO mcp_audit_log
                (id, model_id, agent_id, tool_name, arguments, outcome, duration_ms, created_at)
            VALUES
                (gen_random_uuid(), :model_id::uuid, :agent_id, :tool_name,
                 :arguments::jsonb, :outcome, :duration_ms, clock_timestamp())
            """;

    private static final String COUNT_SINCE =
            """
            SELECT COUNT(*) FROM mcp_audit_log
            WHERE model_id = :model_id::uuid AND agent_id = :agent_id
              AND created_at >= :since AND outcome != 'QUOTA_EXCEEDED'
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final NamedParameterJdbcTemplate jdbc;

    public McpAuditLog(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Record one tool invocation in {@code mcp_audit_log}.
     *
     * @param ctx        tenant context
     * @param agentId    agent identifier extracted from JWT subject
     * @param toolName   MCP tool name
     * @param arguments  raw tool arguments (serialized to JSONB)
     * @param outcome    one of SUCCESS, ERROR, QUOTA_EXCEEDED, EXCEPTION, EXCEPTION:...
     * @param durationMs wall-clock time from invocation start to completion
     */
    public void record(
            TenantContext ctx,
            String agentId,
            String toolName,
            Map<String, Object> arguments,
            String outcome,
            long durationMs) {

        String argsJson;
        try {
            argsJson = MAPPER.writeValueAsString(arguments != null ? arguments : Map.of());
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize MCP audit arguments for tool {}: {}", toolName, e.getMessage());
            argsJson = "{}";
        }

        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("model_id", ctx.modelId().toString());
        p.addValue("agent_id", agentId);
        p.addValue("tool_name", toolName);
        p.addValue("arguments", argsJson);
        p.addValue("outcome", outcome);
        p.addValue("duration_ms", durationMs);

        jdbc.update(INSERT, p);
    }

    /**
     * Count non-QUOTA_EXCEEDED invocations for an agent since {@code since}.
     * Used by {@code AgentQuotaService} to warm in-memory counters on first
     * access (restart survivability, Pitfall 4).
     *
     * @param ctx    tenant context
     * @param agentId the agent to count for
     * @param since  window start (inclusive)
     * @return number of qualifying rows
     */
    public long countForAgentSince(TenantContext ctx, String agentId, Instant since) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("model_id", ctx.modelId().toString());
        p.addValue("agent_id", agentId);
        p.addValue("since", java.sql.Timestamp.from(since));

        Long count = jdbc.queryForObject(COUNT_SINCE, p, Long.class);
        return count != null ? count : 0L;
    }
}
