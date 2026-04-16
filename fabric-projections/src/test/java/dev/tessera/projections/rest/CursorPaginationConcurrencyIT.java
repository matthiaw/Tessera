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
package dev.tessera.projections.rest;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tessera.core.graph.GraphMutation;
import dev.tessera.core.graph.GraphService;
import dev.tessera.core.graph.Operation;
import dev.tessera.core.graph.SourceType;
import dev.tessera.core.schema.AddPropertySpec;
import dev.tessera.core.schema.CreateNodeTypeSpec;
import dev.tessera.core.schema.SchemaRegistry;
import dev.tessera.core.tenant.TenantContext;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * W2a IT: insert 100 nodes, page through with cursor pagination, verify no
 * duplicates and stable ordering. Then insert more nodes mid-pagination and
 * verify cursor stability.
 */
@SpringBootTest(classes = ProjectionItApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("projection-it")
@Testcontainers
class CursorPaginationConcurrencyIT {

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
    GraphService graphService;

    @Autowired
    JdbcTemplate jdbc;

    private UUID modelId;
    private TenantContext ctx;
    private String token;

    @BeforeEach
    void setUp() {
        modelId = UUID.randomUUID();
        ctx = TenantContext.of(modelId);
        token = JwtTestHelper.mint(modelId.toString(), java.util.List.of("ADMIN"));

        // Declare type and expose for read + write.
        schemaRegistry.createNodeType(ctx, new CreateNodeTypeSpec("Item", "Item", "Item", "A test item"));
        schemaRegistry.addProperty(ctx, "Item", new AddPropertySpec("name", "Name", "string", false));
        jdbc.update(
                "UPDATE schema_node_types SET rest_read_enabled = TRUE, rest_write_enabled = TRUE"
                        + " WHERE model_id = ?::uuid AND slug = ?",
                modelId.toString(),
                "Item");
        // Force cache invalidation.
        schemaRegistry.addProperty(ctx, "Item", new AddPropertySpec("rank", "Rank", "integer", false));
    }

    @Test
    void cursor_pagination_returns_all_nodes_without_duplicates() {
        // Insert 100 nodes.
        for (int i = 0; i < 100; i++) {
            createItem("item-" + i);
        }

        // Page through with limit=10.
        Set<String> seenUuids = new HashSet<>();
        String cursor = null;
        int pages = 0;

        do {
            ExtractableResponse<Response> resp = fetchPage(cursor, 10);
            List<Map<String, Object>> items = resp.jsonPath().getList("items");
            cursor = resp.jsonPath().getString("next_cursor");

            for (Map<String, Object> item : items) {
                String uuid = (String) item.get("uuid");
                assertThat(seenUuids.add(uuid))
                        .as("duplicate UUID detected: %s on page %d", uuid, pages)
                        .isTrue();
            }
            pages++;

            // Safety: prevent infinite loop.
            assertThat(pages).as("too many pages").isLessThanOrEqualTo(15);
        } while (cursor != null);

        assertThat(seenUuids).hasSize(100);
    }

    @Test
    void cursor_remains_stable_when_new_nodes_inserted_mid_pagination() {
        // Insert 30 nodes.
        for (int i = 0; i < 30; i++) {
            createItem("pre-" + i);
        }

        // Fetch first page (limit=10).
        ExtractableResponse<Response> page1 = fetchPage(null, 10);
        String cursor1 = page1.jsonPath().getString("next_cursor");
        assertThat(cursor1).isNotNull();
        List<Map<String, Object>> page1Items = page1.jsonPath().getList("items");
        assertThat(page1Items).hasSize(10);

        // Insert 20 more nodes mid-pagination.
        for (int i = 0; i < 20; i++) {
            createItem("mid-" + i);
        }

        // Continue paginating from cursor1 — the cursor must not break.
        Set<String> allUuids = new HashSet<>();
        page1Items.forEach(item -> allUuids.add((String) item.get("uuid")));

        String cursor = cursor1;
        int pages = 1;
        do {
            ExtractableResponse<Response> resp = fetchPage(cursor, 10);
            List<Map<String, Object>> items = resp.jsonPath().getList("items");
            cursor = resp.jsonPath().getString("next_cursor");

            for (Map<String, Object> item : items) {
                String uuid = (String) item.get("uuid");
                assertThat(allUuids.add(uuid))
                        .as("duplicate UUID after mid-insert: %s", uuid)
                        .isTrue();
            }
            pages++;
            assertThat(pages).isLessThanOrEqualTo(10);
        } while (cursor != null);

        // All 50 nodes (30 pre + 20 mid) must be seen.
        assertThat(allUuids).hasSize(50);
    }

    private void createItem(String name) {
        graphService.apply(GraphMutation.builder()
                .tenantContext(ctx)
                .operation(Operation.CREATE)
                .type("Item")
                .payload(Map.of("name", name))
                .sourceType(SourceType.MANUAL)
                .sourceId("test")
                .sourceSystem("cursor-it")
                .confidence(BigDecimal.ONE)
                .build());
    }

    private ExtractableResponse<Response> fetchPage(String cursor, int limit) {
        var req = given().port(port).header("Authorization", "Bearer " + token).queryParam("limit", limit);
        if (cursor != null) {
            req = req.queryParam("cursor", cursor);
        }
        return req.when()
                .get("/api/v1/" + modelId + "/entities/Item")
                .then()
                .statusCode(200)
                .extract();
    }
}
