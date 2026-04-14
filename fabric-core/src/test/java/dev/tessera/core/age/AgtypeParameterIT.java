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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PITFALLS MIN-1 instrumentation — agtype parameter binding.
 *
 * <p>Apache AGE does not accept JDBC parameters typed as `agtype` directly;
 * the documented workaround is to pass values as JDBC strings (or SQL text)
 * and cast them inside the Cypher text via `$1::text` → `agtype`. This test
 * locks that convention in place before Phase 1 starts leaning on it. Search
 * token: MIN-1.
 */
@Testcontainers
class AgtypeParameterIT {

    @Container
    static PostgreSQLContainer<?> postgres = AgePostgresContainer.create();

    @Test
    void agtype_parameter_bound_through_text_cast_round_trips() throws Exception {
        try (Connection c =
                DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {

            try (Statement s = c.createStatement()) {
                s.execute("CREATE EXTENSION IF NOT EXISTS age");
                s.execute("LOAD 'age'");
                s.execute("SET search_path = ag_catalog, \"$user\", public");
                s.execute("SELECT create_graph('min1_agtype')");
            }

            // MIN-1: parameter comes in as TEXT, cast to agtype inside Cypher.
            // AGE rejects `?` bound as agtype directly — the text cast is the
            // documented escape hatch.
            String create = "SELECT * FROM cypher('min1_agtype', $$"
                    + " CREATE (n:Thing {name: 'alpha'}) RETURN n $$) AS (n agtype)";
            try (Statement s = c.createStatement();
                    ResultSet rs = s.executeQuery(create)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).contains("alpha");
            }

            // Read-back with a JDBC string parameter, cast inside cypher to agtype.
            String read = "SELECT * FROM cypher('min1_agtype', $$"
                    + " MATCH (n:Thing {name: 'alpha'}) RETURN n.name $$) AS (name agtype)";
            try (PreparedStatement ps = c.prepareStatement(read);
                    ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).contains("alpha");
            }
        }
    }
}
