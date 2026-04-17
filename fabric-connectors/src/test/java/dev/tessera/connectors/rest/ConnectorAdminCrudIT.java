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
package dev.tessera.connectors.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

import dev.tessera.core.graph.GraphMutationOutcome;
import dev.tessera.core.graph.GraphRepository;
import dev.tessera.core.graph.GraphService;
import dev.tessera.core.graph.NodeState;
import dev.tessera.core.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 02-W3-02: Full CRUD lifecycle for connector admin endpoints.
 */
@SpringBootTest(
        classes = ConnectorAdminCrudIT.TestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ConnectorAdminCrudIT {

    private static final String AGE_IMAGE =
            "apache/age@sha256:16aa423d20a31aed36a3313244bf7aa00731325862f20ed584510e381f2feaed";

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
                    DockerImageName.parse(AGE_IMAGE).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("tessera")
            .withUsername("tessera")
            .withPassword("tessera");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
    }

    @LocalServerPort
    int port;

    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();

    @org.springframework.beans.factory.annotation.Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        // Create tables (normally via Flyway)
        jdbc.execute(
                """
                CREATE TABLE IF NOT EXISTS shedlock (
                    name VARCHAR(64) NOT NULL, lock_until TIMESTAMP NOT NULL,
                    locked_at TIMESTAMP NOT NULL, locked_by VARCHAR(255) NOT NULL,
                    PRIMARY KEY (name))
                """);
        jdbc.execute(
                """
                CREATE TABLE IF NOT EXISTS connectors (
                    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), model_id UUID NOT NULL,
                    type TEXT NOT NULL, mapping_def JSONB NOT NULL,
                    auth_type TEXT NOT NULL CHECK (auth_type IN ('BEARER')),
                    credentials_ref TEXT NOT NULL, poll_interval_seconds INT NOT NULL CHECK (poll_interval_seconds >= 1),
                    enabled BOOLEAN NOT NULL DEFAULT TRUE,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp())
                """);
        jdbc.execute(
                """
                CREATE TABLE IF NOT EXISTS connector_sync_status (
                    connector_id UUID PRIMARY KEY REFERENCES connectors(id) ON DELETE CASCADE,
                    model_id UUID NOT NULL, last_poll_at TIMESTAMPTZ, last_success_at TIMESTAMPTZ,
                    last_outcome TEXT, last_etag TEXT, last_modified TEXT,
                    events_processed BIGINT NOT NULL DEFAULT 0, dlq_count BIGINT NOT NULL DEFAULT 0,
                    next_poll_at TIMESTAMPTZ, state_blob JSONB)
                """);
    }

    @Test
    void full_crud_lifecycle() {
        String token = JwtTestUtil.mintAdmin(tenantA.toString());

        // CREATE
        String connectorId = given().port(port)
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .body(
                        """
                {
                    "type": "rest-poll",
                    "authType": "BEARER",
                    "credentialsRef": "secret/tessera/connectors/test/bearer_token",
                    "pollIntervalSeconds": 30,
                    "mappingDef": {
                        "sourceEntityType": "customer",
                        "targetNodeTypeSlug": "Customer",
                        "rootPath": "$.data[*]",
                        "fields": [{"target": "name", "sourcePath": "$.name", "required": false}],
                        "identityFields": ["name"],
                        "sourceUrl": "http://example.com/api"
                    }
                }
                """)
                .when()
                .post("/admin/connectors")
                .then()
                .statusCode(201)
                .body("id", notNullValue())
                .body("type", equalTo("rest-poll"))
                .extract()
                .path("id");

        // LIST
        given().port(port)
                .header("Authorization", "Bearer " + token)
                .when()
                .get("/admin/connectors")
                .then()
                .statusCode(200)
                .body("$", hasSize(1));

        // GET
        given().port(port)
                .header("Authorization", "Bearer " + token)
                .when()
                .get("/admin/connectors/" + connectorId)
                .then()
                .statusCode(200)
                .body("type", equalTo("rest-poll"));

        // UPDATE
        given().port(port)
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .body("{\"pollIntervalSeconds\": 60}")
                .when()
                .put("/admin/connectors/" + connectorId)
                .then()
                .statusCode(200)
                .body("updated", equalTo(true));

        // STATUS (should be NEVER_POLLED)
        given().port(port)
                .header("Authorization", "Bearer " + token)
                .when()
                .get("/admin/connectors/" + connectorId + "/status")
                .then()
                .statusCode(200)
                .body("last_outcome", equalTo("NEVER_POLLED"));

        // DELETE
        given().port(port)
                .header("Authorization", "Bearer " + token)
                .when()
                .delete("/admin/connectors/" + connectorId)
                .then()
                .statusCode(204);

        // LIST should be empty now
        given().port(port)
                .header("Authorization", "Bearer " + token)
                .when()
                .get("/admin/connectors")
                .then()
                .statusCode(200)
                .body("$", hasSize(0));
    }

    @Test
    void cross_tenant_isolation() {
        String tokenA = JwtTestUtil.mintAdmin(tenantA.toString());
        String tokenB = JwtTestUtil.mintAdmin(tenantB.toString());

        // Tenant A creates a connector
        String connectorId = given().port(port)
                .header("Authorization", "Bearer " + tokenA)
                .contentType("application/json")
                .body(
                        """
                {
                    "type": "rest-poll",
                    "authType": "BEARER",
                    "credentialsRef": "secret/tessera/connectors/test/bearer_token",
                    "pollIntervalSeconds": 30,
                    "mappingDef": {
                        "sourceEntityType": "customer",
                        "targetNodeTypeSlug": "Customer",
                        "rootPath": "$.data[*]",
                        "fields": [{"target": "name", "sourcePath": "$.name", "required": false}],
                        "identityFields": ["name"],
                        "sourceUrl": "http://example.com/api"
                    }
                }
                """)
                .when()
                .post("/admin/connectors")
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        // Tenant B cannot GET it -> 404
        given().port(port)
                .header("Authorization", "Bearer " + tokenB)
                .when()
                .get("/admin/connectors/" + connectorId)
                .then()
                .statusCode(404);

        // Tenant B cannot PUT it -> 404
        given().port(port)
                .header("Authorization", "Bearer " + tokenB)
                .contentType("application/json")
                .body("{\"pollIntervalSeconds\": 60}")
                .when()
                .put("/admin/connectors/" + connectorId)
                .then()
                .statusCode(404);

        // Tenant B cannot DELETE it -> 404
        given().port(port)
                .header("Authorization", "Bearer " + tokenB)
                .when()
                .delete("/admin/connectors/" + connectorId)
                .then()
                .statusCode(404);

        // Tenant B list is empty
        given().port(port)
                .header("Authorization", "Bearer " + tokenB)
                .when()
                .get("/admin/connectors")
                .then()
                .statusCode(200)
                .body("$", hasSize(0));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = FlywayAutoConfiguration.class)
    @EnableScheduling
    @ComponentScan(basePackages = {"dev.tessera.connectors"})
    static class TestApp {

        @Bean
        @Primary
        public GraphService mockGraphService() {
            return mutation -> new GraphMutationOutcome.Committed(UUID.randomUUID(), 1L, UUID.randomUUID());
        }

        @Bean
        @Primary
        public GraphRepository mockGraphRepository() {
            return new GraphRepository() {
                @Override
                public Optional<NodeState> findNode(TenantContext ctx, String typeSlug, UUID nodeUuid) {
                    return Optional.empty();
                }

                @Override
                public List<NodeState> queryAll(TenantContext ctx, String typeSlug) {
                    return List.of();
                }

                @Override
                public List<NodeState> queryAllAfter(TenantContext ctx, String typeSlug, long afterSeq, int limit) {
                    return List.of();
                }

                @Override
                public java.util.List<java.util.Map<String, Object>> executeTenantCypher(
                        TenantContext ctx, String cypher) {
                    return List.of();
                }

                @Override
                public List<NodeState> findShortestPath(TenantContext ctx, UUID fromUuid, UUID toUuid) {
                    return List.of();
                }
            };
        }

        // GenericRestPollerConnector is picked up by component scan
        // No need for a separate test connector bean
    }
}
