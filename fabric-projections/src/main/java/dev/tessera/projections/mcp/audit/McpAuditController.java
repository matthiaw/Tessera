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

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * MCP-09 / T-03-12: admin endpoint for querying the MCP audit log and quota
 * configuration. Requires {@code ROLE_ADMIN} or {@code ROLE_TOKEN_ISSUER} (enforced by
 * {@code SecurityConfig} — all {@code /admin/**} routes).
 *
 * <p>Tenant isolation: the JWT {@code tenant} claim must equal the requested
 * {@code model_id}. A mismatch returns 403 to prevent cross-tenant audit disclosure
 * (T-03-12 information disclosure mitigation).
 */
@RestController
@RequestMapping("/admin/mcp")
public class McpAuditController {

    private static final int MAX_LIMIT = 500;

    private final NamedParameterJdbcTemplate jdbc;

    public McpAuditController(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * GET /admin/mcp/audit — query the MCP audit log with optional filters.
     *
     * @param modelId  tenant UUID (required)
     * @param agentId  optional agent_id filter
     * @param from     optional ISO-8601 lower bound on created_at (inclusive)
     * @param to       optional ISO-8601 upper bound on created_at (inclusive)
     * @param limit    max rows to return (default 100, capped at 500)
     * @param jwt      JWT principal for tenant validation
     */
    @GetMapping("/audit")
    public ResponseEntity<Map<String, Object>> getAuditLog(
            @RequestParam("model_id") UUID modelId,
            @RequestParam(value = "agent_id", required = false) String agentId,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @AuthenticationPrincipal Jwt jwt) {

        if (!isTenantMatch(jwt, modelId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Tenant mismatch: JWT tenant does not match requested model_id"));
        }

        int effectiveLimit = Math.max(1, Math.min(limit, MAX_LIMIT));

        StringBuilder sql = new StringBuilder(
                """
                SELECT id, model_id, agent_id, tool_name, arguments, outcome, duration_ms, created_at
                  FROM mcp_audit_log
                 WHERE model_id = :model_id::uuid
                """);

        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("model_id", modelId.toString());

        if (agentId != null && !agentId.isBlank()) {
            sql.append(" AND agent_id = :agent_id");
            p.addValue("agent_id", agentId);
        }
        if (from != null && !from.isBlank()) {
            sql.append(" AND created_at >= :from::timestamptz");
            p.addValue("from", from);
        }
        if (to != null && !to.isBlank()) {
            sql.append(" AND created_at <= :to::timestamptz");
            p.addValue("to", to);
        }

        sql.append(" ORDER BY created_at DESC LIMIT :limit");
        p.addValue("limit", effectiveLimit);

        List<Map<String, Object>> results = jdbc.queryForList(sql.toString(), p);
        return ResponseEntity.ok(Map.of("entries", results, "count", results.size()));
    }

    /**
     * GET /admin/mcp/quotas — list all agent write quotas for a tenant.
     *
     * @param modelId  tenant UUID (required)
     * @param jwt      JWT principal for tenant validation
     */
    @GetMapping("/quotas")
    public ResponseEntity<Map<String, Object>> getQuotas(
            @RequestParam("model_id") UUID modelId, @AuthenticationPrincipal Jwt jwt) {

        if (!isTenantMatch(jwt, modelId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Tenant mismatch: JWT tenant does not match requested model_id"));
        }

        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("model_id", modelId.toString());

        List<Map<String, Object>> quotas = jdbc.queryForList(
                """
                SELECT agent_id, writes_per_hour, writes_per_day, updated_at
                  FROM mcp_agent_quotas
                 WHERE model_id = :model_id::uuid
                 ORDER BY agent_id
                """,
                p);

        return ResponseEntity.ok(Map.of("quotas", quotas, "count", quotas.size()));
    }

    /**
     * Verify that the JWT tenant claim matches the requested model_id.
     * Returns false (403) on any mismatch to prevent cross-tenant disclosure.
     */
    private static boolean isTenantMatch(Jwt jwt, UUID modelId) {
        if (jwt == null) {
            return false;
        }
        String tenant = jwt.getClaimAsString("tenant");
        return modelId.toString().equals(tenant);
    }
}
