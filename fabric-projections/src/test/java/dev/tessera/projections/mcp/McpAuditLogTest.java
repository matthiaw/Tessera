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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.tessera.core.tenant.TenantContext;
import dev.tessera.projections.mcp.audit.McpAuditLog;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * MCP-09: McpAuditLog.record() must INSERT a row to mcp_audit_log with all required fields.
 */
@ExtendWith(MockitoExtension.class)
class McpAuditLogTest {

    @Mock
    private NamedParameterJdbcTemplate jdbc;

    private McpAuditLog auditLog;

    private TenantContext ctx;
    private static final UUID MODEL_ID = UUID.randomUUID();
    private static final String AGENT_ID = "agent-001";

    @BeforeEach
    void setUp() {
        auditLog = new McpAuditLog(jdbc);
        ctx = TenantContext.of(MODEL_ID);
    }

    @Test
    void records_successful_invocation() {
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);

        auditLog.record(ctx, AGENT_ID, "list_entity_types", Map.of("filter", "type1"), "SUCCESS", 42L);

        ArgumentCaptor<MapSqlParameterSource> captor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(anyString(), captor.capture());

        MapSqlParameterSource params = captor.getValue();
        assertThat(params.getValue("model_id")).isEqualTo(MODEL_ID.toString());
        assertThat(params.getValue("agent_id")).isEqualTo(AGENT_ID);
        assertThat(params.getValue("tool_name")).isEqualTo("list_entity_types");
        assertThat(params.getValue("outcome")).isEqualTo("SUCCESS");
        assertThat(params.getValue("duration_ms")).isEqualTo(42L);
        // arguments should be serialized JSON containing the key
        assertThat(params.getValue("arguments").toString()).contains("filter");
    }

    @Test
    void records_failed_invocation() {
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);

        auditLog.record(ctx, AGENT_ID, "get_entity", Map.of("id", "node-123"), "ERROR", 15L);

        ArgumentCaptor<MapSqlParameterSource> captor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(anyString(), captor.capture());

        MapSqlParameterSource params = captor.getValue();
        assertThat(params.getValue("outcome")).isEqualTo("ERROR");
        assertThat(params.getValue("tool_name")).isEqualTo("get_entity");
    }

    @Test
    void count_for_agent_since_returns_correct_count() {
        Instant since = Instant.now().minusSeconds(3600);
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(3L);

        long count = auditLog.countForAgentSince(ctx, AGENT_ID, since);

        assertThat(count).isEqualTo(3L);
    }
}
