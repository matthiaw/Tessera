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
package dev.tessera.connectors.review;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

import dev.tessera.connectors.rest.JwtTestUtil;
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
 * Integration test for {@link ExtractionReviewController}.
 * Verifies CRUD operations, tenant isolation, and GraphService integration.
 */
@SpringBootTest(
        classes = ExtractionReviewControllerIT.TestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.main.allow-bean-definition-overriding=true")
@Testcontainers
class ExtractionReviewControllerIT {

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
        // Create required tables
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
                    auth_type TEXT NOT NULL,
                    credentials_ref TEXT, poll_interval_seconds INT NOT NULL CHECK (poll_interval_seconds >= 1),
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
        jdbc.execute(
                """
                CREATE TABLE IF NOT EXISTS extraction_review_queue (
                    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    model_id UUID NOT NULL,
                    connector_id UUID NOT NULL REFERENCES connectors(id),
                    source_document_id TEXT NOT NULL,
                    source_chunk_range TEXT NOT NULL,
                    type_slug TEXT NOT NULL,
                    extracted_properties JSONB NOT NULL,
                    extraction_confidence NUMERIC(4,3) NOT NULL,
                    extractor_version TEXT NOT NULL,
                    llm_model_id TEXT NOT NULL,
                    resolution_tier TEXT,
                    resolution_score NUMERIC(6,5),
                    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
                    decided_at TIMESTAMPTZ,
                    decision TEXT,
                    decision_reason TEXT,
                    operator_target_node_uuid UUID)
                """);

        // Clean up between tests
        jdbc.execute("DELETE FROM extraction_review_queue");
        jdbc.execute("DELETE FROM connector_sync_status");
        jdbc.execute("DELETE FROM connectors");
    }

    private UUID seedConnector(UUID tenantId) {
        UUID connectorId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO connectors (id, model_id, type, mapping_def, auth_type, credentials_ref, poll_interval_seconds)
                VALUES (?::uuid, ?::uuid, 'unstructured-text', '{}', 'NONE', null, 60)
                """,
                connectorId.toString(),
                tenantId.toString());
        return connectorId;
    }

    private UUID seedReviewEntry(UUID tenantId, UUID connectorId) {
        UUID entryId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO extraction_review_queue
                    (id, model_id, connector_id, source_document_id, source_chunk_range,
                     type_slug, extracted_properties, extraction_confidence, extractor_version,
                     llm_model_id, resolution_tier, resolution_score)
                VALUES (?::uuid, ?::uuid, ?::uuid, 'doc-sha256-abc', '0:512', 'Person',
                    '{"name": "Jane Smith", "role": "Engineer"}', 0.650, '0.1.0',
                    'claude-sonnet-4-5', 'ALL', 0.42000)
                """,
                entryId.toString(),
                tenantId.toString(),
                connectorId.toString());
        return entryId;
    }

    @Test
    void list_pending_returns_entries_for_tenant() {
        UUID connA = seedConnector(tenantA);
        seedReviewEntry(tenantA, connA);
        seedReviewEntry(tenantA, connA);

        String token = JwtTestUtil.mintAdmin(tenantA.toString());

        given().port(port)
                .header("Authorization", "Bearer " + token)
                .when()
                .get("/admin/extraction/review")
                .then()
                .statusCode(200)
                .body("$", hasSize(2));
    }

    @Test
    void accept_marks_entry_accepted_and_calls_graph_service() {
        UUID connA = seedConnector(tenantA);
        UUID entryId = seedReviewEntry(tenantA, connA);

        String token = JwtTestUtil.mintAdmin(tenantA.toString());

        given().port(port)
                .header("Authorization", "Bearer " + token)
                .when()
                .post("/admin/extraction/review/" + entryId + "/accept")
                .then()
                .statusCode(200)
                .body("status", equalTo("ACCEPTED"))
                .body("nodeUuid", notNullValue());

        // Verify entry is now decided (no longer pending)
        given().port(port)
                .header("Authorization", "Bearer " + token)
                .when()
                .get("/admin/extraction/review")
                .then()
                .statusCode(200)
                .body("$", hasSize(0));
    }

    @Test
    void reject_marks_entry_rejected_with_reason() {
        UUID connA = seedConnector(tenantA);
        UUID entryId = seedReviewEntry(tenantA, connA);

        String token = JwtTestUtil.mintAdmin(tenantA.toString());

        given().port(port)
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .body("{\"reason\": \"Duplicate entity\"}")
                .when()
                .post("/admin/extraction/review/" + entryId + "/reject")
                .then()
                .statusCode(200)
                .body("status", equalTo("REJECTED"));

        // Verify entry is no longer pending
        given().port(port)
                .header("Authorization", "Bearer " + token)
                .when()
                .get("/admin/extraction/review")
                .then()
                .statusCode(200)
                .body("$", hasSize(0));
    }

    @Test
    void override_merges_into_target_node_and_calls_graph_service() {
        UUID connA = seedConnector(tenantA);
        UUID entryId = seedReviewEntry(tenantA, connA);
        UUID targetNode = UUID.randomUUID();

        String token = JwtTestUtil.mintAdmin(tenantA.toString());

        given().port(port)
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .body("{\"targetNodeUuid\": \"" + targetNode + "\"}")
                .when()
                .post("/admin/extraction/review/" + entryId + "/override")
                .then()
                .statusCode(200)
                .body("status", equalTo("OVERRIDDEN"))
                .body("nodeUuid", notNullValue());
    }

    @Test
    void cross_tenant_access_returns_404() {
        UUID connA = seedConnector(tenantA);
        UUID entryId = seedReviewEntry(tenantA, connA);

        // Tenant B tries to access Tenant A's entry
        String tokenB = JwtTestUtil.mintAdmin(tenantB.toString());

        // GET list returns empty (not Tenant B's entries)
        given().port(port)
                .header("Authorization", "Bearer " + tokenB)
                .when()
                .get("/admin/extraction/review")
                .then()
                .statusCode(200)
                .body("$", hasSize(0));

        // POST accept returns 404
        given().port(port)
                .header("Authorization", "Bearer " + tokenB)
                .when()
                .post("/admin/extraction/review/" + entryId + "/accept")
                .then()
                .statusCode(404);

        // POST reject returns 404
        given().port(port)
                .header("Authorization", "Bearer " + tokenB)
                .contentType("application/json")
                .body("{\"reason\": \"test\"}")
                .when()
                .post("/admin/extraction/review/" + entryId + "/reject")
                .then()
                .statusCode(404);

        // POST override returns 404
        given().port(port)
                .header("Authorization", "Bearer " + tokenB)
                .contentType("application/json")
                .body("{\"targetNodeUuid\": \"" + UUID.randomUUID() + "\"}")
                .when()
                .post("/admin/extraction/review/" + entryId + "/override")
                .then()
                .statusCode(404);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(
            exclude = FlywayAutoConfiguration.class,
            excludeName = {
                    "org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatAutoConfiguration",
                    "org.springframework.ai.model.ollama.autoconfigure.OllamaChatAutoConfiguration",
                    "org.springframework.ai.model.ollama.autoconfigure.OllamaEmbeddingAutoConfiguration"
            })
    @EnableScheduling
    @ComponentScan(
            basePackages = {"dev.tessera.connectors"},
            excludeFilters = @ComponentScan.Filter(
                    type = org.springframework.context.annotation.FilterType.REGEX,
                    pattern = "dev\\.tessera\\.connectors\\.extraction\\..*"))
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
            };
        }

        @Bean
        @Primary
        public org.springframework.ai.chat.model.ChatModel mockChatModel() {
            return org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatModel.class);
        }

        @Bean
        @Primary
        public org.springframework.ai.embedding.EmbeddingModel mockEmbeddingModel() {
            return org.mockito.Mockito.mock(org.springframework.ai.embedding.EmbeddingModel.class);
        }

        @Bean
        @Primary
        public dev.tessera.core.schema.SchemaRegistry mockSchemaRegistry() {
            return org.mockito.Mockito.mock(dev.tessera.core.schema.SchemaRegistry.class);
        }
    }
}
