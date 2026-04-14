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
package dev.tessera.core.age;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tessera.core.support.AgePostgresContainer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * FOUND-06 smoke: the digest-pinned apache/age image boots, AGE loads,
 * a graph is created, and a trivial Cypher query returns a row.
 */
@Testcontainers
class AgePostgresContainerIT {

    @Container
    static PostgreSQLContainer<?> postgres = AgePostgresContainer.create();

    @Test
    void age_extension_loads_and_simple_cypher_runs() throws Exception {
        try (Connection c = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement s = c.createStatement()) {
            s.execute("CREATE EXTENSION IF NOT EXISTS age");
            s.execute("LOAD 'age'");
            s.execute("SET search_path = ag_catalog, \"$user\", public");
            s.execute("SELECT create_graph('it_smoke')");
            try (ResultSet rs = s.executeQuery("SELECT * FROM cypher('it_smoke', $$ RETURN 1 $$) AS (n agtype)")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).contains("1");
            }
        }
    }
}
