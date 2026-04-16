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

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * REST-06 / Decision 11 / T-02-W2-02: cross-tenant leak property test.
 * For many random (tenantA_jwt, tenantB_path) pairs, asserts every
 * combination returns 404 with an identical error body shape -- no
 * tenant-specific content in the response.
 */
@SpringBootTest(classes = ProjectionItApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("projection-it")
@Testcontainers
class CrossTenantLeakPropertyIT {

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

    /**
     * Property test: 100 random (tenantA, tenantB) pairs. For each,
     * mint a JWT for tenantA, request tenantB's path, assert 404 with
     * constant body shape.
     */
    @Test
    void cross_tenant_always_returns_404_with_identical_body() {
        for (int i = 0; i < 100; i++) {
            UUID tenantA = UUID.randomUUID();
            UUID tenantB = UUID.randomUUID();
            String typeSlug = "type-" + UUID.randomUUID().toString().substring(0, 8);

            String tokenA = JwtTestHelper.mint(tenantA.toString(), List.of("ADMIN"));

            // Request tenantB's path with tenantA's token -> must be 404
            String body = given().port(port)
                    .header("Authorization", "Bearer " + tokenA)
                    .when()
                    .get("/api/v1/" + tenantB + "/entities/" + typeSlug)
                    .then()
                    .statusCode(404)
                    .contentType("application/problem+json")
                    .extract()
                    .body()
                    .asString();

            // Body must NOT contain either tenant UUID
            assertThat(body)
                    .as("response must not leak tenantA UUID (iteration %d)", i)
                    .doesNotContain(tenantA.toString());
            assertThat(body)
                    .as("response must not leak tenantB UUID (iteration %d)", i)
                    .doesNotContain(tenantB.toString());

            // Body must contain constant fields
            assertThat(body).contains("\"title\"");
            assertThat(body).contains("\"status\"");
            assertThat(body).contains("\"detail\"");
            assertThat(body).contains("Resource not found.");

            // Must NOT have a "tenant" field (cross-tenant = no tenant context)
            assertThat(body).doesNotContain("\"tenant\"");
        }
    }

    @Test
    void request_without_jwt_returns_401() {
        UUID tenant = UUID.randomUUID();
        given().port(port)
                .when()
                .get("/api/v1/" + tenant + "/entities/anything")
                .then()
                .statusCode(401);
    }
}
