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

/**
 * CORE-07: tombstone-default delete + opt-in hard delete. A {@code TOMBSTONE}
 * mutation must leave the node in place with {@code _tombstoned=true}; only
 * an explicit {@link GraphSession#hardDelete} call removes it.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TombstoneSemanticsIT {

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
    void tombstone_keeps_node_soft_but_hard_delete_removes_it() {
        TenantContext ctx = TenantContext.of(UUID.randomUUID());
        NodeState created = session.apply(
                ctx,
                GraphMutation.builder()
                        .tenantContext(ctx)
                        .operation(Operation.CREATE)
                        .type("Person")
                        .payload(Map.of("name", "Ellen"))
                        .sourceType(SourceType.STRUCTURED)
                        .sourceId("src-1")
                        .sourceSystem("test")
                        .confidence(BigDecimal.ONE)
                        .build());

        // TOMBSTONE: node still readable with the flag set.
        session.apply(
                ctx,
                GraphMutation.builder()
                        .tenantContext(ctx)
                        .operation(Operation.TOMBSTONE)
                        .type("Person")
                        .targetNodeUuid(created.uuid())
                        .payload(Map.of())
                        .sourceType(SourceType.SYSTEM)
                        .sourceId("src-1")
                        .sourceSystem("test")
                        .confidence(BigDecimal.ONE)
                        .build());

        Optional<NodeState> afterTomb = session.findNode(ctx, "Person", created.uuid());
        assertThat(afterTomb).isPresent();
        assertThat(afterTomb.get().properties()).containsEntry("_tombstoned", true);

        // Hard delete is an explicit separate method — nothing in the normal
        // apply() path reaches it.
        session.hardDelete(ctx, "Person", created.uuid());
        assertThat(session.findNode(ctx, "Person", created.uuid())).isEmpty();
    }
}
