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
package dev.tessera.projections.mcp.quota;

import dev.tessera.core.tenant.TenantContext;
import dev.tessera.projections.mcp.audit.McpAuditLog;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * SEC-07 / D-C2: enforces per-agent hourly write quotas using in-memory
 * {@link AtomicLong} counters.
 *
 * <p>Agents with no row in {@code mcp_agent_quotas} (or {@code writes_per_hour=0})
 * are rejected on any write attempt — read-only by default (T-03-10
 * elevation-of-privilege mitigation).
 *
 * <p>Counters are warmed from {@code mcp_audit_log} on first access to survive
 * application restarts without losing the current window's count (Pitfall 4
 * in RESEARCH.md).
 *
 * <p>Note (T-03-13 accepted risk): in-memory counters can drift on multi-instance
 * deployments. For MVP, Tessera is single-instance on IONOS; the warm-up from
 * the audit log on restart provides sufficient protection.
 */
@Service
public class AgentQuotaService {

    private static final String LOAD_QUOTA =
            """
            SELECT writes_per_hour FROM mcp_agent_quotas
            WHERE agent_id = :agent_id AND model_id = :model_id::uuid
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final McpAuditLog auditLog;

    /** Key: {@code agentId|modelId} → rolling window counter. */
    private final ConcurrentHashMap<String, AtomicLong> hourlyCounters = new ConcurrentHashMap<>();

    /** Key: {@code agentId|modelId} → window start instant. */
    private final ConcurrentHashMap<String, Instant> hourlyWindowStarts = new ConcurrentHashMap<>();

    public AgentQuotaService(NamedParameterJdbcTemplate jdbc, McpAuditLog auditLog) {
        this.jdbc = jdbc;
        this.auditLog = auditLog;
    }

    /**
     * Enforce write quota for the given agent. Must be called before any write
     * tool execution.
     *
     * @param ctx     tenant context
     * @param agentId agent identifier
     * @throws QuotaExceededException if the agent has no write quota or has
     *                                exceeded their hourly limit
     */
    public void checkWriteQuota(TenantContext ctx, String agentId) throws QuotaExceededException {
        String key = agentId + "|" + ctx.modelId();

        int limit = loadQuotaLimit(ctx, agentId);
        if (limit <= 0) {
            throw new QuotaExceededException("Agent '" + agentId + "' has no write quota for model " + ctx.modelId());
        }

        Instant now = Instant.now();

        // Check whether the rolling window has expired (> 1 hour old).
        Instant windowStart = hourlyWindowStarts.compute(key, (k, existing) -> {
            if (existing == null || now.isAfter(existing.plus(1, ChronoUnit.HOURS))) {
                return now;
            }
            return existing;
        });

        // If the window was just reset, reset the counter too.
        AtomicLong counter = hourlyCounters.compute(key, (k, existing) -> {
            if (existing == null) {
                // First access: create counter and warm from audit log.
                AtomicLong fresh = new AtomicLong(0);
                long historical = auditLog.countForAgentSince(ctx, agentId, windowStart);
                fresh.set(historical);
                return fresh;
            }
            // Check if the window was just reset (windowStart == now means reset happened).
            if (windowStart.equals(now)) {
                // Window reset; re-seed from audit log for the new window.
                long historical = auditLog.countForAgentSince(ctx, agentId, windowStart);
                existing.set(historical);
            }
            return existing;
        });

        long count = counter.incrementAndGet();
        if (count > limit) {
            throw new QuotaExceededException(
                    "Write quota exceeded: " + count + "/" + limit + " writes/hour for agent '" + agentId + "'");
        }
    }

    /**
     * Load the hourly write quota limit for an agent.
     *
     * @return the writes_per_hour value, or 0 if no row exists
     */
    private int loadQuotaLimit(TenantContext ctx, String agentId) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("agent_id", agentId);
        p.addValue("model_id", ctx.modelId().toString());
        try {
            List<Integer> results = jdbc.queryForList(LOAD_QUOTA, p, Integer.class);
            if (results.isEmpty()) {
                return 0;
            }
            Integer value = results.get(0);
            return value != null ? value : 0;
        } catch (EmptyResultDataAccessException e) {
            return 0;
        }
    }
}
