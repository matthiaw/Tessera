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
package dev.tessera.projections.sql;

import dev.tessera.projections.rest.ProjectionItApplication;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * SQL-02: Verifies that SQL views are regenerated when the underlying schema changes,
 * and that views survive application restarts via the {@code ApplicationRunner} startup
 * regeneration path.
 *
 * <p>Wave 0 stub — enabled by Plan 04-01.
 */
@Disabled("Wave 0 stub — implementation in Plan 04-01")
@SpringBootTest(
        classes = ProjectionItApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class SqlViewSchemaChangeIT {

    private static final String AGE_IMAGE =
            "apache/age@sha256:16aa423d20a31aed36a3313244bf7aa00731325862f20ed584510e381f2feaed";

    @Container
    static final PostgreSQLContainer<?> PG =
            new PostgreSQLContainer<>(DockerImageName.parse(AGE_IMAGE).asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("tessera")
                    .withUsername("tessera")
                    .withPassword("tessera")
                    .withReuse(true);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
    }

    /**
     * SQL-02: When a schema change is published (new property added to a node type),
     * the corresponding SQL view is automatically dropped and recreated to include
     * the new column.
     */
    @Test
    void viewRegeneratedOnSchemaChange() {
        fail("Not yet implemented — SQL-02: schema change triggers view DROP/CREATE (Plan 04-01)");
    }

    /**
     * SQL-02: On application restart, the {@code ApplicationRunner} regenerates all
     * SQL views for all registered tenants, ensuring views are not lost between
     * deployments.
     */
    @Test
    void viewsSurviveApplicationRestart() {
        fail("Not yet implemented — SQL-02: ApplicationRunner regenerates views on startup (Plan 04-01)");
    }
}
