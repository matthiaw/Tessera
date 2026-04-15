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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tessera.core.graph.GraphMutation;
import dev.tessera.core.graph.GraphMutationOutcome;
import dev.tessera.core.graph.GraphService;
import dev.tessera.core.graph.Operation;
import dev.tessera.core.graph.SourceType;
import dev.tessera.core.support.AgePostgresContainer;
import dev.tessera.core.support.FlywayItApplication;
import dev.tessera.core.tenant.TenantContext;
import java.math.BigDecimal;
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
 * Wave 2 — plan 01-W2-03. EVENT-04: {@code graph_outbox} is written in the same
 * transaction as {@code graph_events} and the Cypher mutation; if the write
 * funnel fails mid-apply both tables roll back atomically.
 *
 * <p>Success path: a committed {@code GraphService.apply} leaves exactly one
 * row in each of {@code graph_events} and {@code graph_outbox} for the tenant.
 *
 * <p>Failure path: a mutation crafted to fail inside {@code GraphService.apply}
 * (here: a TOMBSTONE against a non-existent node, which throws at Cypher
 * execution time) rolls back and leaves zero rows in both tables.
 */
@SpringBootTest(classes = FlywayItApplication.class)
@ActiveProfiles("flyway-it")
@Testcontainers
class OutboxTransactionalIT {

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
    void success_path_writes_one_event_and_one_outbox_row_in_same_tx() {
        TenantContext ctx = TenantContext.of(UUID.randomUUID());

        GraphMutationOutcome.Committed outcome =
                (GraphMutationOutcome.Committed) graphService.apply(GraphMutation.builder()
                        .tenantContext(ctx)
                        .operation(Operation.CREATE)
                        .type("Person")
                        .payload(Map.of("name", "Atomic"))
                        .sourceType(SourceType.STRUCTURED)
                        .sourceId("tx-src")
                        .sourceSystem("tx-system")
                        .confidence(BigDecimal.ONE)
                        .originConnectorId("tx-conn")
                        .originChangeId("tx-chg-1")
                        .build());

        Integer events = jdbc.queryForObject(
                "SELECT COUNT(*) FROM graph_events WHERE model_id = ?::uuid",
                Integer.class,
                ctx.modelId().toString());
        assertThat(events).isEqualTo(1);

        Integer outbox = jdbc.queryForObject(
                "SELECT COUNT(*) FROM graph_outbox WHERE model_id = ?::uuid AND event_id = ?::uuid",
                Integer.class,
                ctx.modelId().toString(),
                outcome.eventId().toString());
        assertThat(outbox).isEqualTo(1);
    }

    @Test
    void failure_path_rolls_back_both_graph_events_and_graph_outbox() {
        TenantContext ctx = TenantContext.of(UUID.randomUUID());

        // TOMBSTONE against a random node that never existed — GraphSession will
        // throw inside apply(), rolling back the transaction before EventLog or
        // Outbox commit.
        assertThatThrownBy(() -> graphService.apply(GraphMutation.builder()
                        .tenantContext(ctx)
                        .operation(Operation.TOMBSTONE)
                        .type("Person")
                        .targetNodeUuid(UUID.randomUUID())
                        .payload(Map.of())
                        .sourceType(SourceType.STRUCTURED)
                        .sourceId("bad-src")
                        .sourceSystem("tx-system")
                        .confidence(BigDecimal.ONE)
                        .originConnectorId("tx-conn")
                        .originChangeId("tx-chg-fail")
                        .build()))
                .isInstanceOf(RuntimeException.class);

        Integer events = jdbc.queryForObject(
                "SELECT COUNT(*) FROM graph_events WHERE model_id = ?::uuid",
                Integer.class,
                ctx.modelId().toString());
        assertThat(events).isZero();

        Integer outbox = jdbc.queryForObject(
                "SELECT COUNT(*) FROM graph_outbox WHERE model_id = ?::uuid",
                Integer.class,
                ctx.modelId().toString());
        assertThat(outbox).isZero();
    }
}
