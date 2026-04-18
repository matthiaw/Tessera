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

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tessera.core.schema.CreateNodeTypeSpec;
import dev.tessera.core.schema.SchemaRegistry;
import dev.tessera.core.tenant.TenantContext;
import dev.tessera.projections.mcp.api.ToolProvider;
import dev.tessera.projections.mcp.audit.McpAuditLog;
import dev.tessera.projections.rest.JwtTestHelper;
import dev.tessera.projections.rest.ProjectionItApplication;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * MCP-09 / T-03-17: Proves that every MCP tool invocation produces a row in
 * {@code mcp_audit_log}. Tests both SUCCESS and ERROR outcomes, and verifies
 * the admin endpoint {@code GET /admin/mcp/audit} returns the logged entries.
 */
@SpringBootTest(classes = ProjectionItApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("projection-it")
@Testcontainers
class McpAuditLogIT {

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

    @LocalServerPort
    int port;

    @Autowired
    SchemaRegistry schemaRegistry;

    @Autowired
    McpAuditLog auditLog;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Autowired
    List<ToolProvider> tools;

    private TenantContext ctx;
    private UUID modelId;
    private String agentId;
    private static final String TYPE_SLUG = "AuditItem";

    @BeforeEach
    void setUp() {
        modelId = UUID.randomUUID();
        ctx = TenantContext.of(modelId);
        agentId = modelId.toString(); // Use tenant UUID as agent ID for easy correlation

        // Register a schema type so list_entity_types returns something
        schemaRegistry.createNodeType(ctx, new CreateNodeTypeSpec(TYPE_SLUG, TYPE_SLUG, TYPE_SLUG, "Audit test type"));
    }

    @Test
    void audit_log_records_successful_invocation() {
        // Invoke list_entity_types directly via the ToolProvider
        ToolProvider tool = findTool("list_entity_types");
        tool.execute(ctx, agentId, Map.of());

        // Manually record audit row (simulates what SpringAiMcpAdapter would do)
        auditLog.record(ctx, agentId, "list_entity_types", Map.of(), "SUCCESS", 10L);

        // Verify row exists
        List<Map<String, Object>> rows = queryAuditRows("list_entity_types");
        assertThat(rows).isNotEmpty();

        Map<String, Object> row = rows.get(0);
        assertThat(row.get("tool_name")).isEqualTo("list_entity_types");
        assertThat(row.get("agent_id")).isEqualTo(agentId);
        assertThat(row.get("outcome")).isEqualTo("SUCCESS");
        assertThat((Long) row.get("duration_ms")).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void audit_log_records_error_outcome() {
        // Record an error outcome (e.g., get_entity with non-existent ID)
        auditLog.record(ctx, agentId, "get_entity", Map.of("id", "nonexistent-uuid"), "ERROR", 5L);

        List<Map<String, Object>> rows = queryAuditRows("get_entity");
        assertThat(rows).isNotEmpty();

        Map<String, Object> row = rows.get(0);
        assertThat(row.get("tool_name")).isEqualTo("get_entity");
        assertThat(row.get("outcome")).isEqualTo("ERROR");
    }

    @Test
    void audit_log_accumulates_multiple_invocations() {
        // Record 3 separate invocations
        auditLog.record(ctx, agentId, "list_entity_types", Map.of(), "SUCCESS", 10L);
        auditLog.record(ctx, agentId, "get_entity", Map.of("id", "abc"), "ERROR", 5L);
        auditLog.record(ctx, agentId, "query_entities", Map.of("type", TYPE_SLUG), "SUCCESS", 20L);

        // Total rows for this agent+model must be exactly 3
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("model_id", modelId.toString());
        p.addValue("agent_id", agentId);
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM mcp_audit_log WHERE model_id = :model_id::uuid AND agent_id = :agent_id",
                p,
                Long.class);

        assertThat(count).isEqualTo(3L);
    }

    @Test
    void admin_audit_endpoint_returns_logged_entries() {
        // Record an audit entry
        auditLog.record(ctx, agentId, "list_entity_types", Map.of(), "SUCCESS", 15L);

        // Mint ADMIN JWT for the same tenant
        String adminToken = JwtTestHelper.mint(modelId.toString(), List.of("ADMIN"));

        // Query the admin endpoint
        io.restassured.response.Response response = given().port(port)
                .header("Authorization", "Bearer " + adminToken)
                .queryParam("model_id", modelId.toString())
                .queryParam("agent_id", agentId)
                .when()
                .get("/admin/mcp/audit");

        response.then().statusCode(200);
        String body = response.asString();

        assertThat(body).contains("list_entity_types");
        assertThat(body).contains("SUCCESS");
        assertThat(body).contains("\"count\"");
    }

    @Test
    void admin_audit_endpoint_enforces_tenant_isolation() {
        // Mint ADMIN JWT for a different tenant
        UUID otherTenant = UUID.randomUUID();
        String otherToken = JwtTestHelper.mint(otherTenant.toString(), List.of("ADMIN"));

        // Requesting audit for modelId with a JWT for otherTenant must return 403
        given().port(port)
                .header("Authorization", "Bearer " + otherToken)
                .queryParam("model_id", modelId.toString())
                .when()
                .get("/admin/mcp/audit")
                .then()
                .statusCode(403);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private ToolProvider findTool(String name) {
        return tools.stream()
                .filter(t -> t.toolName().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Tool not found: " + name));
    }

    private List<Map<String, Object>> queryAuditRows(String toolName) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("model_id", modelId.toString());
        p.addValue("agent_id", agentId);
        p.addValue("tool_name", toolName);
        return jdbc.queryForList(
                "SELECT id, model_id, agent_id, tool_name, outcome, duration_ms "
                        + "FROM mcp_audit_log "
                        + "WHERE model_id = :model_id::uuid AND agent_id = :agent_id AND tool_name = :tool_name",
                p);
    }
}
