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
import static org.awaitility.Awaitility.await;

import dev.tessera.core.graph.GraphMutation;
import dev.tessera.core.graph.GraphService;
import dev.tessera.core.graph.Operation;
import dev.tessera.core.graph.SourceType;
import dev.tessera.core.support.AgePostgresContainer;
import dev.tessera.core.support.FlywayItApplication;
import dev.tessera.core.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Wave 2 — plan 01-W2-03. EVENT-05: outbox poller polls {@code PENDING} rows
 * via {@code FOR UPDATE SKIP LOCKED}, publishes {@link GraphEventPublished} on
 * Spring's {@code ApplicationEventPublisher}, and marks them {@code DELIVERED}.
 *
 * <p>Also asserts the {@code routing_hints} JSONB column round-trips through
 * the poller's row mapper into the published record.
 */
@SpringBootTest(classes = {FlywayItApplication.class, OutboxPollerIT.TestListeners.class})
@ActiveProfiles("flyway-it")
@Testcontainers
class OutboxPollerIT {

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

    @Autowired
    Received received;

    @Test
    void poller_delivers_events_and_marks_rows_delivered() {
        TenantContext ctx = TenantContext.of(UUID.randomUUID());
        received.forTenant(ctx.modelId()).clear();

        List<UUID> nodeIds = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            var outcome =
                    (dev.tessera.core.graph.GraphMutationOutcome.Committed) graphService.apply(GraphMutation.builder()
                            .tenantContext(ctx)
                            .operation(Operation.CREATE)
                            .type("Person")
                            .payload(Map.of("name", "Poll-" + i))
                            .sourceType(SourceType.STRUCTURED)
                            .sourceId("poll-src-" + i)
                            .sourceSystem("poll-test")
                            .confidence(BigDecimal.ONE)
                            .originConnectorId("poll-conn")
                            .originChangeId("poll-chg-" + i)
                            .build());
            nodeIds.add(outcome.nodeUuid());
        }

        // Poller ticks every 500 ms; 3 s gives ample slack for CI.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(received.forTenant(ctx.modelId()))
                .hasSize(5));

        // Every row DELIVERED in DB.
        Integer pendingCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM graph_outbox WHERE model_id = ?::uuid AND status = 'PENDING'",
                Integer.class,
                ctx.modelId().toString());
        assertThat(pendingCount).isZero();

        Integer deliveredCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM graph_outbox WHERE model_id = ?::uuid AND status = 'DELIVERED'",
                Integer.class,
                ctx.modelId().toString());
        assertThat(deliveredCount).isEqualTo(5);

        // Aggregate ids match the nodes we wrote.
        assertThat(received.forTenant(ctx.modelId()).stream()
                        .map(GraphEventPublished::aggregateId)
                        .toList())
                .containsExactlyInAnyOrderElementsOf(nodeIds);
    }

    /**
     * EVENT-05 / W8: routing_hints JSONB round-trips from DB → row mapper →
     * published record. Seeds an outbox row directly with non-null routing_hints
     * and asserts the listener receives the parsed map.
     */
    @Test
    void routing_hints_jsonb_round_trips_through_row_mapper() {
        TenantContext ctx = TenantContext.of(UUID.randomUUID());
        received.forTenant(ctx.modelId()).clear();

        UUID eventId = UUID.randomUUID();
        UUID aggId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO graph_outbox (model_id, event_id, aggregatetype, aggregateid, type, payload, routing_hints, status)"
                        + " VALUES (?::uuid, ?::uuid, ?, ?, ?, ?::jsonb, ?::jsonb, 'PENDING')",
                ctx.modelId().toString(),
                eventId.toString(),
                "Person",
                aggId.toString(),
                "CREATE_NODE",
                "{\"name\":\"Hinted\"}",
                "{\"projection\":\"rest\",\"topic\":\"people\"}");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(received.forTenant(ctx.modelId()))
                .hasSize(1));

        GraphEventPublished evt = received.forTenant(ctx.modelId()).get(0);
        assertThat(evt.routingHints()).containsEntry("projection", "rest").containsEntry("topic", "people");
        assertThat(evt.payload()).containsEntry("name", "Hinted");
        assertThat(evt.eventId()).isEqualTo(eventId);
        assertThat(evt.aggregateId()).isEqualTo(aggId);
    }

    /** Per-tenant event buffer so tests do not cross-contaminate. */
    static class Received {
        private final java.util.concurrent.ConcurrentMap<UUID, CopyOnWriteArrayList<GraphEventPublished>> byTenant =
                new java.util.concurrent.ConcurrentHashMap<>();

        CopyOnWriteArrayList<GraphEventPublished> forTenant(UUID modelId) {
            return byTenant.computeIfAbsent(modelId, k -> new CopyOnWriteArrayList<>());
        }

        void record(GraphEventPublished event) {
            forTenant(event.modelId()).add(event);
        }
    }

    /** Spring test configuration: a listener bean + the {@link Received} buffer. */
    @org.springframework.boot.test.context.TestConfiguration
    @Import(FlywayItApplication.class)
    static class TestListeners {

        @Bean
        Received received() {
            return new Received();
        }

        @Bean
        OutboxTestListener outboxTestListener(Received received) {
            return new OutboxTestListener(received);
        }
    }

    static class OutboxTestListener {
        private final Received received;

        OutboxTestListener(Received received) {
            this.received = received;
        }

        @EventListener
        public void on(GraphEventPublished event) {
            received.record(event);
        }
    }
}
