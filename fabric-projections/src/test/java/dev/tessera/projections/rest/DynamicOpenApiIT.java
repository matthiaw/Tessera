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
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;

import dev.tessera.core.schema.AddPropertySpec;
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
 * REST-05 definitive gate: proves that flipping {@code rest_read_enabled}
 * on a node type makes it appear (or disappear) in {@code /v3/api-docs}
 * without an application restart.
 *
 * <p>Flow:
 * <ol>
 *   <li>Declare type with {@code rest_read_enabled=false}, hit api-docs,
 *       assert path absent.</li>
 *   <li>Flip {@code rest_read_enabled=true}, invalidate cache via a schema
 *       change, hit api-docs again, assert path present with CRUD ops.</li>
 *   <li>Assert property descriptors appear as schema properties.</li>
 * </ol>
 */
@SpringBootTest(classes = ProjectionItApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("projection-it")
@Testcontainers
class DynamicOpenApiIT {

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
    private String typeSlug;

    @BeforeEach
    void setUp() {
        modelId = UUID.randomUUID();
        typeSlug = "Alpha";
    }

    @Test
    void openapi_reflects_exposure_flag_flip_without_restart() {
        TenantContext ctx = TenantContext.of(modelId);

        // 1. Declare a type with rest_read_enabled=false (default).
        schemaRegistry.createNodeType(ctx, new CreateNodeTypeSpec(typeSlug, "Alpha Type", "Alpha", "An alpha type"));

        // Add a property so we can assert it shows up in the schema.
        schemaRegistry.addProperty(ctx, typeSlug, new AddPropertySpec("email", "Email", "STRING", false));

        String pathKey = "/api/v1/" + modelId + "/entities/" + typeSlug;

        // Hit api-docs: path should be absent.
        given().port(port)
                .when()
                .get("/v3/api-docs/entities")
                .then()
                .statusCode(200)
                .body("paths", not(hasKey(pathKey)));

        // 2. Flip rest_read_enabled=true via JDBC + invalidate cache.
        jdbc.update(
                "UPDATE schema_node_types SET rest_read_enabled = TRUE WHERE model_id = ?::uuid AND slug = ?",
                modelId.toString(),
                typeSlug);

        // Force cache invalidation by bumping schema version (addProperty does this).
        schemaRegistry.addProperty(ctx, typeSlug, new AddPropertySpec("name", "Name", "STRING", false));

        // Hit api-docs again: path should now be present.
        given().port(port)
                .when()
                .get("/v3/api-docs/entities")
                .then()
                .statusCode(200)
                .body("paths", hasKey(pathKey))
                // Verify CRUD operations exist on the collection path
                .body("paths.'" + pathKey + "'", hasKey("get"))
                .body("paths.'" + pathKey + "'", hasKey("post"))
                // Verify single-resource path exists with GET/PUT/DELETE
                .body("paths", hasKey(pathKey + "/{id}"))
                .body("paths.'" + pathKey + "/{id}'", hasKey("get"))
                .body("paths.'" + pathKey + "/{id}'", hasKey("put"))
                .body("paths.'" + pathKey + "/{id}'", hasKey("delete"));

        // 3. Verify property descriptors appear in schema.
        String schemaKey = modelId + "_" + typeSlug + "Entity";
        given().port(port)
                .when()
                .get("/v3/api-docs/entities")
                .then()
                .statusCode(200)
                .body("components.schemas.'" + schemaKey + "'.properties", hasKey("email"))
                .body("components.schemas.'" + schemaKey + "'.properties", hasKey("name"))
                .body("components.schemas.'" + schemaKey + "'.properties", hasKey("uuid"));

        // 4. Flip back to false: path should disappear.
        jdbc.update(
                "UPDATE schema_node_types SET rest_read_enabled = FALSE WHERE model_id = ?::uuid AND slug = ?",
                modelId.toString(),
                typeSlug);
        // Invalidate cache
        schemaRegistry.addProperty(ctx, typeSlug, new AddPropertySpec("age", "Age", "INTEGER", false));

        given().port(port)
                .when()
                .get("/v3/api-docs/entities")
                .then()
                .statusCode(200)
                .body("paths", not(hasKey(pathKey)));
    }
}
