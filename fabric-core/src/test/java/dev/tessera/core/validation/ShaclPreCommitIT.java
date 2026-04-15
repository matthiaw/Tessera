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
package dev.tessera.core.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tessera.core.graph.GraphMutation;
import dev.tessera.core.graph.GraphService;
import dev.tessera.core.graph.Operation;
import dev.tessera.core.graph.SourceType;
import dev.tessera.core.schema.AddPropertySpec;
import dev.tessera.core.schema.CreateNodeTypeSpec;
import dev.tessera.core.schema.SchemaRegistry;
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

/** VALID-01 — synchronous SHACL validation inside the @Transactional write funnel. */
@SpringBootTest(classes = FlywayItApplication.class)
@ActiveProfiles("flyway-it")
@Testcontainers
class ShaclPreCommitIT {

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
    void invalid_mutation_is_rejected_and_transaction_rolls_back() {
        TenantContext ctx = TenantContext.of(UUID.randomUUID());
        registry.createNodeType(ctx, new CreateNodeTypeSpec("Person", "Person", "Person", "A human"));
        registry.addProperty(ctx, "Person", AddPropertySpec.required("name", "string"));

        long eventsBefore = countEvents(ctx);
        long outboxBefore = countOutbox(ctx);

        // Missing required `name`.
        GraphMutation invalid = GraphMutation.builder()
                .tenantContext(ctx)
                .operation(Operation.CREATE)
                .type("Person")
                .payload(Map.of())
                .sourceType(SourceType.STRUCTURED)
                .sourceId("src-bad")
                .sourceSystem("test")
                .confidence(BigDecimal.ONE)
                .build();

        assertThatThrownBy(() -> graphService.apply(invalid)).isInstanceOf(ShaclValidationException.class);

        assertThat(countEvents(ctx)).isEqualTo(eventsBefore);
        assertThat(countOutbox(ctx)).isEqualTo(outboxBefore);
    }

    @Test
    void valid_mutation_is_accepted() {
        TenantContext ctx = TenantContext.of(UUID.randomUUID());
        registry.createNodeType(ctx, new CreateNodeTypeSpec("Person", "Person", "Person", "A human"));
        registry.addProperty(ctx, "Person", AddPropertySpec.required("name", "string"));

        long eventsBefore = countEvents(ctx);

        graphService.apply(GraphMutation.builder()
                .tenantContext(ctx)
                .operation(Operation.CREATE)
                .type("Person")
                .payload(Map.of("name", "Alice"))
                .sourceType(SourceType.STRUCTURED)
                .sourceId("src-ok")
                .sourceSystem("test")
                .confidence(BigDecimal.ONE)
                .build());

        assertThat(countEvents(ctx)).isEqualTo(eventsBefore + 1);
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
