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

import com.zaxxer.hikari.HikariDataSource;
import dev.tessera.core.graph.internal.GraphSession;
import dev.tessera.core.support.AgePostgresContainer;
import dev.tessera.core.support.AgeTestHarness;
import dev.tessera.core.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** CORE-06: every write stamps the eight mandatory system properties. */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SystemPropertiesIT {

    @Container
    static final PostgreSQLContainer<?> PG = AgePostgresContainer.create();

    private HikariDataSource ds;
    private GraphSession session;

    @BeforeAll
    void setUp() {
        ds = AgeTestHarness.dataSourceFor(PG);
        session = new GraphSession(AgeTestHarness.jdbcTemplate(ds));
    }

    @AfterAll
    void tearDown() {
        if (ds != null) {
            ds.close();
        }
    }

    @Test
    void every_system_property_is_stamped_on_create() {
        TenantContext ctx = TenantContext.of(UUID.randomUUID());
        Instant before = Instant.now().minus(Duration.ofSeconds(1));

        GraphMutation m = GraphMutation.builder()
                .tenantContext(ctx)
                .operation(Operation.CREATE)
                .type("Person")
                .payload(Map.of("name", "Dave"))
                .sourceType(SourceType.MANUAL)
                .sourceId("src-sys")
                .sourceSystem("manual-entry")
                .confidence(BigDecimal.ONE)
                .build();

        NodeState state = session.apply(ctx, m);

        assertThat(state.uuid()).isNotNull();
        assertThat(state.properties())
                .containsKey("uuid")
                .containsKey("model_id")
                .containsKey("_type")
                .containsKey("_created_at")
                .containsKey("_updated_at")
                .containsKey("_created_by")
                .containsKey("_source")
                .containsKey("_source_id");

        assertThat(state.properties().get("model_id")).isEqualTo(ctx.modelId().toString());
        assertThat(state.properties().get("_type")).isEqualTo("Person");
        assertThat(state.properties().get("_created_by")).isEqualTo("src-sys");
        assertThat(state.properties().get("_source")).isEqualTo("manual-entry");
        assertThat(state.properties().get("_source_id")).isEqualTo("src-sys");

        Instant after = Instant.now().plus(Duration.ofSeconds(1));
        Instant createdAt = state.createdAt();
        Instant updatedAt = state.updatedAt();
        assertThat(createdAt).isBetween(before, after);
        assertThat(updatedAt).isEqualTo(createdAt); // CREATE: created_at == updated_at
    }

    @Test
    void payload_supplied_timestamps_are_stripped() {
        // CORE-08: Tessera-owned timestamps. Payload-supplied _created_at must be ignored.
        TenantContext ctx = TenantContext.of(UUID.randomUUID());
        Instant before = Instant.now().minus(Duration.ofSeconds(1));

        GraphMutation m = GraphMutation.builder()
                .tenantContext(ctx)
                .operation(Operation.CREATE)
                .type("Person")
                .payload(Map.of("name", "Evil", "_created_at", "1999-01-01T00:00:00Z"))
                .sourceType(SourceType.MANUAL)
                .sourceId("src-ts")
                .sourceSystem("test")
                .confidence(BigDecimal.ONE)
                .build();

        NodeState state = session.apply(ctx, m);
        Instant createdAt = state.createdAt();
        assertThat(createdAt).isAfter(before);
        assertThat(state.properties().get("_created_at")).asString().doesNotContain("1999");
    }
}
