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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Wave 2 — plan 01-W2-02. EVENT-07: full mutation history per node, with
 * sequence ordering and full origin attribution per row.
 */
@SpringBootTest(classes = FlywayItApplication.class)
@ActiveProfiles("flyway-it")
@Testcontainers
class AuditHistoryIT {

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
    void history_returns_all_events_in_sequence_order_with_origin_attribution() {
        TenantContext ctx = TenantContext.of(UUID.randomUUID());

        GraphMutationOutcome.Committed created = (GraphMutationOutcome.Committed)
                graphService.apply(build(ctx, Operation.CREATE, null, Map.of("name", "Alice"), "c"));
        UUID node = created.nodeUuid();

        graphService.apply(build(ctx, Operation.UPDATE, node, Map.of("name", "Bob"), "u1"));
        graphService.apply(build(ctx, Operation.UPDATE, node, Map.of("name", "Carol"), "u2"));
        graphService.apply(build(ctx, Operation.TOMBSTONE, node, Map.of(), "t"));

        List<EventLog.EventRow> history = eventLog.history(ctx, node);
        assertThat(history).hasSize(4);

        // Ordered by sequence_nr
        for (int i = 1; i < history.size(); i++) {
            assertThat(history.get(i).sequenceNr())
                    .isGreaterThan(history.get(i - 1).sequenceNr());
        }

        assertThat(history.get(0).eventType()).isEqualTo("CREATE_NODE");
        assertThat(history.get(1).eventType()).isEqualTo("UPDATE_NODE");
        assertThat(history.get(2).eventType()).isEqualTo("UPDATE_NODE");
        assertThat(history.get(3).eventType()).isEqualTo("TOMBSTONE_NODE");

        // Each row carries the origin_connector_id / origin_change_id we wrote
        for (EventLog.EventRow row : history) {
            assertThat(row.originConnectorId()).isEqualTo("audit-conn");
            assertThat(row.originChangeId()).startsWith("audit-chg-");
            assertThat(row.modelId()).isEqualTo(ctx.modelId());
            assertThat(row.nodeUuid()).isEqualTo(node);
            assertThat(row.sourceSystem()).isEqualTo("test");
        }

        // Spot-check payload + delta were parsed back into maps
        assertThat(history.get(0).payload()).containsEntry("name", "Alice");
        assertThat(history.get(1).delta()).containsEntry("name", "Bob");
    }

    private GraphMutation build(TenantContext ctx, Operation op, UUID target, Map<String, Object> payload, String tag) {
        return GraphMutation.builder()
                .tenantContext(ctx)
                .operation(op)
                .type("Person")
                .targetNodeUuid(target)
                .payload(payload)
                .sourceType(SourceType.STRUCTURED)
                .sourceId("audit-" + tag)
                .sourceSystem("test")
                .confidence(BigDecimal.ONE)
                .originConnectorId("audit-conn")
                .originChangeId("audit-chg-" + tag)
                .build();
    }
}
