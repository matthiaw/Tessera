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
package dev.tessera.core.flyway;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tessera.core.support.AgePostgresContainer;
import dev.tessera.core.support.FlywayItApplication;
import org.flywaydb.core.Flyway;
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
 * FOUND-03 part 1: Flyway V1 baseline applies against a fresh AGE container,
 * records a migration, and creates the tessera_main graph.
 */
@SpringBootTest(classes = FlywayItApplication.class)
@ActiveProfiles("flyway-it")
@Testcontainers
class FlywayBaselineIT {

    @Container
    static PostgreSQLContainer<?> postgres = AgePostgresContainer.create();

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    Flyway flyway;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void v1_enable_age_is_applied_and_tessera_main_graph_exists() {
        // Wave 1 note: fabric-core now ships V1..V9 mirrored from fabric-app so
        // integration tests can boot the full Phase 1 schema. FOUND-03 still
        // holds: V1 enabled AGE and created tessera_main; Wave 0 added V2..V9.
        assertThat(flyway.info().applied().length).isGreaterThanOrEqualTo(1);
        assertThat(flyway.info().applied()[0].getVersion().getVersion()).isEqualTo("1");

        Integer graphs = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ag_catalog.ag_graph WHERE name = 'tessera_main'", Integer.class);
        assertThat(graphs).isEqualTo(1);
    }
}
