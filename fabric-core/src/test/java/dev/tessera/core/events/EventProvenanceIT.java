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
package dev.tessera.core.events;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tessera.core.graph.GraphMutation;
import dev.tessera.core.graph.GraphMutationOutcome;
import dev.tessera.core.graph.GraphService;
import dev.tessera.core.graph.Operation;
import dev.tessera.core.graph.SourceType;
import dev.tessera.core.support.AgePostgresContainer;
import dev.tessera.core.support.FlywayItApplication;
import dev.tessera.core.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Wave 2 — plan 01-W2-02. EVENT-03: every {@code graph_events} row carries the
 * full provenance set (D-A1) AND a correctly-shaped {@code delta} JSONB for
 * each of CREATE / UPDATE / TOMBSTONE.
 */
@SpringBootTest(classes = FlywayItApplication.class)
@ActiveProfiles("flyway-it")
@Testcontainers
class EventProvenanceIT {

    @Container
    static final PostgreSQLContainer<?> PG = AgePostgresContainer.create();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
    }

    @Autowired
    GraphService graphService;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void extraction_mutation_persists_every_provenance_field() {
        TenantContext ctx = TenantContext.of(UUID.randomUUID());
        graphService.apply(GraphMutation.builder()
                .tenantContext(ctx)
                .operation(Operation.CREATE)
                .type("Person")
                .payload(Map.of("name", "Eve"))
                .sourceType(SourceType.EXTRACTION)
                .sourceId("obsidian-doc-1")
                .sourceSystem("obsidian")
                .confidence(new BigDecimal("0.870"))
                .extractorVersion("test-v1")
                .llmModelId("claude-3")
                .originConnectorId("obsidian-1")
                .originChangeId("chunk-42")
                .build());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT source_type, source_id, source_system, confidence, extractor_version, llm_model_id,"
                        + " origin_connector_id, origin_change_id"
                        + " FROM graph_events WHERE model_id = ?::uuid",
                ctx.modelId().toString());

        assertThat(row.get("source_type")).isEqualTo("EXTRACTION");
        assertThat(row.get("source_id")).isEqualTo("obsidian-doc-1");
        assertThat(row.get("source_system")).isEqualTo("obsidian");
        assertThat(((BigDecimal) row.get("confidence")).compareTo(new BigDecimal("0.870")))
                .isZero();
        assertThat(row.get("extractor_version")).isEqualTo("test-v1");
        assertThat(row.get("llm_model_id")).isEqualTo("claude-3");
        assertThat(row.get("origin_connector_id")).isEqualTo("obsidian-1");
        assertThat(row.get("origin_change_id")).isEqualTo("chunk-42");
    }

    @Test
    void delta_is_full_payload_on_create_only_changed_field_on_update_and_tombstone_marker_on_tombstone() {
        TenantContext ctx = TenantContext.of(UUID.randomUUID());

        // CREATE
        GraphMutationOutcome.Committed created = (GraphMutationOutcome.Committed)
                graphService.apply(build(ctx, Operation.CREATE, null, Map.of("name", "Alice", "age", 30), "create"));

        String createDelta = jdbc.queryForObject(
                "SELECT delta::text FROM graph_events WHERE model_id = ?::uuid AND sequence_nr = ?",
                String.class,
                ctx.modelId().toString(),
                created.sequenceNr());
        // Full payload — both keys present
        assertThat(createDelta).contains("\"name\"").contains("\"Alice\"");
        assertThat(createDelta).contains("\"age\"");

        // UPDATE — change only `name`
        GraphMutationOutcome.Committed updated = (GraphMutationOutcome.Committed)
                graphService.apply(build(ctx, Operation.UPDATE, created.nodeUuid(), Map.of("name", "Bob"), "update"));

        String updateDelta = jdbc.queryForObject(
                "SELECT delta::text FROM graph_events WHERE model_id = ?::uuid AND sequence_nr = ?",
                String.class,
                ctx.modelId().toString(),
                updated.sequenceNr());
        assertThat(updateDelta).contains("\"name\"").contains("\"Bob\"");
        assertThat(updateDelta).doesNotContain("\"age\"");

        // TOMBSTONE
        GraphMutationOutcome.Committed tomb = (GraphMutationOutcome.Committed)
                graphService.apply(build(ctx, Operation.TOMBSTONE, created.nodeUuid(), Map.of(), "tomb"));

        String tombDelta = jdbc.queryForObject(
                "SELECT delta::text FROM graph_events WHERE model_id = ?::uuid AND sequence_nr = ?",
                String.class,
                ctx.modelId().toString(),
                tomb.sequenceNr());
        assertThat(tombDelta).contains("_tombstoned").contains("true");

        // Sanity: 3 events total for this tenant
        List<Long> seqs = jdbc.queryForList(
                "SELECT sequence_nr FROM graph_events WHERE model_id = ?::uuid ORDER BY sequence_nr",
                Long.class,
                ctx.modelId().toString());
        assertThat(seqs).hasSize(3);
    }

    private GraphMutation build(TenantContext ctx, Operation op, UUID target, Map<String, Object> payload, String tag) {
        return GraphMutation.builder()
                .tenantContext(ctx)
                .operation(op)
                .type("Person")
                .targetNodeUuid(target)
                .payload(payload)
                .sourceType(SourceType.STRUCTURED)
                .sourceId("prov-" + tag)
                .sourceSystem("test")
                .confidence(BigDecimal.ONE)
                .originConnectorId("prov-conn")
                .originChangeId("prov-chg-" + tag)
                .build();
    }
}
