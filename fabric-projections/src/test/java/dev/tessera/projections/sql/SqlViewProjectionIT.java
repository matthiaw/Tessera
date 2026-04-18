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
package dev.tessera.projections.sql;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tessera.core.graph.GraphMutation;
import dev.tessera.core.graph.GraphService;
import dev.tessera.core.graph.Operation;
import dev.tessera.core.graph.SourceType;
import dev.tessera.core.schema.AddPropertySpec;
import dev.tessera.core.schema.CreateNodeTypeSpec;
import dev.tessera.core.schema.SchemaRegistry;
import dev.tessera.core.tenant.TenantContext;
import dev.tessera.projections.rest.ProjectionItApplication;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
 * SQL-01 / D-D3: Verifies that the SQL view projection generates queryable Postgres views
 * for each node type registered in a tenant's schema, with columns matching the
 * {@code NodeTypeDescriptor} and tombstoned entities excluded.
 */
@SpringBootTest(classes = ProjectionItApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("projection-it")
@Testcontainers
class SqlViewProjectionIT {

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
    private SchemaRegistry schemaRegistry;

    @Autowired
    private GraphService graphService;

    @Autowired
    private SqlViewProjection sqlViewProjection;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    private TenantContext ctx;
    private UUID modelId;

    @BeforeEach
    void setUp() {
        modelId = UUID.randomUUID();
        ctx = TenantContext.of(modelId);
    }

    /**
     * SQL-01: After registering a node type and calling regenerateForTenant,
     * the corresponding SQL view is created and queryable via plain SQL.
     */
    @Test
    void viewCreatedForTenantNodeType() {
        // Register a node type in the schema registry
        schemaRegistry.createNodeType(
                ctx, new CreateNodeTypeSpec("employee", "Employee", "employee", "Test employee type"));

        // Create a seed node so the AGE label table exists
        seedNode("employee");

        // Trigger view generation
        sqlViewProjection.regenerateForTenant(ctx);

        // View name follows the resolver convention
        String viewName = SqlViewNameResolver.resolve(modelId, "employee");

        // Query the view via plain SQL — must not throw
        List<Map<String, Object>> rows =
                jdbc.queryForList("SELECT * FROM " + viewName + " LIMIT 10", new MapSqlParameterSource());

        // Empty is fine — no nodes inserted — but the view must exist and be queryable
        assertThat(rows).isNotNull();
    }

    /**
     * SQL-01: The view DDL uses (properties::jsonb) cast, not raw properties.
     * Verified by reading pg_get_viewdef after regeneration.
     */
    @Test
    void viewDdlUsesAgtypeToJsonbCast() {
        schemaRegistry.createNodeType(
                ctx, new CreateNodeTypeSpec("contract", "Contract", "contract", "Test contract type"));
        schemaRegistry.addProperty(ctx, "contract", new AddPropertySpec("title", "Title", "STRING", false));

        seedNode("contract");
        sqlViewProjection.regenerateForTenant(ctx);

        String viewName = SqlViewNameResolver.resolve(modelId, "contract");

        // Read view definition — must contain the (properties::jsonb) cast
        String viewDef = jdbc.queryForObject(
                "SELECT pg_get_viewdef(:name::regclass, true)",
                new MapSqlParameterSource("name", viewName),
                String.class);

        assertThat(viewDef).isNotNull();
        assertThat(viewDef).contains("properties::jsonb");
    }

    /**
     * D-D3: The generated view DDL contains a schema_version comment for staleness detection.
     */
    @Test
    void viewDdlContainsSchemaVersionComment() {
        schemaRegistry.createNodeType(
                ctx, new CreateNodeTypeSpec("project", "Project", "project", "Test project type"));

        seedNode("project");
        sqlViewProjection.regenerateForTenant(ctx);

        String viewName = SqlViewNameResolver.resolve(modelId, "project");
        String viewDef = jdbc.queryForObject(
                "SELECT pg_get_viewdef(:name::regclass, true)",
                new MapSqlParameterSource("name", viewName),
                String.class);

        assertThat(viewDef).isNotNull();
        assertThat(viewDef).contains("schema_version:");
        assertThat(viewDef).contains("model_id:" + modelId.toString());
        assertThat(viewDef).contains("type:project");
    }

    /**
     * SQL-02: Calling regenerateForTenant again after a schema change replaces the view
     * (CREATE OR REPLACE) and the new view reflects the updated schema version.
     */
    @Test
    void viewIsReplacedAfterSchemaChange() {
        schemaRegistry.createNodeType(ctx, new CreateNodeTypeSpec("task", "Task", "task", "Test task type"));
        seedNode("task");
        sqlViewProjection.regenerateForTenant(ctx);

        String viewName = SqlViewNameResolver.resolve(modelId, "task");

        // Add a property — bumps the schema version
        schemaRegistry.addProperty(ctx, "task", new AddPropertySpec("priority", "Priority", "INTEGER", false));

        // Regenerate — the view should be replaced with the new version
        sqlViewProjection.regenerateForTenant(ctx);

        String viewDefAfter = jdbc.queryForObject(
                "SELECT pg_get_viewdef(:name::regclass, true)",
                new MapSqlParameterSource("name", viewName),
                String.class);
        assertThat(viewDefAfter).isNotNull();

        // The new view DDL should reference the priority column
        assertThat(viewDefAfter).contains("priority");
    }

    /**
     * SQL-01: The view filters out tombstoned entries.
     * Verified by checking the WHERE clause in the view definition includes the _tombstoned filter.
     */
    @Test
    void viewExcludesTombstonedEntities() {
        schemaRegistry.createNodeType(ctx, new CreateNodeTypeSpec("ticket", "Ticket", "ticket", "Test ticket type"));

        seedNode("ticket");
        sqlViewProjection.regenerateForTenant(ctx);

        String viewName = SqlViewNameResolver.resolve(modelId, "ticket");
        String viewDef = jdbc.queryForObject(
                "SELECT pg_get_viewdef(:name::regclass, true)",
                new MapSqlParameterSource("name", viewName),
                String.class);

        assertThat(viewDef).isNotNull();
        // View must have tombstone exclusion in its WHERE clause
        assertThat(viewDef).contains("_tombstoned");
    }

    /**
     * SQL-01: View columns match the properties defined in the NodeTypeDescriptor.
     */
    @Test
    void viewColumnsMatchSchemaProperties() {
        schemaRegistry.createNodeType(
                ctx, new CreateNodeTypeSpec("invoice", "Invoice", "invoice", "Test invoice type"));
        schemaRegistry.addProperty(ctx, "invoice", new AddPropertySpec("amount", "Amount", "INTEGER", false));
        schemaRegistry.addProperty(ctx, "invoice", new AddPropertySpec("paid", "Paid", "BOOLEAN", false));

        seedNode("invoice");
        sqlViewProjection.regenerateForTenant(ctx);

        String viewName = SqlViewNameResolver.resolve(modelId, "invoice");
        String viewDef = jdbc.queryForObject(
                "SELECT pg_get_viewdef(:name::regclass, true)",
                new MapSqlParameterSource("name", viewName),
                String.class);

        assertThat(viewDef).isNotNull();
        // Both user-defined columns should appear
        assertThat(viewDef).contains("amount");
        assertThat(viewDef).contains("paid");
        // System columns should also appear
        assertThat(viewDef).contains("uuid");
        assertThat(viewDef).contains("_created_at");
    }

    private void seedNode(String typeSlug) {
        graphService.apply(GraphMutation.builder()
                .tenantContext(ctx)
                .operation(Operation.CREATE)
                .type(typeSlug)
                .payload(Map.of("name", "seed"))
                .sourceType(SourceType.MANUAL)
                .sourceId("sql-view-it")
                .sourceSystem("sql-view-it")
                .confidence(BigDecimal.ONE)
                .build());
    }
}
