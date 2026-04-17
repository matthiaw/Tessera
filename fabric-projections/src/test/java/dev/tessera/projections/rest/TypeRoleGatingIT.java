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
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;

import dev.tessera.core.schema.AddPropertySpec;
import dev.tessera.core.schema.CreateNodeTypeSpec;
import dev.tessera.core.schema.SchemaRegistry;
import dev.tessera.core.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * SEC-04: Integration test proving type-level role gating and write-role
 * enforcement. Restricted types return 404 for unauthorized callers.
 * Write-restricted properties are rejected for unauthorized callers.
 */
@SpringBootTest(classes = ProjectionItApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("projection-it")
@Testcontainers
class TypeRoleGatingIT {

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
    NamedParameterJdbcTemplate jdbc;

    private UUID modelId;

    @BeforeEach
    void setUp() {
        modelId = UUID.randomUUID();
        TenantContext ctx = TenantContext.of(modelId);

        // Create Secret type: read_roles = {ADMIN}, rest_read_enabled = true
        schemaRegistry.createNodeType(ctx, new CreateNodeTypeSpec("Secret", "Secret", "Secret", "Restricted type"));
        jdbc.getJdbcTemplate()
                .update(
                        "UPDATE schema_node_types SET rest_read_enabled = TRUE, rest_write_enabled = TRUE,"
                                + " read_roles = '{ADMIN}'"
                                + " WHERE model_id = ?::uuid AND slug = ?",
                        modelId.toString(),
                        "Secret");
        schemaRegistry.addProperty(ctx, "Secret", AddPropertySpec.required("name", "string"));

        // Create Public type: no read_roles (visible to all)
        schemaRegistry.createNodeType(ctx, new CreateNodeTypeSpec("Public", "Public", "Public", "Open type"));
        jdbc.getJdbcTemplate()
                .update(
                        "UPDATE schema_node_types SET rest_read_enabled = TRUE, rest_write_enabled = TRUE"
                                + " WHERE model_id = ?::uuid AND slug = ?",
                        modelId.toString(),
                        "Public");
        schemaRegistry.addProperty(ctx, "Public", AddPropertySpec.required("name", "string"));

        // Create entities via ADMIN JWT
        String adminToken = JwtTestHelper.mint(modelId.toString(), List.of("ADMIN"));
        given().port(port)
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .body(Map.of("name", "TopSecret"))
                .when()
                .post("/api/v1/" + modelId + "/entities/Secret")
                .then()
                .statusCode(201);

        given().port(port)
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .body(Map.of("name", "OpenData"))
                .when()
                .post("/api/v1/" + modelId + "/entities/Public")
                .then()
                .statusCode(201);
    }

    @Test
    void admin_can_access_restricted_type() {
        String adminToken = JwtTestHelper.mint(modelId.toString(), List.of("ADMIN"));

        given().port(port)
                .header("Authorization", "Bearer " + adminToken)
                .when()
                .get("/api/v1/" + modelId + "/entities/Secret")
                .then()
                .statusCode(200);
    }

    @Test
    void agent_gets_404_for_restricted_type() {
        String agentToken = JwtTestHelper.mint(modelId.toString(), List.of("AGENT"));

        given().port(port)
                .header("Authorization", "Bearer " + agentToken)
                .when()
                .get("/api/v1/" + modelId + "/entities/Secret")
                .then()
                .statusCode(404)
                .body("detail", not(containsString("Secret")));
    }

    @Test
    void agent_can_access_unrestricted_type() {
        String agentToken = JwtTestHelper.mint(modelId.toString(), List.of("AGENT"));

        given().port(port)
                .header("Authorization", "Bearer " + agentToken)
                .when()
                .get("/api/v1/" + modelId + "/entities/Public")
                .then()
                .statusCode(200);
    }

    @Test
    void write_to_restricted_property_rejected() {
        TenantContext ctx = TenantContext.of(modelId);

        // Add a write-restricted property to Public type
        schemaRegistry.addProperty(ctx, "Public", AddPropertySpec.of("classified", "string"));
        jdbc.getJdbcTemplate()
                .update(
                        "UPDATE schema_properties SET write_roles = '{ADMIN}'"
                                + " WHERE model_id = ?::uuid AND type_slug = ? AND slug = ?",
                        modelId.toString(),
                        "Public",
                        "classified");

        String agentToken = JwtTestHelper.mint(modelId.toString(), List.of("AGENT"));

        // AGENT cannot write to restricted property -- returns 404 (Decision 11: AccessDenied -> 404)
        given().port(port)
                .header("Authorization", "Bearer " + agentToken)
                .contentType("application/json")
                .body(Map.of("name", "Test", "classified", "secret"))
                .when()
                .post("/api/v1/" + modelId + "/entities/Public")
                .then()
                .statusCode(404);

        // ADMIN can write to restricted property
        String adminToken = JwtTestHelper.mint(modelId.toString(), List.of("ADMIN"));
        given().port(port)
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .body(Map.of("name", "Test", "classified", "secret"))
                .when()
                .post("/api/v1/" + modelId + "/entities/Public")
                .then()
                .statusCode(201);
    }
}
