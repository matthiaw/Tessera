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
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Wave 2 — plan 01-W2-02. EVENT-06: temporal replay reconstructs node state at
 * a given timestamp by folding events with {@code event_time <= T}.
 */
@SpringBootTest(classes = FlywayItApplication.class)
@ActiveProfiles("flyway-it")
@Testcontainers
class TemporalReplayIT {

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
    EventLog eventLog;

    @Test
    void replay_reconstructs_state_at_each_point_in_time() throws InterruptedException {
        TenantContext ctx = TenantContext.of(UUID.randomUUID());

        // T0: create
        GraphMutationOutcome.Committed created = (GraphMutationOutcome.Committed)
                graphService.apply(build(ctx, Operation.CREATE, null, Map.of("name", "Alice"), "c"));
        UUID node = created.nodeUuid();
        Thread.sleep(20);
        Instant afterCreate = Instant.now();
        Thread.sleep(20);

        // T1: update name -> Bob
        graphService.apply(build(ctx, Operation.UPDATE, node, Map.of("name", "Bob"), "u"));
        Thread.sleep(20);
        Instant afterUpdate = Instant.now();
        Thread.sleep(20);

        // T2: tombstone
        graphService.apply(build(ctx, Operation.TOMBSTONE, node, Map.of(), "t"));
        Thread.sleep(20);
        Instant afterTombstone = Instant.now();

        Optional<Map<String, Object>> atCreate = eventLog.replayToState(ctx, node, afterCreate);
        assertThat(atCreate).isPresent();
        assertThat(atCreate.get()).containsEntry("name", "Alice");
        assertThat(atCreate.get()).doesNotContainKey("_tombstoned");

        Optional<Map<String, Object>> atUpdate = eventLog.replayToState(ctx, node, afterUpdate);
        assertThat(atUpdate).isPresent();
        assertThat(atUpdate.get()).containsEntry("name", "Bob");
        assertThat(atUpdate.get()).doesNotContainKey("_tombstoned");

        Optional<Map<String, Object>> atTomb = eventLog.replayToState(ctx, node, afterTombstone);
        assertThat(atTomb).isPresent();
        assertThat(atTomb.get()).containsEntry("_tombstoned", Boolean.TRUE);

        // Querying for a node that does not exist returns empty
        Optional<Map<String, Object>> ghost = eventLog.replayToState(ctx, UUID.randomUUID(), afterTombstone);
        assertThat(ghost).isEmpty();
    }

    private GraphMutation build(TenantContext ctx, Operation op, UUID target, Map<String, Object> payload, String tag) {
        return GraphMutation.builder()
                .tenantContext(ctx)
                .operation(op)
                .type("Person")
                .targetNodeUuid(target)
                .payload(payload)
                .sourceType(SourceType.STRUCTURED)
                .sourceId("replay-" + tag)
                .sourceSystem("test")
                .confidence(BigDecimal.ONE)
                .originConnectorId("replay-conn")
                .originChangeId("replay-chg-" + tag)
                .build();
    }
}
