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
package dev.tessera.projections.mcp;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import dev.tessera.core.tenant.TenantContext;
import dev.tessera.projections.mcp.audit.McpAuditLog;
import dev.tessera.projections.mcp.quota.AgentQuotaService;
import dev.tessera.projections.mcp.quota.QuotaExceededException;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * SEC-07: AgentQuotaService must enforce per-agent write quotas.
 *
 * <p>Default quota = 0 writes (read-only by default).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentQuotaServiceTest {

    @Mock
    private NamedParameterJdbcTemplate jdbc;

    @Mock
    private McpAuditLog auditLog;

    private AgentQuotaService quotaService;

    private TenantContext ctx;
    private static final String AGENT_ID = "agent-test-001";

    @BeforeEach
    void setUp() {
        quotaService = new AgentQuotaService(jdbc, auditLog);
        ctx = TenantContext.of(UUID.randomUUID());
        // Default: countForAgentSince returns 0 (no history)
        when(auditLog.countForAgentSince(any(), anyString(), any())).thenReturn(0L);
    }

    @Test
    void rejects_write_when_no_quota_row_exists() {
        // No row in mcp_agent_quotas -> empty list from queryForList
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class), any(Class.class)))
                .thenReturn(Collections.emptyList());

        assertThatThrownBy(() -> quotaService.checkWriteQuota(ctx, AGENT_ID))
                .isInstanceOf(QuotaExceededException.class)
                .hasMessageContaining("no write quota");
    }

    @Test
    void rejects_write_when_quota_is_zero() {
        // Row exists but writes_per_hour = 0
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class), any(Class.class)))
                .thenReturn(List.of(0));

        assertThatThrownBy(() -> quotaService.checkWriteQuota(ctx, AGENT_ID))
                .isInstanceOf(QuotaExceededException.class)
                .hasMessageContaining("no write quota");
    }

    @Test
    void allows_writes_within_quota_then_rejects() {
        // writes_per_hour = 2 -> first 2 succeed, 3rd throws
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class), any(Class.class)))
                .thenReturn(List.of(2));

        // First two calls should pass
        assertThatCode(() -> quotaService.checkWriteQuota(ctx, AGENT_ID)).doesNotThrowAnyException();
        assertThatCode(() -> quotaService.checkWriteQuota(ctx, AGENT_ID)).doesNotThrowAnyException();

        // Third call should throw
        assertThatThrownBy(() -> quotaService.checkWriteQuota(ctx, AGENT_ID))
                .isInstanceOf(QuotaExceededException.class)
                .hasMessageContaining("exceeded");
    }
}
