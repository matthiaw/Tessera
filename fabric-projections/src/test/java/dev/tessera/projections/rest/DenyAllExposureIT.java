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
import static org.hamcrest.Matchers.equalTo;

import dev.tessera.core.schema.CreateNodeTypeSpec;
import dev.tessera.core.schema.SchemaRegistry;
import dev.tessera.core.tenant.TenantContext;
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
 * W2a IT: verifies that the REST projection respects the
 * {@code rest_read_enabled} exposure flag. A type with the flag set to
 * {@code false} must return 404; flipping it to {@code true} must return 200.
 */
@SpringBootTest(classes = ProjectionItApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("projection-it")
@Testcontainers
class DenyAllExposureIT {

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
    JdbcTemplate jdbc;

    private UUID modelId;

    @BeforeEach
    void setUp() {
        modelId = UUID.randomUUID();
    }

    @Test
    void type_with_read_disabled_returns_404_then_200_after_flip() {
        TenantContext ctx = TenantContext.of(modelId);

        // Declare a type (rest_read_enabled defaults to false).
        schemaRegistry.createNodeType(ctx, new CreateNodeTypeSpec("Widget", "Widget", "Widget", "A widget"));

        // GET should return 404 — type not exposed.
        given().port(port)
                .when()
                .get("/api/v1/" + modelId + "/entities/Widget")
                .then()
                .statusCode(404);

        // Flip rest_read_enabled=true via direct JDBC.
        jdbc.update(
                "UPDATE schema_node_types SET rest_read_enabled = TRUE" + " WHERE model_id = ?::uuid AND slug = ?",
                modelId.toString(),
                "Widget");

        // Force cache invalidation by adding a property (bumps schema version).
        schemaRegistry.addProperty(
                ctx, "Widget", new dev.tessera.core.schema.AddPropertySpec("name", "Name", "string", false));

        // GET should now return 200.
        given().port(port)
                .when()
                .get("/api/v1/" + modelId + "/entities/Widget")
                .then()
                .statusCode(200)
                .body("next_cursor", equalTo(null));
    }
}
