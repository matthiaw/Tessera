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
 * EVENT-03 Wave-1 smoke: one {@code apply} call must persist
 * {@code origin_connector_id}, {@code origin_change_id}, and a non-null
 * {@code delta} into {@code graph_events}. Full provenance IT lives in
 * Wave 2 as {@code EventProvenanceIT}.
 */
@SpringBootTest(classes = FlywayItApplication.class)
@ActiveProfiles("flyway-it")
@Testcontainers
class EventProvenanceSmokeIT {

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
    void single_write_persists_origin_fields_and_delta() {
        TenantContext ctx = TenantContext.of(UUID.randomUUID());
        graphService.apply(GraphMutation.builder()
                .tenantContext(ctx)
                .operation(Operation.CREATE)
                .type("Person")
                .payload(Map.of("name", "Smoke"))
                .sourceType(SourceType.STRUCTURED)
                .sourceId("smoke-src")
                .sourceSystem("smoke-system")
                .confidence(BigDecimal.ONE)
                .originConnectorId("smoke-conn")
                .originChangeId("smoke-chg-1")
                .build());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT origin_connector_id, origin_change_id, delta FROM graph_events WHERE model_id = ?::uuid",
                ctx.modelId().toString());

        assertThat(row.get("origin_connector_id")).isEqualTo("smoke-conn");
        assertThat(row.get("origin_change_id")).isEqualTo("smoke-chg-1");
        assertThat(row.get("delta")).isNotNull();
        // Postgres re-emits jsonb with its own formatting (spaces after colons);
        // we only assert the payload key + value are present.
        assertThat(row.get("delta").toString()).contains("\"name\"").contains("\"Smoke\"");
    }
}
