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
package dev.tessera.core.graph;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tessera.core.support.AgePostgresContainer;
import dev.tessera.core.support.FlywayItApplication;
import dev.tessera.core.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.ArrayList;
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
 * 02-W1-01: asserts CONTEXT Decision 12 — every node created or updated
 * through {@code GraphService.apply} carries a monotonic BIGINT {@code _seq}
 * property allocated from the same per-tenant SEQUENCE as the event row, and
 * the same value is visible on {@code graph_events.sequence_nr}.
 */
@SpringBootTest(classes = FlywayItApplication.class)
@ActiveProfiles("flyway-it")
@Testcontainers
class NodeSequencePropertyIT {

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
    GraphRepository graphRepository;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void every_created_node_carries_monotonic_seq_within_one_tenant() {
        TenantContext ctx = TenantContext.of(UUID.randomUUID());

        int n = 25;
        List<UUID> created = new ArrayList<>();
        List<Long> returnedSeqs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            GraphMutationOutcome outcome = graphService.apply(GraphMutation.builder()
                    .tenantContext(ctx)
                    .operation(Operation.CREATE)
                    .type("Person")
                    .payload(Map.of("name", "person-" + i))
                    .sourceType(SourceType.STRUCTURED)
                    .sourceId("src-" + i)
                    .sourceSystem("test")
                    .confidence(BigDecimal.ONE)
                    .build());
            GraphMutationOutcome.Committed committed = (GraphMutationOutcome.Committed) outcome;
            created.add(committed.nodeUuid());
            returnedSeqs.add(committed.sequenceNr());
        }

        // Read back via repository and assert _seq is present and strictly increasing.
        List<Long> readSeqs = new ArrayList<>();
        for (UUID uuid : created) {
            NodeState node = graphRepository.findNode(ctx, "Person", uuid).orElseThrow();
            assertThat(node.properties()).containsKey("_seq");
            Object rawSeq = node.properties().get("_seq");
            long seq = ((Number) rawSeq).longValue();
            readSeqs.add(seq);
        }
        // Strictly increasing.
        for (int i = 1; i < readSeqs.size(); i++) {
            assertThat(readSeqs.get(i))
                    .as("node %d seq must be > node %d seq", i, i - 1)
                    .isGreaterThan(readSeqs.get(i - 1));
        }

        // Load-bearing invariant: node _seq must match the GraphMutationOutcome
        // sequenceNr returned from apply — one allocation per mutation threaded
        // through both graph_events.sequence_nr AND node._seq.
        for (int i = 0; i < n; i++) {
            assertThat(readSeqs.get(i))
                    .as("node %d _seq property must equal outcome.sequenceNr()", i)
                    .isEqualTo(returnedSeqs.get(i));
        }

        // Cross-check graph_events: sequence_nr for this tenant must equal the
        // read-back list and there must be exactly n rows (no double-append).
        List<Long> eventSeqs = jdbc.query(
                "SELECT sequence_nr FROM graph_events" + " WHERE model_id = ?::uuid" + " ORDER BY sequence_nr ASC",
                (rs, i) -> rs.getLong(1),
                ctx.modelId().toString());
        assertThat(eventSeqs).hasSize(n).containsExactlyElementsOf(returnedSeqs);
    }

    @Test
    void cross_tenant_sequences_are_independently_monotonic() {
        TenantContext a = TenantContext.of(UUID.randomUUID());
        TenantContext b = TenantContext.of(UUID.randomUUID());

        // Interleave writes between A and B so a bug that shared the sequence
        // across tenants would show up as non-monotonic within one tenant.
        List<UUID> aCreated = new ArrayList<>();
        List<UUID> bCreated = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            aCreated.add(createPerson(a, "a-" + i));
            bCreated.add(createPerson(b, "b-" + i));
        }

        assertMonotonicPerTenant(a, aCreated);
        assertMonotonicPerTenant(b, bCreated);
    }

    private UUID createPerson(TenantContext ctx, String label) {
        GraphMutationOutcome outcome = graphService.apply(GraphMutation.builder()
                .tenantContext(ctx)
                .operation(Operation.CREATE)
                .type("Person")
                .payload(Map.of("name", label))
                .sourceType(SourceType.STRUCTURED)
                .sourceId(label)
                .sourceSystem("test")
                .confidence(BigDecimal.ONE)
                .build());
        return ((GraphMutationOutcome.Committed) outcome).nodeUuid();
    }

    private void assertMonotonicPerTenant(TenantContext ctx, List<UUID> uuids) {
        List<Long> seqs = new ArrayList<>();
        for (UUID uuid : uuids) {
            NodeState node = graphRepository.findNode(ctx, "Person", uuid).orElseThrow();
            Object raw = node.properties().get("_seq");
            assertThat(raw).as("node %s must carry _seq", uuid).isNotNull();
            seqs.add(((Number) raw).longValue());
        }
        for (int i = 1; i < seqs.size(); i++) {
            assertThat(seqs.get(i)).isGreaterThan(seqs.get(i - 1));
        }
    }
}
