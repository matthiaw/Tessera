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
 * Wave 2 — plan 01-W2-02. EVENT-02: per-tenant {@code SEQUENCE CACHE 50}
 * delivers monotonic sequence_nr per model_id and never collides cross-tenant.
 */
@SpringBootTest(classes = FlywayItApplication.class)
@ActiveProfiles("flyway-it")
@Testcontainers
class PerTenantSequenceIT {

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
    void two_tenants_get_independent_monotonic_sequences() {
        TenantContext a = TenantContext.of(UUID.randomUUID());
        TenantContext b = TenantContext.of(UUID.randomUUID());

        int n = 50;
        for (int i = 0; i < n; i++) {
            graphService.apply(create(a, "A-" + i));
            graphService.apply(create(b, "B-" + i));
        }

        List<Long> seqsA = jdbc.queryForList(
                "SELECT sequence_nr FROM graph_events WHERE model_id = ?::uuid ORDER BY sequence_nr",
                Long.class,
                a.modelId().toString());
        List<Long> seqsB = jdbc.queryForList(
                "SELECT sequence_nr FROM graph_events WHERE model_id = ?::uuid ORDER BY sequence_nr",
                Long.class,
                b.modelId().toString());

        assertThat(seqsA).hasSize(n);
        assertThat(seqsB).hasSize(n);

        // Strictly monotonic per tenant (gaps allowed due to CACHE 50).
        for (int i = 1; i < seqsA.size(); i++) {
            assertThat(seqsA.get(i)).isGreaterThan(seqsA.get(i - 1));
        }
        for (int i = 1; i < seqsB.size(); i++) {
            assertThat(seqsB.get(i)).isGreaterThan(seqsB.get(i - 1));
        }

        // Per-tenant minimum is >= 1 (the CACHE 50 first allocation may start
        // anywhere within [1, 50]).
        assertThat(seqsA.get(0)).isGreaterThanOrEqualTo(1L);
        assertThat(seqsB.get(0)).isGreaterThanOrEqualTo(1L);

        // The (model_id, sequence_nr) unique index already guarantees no duplicates
        // within a tenant — assert it explicitly here for documentation value.
        assertThat(seqsA).doesNotHaveDuplicates();
        assertThat(seqsB).doesNotHaveDuplicates();

        // Each tenant has its own dedicated Postgres SEQUENCE object.
        Long seqACount = jdbc.queryForObject(
                "SELECT count(*) FROM pg_class WHERE relname = ? AND relkind = 'S'",
                Long.class,
                "graph_events_seq_" + a.modelId().toString().replace("-", ""));
        Long seqBCount = jdbc.queryForObject(
                "SELECT count(*) FROM pg_class WHERE relname = ? AND relkind = 'S'",
                Long.class,
                "graph_events_seq_" + b.modelId().toString().replace("-", ""));
        assertThat(seqACount).isEqualTo(1L);
        assertThat(seqBCount).isEqualTo(1L);
    }

    private GraphMutation create(TenantContext ctx, String name) {
        return GraphMutation.builder()
                .tenantContext(ctx)
                .operation(Operation.CREATE)
                .type("Person")
                .payload(Map.of("name", name))
                .sourceType(SourceType.STRUCTURED)
                .sourceId("seq-" + name)
                .sourceSystem("test")
                .confidence(BigDecimal.ONE)
                .build();
    }
}
