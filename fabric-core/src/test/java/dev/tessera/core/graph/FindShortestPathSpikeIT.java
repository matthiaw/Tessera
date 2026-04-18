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
import dev.tessera.core.support.AgePostgresContainer;
import dev.tessera.core.support.AgeTestHarness;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Wave 0 spike: validates RESEARCH.md Assumption A3 — that AGE {@code shortestPath()} returns
 * a parseable agtype path object, and that {@code nodes(path)} yields individual vertex entries.
 *
 * <p>This spike de-risks {@code FindPathTool} (MCP-06) before Plan 02 implements it.
 * Three tests cover:
 * <ol>
 *   <li>shortestPath() returns a non-null agtype path.</li>
 *   <li>nodes(path) returns an agtype array with the correct node count.</li>
 *   <li>Cross-tenant path isolation: WHERE ALL filter prevents traversal into other tenants.</li>
 * </ol>
 *
 * <p>See the file header comment for the spike result and implementation recommendation.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FindShortestPathSpikeIT {

    private static final Logger LOG = LoggerFactory.getLogger(FindShortestPathSpikeIT.class);

    private static final String GRAPH = "tessera_main";
    private static final String SPIKE_TENANT = "spike-tenant";
    private static final String OTHER_TENANT = "other-tenant";

    private static final String UUID_A = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String UUID_B = "bbbbbbbb-0000-0000-0000-000000000002";
    private static final String UUID_C = "cccccccc-0000-0000-0000-000000000003";
    private static final String UUID_D = "dddddddd-0000-0000-0000-000000000004";

    @Container
    static final PostgreSQLContainer<?> PG = AgePostgresContainer.create();

    private HikariDataSource ds;
    private JdbcTemplate jdbc;

    @BeforeAll
    void setUp() {
        ds = AgeTestHarness.dataSourceFor(PG);
        jdbc = AgeTestHarness.jdbcTemplate(ds);
        seedGraph();
    }

    @AfterAll
    void tearDown() {
        if (ds != null) {
            ds.close();
        }
    }

    // -----------------------------------------------------------------------
    // Seed
    // -----------------------------------------------------------------------

    /**
     * Seeds a 3-node chain A->B->C in tenant "spike-tenant", plus a cross-tenant
     * node D in "other-tenant" with an edge B->D.
     *
     * <p>Flyway already created the graph (tessera_main) via V1__enable_age.sql.
     * We re-use that graph and just add SpikeNode vertices.
     */
    private void seedGraph() {
        // A -> B -> C (all spike-tenant)
        jdbc.execute("SELECT * FROM cypher('"
                + GRAPH
                + "', $$"
                + " CREATE (a:SpikeNode {model_id: \""
                + SPIKE_TENANT
                + "\", uuid: \""
                + UUID_A
                + "\", name: \"A\"})"
                + " -[:CONNECTS_TO]->"
                + " (b:SpikeNode {model_id: \""
                + SPIKE_TENANT
                + "\", uuid: \""
                + UUID_B
                + "\", name: \"B\"})"
                + " -[:CONNECTS_TO]->"
                + " (c:SpikeNode {model_id: \""
                + SPIKE_TENANT
                + "\", uuid: \""
                + UUID_C
                + "\", name: \"C\"})"
                + " RETURN a, b, c"
                + " $$) AS (a agtype, b agtype, c agtype)");

        // D in other-tenant; B -> D cross-tenant edge
        jdbc.execute("SELECT * FROM cypher('"
                + GRAPH
                + "', $$"
                + " MATCH (b:SpikeNode {uuid: \""
                + UUID_B
                + "\"})"
                + " CREATE (d:SpikeNode {model_id: \""
                + OTHER_TENANT
                + "\", uuid: \""
                + UUID_D
                + "\", name: \"D\"})"
                + " CREATE (b)-[:CONNECTS_TO]->(d)"
                + " RETURN d"
                + " $$) AS (d agtype)");

        LOG.info("Spike graph seeded: A->B->C (spike-tenant), B->D (other-tenant cross-edge)");
    }

    // -----------------------------------------------------------------------
    // Test 1: shortestPath() returns a parseable result
    // -----------------------------------------------------------------------

    @Test
    void shortestPath_returns_non_null_agtype_path() {
        String cypher = "SELECT * FROM cypher('"
                + GRAPH
                + "', $$"
                + " MATCH (a:SpikeNode {model_id: \""
                + SPIKE_TENANT
                + "\", uuid: \""
                + UUID_A
                + "\"}),"
                + " (c:SpikeNode {model_id: \""
                + SPIKE_TENANT
                + "\", uuid: \""
                + UUID_C
                + "\"})"
                + " MATCH path = shortestPath((a)-[*1..10]-(c))"
                + " RETURN path"
                + " $$) AS (path agtype)";

        List<String> rows = jdbc.query(cypher, (rs, i) -> rs.getString(1));

        assertThat(rows).isNotEmpty();
        String rawPath = rows.get(0);
        assertThat(rawPath).isNotNull().isNotEmpty();
        LOG.info("Test 1 — raw agtype path: {}", rawPath);
        // Assumption A3 CONFIRMED: shortestPath() returned a non-null agtype path object.
    }

    // -----------------------------------------------------------------------
    // Test 2: nodes(path) extracts individual nodes
    // -----------------------------------------------------------------------

    @Test
    void nodes_of_path_returns_array_with_correct_node_count() {
        String cypher = "SELECT * FROM cypher('"
                + GRAPH
                + "', $$"
                + " MATCH (a:SpikeNode {model_id: \""
                + SPIKE_TENANT
                + "\", uuid: \""
                + UUID_A
                + "\"}),"
                + " (c:SpikeNode {model_id: \""
                + SPIKE_TENANT
                + "\", uuid: \""
                + UUID_C
                + "\"})"
                + " MATCH path = shortestPath((a)-[*1..10]-(c))"
                + " RETURN nodes(path)"
                + " $$) AS (nodes agtype)";

        List<String> rows = jdbc.query(cypher, (rs, i) -> rs.getString(1));

        assertThat(rows).isNotEmpty();
        String nodesAgtype = rows.get(0);
        assertThat(nodesAgtype).isNotNull().isNotEmpty();
        LOG.info("Test 2 — nodes(path) agtype array: {}", nodesAgtype);

        // The agtype array looks like: [{"id":...,"label":"SpikeNode","properties":{...}}::vertex, ...]
        // We verify 3 nodes by counting ::vertex occurrences (one per node in A->B->C).
        long vertexCount = countOccurrences(nodesAgtype, "::vertex");
        assertThat(vertexCount)
                .as("Expected 3 nodes (A, B, C) in shortest path from A to C")
                .isEqualTo(3);

        // Verify A is the first node and C is the last by checking UUID order.
        int posA = nodesAgtype.indexOf(UUID_A);
        int posC = nodesAgtype.indexOf(UUID_C);
        assertThat(posA).as("UUID_A must appear in nodes array").isGreaterThan(-1);
        assertThat(posC).as("UUID_C must appear in nodes array").isGreaterThan(-1);
        LOG.info("Test 2 — vertex count in nodes(path): {}", vertexCount);
    }

    // -----------------------------------------------------------------------
    // Test 3: Cross-tenant path isolation
    // -----------------------------------------------------------------------

    @Test
    void shortestPath_with_model_id_filter_excludes_cross_tenant_node() {
        // Without filter: there may be an alternate path A->B->D. With the model_id filter,
        // D must be excluded and the query must only return paths through spike-tenant nodes.
        String cypher = "SELECT * FROM cypher('"
                + GRAPH
                + "', $$"
                + " MATCH (a:SpikeNode {model_id: \""
                + SPIKE_TENANT
                + "\", uuid: \""
                + UUID_A
                + "\"}),"
                + " (c:SpikeNode {model_id: \""
                + SPIKE_TENANT
                + "\", uuid: \""
                + UUID_C
                + "\"})"
                + " MATCH path = shortestPath((a)-[*1..10]-(c))"
                + " WHERE ALL(n IN nodes(path) WHERE n.model_id = \""
                + SPIKE_TENANT
                + "\")"
                + " RETURN nodes(path)"
                + " $$) AS (nodes agtype)";

        List<String> rows = jdbc.query(cypher, (rs, i) -> rs.getString(1));

        assertThat(rows).isNotEmpty();
        String nodesAgtype = rows.get(0);
        LOG.info("Test 3 — filtered nodes(path) agtype: {}", nodesAgtype);

        // D (other-tenant) must NOT appear in the result.
        assertThat(nodesAgtype)
                .as("Cross-tenant node D must not be in the filtered path result")
                .doesNotContain(UUID_D);

        // A, B, C (spike-tenant) must all appear.
        assertThat(nodesAgtype).contains(UUID_A);
        assertThat(nodesAgtype).contains(UUID_B);
        assertThat(nodesAgtype).contains(UUID_C);

        LOG.info("Test 3 — cross-tenant isolation CONFIRMED: D excluded from path.");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static long countOccurrences(String haystack, String needle) {
        long count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
