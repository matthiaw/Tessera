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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** CORE-05: edge CRUD + tombstone semantics. */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EdgeLifecycleIT {

    @Container
    static final PostgreSQLContainer<?> PG = AgePostgresContainer.create();

    private HikariDataSource ds;
    private JdbcTemplate jdbc;
    private GraphSession session;

    @BeforeAll
    void setUp() {
        ds = AgeTestHarness.dataSourceFor(PG);
        jdbc = AgeTestHarness.jdbcTemplate(ds);
        session = new GraphSession(jdbc);
    }

    @AfterAll
    void tearDown() {
        if (ds != null) {
            ds.close();
        }
    }

    @Test
    void create_edge_between_two_person_nodes_and_tombstone_it() {
        TenantContext ctx = TenantContext.of(UUID.randomUUID());

        NodeState alice = session.apply(ctx, createPerson(ctx, "Alice"));
        NodeState bob = session.apply(ctx, createPerson(ctx, "Bob"));

        UUID edgeUuid = UUID.randomUUID();
        Map<String, Object> edgePayload = new HashMap<>();
        edgePayload.put("sourceUuid", alice.uuid().toString());
        edgePayload.put("targetUuid", bob.uuid().toString());
        edgePayload.put("sourceLabel", "Person");
        edgePayload.put("targetLabel", "Person");
        edgePayload.put("since", "2026-04");

        GraphMutation createEdge = GraphMutation.builder()
                .tenantContext(ctx)
                .operation(Operation.CREATE)
                .type(GraphSession.EDGE_PREFIX + "KNOWS")
                .targetNodeUuid(edgeUuid)
                .payload(edgePayload)
                .sourceType(SourceType.STRUCTURED)
                .sourceId("src-edge")
                .sourceSystem("test")
                .confidence(BigDecimal.ONE)
                .build();

        NodeState edge = session.apply(ctx, createEdge);
        assertThat(edge.uuid()).isEqualTo(edgeUuid);
        assertThat(edge.typeSlug()).isEqualTo("KNOWS");

        // Read-back via Cypher through the session's internal path — we use a small
        // count query through JdbcTemplate. The Cypher string is in this test file but
        // scoped to src/test/java so the CORE-02 RawCypherBanTest (DoNotIncludeTests)
        // does not scan it.
        long edgesFound = jdbc.queryForObject(
                "SELECT count(*) FROM cypher('tessera_main', $$"
                        + " MATCH ()-[e:KNOWS]->() WHERE e.model_id = \""
                        + ctx.modelId() + "\" AND e.uuid = \"" + edgeUuid + "\""
                        + " RETURN 1 $$) AS (x agtype)",
                Long.class);
        assertThat(edgesFound).isEqualTo(1L);

        // Tombstone the edge
        Map<String, Object> tombPayload = new HashMap<>();
        tombPayload.put("sourceUuid", alice.uuid().toString());
        tombPayload.put("targetUuid", bob.uuid().toString());
        tombPayload.put("sourceLabel", "Person");
        tombPayload.put("targetLabel", "Person");
        GraphMutation tomb = GraphMutation.builder()
                .tenantContext(ctx)
                .operation(Operation.TOMBSTONE)
                .type(GraphSession.EDGE_PREFIX + "KNOWS")
                .targetNodeUuid(edgeUuid)
                .payload(tombPayload)
                .sourceType(SourceType.STRUCTURED)
                .sourceId("src-edge")
                .sourceSystem("test")
                .confidence(BigDecimal.ONE)
                .build();
        session.apply(ctx, tomb);

        // Edge still present but tombstoned
        List<String> rows = jdbc.query(
                "SELECT * FROM cypher('tessera_main', $$"
                        + " MATCH ()-[e:KNOWS]->() WHERE e.model_id = \""
                        + ctx.modelId() + "\" AND e.uuid = \"" + edgeUuid + "\""
                        + " RETURN e._tombstoned $$) AS (v agtype)",
                (rs, i) -> rs.getString(1));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).contains("true");
    }

    private static GraphMutation createPerson(TenantContext ctx, String name) {
        return GraphMutation.builder()
                .tenantContext(ctx)
                .operation(Operation.CREATE)
                .type("Person")
                .payload(Map.of("name", name))
                .sourceType(SourceType.STRUCTURED)
                .sourceId("src-" + name)
                .sourceSystem("test")
                .confidence(BigDecimal.ONE)
                .build();
    }
}
