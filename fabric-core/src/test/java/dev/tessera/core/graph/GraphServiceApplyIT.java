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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tessera.core.support.AgePostgresContainer;
import dev.tessera.core.support.FlywayItApplication;
import dev.tessera.core.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.Collections;
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
 * CORE-01 + EVENT-02/03/04: the single-TX write funnel. Verifies:
 *
 * <ul>
 *   <li>A successful {@code apply} writes exactly one graph_events row and one
 *       graph_outbox row in the same Postgres transaction.
 *   <li>An exception thrown from inside the transaction rolls BOTH tables back.
 *   <li>{@code sequence_nr} is monotonic per tenant and uses Postgres
 *       {@code nextval} (not {@code MAX()+1}) — a dedicated sequence object
 *       exists after the first write.
 * </ul>
 */
@SpringBootTest(classes = FlywayItApplication.class)
@ActiveProfiles("flyway-it")
@Testcontainers
class GraphServiceApplyIT {

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
    void apply_writes_one_event_and_one_outbox_row_atomically() {
        TenantContext ctx = TenantContext.of(UUID.randomUUID());
        long beforeEvents = countEvents(ctx);
        long beforeOutbox = countOutbox(ctx);

        GraphMutationOutcome outcome = graphService.apply(GraphMutation.builder()
                .tenantContext(ctx)
                .operation(Operation.CREATE)
                .type("Person")
                .payload(Map.of("name", "Alice"))
                .sourceType(SourceType.STRUCTURED)
                .sourceId("src-1")
                .sourceSystem("test")
                .confidence(BigDecimal.ONE)
                .originConnectorId("conn-1")
                .originChangeId("chg-1")
                .build());

        assertThat(outcome).isInstanceOf(GraphMutationOutcome.Committed.class);
        GraphMutationOutcome.Committed committed = (GraphMutationOutcome.Committed) outcome;
        assertThat(committed.sequenceNr()).isGreaterThanOrEqualTo(1);

        assertThat(countEvents(ctx)).isEqualTo(beforeEvents + 1);
        assertThat(countOutbox(ctx)).isEqualTo(beforeOutbox + 1);
    }

    @Test
    void rollback_leaves_no_event_and_no_outbox_row() {
        TenantContext ctx = TenantContext.of(UUID.randomUUID());
        long beforeEvents = countEvents(ctx);
        long beforeOutbox = countOutbox(ctx);

        // An immutable Map.of forces IllegalArgumentException inside GraphSession
        // UPDATE path when we try to SET on an unknown target_node_uuid (no match,
        // find returns empty, throws). The exception propagates out of apply(),
        // @Transactional rolls back every side-effect.
        UUID fakeTarget = UUID.randomUUID();
        assertThatThrownBy(() -> graphService.apply(GraphMutation.builder()
                        .tenantContext(ctx)
                        .operation(Operation.UPDATE)
                        .type("Person")
                        .targetNodeUuid(fakeTarget)
                        .payload(Collections.singletonMap("name", "Bob"))
                        .sourceType(SourceType.STRUCTURED)
                        .sourceId("src-2")
                        .sourceSystem("test")
                        .confidence(BigDecimal.ONE)
                        .build()))
                .isInstanceOf(RuntimeException.class);

        assertThat(countEvents(ctx)).isEqualTo(beforeEvents);
        assertThat(countOutbox(ctx)).isEqualTo(beforeOutbox);
    }

    @Test
    void sequence_nr_is_monotonic_and_uses_per_tenant_sequence() {
        TenantContext ctx = TenantContext.of(UUID.randomUUID());

        GraphMutationOutcome.Committed a = (GraphMutationOutcome.Committed) graphService.apply(create(ctx, "A"));
        GraphMutationOutcome.Committed b = (GraphMutationOutcome.Committed) graphService.apply(create(ctx, "B"));
        GraphMutationOutcome.Committed c = (GraphMutationOutcome.Committed) graphService.apply(create(ctx, "C"));

        assertThat(b.sequenceNr()).isGreaterThan(a.sequenceNr());
        assertThat(c.sequenceNr()).isGreaterThan(b.sequenceNr());

        String seqName = "graph_events_seq_" + ctx.modelId().toString().replace("-", "");
        Long exists = jdbc.queryForObject(
                "SELECT count(*) FROM pg_class WHERE relname = ? AND relkind = 'S'", Long.class, seqName);
        assertThat(exists).isEqualTo(1L);
    }

    private GraphMutation create(TenantContext ctx, String name) {
        return GraphMutation.builder()
                .tenantContext(ctx)
                .operation(Operation.CREATE)
                .type("Person")
                .payload(Map.of("name", name))
                .sourceType(SourceType.STRUCTURED)
                .sourceId("src-" + name)
                .sourceSystem("test")
                .confidence(BigDecimal.ONE)
                .build();
    }

    private long countEvents(TenantContext ctx) {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM graph_events WHERE model_id = ?::uuid",
                Long.class,
                ctx.modelId().toString());
        return n == null ? 0 : n;
    }

    private long countOutbox(TenantContext ctx) {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM graph_outbox WHERE model_id = ?::uuid",
                Long.class,
                ctx.modelId().toString());
        return n == null ? 0 : n;
    }
}
