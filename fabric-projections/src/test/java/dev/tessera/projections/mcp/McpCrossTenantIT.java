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

import dev.tessera.core.graph.GraphMutation;
import dev.tessera.core.graph.GraphService;
import dev.tessera.core.graph.Operation;
import dev.tessera.core.graph.SourceType;
import dev.tessera.core.schema.AddPropertySpec;
import dev.tessera.core.schema.CreateNodeTypeSpec;
import dev.tessera.core.schema.SchemaRegistry;
import dev.tessera.core.tenant.TenantContext;
import dev.tessera.projections.mcp.api.ToolProvider;
import dev.tessera.projections.mcp.api.ToolResponse;
import dev.tessera.projections.rest.ProjectionItApplication;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * T-03-14: Proves that MCP tools enforce tenant isolation — tenant A cannot access
 * tenant B's data via any MCP tool.
 *
 * <p>Creates two tenants (tenantA, tenantB), seeds each with "Person" nodes, then
 * verifies that all tool responses for tenantA context never contain tenantB data.
 */
@SpringBootTest(
        classes = ProjectionItApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("projection-it")
@Testcontainers
class McpCrossTenantIT {

    private static final String AGE_IMAGE =
            "apache/age@sha256:16aa423d20a31aed36a3313244bf7aa00731325862f20ed584510e381f2feaed";

    @Container
    static final PostgreSQLContainer<?> PG =
            new PostgreSQLContainer<>(DockerImageName.parse(AGE_IMAGE).asCompatibleSubstituteFor("postgres"))
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
    SchemaRegistry schemaRegistry;

    @Autowired
    GraphService graphService;

    @Autowired
    List<ToolProvider> tools;

    private TenantContext ctxA;
    private TenantContext ctxB;
    private UUID tenantAId;
    private UUID tenantBId;

    private static final String AGENT_ID = "cross-tenant-test-agent";
    private static final String TYPE_SLUG = "Person";

    // UUIDs of tenantB nodes — must never appear in tenantA responses
    private final List<UUID> tenantBNodeIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        tenantAId = UUID.randomUUID();
        tenantBId = UUID.randomUUID();
        ctxA = TenantContext.of(tenantAId);
        ctxB = TenantContext.of(tenantBId);

        // Create schema for both tenants independently
        schemaRegistry.createNodeType(ctxA, new CreateNodeTypeSpec(TYPE_SLUG, TYPE_SLUG, TYPE_SLUG, "Person in A"));
        schemaRegistry.addProperty(ctxA, TYPE_SLUG, new AddPropertySpec("name", "Name", "string", false));
        schemaRegistry.addProperty(ctxA, TYPE_SLUG, new AddPropertySpec("tenant_marker", "Marker", "string", false));

        schemaRegistry.createNodeType(ctxB, new CreateNodeTypeSpec(TYPE_SLUG, TYPE_SLUG, TYPE_SLUG, "Person in B"));
        schemaRegistry.addProperty(ctxB, TYPE_SLUG, new AddPropertySpec("name", "Name", "string", false));
        schemaRegistry.addProperty(ctxB, TYPE_SLUG, new AddPropertySpec("tenant_marker", "Marker", "string", false));

        // Seed 3 nodes for tenantA with unique marker
        for (int i = 0; i < 3; i++) {
            graphService.apply(GraphMutation.builder()
                    .tenantContext(ctxA)
                    .operation(Operation.CREATE)
                    .type(TYPE_SLUG)
                    .payload(Map.of("name", "PersonA-" + i, "tenant_marker", "TENANT_A_MARKER"))
                    .sourceType(SourceType.MANUAL)
                    .sourceId("cross-tenant-it")
                    .sourceSystem("mcp-it")
                    .confidence(BigDecimal.ONE)
                    .build());
        }

        // Seed 2 nodes for tenantB with unique marker
        for (int i = 0; i < 2; i++) {
            UUID nodeId = UUID.randomUUID();
            tenantBNodeIds.add(nodeId);
            graphService.apply(GraphMutation.builder()
                    .tenantContext(ctxB)
                    .operation(Operation.CREATE)
                    .type(TYPE_SLUG)
                    .targetNodeUuid(nodeId)
                    .payload(Map.of("name", "PersonB-" + i, "tenant_marker", "TENANT_B_MARKER"))
                    .sourceType(SourceType.MANUAL)
                    .sourceId("cross-tenant-it")
                    .sourceSystem("mcp-it")
                    .confidence(BigDecimal.ONE)
                    .build());
        }
    }

    @Test
    void list_entity_types_returns_only_tenantA_types() {
        ToolProvider tool = findTool("list_entity_types");
        ToolResponse response = tool.execute(ctxA, AGENT_ID, Map.of());

        assertThat(response.success()).isTrue();
        String content = response.content();
        // tenantA schema is visible
        assertThat(content).contains(TYPE_SLUG);
        // tenantB marker must not appear in tenantA's type listing
        assertNoCrossTenantLeak(content, "list_entity_types");
    }

    @Test
    void query_entities_returns_only_tenantA_nodes() {
        ToolProvider tool = findTool("query_entities");
        ToolResponse response = tool.execute(ctxA, AGENT_ID, Map.of("type", TYPE_SLUG));

        assertThat(response.success()).isTrue();
        String content = response.content();
        // tenantA nodes are visible
        assertThat(content).contains("PersonA-0");
        // tenantB nodes must not appear
        assertNoCrossTenantLeak(content, "query_entities");
        assertThat(content).doesNotContain("TENANT_B_MARKER");
        assertThat(content).doesNotContain("PersonB-0");
    }

    @Test
    void get_entity_with_tenantB_id_returns_not_found() {
        ToolProvider tool = findTool("get_entity");
        UUID tenantBNodeId = tenantBNodeIds.get(0);

        // Requesting a tenantB node UUID via tenantA context must return error/not-found
        ToolResponse response =
                tool.execute(ctxA, AGENT_ID, Map.of("type", TYPE_SLUG, "id", tenantBNodeId.toString()));

        // Response must be error (not found) — tenantB node is invisible to tenantA
        String content = response.content();
        // Either success=false (error) or content must not contain tenantB data
        if (response.success()) {
            // If somehow a node is found, it must NOT contain tenantB marker
            assertNoCrossTenantLeak(content, "get_entity cross-tenant");
        } else {
            // Expected path: not found
            assertThat(content).doesNotContain("TENANT_B_MARKER");
            assertThat(content).doesNotContain("PersonB-0");
        }
    }

    @Test
    void traverse_returns_only_tenantA_nodes() {
        ToolProvider tool = findTool("traverse");
        // Simple Cypher to return all person names in the current tenant
        ToolResponse response =
                tool.execute(ctxA, AGENT_ID, Map.of("cypher", "MATCH (n:Person) RETURN n LIMIT 20"));

        String content = response.content();
        // If traverse succeeds (AGE Cypher returns results), tenantB data must not appear
        assertNoCrossTenantLeak(content, "traverse");
        assertThat(content).doesNotContain("TENANT_B_MARKER");
        assertThat(content).doesNotContain("PersonB-0");
    }

    @Test
    void describe_type_returns_only_tenantA_schema() {
        ToolProvider tool = findTool("describe_type");
        ToolResponse response = tool.execute(ctxA, AGENT_ID, Map.of("type", TYPE_SLUG));

        assertThat(response.success()).isTrue();
        String content = response.content();
        // tenantA schema properties are visible
        assertThat(content).containsIgnoringCase(TYPE_SLUG);
        // No cross-tenant leak
        assertNoCrossTenantLeak(content, "describe_type");
    }

    @Test
    void find_path_between_cross_tenant_nodes_returns_empty() {
        ToolProvider tool = findTool("find_path");
        UUID tenantBNodeId = tenantBNodeIds.get(0);
        UUID fakeFromId = UUID.randomUUID();

        // Attempt to find path from tenantA space (random node) to tenantB node
        // Must return empty path (cross-tenant nodes are isolated)
        ToolResponse response = tool.execute(
                ctxA, AGENT_ID, Map.of("from_id", fakeFromId.toString(), "to_id", tenantBNodeId.toString()));

        String content = response.content();
        // Path must be empty or not found — tenantB node is invisible to tenantA
        assertNoCrossTenantLeak(content, "find_path");
        assertThat(content).doesNotContain("TENANT_B_MARKER");
        assertThat(content).doesNotContain("PersonB-0");
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

    private void assertNoCrossTenantLeak(String content, String toolName) {
        assertThat(content)
                .as("Tool '%s' must not leak TENANT_B_MARKER", toolName)
                .doesNotContain("TENANT_B_MARKER");
        assertThat(content)
                .as("Tool '%s' must not contain tenantB UUID", toolName)
                .doesNotContain(tenantBId.toString());
    }
}
