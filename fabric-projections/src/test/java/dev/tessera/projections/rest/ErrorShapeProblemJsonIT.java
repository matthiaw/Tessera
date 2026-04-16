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
import static org.hamcrest.Matchers.notNullValue;

import dev.tessera.core.schema.AddPropertySpec;
import dev.tessera.core.schema.CreateNodeTypeSpec;
import dev.tessera.core.schema.SchemaRegistry;
import dev.tessera.core.tenant.TenantContext;
import java.util.List;
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
 * REST-06 / CONTEXT Decision 8: every error response is
 * {@code application/problem+json} (RFC 7807) with correct
 * {@code status}, {@code type}, {@code title}, {@code detail} fields
 * and Tessera extensions ({@code code}).
 *
 * <p>T-02-W2-06: {@code detail} NEVER echoes caller input verbatim.
 */
@SpringBootTest(classes = ProjectionItApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("projection-it")
@Testcontainers
class ErrorShapeProblemJsonIT {

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
    private String token;

    @BeforeEach
    void setUp() {
        modelId = UUID.randomUUID();
        token = JwtTestHelper.mint(modelId.toString(), List.of("ADMIN"));
    }

    @Test
    void not_found_returns_problem_json_with_correct_shape() {
        given().port(port)
                .header("Authorization", "Bearer " + token)
                .when()
                .get("/api/v1/" + modelId + "/entities/nonexistent")
                .then()
                .statusCode(404)
                .contentType("application/problem+json")
                .body("status", equalTo(404))
                .body("title", equalTo("Not Found"))
                .body("detail", equalTo("Resource not found."))
                .body("type", notNullValue())
                .body("code", equalTo("TESSERA_NOT_FOUND"));
    }

    @Test
    void disabled_type_returns_404_problem_json() {
        TenantContext ctx = TenantContext.of(modelId);
        schemaRegistry.createNodeType(ctx, new CreateNodeTypeSpec("Hidden", "Hidden", "Hidden", "hidden type"));

        given().port(port)
                .header("Authorization", "Bearer " + token)
                .when()
                .get("/api/v1/" + modelId + "/entities/Hidden")
                .then()
                .statusCode(404)
                .contentType("application/problem+json")
                .body("code", equalTo("TESSERA_NOT_FOUND"));
    }

    @Test
    void invalid_cursor_returns_400_problem_json() {
        TenantContext ctx = TenantContext.of(modelId);
        schemaRegistry.createNodeType(ctx, new CreateNodeTypeSpec("Gadget", "Gadget", "Gadget", "gadget"));
        schemaRegistry.addProperty(ctx, "Gadget", new AddPropertySpec("name", "Name", "string", false));
        jdbc.update(
                "UPDATE schema_node_types SET rest_read_enabled = TRUE WHERE model_id = ?::uuid AND slug = ?",
                modelId.toString(),
                "Gadget");
        // Force cache invalidation
        schemaRegistry.addProperty(ctx, "Gadget", new AddPropertySpec("rank", "Rank", "integer", false));

        given().port(port)
                .header("Authorization", "Bearer " + token)
                .queryParam("cursor", "not-a-valid-cursor!!!")
                .when()
                .get("/api/v1/" + modelId + "/entities/Gadget")
                .then()
                .statusCode(400)
                .contentType("application/problem+json")
                .body("status", equalTo(400))
                .body("title", equalTo("Bad Request"))
                .body("code", equalTo("TESSERA_INVALID_CURSOR"));
    }

    @Test
    void cross_tenant_returns_404_not_403() {
        UUID otherTenant = UUID.randomUUID();

        given().port(port)
                .header("Authorization", "Bearer " + token)
                .when()
                .get("/api/v1/" + otherTenant + "/entities/anything")
                .then()
                .statusCode(404)
                .contentType("application/problem+json")
                .body("code", equalTo("TESSERA_NOT_FOUND"))
                .body("detail", equalTo("Resource not found."));
    }

    @Test
    void detail_never_echoes_caller_input() {
        // Send a random marker string as a type slug
        String markerInput = "MARKER-" + UUID.randomUUID();

        String body = given().port(port)
                .header("Authorization", "Bearer " + token)
                .when()
                .get("/api/v1/" + modelId + "/entities/" + markerInput)
                .then()
                .statusCode(404)
                .contentType("application/problem+json")
                .extract()
                .body()
                .asString();

        // The marker must NOT appear in the response detail
        org.assertj.core.api.Assertions.assertThat(body)
                .as("detail must not echo caller input verbatim (XSS hygiene)")
                .doesNotContain(markerInput);
    }
}
