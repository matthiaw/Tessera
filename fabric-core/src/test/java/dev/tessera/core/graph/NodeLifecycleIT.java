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
import dev.tessera.core.graph.internal.GraphRepositoryImpl;
import dev.tessera.core.graph.internal.GraphSession;
import dev.tessera.core.support.AgePostgresContainer;
import dev.tessera.core.support.AgeTestHarness;
import dev.tessera.core.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** CORE-04: node create/read/update lifecycle through {@link GraphSession}. */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NodeLifecycleIT {

    @Container
    static final PostgreSQLContainer<?> PG = AgePostgresContainer.create();

    private HikariDataSource ds;
    private GraphSession session;
    private GraphRepository repo;

    @BeforeAll
    void setUp() {
        ds = AgeTestHarness.dataSourceFor(PG);
        session = new GraphSession(AgeTestHarness.jdbcTemplate(ds));
        repo = new GraphRepositoryImpl(session);
    }

    @AfterAll
    void tearDown() {
        if (ds != null) {
            ds.close();
        }
    }

    @Test
    void create_then_find_returns_node_with_system_properties() {
        TenantContext ctx = TenantContext.of(UUID.randomUUID());
        GraphMutation create = GraphMutation.builder()
                .tenantContext(ctx)
                .operation(Operation.CREATE)
                .type("Person")
                .payload(Map.of("name", "Alice"))
                .sourceType(SourceType.STRUCTURED)
                .sourceId("src-1")
                .sourceSystem("test")
                .confidence(BigDecimal.ONE)
                .build();

        NodeState created = session.apply(ctx, create);
        assertThat(created.uuid()).isNotNull();
        assertThat(created.typeSlug()).isEqualTo("Person");
        assertThat(created.properties()).containsEntry("name", "Alice");

        Optional<NodeState> found = repo.findNode(ctx, "Person", created.uuid());
        assertThat(found).isPresent();
        assertThat(found.get().properties()).containsEntry("name", "Alice");
        assertThat(found.get().properties()).containsEntry("_type", "Person");
    }

    @Test
    void update_changes_payload_fields_but_preserves_uuid_and_created_at() {
        TenantContext ctx = TenantContext.of(UUID.randomUUID());
        GraphMutation create = GraphMutation.builder()
                .tenantContext(ctx)
                .operation(Operation.CREATE)
                .type("Person")
                .payload(Map.of("name", "Bob"))
                .sourceType(SourceType.STRUCTURED)
                .sourceId("src-2")
                .sourceSystem("test")
                .confidence(BigDecimal.ONE)
                .build();
        NodeState original = session.apply(ctx, create);

        // tiny sleep so _updated_at strictly > _created_at
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        GraphMutation update = GraphMutation.builder()
                .tenantContext(ctx)
                .operation(Operation.UPDATE)
                .type("Person")
                .targetNodeUuid(original.uuid())
                .payload(Map.of("name", "Robert"))
                .sourceType(SourceType.STRUCTURED)
                .sourceId("src-2")
                .sourceSystem("test")
                .confidence(BigDecimal.ONE)
                .build();
        NodeState updated = session.apply(ctx, update);

        assertThat(updated.uuid()).isEqualTo(original.uuid());
        assertThat(updated.properties()).containsEntry("name", "Robert");
        // _created_at is preserved through UPDATE because sanitizePayload strips it and
        // we only SET the fields in the UPDATE mutation's payload + _updated_at.
        assertThat(updated.properties().get("_created_at"))
                .isEqualTo(original.properties().get("_created_at"));
        assertThat(updated.updatedAt()).isAfterOrEqualTo(original.updatedAt());
    }

    @Test
    void find_with_wrong_tenant_returns_empty() {
        TenantContext a = TenantContext.of(UUID.randomUUID());
        TenantContext b = TenantContext.of(UUID.randomUUID());
        GraphMutation create = GraphMutation.builder()
                .tenantContext(a)
                .operation(Operation.CREATE)
                .type("Person")
                .payload(Map.of("name", "Carol"))
                .sourceType(SourceType.STRUCTURED)
                .sourceId("src-3")
                .sourceSystem("test")
                .confidence(BigDecimal.ONE)
                .build();
        NodeState created = session.apply(a, create);

        assertThat(repo.findNode(b, "Person", created.uuid())).isEmpty();
        assertThat(repo.findNode(a, "Person", created.uuid())).isPresent();
    }
}
