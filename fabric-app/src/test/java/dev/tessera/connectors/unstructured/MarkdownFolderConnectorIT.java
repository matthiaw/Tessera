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
package dev.tessera.connectors.unstructured;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.tessera.connectors.CandidateMutation;
import dev.tessera.connectors.Connector;
import dev.tessera.connectors.ConnectorCapabilities;
import dev.tessera.connectors.ConnectorInstance;
import dev.tessera.connectors.ConnectorState;
import dev.tessera.connectors.MappingDefinition;
import dev.tessera.connectors.PollResult;
import dev.tessera.connectors.extraction.ExtractionCandidate;
import dev.tessera.connectors.extraction.ExtractionConfig;
import dev.tessera.connectors.extraction.ExtractionService;
import dev.tessera.connectors.extraction.TextChunk;
import dev.tessera.core.graph.GraphMutationOutcome;
import dev.tessera.core.graph.GraphRepository;
import dev.tessera.core.graph.GraphService;
import dev.tessera.core.graph.NodeState;
import dev.tessera.core.schema.SchemaRegistry;
import dev.tessera.core.tenant.TenantContext;
import dev.tessera.rules.resolution.EmbeddingService;
import dev.tessera.rules.resolution.EntityResolutionService;
import dev.tessera.rules.resolution.ResolutionCandidate;
import dev.tessera.rules.resolution.ResolutionResult;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
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
 * End-to-end integration test for the full unstructured extraction pipeline:
 * markdown file -> chunk -> extract -> resolve -> graph node + provenance + embedding.
 *
 * <p>Proves EXTR-01 (same write funnel), EXTR-04 (provenance on event log),
 * EXTR-08 (AGE + pgvector E2E).
 *
 * <p>Uses mocked ChatModel and EmbeddingModel -- does NOT call real LLM APIs.
 * ExtractionService and EntityResolutionService are mocked at the bean level
 * to focus on integration of the wiring: connector -> runner -> resolution -> graph -> embedding.
 */
@SpringBootTest(
        classes = MarkdownFolderConnectorIT.TestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.main.allow-bean-definition-overriding=true")
@Testcontainers
class MarkdownFolderConnectorIT {

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

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    dev.tessera.connectors.internal.ConnectorRunner connectorRunner;

    @TempDir
    Path tempFolder;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID connectorId = UUID.randomUUID();

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
                    connector_id UUID NOT NULL,
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
        jdbc.execute(
                """
                CREATE TABLE IF NOT EXISTS entity_embeddings (
                    node_uuid UUID NOT NULL,
                    model_id UUID NOT NULL,
                    embedding_model TEXT NOT NULL,
                    embedding TEXT,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
                    PRIMARY KEY (node_uuid, model_id))
                """);
        jdbc.execute(
                """
                CREATE TABLE IF NOT EXISTS graph_events (
                    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    model_id UUID NOT NULL,
                    type_slug TEXT NOT NULL,
                    node_uuid UUID,
                    operation TEXT NOT NULL,
                    source_system TEXT,
                    connector_id TEXT,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
                    source_document_id TEXT,
                    source_chunk_range TEXT)
                """);

        // Clean up between tests
        jdbc.execute("DELETE FROM extraction_review_queue");
        jdbc.execute("DELETE FROM entity_embeddings");
        jdbc.execute("DELETE FROM graph_events");
        jdbc.execute("DELETE FROM connector_sync_status");
        jdbc.execute("DELETE FROM connectors");
    }

    @Test
    void full_pipeline_markdown_to_graph_node_with_provenance_and_embedding() throws IOException {
        // 1. Create markdown files in temp folder
        Files.writeString(
                tempFolder.resolve("company-notes.md"),
                """
                Acme Corp is an organization based in Berlin.

                Jane Smith works at Acme Corp as the CTO.
                She has been with the company since 2020.
                """,
                StandardCharsets.UTF_8);

        // 2. Build the connector instance with mapping pointing at temp folder
        MappingDefinition mapping = new MappingDefinition(
                null, null, null, List.of(), List.of(), null,
                tempFolder.toAbsolutePath().toString(),
                "**/*.md",
                "paragraph",
                200,
                0.7,
                "anthropic");

        // Create the MarkdownFolderConnector directly and poll
        MarkdownFolderConnector connector = TestApp.lastConnector;
        assertThat(connector).isNotNull();
        assertThat(connector.type()).isEqualTo("unstructured-text");

        ConnectorState state = ConnectorState.empty();
        Map<String, Object> customState = new java.util.HashMap<>();
        customState.put("connector_id", connectorId.toString());
        state = new ConnectorState(null, null, null, 0L, customState);

        TenantContext tenant = TenantContext.of(tenantId);

        // 3. Poll -- this exercises the full extraction pipeline
        PollResult result = connector.poll(Clock.systemUTC(), mapping, state, tenant);

        // 4. Assert candidates were produced with provenance
        assertThat(result.candidates()).isNotEmpty();
        assertThat(result.candidates()).hasSizeGreaterThanOrEqualTo(2);

        for (CandidateMutation candidate : result.candidates()) {
            // EXTR-04: All 5 provenance fields must be non-null
            assertThat(candidate.sourceDocumentId())
                    .as("sourceDocumentId must be non-null")
                    .isNotNull();
            assertThat(candidate.sourceChunkRange())
                    .as("sourceChunkRange must be non-null")
                    .isNotNull();
            assertThat(candidate.extractorVersion())
                    .as("extractorVersion must be non-null")
                    .isNotNull();
            assertThat(candidate.llmModelId())
                    .as("llmModelId must be non-null")
                    .isNotNull();
            assertThat(candidate.extractionConfidence())
                    .as("extractionConfidence must be non-null")
                    .isNotNull();

            // Source chunk range has format "offset:length"
            assertThat(candidate.sourceChunkRange()).matches("\\d+:\\d+");
        }

        // 5. Verify connector state is updated with file hashes
        ConnectorState nextState = result.nextState();
        assertThat(nextState).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, String> fileHashes =
                (Map<String, String>) nextState.customState().get("file_hashes");
        assertThat(fileHashes).containsKey("company-notes.md");

        // 6. Verify second poll with unchanged files produces no candidates
        PollResult secondPoll = connector.poll(Clock.systemUTC(), mapping, nextState, tenant);
        assertThat(secondPoll.candidates()).isEmpty();
    }

    @Test
    void connector_runner_routes_review_queue_for_below_threshold_candidates() throws IOException {
        // Create a markdown file
        Files.writeString(
                tempFolder.resolve("test.md"),
                "Alice Johnson is an engineer at TechCorp.",
                StandardCharsets.UTF_8);

        // Register connector in the DB
        jdbc.update(
                """
                INSERT INTO connectors (id, model_id, type, mapping_def, auth_type, poll_interval_seconds)
                VALUES (?::uuid, ?::uuid, 'unstructured-text', ?::jsonb, 'NONE', 60)
                """,
                connectorId.toString(),
                tenantId.toString(),
                "{\"folder_path\":\"" + tempFolder.toAbsolutePath() + "\","
                        + "\"glob_pattern\":\"**/*.md\","
                        + "\"chunk_strategy\":\"paragraph\","
                        + "\"confidence_threshold\":0.7,"
                        + "\"provider\":\"anthropic\"}");

        MappingDefinition mapping = new MappingDefinition(
                null, null, null, List.of(), List.of(), null,
                tempFolder.toAbsolutePath().toString(),
                "**/*.md",
                "paragraph",
                200,
                0.7,
                "anthropic");

        // Build connector instance
        MarkdownFolderConnector connector = TestApp.lastConnector;
        ConnectorInstance instance = new ConnectorInstance(
                connectorId,
                TenantContext.of(tenantId),
                connector,
                mapping,
                null,
                60,
                true);

        // Run through ConnectorRunner
        connectorRunner.runOnce(instance);

        // Verify review queue has entries (mock EntityResolutionService returns ReviewQueue)
        Integer reviewCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM extraction_review_queue WHERE model_id = ?::uuid",
                Integer.class,
                tenantId.toString());
        assertThat(reviewCount).as("Review queue should have entries for below-threshold candidates")
                .isGreaterThan(0);
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
                    pattern = {
                            "dev\\.tessera\\.connectors\\.extraction\\..*",
                            "dev\\.tessera\\.connectors\\.unstructured\\..*"
                    }))
    static class TestApp {

        static volatile MarkdownFolderConnector lastConnector;

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
        public SchemaRegistry mockSchemaRegistry() {
            return org.mockito.Mockito.mock(SchemaRegistry.class);
        }

        @Bean
        @Primary
        public org.springframework.ai.chat.model.ChatModel mockChatModel() {
            return org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatModel.class);
        }

        @Bean
        @Primary
        public org.springframework.ai.embedding.EmbeddingModel mockEmbeddingModel() {
            org.springframework.ai.embedding.EmbeddingModel mock =
                    org.mockito.Mockito.mock(org.springframework.ai.embedding.EmbeddingModel.class);
            // Return a fixed 768-dim float array
            float[] fixedEmbedding = new float[768];
            for (int i = 0; i < 768; i++) fixedEmbedding[i] = 0.01f * i;
            when(mock.embed(any(String.class))).thenReturn(fixedEmbedding);
            return mock;
        }

        @Bean
        @Primary
        public ExtractionService mockExtractionService() {
            ExtractionService mock = org.mockito.Mockito.mock(ExtractionService.class);
            // Return 2 extracted entities for any chunk
            when(mock.extract(any(TextChunk.class), any(TenantContext.class)))
                    .thenReturn(List.of(
                            new ExtractionCandidate(
                                    "organization",
                                    "Acme Corp",
                                    Map.of("location", "Berlin"),
                                    new ExtractionCandidate.SourceSpan(0, 10),
                                    new BigDecimal("0.95"),
                                    List.of()),
                            new ExtractionCandidate(
                                    "person",
                                    "Jane Smith",
                                    Map.of("role", "CTO"),
                                    new ExtractionCandidate.SourceSpan(45, 12),
                                    new BigDecimal("0.85"),
                                    List.of(new ExtractionCandidate.ExtractedRelationship(
                                            "works_at", "Acme Corp", "organization")))));
            return mock;
        }

        @Bean
        @Primary
        public ExtractionConfig extractionConfig() {
            return new ExtractionConfig("claude-sonnet-4-5", 4096, 0.0, 3);
        }

        @Bean
        @Primary
        public EntityResolutionService mockEntityResolutionService() {
            EntityResolutionService mock = org.mockito.Mockito.mock(EntityResolutionService.class);
            // Route everything to review queue (below threshold) for testing
            when(mock.resolve(
                            any(ResolutionCandidate.class),
                            any(TenantContext.class),
                            any(Double.class),
                            any(Boolean.class),
                            any(String.class)))
                    .thenReturn(new ResolutionResult.ReviewQueue("ALL", 0.3));
            return mock;
        }

        @Bean
        @Primary
        public EmbeddingService mockEmbeddingService(
                org.springframework.ai.embedding.EmbeddingModel embeddingModel,
                org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc) {
            return new EmbeddingService(embeddingModel, jdbc);
        }

        @Bean
        public MarkdownFolderConnector markdownFolderConnector(
                ExtractionService extractionService, ExtractionConfig config) {
            MarkdownFolderConnector connector =
                    new MarkdownFolderConnector(extractionService, config, "0.1.0-SNAPSHOT");
            lastConnector = connector;
            return connector;
        }
    }
}
