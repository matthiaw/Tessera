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

import dev.tessera.core.schema.AddPropertySpec;
import dev.tessera.core.schema.CreateNodeTypeSpec;
import dev.tessera.core.schema.SchemaRegistry;
import dev.tessera.core.support.AgePostgresContainer;
import dev.tessera.core.support.FlywayItApplication;
import dev.tessera.core.tenant.TenantContext;
import dev.tessera.core.validation.ShaclValidationException;
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
 * 02-W1-02 / CONTEXT Decision 14: connector-origin mutation failure produces
 * a same-TX-REQUIRES_NEW {@code connector_dlq} row, while the graph write
 * rolls back (no nodes, no graph_events, no outbox rows).
 *
 * <p>Also pins the Decision-14 scope guard: admin-origin mutations (null
 * {@code originConnectorId}) that fail SHACL do NOT produce DLQ rows.
 */
@SpringBootTest(classes = FlywayItApplication.class)
@ActiveProfiles("flyway-it")
@Testcontainers
class ConnectorDlqSameTxIT {

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
    SchemaRegistry registry;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void connector_origin_shacl_failure_writes_dlq_row_and_rolls_back_graph() {
        TenantContext ctx = TenantContext.of(UUID.randomUUID());
        // Declare a type with a required property so omitting it triggers SHACL rejection.
        registry.createNodeType(ctx, new CreateNodeTypeSpec("Invoice", "Invoice", "Invoice", "desc"));
        registry.addProperty(ctx, "Invoice", AddPropertySpec.required("number", "string"));

        long dlqBefore = countDlq(ctx);
        long eventsBefore = countEvents(ctx);

        GraphMutation connectorMutation = GraphMutation.builder()
                .tenantContext(ctx)
                .operation(Operation.CREATE)
                .type("Invoice")
                .payload(Map.of("notnumber", "bogus")) // missing required "number"
                .sourceType(SourceType.STRUCTURED)
                .sourceId("conn-001-rec-7")
                .sourceSystem("upstream-erp")
                .confidence(BigDecimal.ONE)
                .originConnectorId("conn-001")
                .originChangeId("chg-7")
                .build();

        assertThatThrownBy(() -> graphService.apply(connectorMutation)).isInstanceOf(ShaclValidationException.class);

        // Graph-side rollback: no new graph_events rows for this tenant.
        assertThat(countEvents(ctx)).isEqualTo(eventsBefore);

        // DLQ-side commit (REQUIRES_NEW): exactly one new row, carrying the
        // connector id, rejection reason, and origin change id.
        List<Map<String, Object>> dlqRows = jdbc.queryForList(
                "SELECT connector_id, rejection_reason, rejection_detail, origin_change_id"
                        + " FROM connector_dlq WHERE model_id = ?::uuid ORDER BY created_at DESC",
                ctx.modelId().toString());
        assertThat(dlqRows).hasSize((int) (dlqBefore + 1));
        Map<String, Object> row = dlqRows.get(0);
        assertThat(row.get("connector_id")).isEqualTo("conn-001");
        assertThat(row.get("rejection_reason")).isEqualTo("SHACL_VIOLATION");
        assertThat(row.get("origin_change_id")).isEqualTo("chg-7");
        assertThat((String) row.get("rejection_detail")).isNotNull();
    }

    @Test
    void admin_origin_failure_does_not_write_dlq_row() {
        TenantContext ctx = TenantContext.of(UUID.randomUUID());
        registry.createNodeType(ctx, new CreateNodeTypeSpec("Ticket", "Ticket", "Ticket", "desc"));
        registry.addProperty(ctx, "Ticket", AddPropertySpec.required("subject", "string"));

        long dlqBefore = countDlq(ctx);

        GraphMutation adminMutation = GraphMutation.builder()
                .tenantContext(ctx)
                .operation(Operation.CREATE)
                .type("Ticket")
                .payload(Map.of("notsubject", "x"))
                .sourceType(SourceType.STRUCTURED)
                .sourceId("admin-007")
                .sourceSystem("admin-ui")
                .confidence(BigDecimal.ONE)
                // originConnectorId deliberately null — this is a direct admin write.
                .build();

        assertThatThrownBy(() -> graphService.apply(adminMutation)).isInstanceOf(ShaclValidationException.class);

        // No DLQ row added — DLQ is exclusively for connector-origin attempts.
        assertThat(countDlq(ctx)).isEqualTo(dlqBefore);
    }

    private long countDlq(TenantContext ctx) {
        Long c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM connector_dlq WHERE model_id = ?::uuid",
                Long.class,
                ctx.modelId().toString());
        return c == null ? 0L : c;
    }

    private long countEvents(TenantContext ctx) {
        Long c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM graph_events WHERE model_id = ?::uuid",
                Long.class,
                ctx.modelId().toString());
        return c == null ? 0L : c;
    }
}
