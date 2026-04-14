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
package dev.tessera.core.bench;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tessera.core.support.AgePostgresContainer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Failsafe-side end-to-end check for {@link SeedGenerator}: actually inserts
 * 1000 nodes into AGE, asserts vertex count and label distribution.
 */
@Testcontainers
class SeedGeneratorIT {

    @Container
    static PostgreSQLContainer<?> postgres = AgePostgresContainer.create();

    @Test
    void inserts_expected_node_and_edge_counts() throws Exception {
        try (Connection c =
                DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            try (Statement s = c.createStatement()) {
                s.execute("CREATE EXTENSION IF NOT EXISTS age");
                s.execute("LOAD 'age'");
                s.execute("SET search_path = ag_catalog, \"$user\", public");
            }

            int nodeCount = 1000;
            int edgesPerNode = 4;
            List<UUID> uuids = SeedGenerator.build(c, nodeCount, edgesPerNode, SeedGenerator.DEFAULT_SEED);
            assertThat(uuids).hasSize(nodeCount);

            // Node count
            try (Statement s = c.createStatement();
                    ResultSet rs = s.executeQuery("SELECT * FROM cypher('" + SeedGenerator.GRAPH_NAME
                            + "', $$ MATCH (n) RETURN count(n) $$) AS (c agtype)")) {
                assertThat(rs.next()).isTrue();
                long count = Long.parseLong(rs.getString(1));
                assertThat(count).isEqualTo(nodeCount);
            }

            // Edge count: ~edgesPerNode * nodeCount minus self-loop dedup (small)
            try (Statement s = c.createStatement();
                    ResultSet rs = s.executeQuery("SELECT * FROM cypher('" + SeedGenerator.GRAPH_NAME
                            + "', $$ MATCH ()-[r:RELATES]->() RETURN count(r) $$) AS (c agtype)")) {
                assertThat(rs.next()).isTrue();
                long edges = Long.parseLong(rs.getString(1));
                long expected = (long) nodeCount * edgesPerNode;
                // allow ±5% (self-loop dedup removes a tiny deterministic slice)
                assertThat(edges).isBetween((long) (expected * 0.95), expected);
            }

            // Label distribution: round-robin across four labels → 250 each
            for (String label : SeedGenerator.LABELS) {
                try (Statement s = c.createStatement();
                        ResultSet rs = s.executeQuery("SELECT * FROM cypher('" + SeedGenerator.GRAPH_NAME
                                + "', $$ MATCH (n:" + label + ") RETURN count(n) $$) AS (c agtype)")) {
                    assertThat(rs.next()).isTrue();
                    long c2 = Long.parseLong(rs.getString(1));
                    assertThat(c2).as("label %s", label).isEqualTo(nodeCount / SeedGenerator.LABELS.size());
                }
            }
        }
    }
}
