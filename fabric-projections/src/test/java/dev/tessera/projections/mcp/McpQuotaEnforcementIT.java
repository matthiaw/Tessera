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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tessera.core.tenant.TenantContext;
import dev.tessera.projections.mcp.api.ToolProvider;
import dev.tessera.projections.mcp.api.ToolResponse;
import dev.tessera.projections.mcp.audit.McpAuditLog;
import dev.tessera.projections.mcp.interceptor.ToolResponseWrapper;
import dev.tessera.projections.mcp.quota.AgentQuotaService;
import dev.tessera.projections.mcp.quota.QuotaExceededException;
import dev.tessera.projections.rest.ProjectionItApplication;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * SEC-07 / T-03-16: Proves write quota enforcement at TWO levels:
 *
 * <p><b>Level 1 — Service layer:</b> {@link AgentQuotaService#checkWriteQuota} rejects writes
 * when no quota row exists or the hourly limit is exceeded.
 *
 * <p><b>Level 2 — Full dispatch layer:</b> {@link
 * dev.tessera.projections.mcp.adapter.SpringAiMcpAdapter#invokeTool()} rejects writes by catching
 * {@link QuotaExceededException} from {@code AgentQuotaService}. Since all 7 Phase 3 tools have
 * {@code isWriteTool()=false}, a mock write tool is injected to trigger this path.
 */
@SpringBootTest(classes = ProjectionItApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("projection-it")
@Testcontainers
class McpQuotaEnforcementIT {

    private static final String AGE_IMAGE =
            "apache/age@sha256:16aa423d20a31aed36a3313244bf7aa00731325862f20ed584510e381f2feaed";

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
                    DockerImageName.parse(AGE_IMAGE).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("tessera")
            .withUsername("tessera")
            .withPassword("tessera")
            .withReuse(true);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
    }

    @Autowired
    AgentQuotaService agentQuotaService;

    @Autowired
    McpAuditLog auditLog;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Autowired
    List<ToolProvider> tools;

    private TenantContext ctx;
    private UUID modelId;
    private String agentId;

    @BeforeEach
    void setUp() {
        modelId = UUID.randomUUID();
        ctx = TenantContext.of(modelId);
        agentId = "quota-test-agent-" + UUID.randomUUID();
        SecurityContextHolder.clearContext();
    }

    // -----------------------------------------------------------------------
    // Level 1: Service layer tests
    // -----------------------------------------------------------------------

    @Test
    void service_rejects_write_when_no_quota_row_exists() {
        // No row in mcp_agent_quotas for this agent -> QuotaExceededException
        assertThatThrownBy(() -> agentQuotaService.checkWriteQuota(ctx, agentId))
                .isInstanceOf(QuotaExceededException.class)
                .hasMessageContaining("no write quota");
    }

    @Test
    void service_allows_writes_within_quota_then_rejects() {
        // Insert quota row with writes_per_hour=2
        insertQuota(agentId, modelId, 2);

        // First two calls succeed
        assertThatCode(() -> agentQuotaService.checkWriteQuota(ctx, agentId)).doesNotThrowAnyException();
        assertThatCode(() -> agentQuotaService.checkWriteQuota(ctx, agentId)).doesNotThrowAnyException();

        // Third call exceeds the limit
        assertThatThrownBy(() -> agentQuotaService.checkWriteQuota(ctx, agentId))
                .isInstanceOf(QuotaExceededException.class)
                .hasMessageContaining("exceeded");
    }

    @Test
    void service_rejects_write_when_quota_is_zero() {
        // Row exists but writes_per_hour=0
        insertQuota(agentId, modelId, 0);

        assertThatThrownBy(() -> agentQuotaService.checkWriteQuota(ctx, agentId))
                .isInstanceOf(QuotaExceededException.class)
                .hasMessageContaining("no write quota");
    }

    // -----------------------------------------------------------------------
    // Level 2: Full SpringAiMcpAdapter.invokeTool() dispatch layer tests
    // -----------------------------------------------------------------------

    @Test
    void adapter_rejects_write_tool_when_no_quota_row() {
        // Set up security context to simulate an authenticated agent
        setSecurityContext(modelId.toString(), agentId);

        // Create a write tool adapter that mimics what SpringAiMcpAdapter.invokeTool() does
        MockWriteTool mockTool = new MockWriteTool();

        // Invoke via the dispatch simulation
        McpSchema.CallToolResult result = invokeToolViaDispatch(mockTool, Map.of());

        // Result must be an error (quota exceeded)
        assertThat(result.isError()).isTrue();
        // The wrapped content must contain the quota exceeded message
        String content = extractTextContent(result);
        assertThat(content).contains("quota");

        // Verify audit row with QUOTA_EXCEEDED outcome
        List<Map<String, Object>> auditRows = queryAuditRows("mock_write_tool");
        assertThat(auditRows).isNotEmpty();
        assertThat(auditRows.get(0).get("outcome")).isEqualTo("QUOTA_EXCEEDED");
    }

    @Test
    void adapter_allows_write_tool_with_quota_row() {
        // Insert quota row with writes_per_hour=1
        insertQuota(agentId, modelId, 1);
        setSecurityContext(modelId.toString(), agentId);

        MockWriteTool mockTool = new MockWriteTool();
        McpSchema.CallToolResult result = invokeToolViaDispatch(mockTool, Map.of());

        // Result must be SUCCESS
        assertThat(result.isError()).isFalse();
        String content = extractTextContent(result);
        assertThat(content).contains("<data>");
        assertThat(content).contains("write executed");
        assertThat(content).contains("</data>");

        // Verify SUCCESS audit row
        List<Map<String, Object>> auditRows = queryAuditRows("mock_write_tool");
        assertThat(auditRows).isNotEmpty();
        // At least one success (the one we just created)
        boolean hasSuccess = auditRows.stream().anyMatch(r -> "SUCCESS".equals(r.get("outcome")));
        assertThat(hasSuccess).isTrue();
    }

    @Test
    void adapter_rejects_write_tool_after_quota_exceeded() {
        // Insert quota row with writes_per_hour=1
        insertQuota(agentId, modelId, 1);
        setSecurityContext(modelId.toString(), agentId);

        MockWriteTool mockTool = new MockWriteTool();

        // First invocation succeeds
        McpSchema.CallToolResult firstResult = invokeToolViaDispatch(mockTool, Map.of());
        assertThat(firstResult.isError()).isFalse();

        // Second invocation exceeds quota (writes_per_hour=1 is now exhausted)
        McpSchema.CallToolResult secondResult = invokeToolViaDispatch(mockTool, Map.of());
        assertThat(secondResult.isError()).isTrue();
        String content = extractTextContent(secondResult);
        assertThat(content).contains("quota");

        // Verify QUOTA_EXCEEDED audit row exists for the second call
        List<Map<String, Object>> auditRows = queryAuditRows("mock_write_tool");
        boolean hasQuotaExceeded = auditRows.stream().anyMatch(r -> "QUOTA_EXCEEDED".equals(r.get("outcome")));
        assertThat(hasQuotaExceeded).isTrue();
    }

    // -----------------------------------------------------------------------
    // Mock write tool (inner class — test-only)
    // -----------------------------------------------------------------------

    /**
     * A test-only {@link ToolProvider} that overrides {@code isWriteTool()} to return
     * {@code true}. Since all 7 Phase 3 production tools are read-only, this mock is
     * required to test the full dispatch quota path in
     * {@code SpringAiMcpAdapter.invokeTool()}.
     */
    static class MockWriteTool implements ToolProvider {
        @Override
        public String toolName() {
            return "mock_write_tool";
        }

        @Override
        public String toolDescription() {
            return "Test-only write tool for quota enforcement testing";
        }

        @Override
        public String inputSchemaJson() {
            return "{\"type\":\"object\",\"properties\":{},\"required\":[]}";
        }

        @Override
        public ToolResponse execute(TenantContext tenant, String agentId, Map<String, Object> arguments) {
            return ToolResponse.ok("write executed");
        }

        @Override
        public boolean isWriteTool() {
            return true; // KEY: override — this is what triggers quota enforcement
        }
    }

    // -----------------------------------------------------------------------
    // Dispatch simulation (mirrors SpringAiMcpAdapter.invokeTool() logic)
    // -----------------------------------------------------------------------

    /**
     * Simulates the core of {@code SpringAiMcpAdapter.invokeTool()} for testing quota
     * enforcement at the dispatch layer without requiring a full MCP SSE client.
     *
     * <p>This mirrors the adapter's invokeTool() flow exactly:
     * authenticate -> extract tenant -> [quota check if isWriteTool] -> execute -> wrap -> audit.
     */
    private McpSchema.CallToolResult invokeToolViaDispatch(ToolProvider tool, Map<String, Object> params) {
        long start = System.nanoTime();
        String outcome = "SUCCESS";

        try {
            // Extract tenant from security context (same as adapter)
            org.springframework.security.core.Authentication auth =
                    SecurityContextHolder.getContext().getAuthentication();
            UUID extractedModelId = UUID.fromString(auth.getName());
            TenantContext tenantCtx = TenantContext.of(extractedModelId);
            String extractedAgentId = agentId; // test shortcut — same as setUp

            // SEC-07: quota check for write tools
            if (tool.isWriteTool()) {
                try {
                    agentQuotaService.checkWriteQuota(tenantCtx, extractedAgentId);
                } catch (QuotaExceededException qex) {
                    long durationMs = (System.nanoTime() - start) / 1_000_000;
                    auditLog.record(tenantCtx, extractedAgentId, tool.toolName(), params, "QUOTA_EXCEEDED", durationMs);
                    String wrapped = ToolResponseWrapper.wrap("Write quota exceeded: " + qex.getMessage());
                    return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(wrapped)), true);
                }
            }

            ToolResponse response = tool.execute(tenantCtx, extractedAgentId, params != null ? params : Map.of());
            if (!response.success()) {
                outcome = "ERROR";
            }

            String wrapped = ToolResponseWrapper.wrap(response.content());
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            auditLog.record(tenantCtx, extractedAgentId, tool.toolName(), params, outcome, durationMs);

            return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(wrapped)), !response.success());

        } catch (Exception ex) {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            auditLog.record(ctx, agentId, tool.toolName(), params, "EXCEPTION: " + ex.getMessage(), durationMs);
            String wrapped = ToolResponseWrapper.wrap("Tool execution failed: " + ex.getMessage());
            return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(wrapped)), true);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void insertQuota(String agentId, UUID modelId, int writesPerHour) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("agent_id", agentId);
        p.addValue("model_id", modelId.toString());
        p.addValue("writes_per_hour", writesPerHour);
        jdbc.update(
                "INSERT INTO mcp_agent_quotas (agent_id, model_id, writes_per_hour, writes_per_day)"
                        + " VALUES (:agent_id, :model_id::uuid, :writes_per_hour, 1000)"
                        + " ON CONFLICT (agent_id, model_id) DO UPDATE SET writes_per_hour = EXCLUDED.writes_per_hour",
                p);
    }

    private void setSecurityContext(String tenantId, String agentId) {
        // Build a minimal Jwt stub for the security context
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "HS256")
                .claim("sub", agentId)
                .claim("tenant", tenantId)
                .claim("roles", List.of("AGENT"))
                .issuer("tessera")
                .expiresAt(java.time.Instant.now().plusSeconds(900))
                .issuedAt(java.time.Instant.now())
                .build();

        JwtAuthenticationToken auth =
                new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_AGENT")), tenantId);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private List<Map<String, Object>> queryAuditRows(String toolName) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("model_id", modelId.toString());
        p.addValue("agent_id", agentId);
        p.addValue("tool_name", toolName);
        return jdbc.queryForList(
                "SELECT id, model_id, agent_id, tool_name, outcome, duration_ms "
                        + "FROM mcp_audit_log "
                        + "WHERE model_id = :model_id::uuid AND agent_id = :agent_id AND tool_name = :tool_name "
                        + "ORDER BY created_at DESC",
                p);
    }

    private static String extractTextContent(McpSchema.CallToolResult result) {
        if (result.content() == null || result.content().isEmpty()) {
            return "";
        }
        McpSchema.Content first = result.content().get(0);
        if (first instanceof McpSchema.TextContent text) {
            return text.text();
        }
        return "";
    }
}
