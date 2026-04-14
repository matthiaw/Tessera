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
package dev.tessera.core.hikari;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tessera.core.support.AgePostgresContainer;
import dev.tessera.core.support.FlywayItApplication;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * FOUND-03 part 2: HikariCP connection-init-sql primes AGE on every pooled
 * connection (not just the first), and D-10's split-responsibility model
 * means no caller needs to issue LOAD 'age' explicitly.
 */
@SpringBootTest(classes = FlywayItApplication.class)
@ActiveProfiles("flyway-it")
@Testcontainers
class HikariInitSqlIT {

    @Container
    static PostgreSQLContainer<?> postgres = AgePostgresContainer.create();

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    DataSource dataSource;

    @Test
    void every_pooled_connection_has_ag_catalog_on_search_path() throws Exception {
        // First pooled connection
        try (Connection c1 = dataSource.getConnection();
                Statement s1 = c1.createStatement();
                ResultSet rs1 = s1.executeQuery("SHOW search_path")) {
            assertThat(rs1.next()).isTrue();
            assertThat(rs1.getString(1)).startsWith("ag_catalog");
        }

        // Second, independent pooled connection — proves init-sql fires per-connection,
        // not just on pool bootstrap.
        try (Connection c2 = dataSource.getConnection();
                Statement s2 = c2.createStatement();
                ResultSet rs2 = s2.executeQuery("SHOW search_path")) {
            assertThat(rs2.next()).isTrue();
            assertThat(rs2.getString(1)).startsWith("ag_catalog");
        }
    }

    @Test
    void cypher_runs_without_manual_load_age() throws Exception {
        try (Connection c = dataSource.getConnection();
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT * FROM cypher('tessera_main', $$ RETURN 1 $$) AS (n agtype)")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).contains("1");
        }
    }
}
