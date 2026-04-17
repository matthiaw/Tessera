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
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;

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
 * SEC-05 / REST-07: Integration test proving that REST responses filter
 * properties based on caller JWT roles. ADMIN sees all properties; AGENT
 * sees only unrestricted properties.
 */
@SpringBootTest(classes = ProjectionItApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("projection-it")
@Testcontainers
class AclFilterRestIT {

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
    private UUID entityUuid;

    @BeforeEach
    void setUp() {
        modelId = UUID.randomUUID();
        TenantContext ctx = TenantContext.of(modelId);

        // Create Employee type with REST read+write enabled
        schemaRegistry.createNodeType(ctx, new CreateNodeTypeSpec("Employee", "Employee", "Employee", "Test"));
        jdbc.getJdbcTemplate()
                .update(
                        "UPDATE schema_node_types SET rest_read_enabled = TRUE, rest_write_enabled = TRUE"
                                + " WHERE model_id = ?::uuid AND slug = ?",
                        modelId.toString(),
                        "Employee");

        // Add properties: name (visible to all), salary (restricted to ADMIN)
        schemaRegistry.addProperty(ctx, "Employee", AddPropertySpec.required("name", "string"));
        schemaRegistry.addProperty(ctx, "Employee", AddPropertySpec.of("salary", "string"));

        // Set read_roles on salary property
        jdbc.getJdbcTemplate()
                .update(
                        "UPDATE schema_properties SET read_roles = '{ADMIN}'"
                                + " WHERE model_id = ?::uuid AND type_slug = ? AND slug = ?",
                        modelId.toString(),
                        "Employee",
                        "salary");

        // Create an entity via REST POST with ADMIN JWT
        String adminToken = JwtTestHelper.mint(modelId.toString(), List.of("ADMIN"));
        String uuidStr = given().port(port)
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .body(Map.of("name", "Alice", "salary", "100000"))
                .when()
                .post("/api/v1/" + modelId + "/entities/Employee")
                .then()
                .statusCode(201)
                .extract()
                .path("uuid");
        entityUuid = UUID.fromString(uuidStr);
    }

    @Test
    void admin_sees_all_properties() {
        String adminToken = JwtTestHelper.mint(modelId.toString(), List.of("ADMIN"));

        given().port(port)
                .header("Authorization", "Bearer " + adminToken)
                .when()
                .get("/api/v1/" + modelId + "/entities/Employee")
                .then()
                .statusCode(200)
                .body("items[0].name", equalTo("Alice"))
                .body("items[0].salary", equalTo("100000"))
                .body("items[0]", hasKey("uuid"))
                .body("items[0]", hasKey("type"));
    }

    @Test
    void agent_sees_only_unrestricted_properties() {
        String agentToken = JwtTestHelper.mint(modelId.toString(), List.of("AGENT"));

        given().port(port)
                .header("Authorization", "Bearer " + agentToken)
                .when()
                .get("/api/v1/" + modelId + "/entities/Employee")
                .then()
                .statusCode(200)
                .body("items[0].name", equalTo("Alice"))
                .body("items[0]", not(hasKey("salary")))
                .body("items[0]", hasKey("uuid"))
                .body("items[0]", hasKey("type"))
                .body("items[0]", hasKey("created_at"));
    }

    @Test
    void same_entity_different_roles_different_fields() {
        String adminToken = JwtTestHelper.mint(modelId.toString(), List.of("ADMIN"));
        String agentToken = JwtTestHelper.mint(modelId.toString(), List.of("AGENT"));

        // ADMIN sees salary
        given().port(port)
                .header("Authorization", "Bearer " + adminToken)
                .when()
                .get("/api/v1/" + modelId + "/entities/Employee/" + entityUuid)
                .then()
                .statusCode(200)
                .body("name", equalTo("Alice"))
                .body("salary", equalTo("100000"));

        // AGENT does NOT see salary
        given().port(port)
                .header("Authorization", "Bearer " + agentToken)
                .when()
                .get("/api/v1/" + modelId + "/entities/Employee/" + entityUuid)
                .then()
                .statusCode(200)
                .body("name", equalTo("Alice"))
                .body("$", not(hasKey("salary")));
    }
}
