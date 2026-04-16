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

import dev.tessera.core.schema.AddPropertySpec;
import dev.tessera.core.schema.CreateNodeTypeSpec;
import dev.tessera.core.schema.SchemaRegistry;
import dev.tessera.core.tenant.TenantContext;
import dev.tessera.projections.rest.security.RotatableJwtDecoder;
import java.util.Base64;
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
 * SEC-02: JWT key rotation test. Mints a JWT with key A, calls a
 * protected endpoint (200), rotates to key B via
 * {@link RotatableJwtDecoder#rotateKey}, then verifies old JWT is
 * rejected (401) and a new JWT signed with key B is accepted (200).
 *
 * <p>Vault integration (VaultContainer) is deferred to W3
 * {@code VaultAppRoleAuthIT}. This test validates the
 * {@code RotatableJwtDecoder} rotation mechanism directly.
 */
@SpringBootTest(classes = ProjectionItApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("projection-it")
@Testcontainers
class JwtRotationIT {

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

    /** Key A = the standard test key from application-projection-it.yml */
    private static final String KEY_A = JwtTestHelper.TEST_SIGNING_KEY;

    /** Key B = a different 32-byte key for rotation */
    private static final String KEY_B =
            Base64.getEncoder().encodeToString("rotation-key-b-1234567890abcdefg".getBytes());

    @LocalServerPort
    int port;

    @Autowired
    RotatableJwtDecoder rotatableJwtDecoder;

    @Autowired
    SchemaRegistry schemaRegistry;

    @Autowired
    JdbcTemplate jdbc;

    private UUID modelId;

    @BeforeEach
    void setUp() {
        modelId = UUID.randomUUID();
        TenantContext ctx = TenantContext.of(modelId);

        // Create an exposed type so we have a valid endpoint to test against.
        schemaRegistry.createNodeType(ctx, new CreateNodeTypeSpec("RotTest", "RotTest", "RotTest", "rotation test"));
        schemaRegistry.addProperty(ctx, "RotTest", new AddPropertySpec("name", "Name", "string", false));
        jdbc.update(
                "UPDATE schema_node_types SET rest_read_enabled = TRUE WHERE model_id = ?::uuid AND slug = ?",
                modelId.toString(),
                "RotTest");
        // Force cache invalidation
        schemaRegistry.addProperty(ctx, "RotTest", new AddPropertySpec("tag", "Tag", "string", false));

        // Ensure decoder is on key A at the start of each test
        rotatableJwtDecoder.rotateKey(KEY_A);
    }

    @Test
    void jwt_signed_with_key_a_works_then_rotation_invalidates_old_accepts_new() {
        String tokenA = JwtTestHelper.mintWithKey(KEY_A, modelId.toString(), List.of("ADMIN"));

        // Token A should work with key A
        given().port(port)
                .header("Authorization", "Bearer " + tokenA)
                .when()
                .get("/api/v1/" + modelId + "/entities/RotTest")
                .then()
                .statusCode(200);

        // Rotate to key B
        rotatableJwtDecoder.rotateKey(KEY_B);

        // Token A (signed with key A) should now be rejected
        given().port(port)
                .header("Authorization", "Bearer " + tokenA)
                .when()
                .get("/api/v1/" + modelId + "/entities/RotTest")
                .then()
                .statusCode(401);

        // Token B (signed with key B) should work
        String tokenB = JwtTestHelper.mintWithKey(KEY_B, modelId.toString(), List.of("ADMIN"));
        given().port(port)
                .header("Authorization", "Bearer " + tokenB)
                .when()
                .get("/api/v1/" + modelId + "/entities/RotTest")
                .then()
                .statusCode(200);
    }
}
